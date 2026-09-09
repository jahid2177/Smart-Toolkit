package com.convert.smartpdf.ui

import android.app.ProgressDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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
import com.convert.smartpdf.databinding.FragmentPdfToJpgBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PdfToJpgFragment : Fragment(R.layout.fragment_pdf_to_jpg) {

    private var _binding: FragmentPdfToJpgBinding? = null
    private val binding get() = _binding!!
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
        _binding = FragmentPdfToJpgBinding.bind(view)

        view.findViewById<View>(R.id.btnBack).setOnClickListener { requireActivity().onBackPressed() }

        val qualities = arrayOf("Standard (1x)", "High Resolution (2x)", "Ultra Print Quality (3x)")
        binding.spinnerQuality.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, qualities)

        binding.btnSelectPdf.setOnClickListener { pickPdfLauncher.launch("application/pdf") }
        binding.btnConvert.setOnClickListener { selectedUri?.let { uri -> startSmartConversion(uri) } }

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

    private fun startSmartConversion(uri: Uri) {
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.btnConvert.isEnabled = false
        val scale = when (binding.spinnerQuality.selectedItemPosition) { 0 -> 1; 1 -> 2; else -> 3 }
        val autoCrop = binding.cbAutoCrop.isChecked

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fd = requireContext().contentResolver.openFileDescriptor(uri, "r")!!
                val renderer = PdfRenderer(fd)
                val outputDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SmartPDF")
                outputDir.mkdirs()
                var lastFile: File? = null

                for (i in 0 until renderer.pageCount) {
                    withContext(Dispatchers.Main) {
                        binding.tvSelectedFile.text = "Processing Page ${i + 1} of ${renderer.pageCount}..."
                        val prog = ((i + 1).toFloat() / renderer.pageCount * 100).toInt()
                        binding.progressBar.progress = prog
                    }
                    val page = renderer.openPage(i)
                    val bmp = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    val finalBmp = if (autoCrop) autoCropBitmap(bmp) else bmp
                    val file = File(outputDir, "Page_${i + 1}_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(file).use { finalBmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    lastFile = file
                }
                renderer.close()

                withContext(Dispatchers.Main) {
                    binding.loadingOverlay.visibility = View.GONE
                    binding.btnConvert.isEnabled = true
                    lastFile?.let { NotificationUtils.showDownloadNotification(requireContext(), it, "image/jpeg") }
                    Toast.makeText(context, "${renderer.pageCount} pages saved to Pictures/SmartPDF!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.loadingOverlay.visibility = View.GONE
                    binding.btnConvert.isEnabled = true
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun autoCropBitmap(bmp: Bitmap): Bitmap {
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        val result = Bitmap.createBitmap(bmp.width, bmp.height, bmp.config)
        Canvas(result).drawBitmap(bmp, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
        var top = 0; var bottom = result.height - 1; var left = 0; var right = result.width - 1
        outer@ for (y in 0 until result.height) { for (x in 0 until result.width) { if (result.getPixel(x, y) != Color.WHITE) { top = y; break@outer } } }
        outer@ for (y in result.height - 1 downTo top) { for (x in 0 until result.width) { if (result.getPixel(x, y) != Color.WHITE) { bottom = y; break@outer } } }
        if (bottom <= top) return bmp
        return Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
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
