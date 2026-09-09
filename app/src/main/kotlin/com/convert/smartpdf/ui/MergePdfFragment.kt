package com.convert.smartpdf.ui

import android.app.ProgressDialog
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.convert.smartpdf.NotificationUtils
import com.convert.smartpdf.R
import com.convert.smartpdf.databinding.FragmentMergePdfBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.itextpdf.text.Document
import com.itextpdf.text.pdf.PdfCopy
import com.itextpdf.text.pdf.PdfReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.Collections

class MergePdfFragment : Fragment(R.layout.fragment_merge_pdf) {

    private var _binding: FragmentMergePdfBinding? = null
    private val binding get() = _binding!!

    data class PdfPageModel(val pdfUri: Uri, val pdfIndex: Int, val pageNum: Int, var thumbnail: Bitmap)

    private val pageList = mutableListOf<PdfPageModel>()
    private val selectedUris = mutableListOf<Uri>()
    private lateinit var adapter: MergePageAdapter

    private val pickPdfsLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            selectedUris.addAll(uris)
            loadAllPdfPages()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentMergePdfBinding.bind(view)

        view.findViewById<View>(R.id.btnBack).setOnClickListener { requireActivity().onBackPressed() }
        binding.btnSelectPdfs.setOnClickListener { pickPdfsLauncher.launch("application/pdf") }
        binding.btnAddMore.setOnClickListener { pickPdfsLauncher.launch("application/pdf") }
        binding.btnMerge.setOnClickListener { if (selectedUris.size >= 2) startSmartMerge() }

        adapter = MergePageAdapter(pageList)
        binding.rvSelectedPdfs.layoutManager = GridLayoutManager(context, 3)
        binding.rvSelectedPdfs.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.adapterPosition
                val to = target.adapterPosition
                Collections.swap(pageList, from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
        })
        touchHelper.attachToRecyclerView(binding.rvSelectedPdfs)
    }

    private fun loadAllPdfPages() {
        val progressDialog = ProgressDialog(requireContext()).apply {
            setMessage("Loading pages..."); setCancelable(false); show()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                pageList.clear()
                selectedUris.forEachIndexed { pdfIdx, uri ->
                    val fd = requireContext().contentResolver.openFileDescriptor(uri, "r") ?: return@forEachIndexed
                    val renderer = PdfRenderer(fd)
                    for (i in 0 until renderer.pageCount) {
                        val page = renderer.openPage(i)
                        val bmp = Bitmap.createBitmap(400, 560, Bitmap.Config.ARGB_8888)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pageList.add(PdfPageModel(uri, pdfIdx, i + 1, bmp))
                        page.close()
                    }
                    renderer.close()
                }
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    binding.btnSelectPdfs.visibility = View.GONE
                    binding.rvSelectedPdfs.visibility = View.VISIBLE
                    binding.bottomBar.visibility = View.VISIBLE
                    binding.tvSubtitle.visibility = View.VISIBLE
                    binding.tvStatus.text = "✨ ${selectedUris.size} PDFs • ${pageList.size} pages total"
                    adapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(context, "Failed to load: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startSmartMerge() {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val document = Document()
                var outputStream: OutputStream? = null
                val smartTitle = generateSmartName(selectedUris[0])
                val fileName = "${smartTitle}_Merged_${System.currentTimeMillis()}.pdf"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = requireContext().contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    outputStream = uri?.let { requireContext().contentResolver.openOutputStream(it) }
                } else {
                    val file = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                    outputStream = java.io.FileOutputStream(file)
                }

                val copy = PdfCopy(document, outputStream)
                document.open()

                // পেজ লিস্টের অর্ডার অনুযায়ী মার্জ করা হবে (drag & drop এর পর যে অর্ডারে আছে)
                val uriReaders = mutableMapOf<Uri, PdfReader>()
                for (item in pageList) {
                    val reader = uriReaders.getOrPut(item.pdfUri) {
                        PdfReader(requireContext().contentResolver.openInputStream(item.pdfUri))
                    }
                    copy.addPage(copy.getImportedPage(reader, item.pageNum))
                }

                document.close()
                uriReaders.values.forEach { it.close() }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(context, "Merged PDF Saved to Downloads!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun generateSmartName(uri: Uri): String {
        return try {
            val fd = requireContext().contentResolver.openFileDescriptor(uri, "r")
            val renderer = PdfRenderer(fd!!)
            val page = renderer.openPage(0)
            val bitmap = Bitmap.createBitmap(page.width / 2, page.height / 2, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close(); renderer.close()
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            val words = result.text.split(" ").filter { it.length > 3 }.take(3)
            if (words.isNotEmpty()) words.joinToString("_") else "Smart_Doc"
        } catch (e: Exception) { "Merged_Document" }
    }

    inner class MergePageAdapter(private val list: List<PdfPageModel>) :
        RecyclerView.Adapter<MergePageAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
            val tvPageNumber: TextView = view.findViewById(R.id.tvPageNumber)
            val btnDelete: View = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_organize_page, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.ivThumbnail.setImageBitmap(item.thumbnail)
            holder.tvPageNumber.text = item.pageNum.toString()
            holder.btnDelete.setOnClickListener {
                val pos = holder.adapterPosition
                pageList.removeAt(pos)
                notifyItemRemoved(pos)
            }
        }

        override fun getItemCount() = list.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
