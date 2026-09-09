package com.convert.smartpdf.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.convert.smartpdf.NotificationUtils
import com.convert.smartpdf.R
import com.convert.smartpdf.databinding.FragmentImageToPdfBinding
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.itextpdf.text.Document
import com.itextpdf.text.Image
import com.itextpdf.text.PageSize
import com.itextpdf.text.pdf.PdfWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class ImageToPdfFragment : Fragment(R.layout.fragment_image_to_pdf) {

    private var _binding: FragmentImageToPdfBinding? = null
    private val binding get() = _binding!!
    
    private val selectedUris = mutableListOf<Uri>()
    private lateinit var adapter: ImagePreviewAdapter // আপনার তৈরি করা অ্যাডাপ্টার

    // গ্যালারি লঞ্চার
    private val pickImagesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            selectedUris.addAll(uris)
            adapter.notifyDataSetChanged()
            updateUI()
        }
    }

    // 🔥 AI Document Scanner লঞ্চার
    private val scannerLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanResult?.pages?.forEach { page ->
                selectedUris.add(page.imageUri) // স্ক্যান করা ছবিগুলো লিস্টে যোগ হবে
            }
            adapter.notifyDataSetChanged()
            updateUI()
            Toast.makeText(context, "AI Scanned Successfully!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentImageToPdfBinding.bind(view)

        setupRecyclerView()

        binding.btnPickImages.setOnClickListener { pickImagesLauncher.launch("image/*") }
        
        binding.btnAiScan.setOnClickListener { startAiDocumentScan() }

        binding.btnConvert.setOnClickListener { createSmartPdfWithAI() }
    }

    private fun setupRecyclerView() {
        adapter = ImagePreviewAdapter(selectedUris) { updateUI() }
        binding.rvImages.layoutManager = GridLayoutManager(context, 2)
        binding.rvImages.adapter = adapter

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                adapter.onItemMove(vh.adapterPosition, target.adapterPosition)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                // চাইলে এখানে ডিলিট লজিক দিতে পারেন
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.rvImages)
    }

    private fun updateUI() {
        binding.tvStatus.text = if (selectedUris.isEmpty()) "No images selected" else "✨ ${selectedUris.size} Images Ready (Drag to Reorder)"
        binding.btnConvert.isEnabled = selectedUris.isNotEmpty()
    }

    // 🔥 Google ML Kit Scanner চালু করার মেথড
    private fun startAiDocumentScan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(30)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL) // ফুল AI এডিটিং মোড
            .build()

        val scanner = GmsDocumentScanning.getClient(options)
        scanner.getStartScanIntent(requireActivity()).addOnSuccessListener { intentSender ->
            scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        }.addOnFailureListener {
            Toast.makeText(context, "Scanner failed to start", Toast.LENGTH_SHORT).show()
        }
    }

    // 🔥 iText দিয়ে হাই-কোয়ালিটি এবং OOM সেফ পিডিএফ জেনারেটর
    private fun createSmartPdfWithAI() {
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.btnConvert.isEnabled = false
        val fitToA4 = binding.cbAutoFitA4.isChecked
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileName = "AI_Scanned_Doc_${System.currentTimeMillis()}.pdf"
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                
                // A4 সাইজ ডকুমেন্ট সেটআপ
                val document = Document(if (fitToA4) PageSize.A4 else PageSize.A4, 0f, 0f, 0f, 0f)
                val writer = PdfWriter.getInstance(document, FileOutputStream(file))
                document.open()

                selectedUris.forEach { uri ->
                    val inputStream = requireContext().contentResolver.openInputStream(uri)
                    val originalBitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()

                    if (originalBitmap != null) {
                        // ইমেজ কম্প্রেশন (যাতে সাইজ ছোট থাকে কিন্তু কোয়ালিটি ভালো থাকে)
                        val stream = ByteArrayOutputStream()
                        originalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                        val iTextImage = Image.getInstance(stream.toByteArray())

                        if (fitToA4) {
                            // 🔥 AI Smart Scale: ইমেজকে A4 পেজের সাথে সুন্দরভাবে ফিট করবে
                            document.setPageSize(PageSize.A4)
                            document.newPage()
                            
                            val widthScale = PageSize.A4.width / iTextImage.width
                            val heightScale = PageSize.A4.height / iTextImage.height
                            val scale = Math.min(widthScale, heightScale)

                            iTextImage.scaleAbsolute(iTextImage.width * scale, iTextImage.height * scale)
                            
                            // Center Alignment
                            val xPos = (PageSize.A4.width - iTextImage.scaledWidth) / 2
                            val yPos = (PageSize.A4.height - iTextImage.scaledHeight) / 2
                            iTextImage.setAbsolutePosition(xPos, yPos)
                        } else {
                            // অরিজিনাল সাইজ অনুযায়ী পেজ তৈরি
                            document.setPageSize(com.itextpdf.text.Rectangle(iTextImage.width, iTextImage.height))
                            document.newPage()
                            iTextImage.setAbsolutePosition(0f, 0f)
                        }

                        document.add(iTextImage)
                        originalBitmap.recycle() // মেমোরি লিক রোধ করা
                    }
                }

                document.close()
                writer.close()

                withContext(Dispatchers.Main) {
                    NotificationUtils.showDownloadNotification(requireContext(), file, "application/pdf")
                    Toast.makeText(context, "PDF Created Successfully!", Toast.LENGTH_LONG).show()
                    binding.loadingOverlay.visibility = View.GONE
                    binding.btnConvert.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    binding.loadingOverlay.visibility = View.GONE
                    binding.btnConvert.isEnabled = true
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
