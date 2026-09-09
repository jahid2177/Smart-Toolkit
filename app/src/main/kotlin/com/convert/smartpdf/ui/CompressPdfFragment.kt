package com.convert.smartpdf.ui

import android.app.ProgressDialog
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.convert.smartpdf.NotificationUtils
import com.convert.smartpdf.R
import com.convert.smartpdf.databinding.FragmentCompressPdfBinding
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

class CompressPdfFragment : Fragment(R.layout.fragment_compress_pdf) {

    private var _binding: FragmentCompressPdfBinding? = null
    private val binding get() = _binding!!
    private var selectedUri: Uri? = null
    private val pageList = mutableListOf<Bitmap>()
    private lateinit var adapter: PageThumbAdapter

    private val pickPdfLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedUri = it
            loadPdfPages(it)
            analyzeFileWithAI(it)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentCompressPdfBinding.bind(view)

        view.findViewById<View>(R.id.btnBack).setOnClickListener { requireActivity().onBackPressed() }

        setupUI()

        binding.btnSelectPdf.setOnClickListener { pickPdfLauncher.launch("application/pdf") }
        binding.btnCompress.setOnClickListener { selectedUri?.let { uri -> startSmartCompression(uri) } }

        adapter = PageThumbAdapter(pageList)
        binding.rvPages.layoutManager = GridLayoutManager(context, 3)
        binding.rvPages.adapter = adapter
    }

    private fun setupUI() {
        val colorModes = arrayOf("Original Color", "Grayscale", "Black & White (Max Compress)")
        binding.spinnerColorMode.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, colorModes)

        binding.sbQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val label = when {
                    progress < 30 -> "Quality: Low ($progress%) - Max Compression"
                    progress < 60 -> "Quality: Medium ($progress%)"
                    progress < 85 -> "Quality: Good ($progress%)"
                    else -> "Quality: High ($progress%) - Min Compression"
                }
                binding.tvQualityLabel.text = label
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
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

    private fun analyzeFileWithAI(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                        val size = if (sizeIndex != -1) it.getLong(sizeIndex) else 0L
                        withContext(Dispatchers.Main) {
                            binding.tvOriginalSize.visibility = View.VISIBLE
                            binding.tvOriginalSize.text = "✨ Original Size: ${Formatter.formatFileSize(context, size)}"
                        }
                    }
                }
            } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun startSmartCompression(uri: Uri) {
        binding.loadingOverlay.visibility = View.VISIBLE
        val quality = binding.sbQuality.progress
        val colorMode = binding.spinnerColorMode.selectedItemPosition
        val sharpen = binding.cbSharpen.isChecked

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fd = requireContext().contentResolver.openFileDescriptor(uri, "r")!!
                val renderer = PdfRenderer(fd)
                val fileName = "Compressed_${System.currentTimeMillis()}.pdf"
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                val document = Document()
                val writer = PdfWriter.getInstance(document, FileOutputStream(file))
                writer.setFullCompression()
                document.open()

                for (i in 0 until renderer.pageCount) {
                    withContext(Dispatchers.Main) {
                        binding.tvProgressText.text = "Compressing Page ${i + 1} of ${renderer.pageCount}..."
                        binding.progressBar.progress = ((i + 1).toFloat() / renderer.pageCount * 100).toInt()
                    }
                    val page = renderer.openPage(i)
                    var bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    // Color mode apply
                    bmp = when (colorMode) {
                        1 -> toGrayscale(bmp)
                        2 -> toBW(bmp)
                        else -> bmp
                    }

                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                    val imgBytes = baos.toByteArray()
                    val itextImg = Image.getInstance(imgBytes)
                    itextImg.scaleToFit(PageSize.A4.width, PageSize.A4.height)
                    itextImg.setAbsolutePosition(0f, 0f)
                    document.setPageSize(PageSize.A4)
                    document.newPage()
                    document.add(itextImg)
                }

                document.close()
                renderer.close()

                withContext(Dispatchers.Main) {
                    binding.loadingOverlay.visibility = View.GONE
                    NotificationUtils.showDownloadNotification(requireContext(), file, "application/pdf")
                    Toast.makeText(context, "Compressed PDF saved! Size: ${Formatter.formatFileSize(context, file.length())}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.loadingOverlay.visibility = View.GONE
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun toGrayscale(src: Bitmap): Bitmap {
        val bmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint()
        val cm = android.graphics.ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return bmp
    }

    private fun toBW(src: Bitmap): Bitmap {
        val gray = toGrayscale(src)
        val bmp = Bitmap.createBitmap(gray.width, gray.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until gray.width) {
            for (y in 0 until gray.height) {
                val pixel = gray.getPixel(x, y)
                val lum = (android.graphics.Color.red(pixel) * 0.299 + android.graphics.Color.green(pixel) * 0.587 + android.graphics.Color.blue(pixel) * 0.114).toInt()
                bmp.setPixel(x, y, if (lum > 128) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            }
        }
        return bmp
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
