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
import com.convert.smartpdf.databinding.FragmentSplitPdfBinding
import com.itextpdf.text.Document
import com.itextpdf.text.pdf.PdfCopy
import com.itextpdf.text.pdf.PdfReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SplitPdfFragment : Fragment(R.layout.fragment_split_pdf) {

    private var _binding: FragmentSplitPdfBinding? = null
    private val binding get() = _binding!!
    private var selectedUri: Uri? = null
    private var totalPdfPages = 0
    private val pageList = mutableListOf<Bitmap>()
    private lateinit var adapter: PageThumbAdapter

    private val pickPdfLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedUri = it
            loadPdfPages(it)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSplitPdfBinding.bind(view)

        view.findViewById<View>(R.id.btnBack).setOnClickListener { requireActivity().onBackPressed() }
        binding.btnSelectPdf.setOnClickListener { pickPdfLauncher.launch("application/pdf") }
        binding.btnExtractOdd.setOnClickListener { binding.etPageRange.setText("odd") }
        binding.btnExtractEven.setOnClickListener { binding.etPageRange.setText("even") }
        binding.btnSplit.setOnClickListener {
            val rangeInput = binding.etPageRange.text.toString()
            if (rangeInput.isNotEmpty() && selectedUri != null) {
                processSmartSplit(selectedUri!!, rangeInput)
            } else {
                Toast.makeText(context, "Please enter a valid page range", Toast.LENGTH_SHORT).show()
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
                totalPdfPages = renderer.pageCount
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
                    binding.tvTotalPages.visibility = View.VISIBLE
                    binding.tvTotalPages.text = "Total Pages: $totalPdfPages"
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

    private fun parsePageRanges(input: String, total: Int): List<Int> {
        val pages = mutableListOf<Int>()
        when (input.trim().lowercase()) {
            "odd" -> (1..total step 2).forEach { pages.add(it) }
            "even" -> (2..total step 2).forEach { pages.add(it) }
            else -> input.split(",").forEach { part ->
                val trimmed = part.trim()
                if (trimmed.contains("-")) {
                    val (s, e) = trimmed.split("-")
                    (s.trim().toInt()..e.trim().toInt()).forEach { pages.add(it) }
                } else {
                    pages.add(trimmed.toInt())
                }
            }
        }
        return pages.filter { it in 1..total }
    }

    private fun processSmartSplit(uri: Uri, rangeInput: String) {
        binding.loadingOverlay.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pagesToExtract = parsePageRanges(rangeInput, totalPdfPages)
                if (pagesToExtract.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        binding.loadingOverlay.visibility = View.GONE
                        Toast.makeText(context, "Invalid page range", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                val reader = PdfReader(inputStream)
                val fileName = "Split_${System.currentTimeMillis()}.pdf"
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                val document = Document()
                val copy = PdfCopy(document, FileOutputStream(file))
                document.open()
                pagesToExtract.forEach { pageNum -> copy.addPage(copy.getImportedPage(reader, pageNum)) }
                document.close(); reader.close()
                withContext(Dispatchers.Main) {
                    binding.loadingOverlay.visibility = View.GONE
                    NotificationUtils.showDownloadNotification(requireContext(), file, "application/pdf")
                    Toast.makeText(context, "${pagesToExtract.size} pages saved!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.loadingOverlay.visibility = View.GONE
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
