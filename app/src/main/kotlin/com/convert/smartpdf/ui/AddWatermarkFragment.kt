package com.convert.smartpdf.ui

import android.app.ProgressDialog
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.convert.smartpdf.NotificationUtils
import com.convert.smartpdf.R
import com.convert.smartpdf.databinding.FragmentAddWatermarkBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.itextpdf.text.BaseColor
import com.itextpdf.text.Element
import com.itextpdf.text.pdf.BaseFont
import com.itextpdf.text.pdf.PdfContentByte
import com.itextpdf.text.pdf.PdfGState
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.PdfStamper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class AddWatermarkFragment : Fragment(R.layout.fragment_add_watermark) {

    private var _binding: FragmentAddWatermarkBinding? = null
    private val binding get() = _binding!!
    private var selectedUri: Uri? = null
    private var suggestedText = "CONFIDENTIAL"
    private val pageList = mutableListOf<Bitmap>()
    private lateinit var adapter: PageThumbAdapter

    private val pickPdfLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedUri = it
            loadPdfPages(it)
            analyzeWithAI(it)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentAddWatermarkBinding.bind(view)

        view.findViewById<View>(R.id.btnBack).setOnClickListener { requireActivity().onBackPressed() }
        binding.btnSelectPdf.setOnClickListener { pickPdfLauncher.launch("application/pdf") }
        binding.btnApplyAiText.setOnClickListener { binding.etWatermarkText.setText(suggestedText) }
        binding.btnApplyWatermark.setOnClickListener {
            val text = binding.etWatermarkText.text.toString()
            if (text.isNotEmpty() && selectedUri != null) {
                applyWatermark(selectedUri!!, text)
            } else {
                Toast.makeText(context, "Please enter watermark text", Toast.LENGTH_SHORT).show()
            }
        }

        adapter = PageThumbAdapter(pageList)
        binding.rvPages.layoutManager = GridLayoutManager(context, 3)
        binding.rvPages.adapter = adapter
    }

    private fun loadPdfPages(uri: Uri) {
        val progressDialog = ProgressDialog(requireContext()).apply {
            setMessage("Loading pages..."); setCancelable(false); show()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                pageList.clear()
                val fd = requireContext().contentResolver.openFileDescriptor(uri, "r") ?: return@launch
                val renderer = PdfRenderer(fd)
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    val bmp = Bitmap.createBitmap(400, 560, Bitmap.Config.ARGB_8888)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pageList.add(bmp)
                    page.close()
                }
                renderer.close()
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    binding.btnSelectPdf.visibility = View.GONE
                    binding.rvPages.visibility = View.VISIBLE
                    binding.bottomOptions.visibility = View.VISIBLE
                    binding.tvSubtitle.visibility = View.VISIBLE
                    adapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(context, "Failed to load PDF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun analyzeWithAI(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fd = requireContext().contentResolver.openFileDescriptor(uri, "r")
                val renderer = PdfRenderer(fd!!)
                val page = renderer.openPage(0)
                val bitmap = Bitmap.createBitmap(page.width / 2, page.height / 2, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close(); renderer.close()
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                val text = result.text.uppercase()
                suggestedText = when {
                    text.contains("INVOICE") -> "INVOICE COPY"
                    text.contains("CONTRACT") -> "CONFIDENTIAL"
                    text.contains("REPORT") -> "DRAFT"
                    else -> "CONFIDENTIAL"
                }
            } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun applyWatermark(uri: Uri, text: String) {
        binding.progressBar.visibility = View.VISIBLE
        val opacity = binding.sbOpacity.progress / 100f
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                val reader = PdfReader(inputStream)
                val fileName = "Watermarked_${System.currentTimeMillis()}.pdf"
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                val stamper = PdfStamper(reader, FileOutputStream(file))
                val font = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED)
                val gState = PdfGState().apply { setFillOpacity(opacity) }
                for (i in 1..reader.numberOfPages) {
                    val rect = reader.getPageSizeWithRotation(i)
                    val canvas: PdfContentByte = stamper.getOverContent(i)
                    canvas.saveState()
                    canvas.setGState(gState)
                    canvas.beginText()
                    canvas.setFontAndSize(font, 50f)
                    canvas.setColorFill(BaseColor.GRAY)
                    canvas.showTextAligned(Element.ALIGN_CENTER, text, rect.width / 2, rect.height / 2, 45f)
                    canvas.endText()
                    canvas.restoreState()
                }
                stamper.close(); reader.close()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    NotificationUtils.showDownloadNotification(requireContext(), file, "application/pdf")
                    Toast.makeText(context, "Watermark Applied!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    inner class PageThumbAdapter(private val list: List<Bitmap>) :
        RecyclerView.Adapter<PageThumbAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
            val tvPageNumber: TextView = view.findViewById(R.id.tvPageNumber)
            val btnRotate: View = view.findViewById(R.id.btnRotate)
            val btnDelete: View = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_organize_page, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.ivThumbnail.setImageBitmap(list[position])
            holder.tvPageNumber.text = (position + 1).toString()
            holder.btnRotate.visibility = View.GONE
            holder.btnDelete.visibility = View.GONE
        }

        override fun getItemCount() = list.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
