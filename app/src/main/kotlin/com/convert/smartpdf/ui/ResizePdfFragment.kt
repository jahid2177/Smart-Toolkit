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
import android.widget.ArrayAdapter
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
import com.convert.smartpdf.databinding.FragmentResizePdfBinding
import com.itextpdf.text.PageSize
import com.itextpdf.text.pdf.PdfArray
import com.itextpdf.text.pdf.PdfName
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.PdfStamper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ResizePdfFragment : Fragment(R.layout.fragment_resize_pdf) {

    private var binding: FragmentResizePdfBinding? = null
    private var selectedUri: Uri? = null
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
        binding = FragmentResizePdfBinding.bind(view)

        view.findViewById<View>(R.id.btnBack).setOnClickListener { requireActivity().onBackPressed() }

        val sizes = arrayOf("A4 (595x842)", "LETTER (612x792)", "LEGAL (612x1008)", "A3 (842x1191)")
        binding?.spinnerPageSize?.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, sizes)

        binding?.btnSelectPdf?.setOnClickListener { pickPdfLauncher.launch("application/pdf") }
        binding?.btnResize?.setOnClickListener { selectedUri?.let { resizePdf(it) } }

        adapter = PageThumbAdapter(pageList)
        binding?.rvPages?.layoutManager = GridLayoutManager(context, 3)
        binding?.rvPages?.adapter = adapter
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
                    binding?.btnSelectPdf?.visibility = View.GONE
                    binding?.rvPages?.visibility = View.VISIBLE
                    binding?.bottomOptions?.visibility = View.VISIBLE
                    binding?.tvSubtitle?.visibility = View.VISIBLE
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

    private fun resizePdf(uri: Uri) {
        val selectedSize = when (binding?.spinnerPageSize?.selectedItemPosition) {
            0 -> PageSize.A4
            1 -> PageSize.LETTER
            2 -> PageSize.LEGAL
            else -> PageSize.A3
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                val reader = PdfReader(inputStream)
                val fileName = "Resized_${System.currentTimeMillis()}.pdf"
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                val stamper = PdfStamper(reader, FileOutputStream(file))
                for (i in 1..reader.numberOfPages) {
                    val pageDict = reader.getPageN(i)
                    pageDict.put(PdfName.MEDIABOX, PdfArray(floatArrayOf(0f, 0f, selectedSize.width, selectedSize.height)))
                }
                stamper.close(); reader.close()
                withContext(Dispatchers.Main) {
                    NotificationUtils.showDownloadNotification(requireContext(), file, "application/pdf")
                    Toast.makeText(context, "Resized to ${binding?.spinnerPageSize?.selectedItem}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
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
        binding = null
    }
}
