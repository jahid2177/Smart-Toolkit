package com.convert.smartpdf.ui

import android.app.ProgressDialog
import android.graphics.Bitmap
import android.graphics.Matrix
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
import com.convert.smartpdf.databinding.FragmentRotatePdfBinding
import com.itextpdf.text.pdf.PdfName
import com.itextpdf.text.pdf.PdfNumber
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.PdfStamper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class RotatePdfFragment : Fragment(R.layout.fragment_rotate_pdf) {

    private var _binding: FragmentRotatePdfBinding? = null
    private val binding get() = _binding!!
    private var selectedUri: Uri? = null

    data class RotatePageModel(var thumbnail: Bitmap, var rotation: Int = 0)

    private val pageList = mutableListOf<RotatePageModel>()
    private lateinit var adapter: RotatePageAdapter

    private val pickPdfLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedUri = it
            loadPdfPages(it)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentRotatePdfBinding.bind(view)

        view.findViewById<View>(R.id.btnBack).setOnClickListener { requireActivity().onBackPressed() }
        binding.btnSelectPdf.setOnClickListener { pickPdfLauncher.launch("application/pdf") }

        // All pages buttons
        binding.btnRotateLeft.setOnClickListener { rotateAllPages(-90) }
        binding.btnRotateRight.setOnClickListener { rotateAllPages(90) }
        binding.btnFlip.setOnClickListener { rotateAllPages(180) }

        binding.btnSavePdf.setOnClickListener {
            selectedUri?.let { uri -> processLosslessRotation(uri) }
        }

        adapter = RotatePageAdapter(pageList,
            onRotateClick = { position ->
                val item = pageList[position]
                val matrix = Matrix().apply { postRotate(90f) }
                item.thumbnail = Bitmap.createBitmap(item.thumbnail, 0, 0, item.thumbnail.width, item.thumbnail.height, matrix, true)
                item.rotation = (item.rotation + 90) % 360
                adapter.notifyItemChanged(position)
            }
        )
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
                    pageList.add(RotatePageModel(bmp))
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

    private fun rotateAllPages(angle: Int) {
        val matrix = Matrix().apply { postRotate(angle.toFloat()) }
        pageList.forEachIndexed { index, item ->
            item.thumbnail = Bitmap.createBitmap(item.thumbnail, 0, 0, item.thumbnail.width, item.thumbnail.height, matrix, true)
            item.rotation = (item.rotation + angle + 360) % 360
        }
        adapter.notifyDataSetChanged()
    }

    private fun processLosslessRotation(uri: Uri) {
        binding.loadingOverlay.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                val reader = PdfReader(inputStream)
                val fileName = "Rotated_${System.currentTimeMillis()}.pdf"
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                val stamper = PdfStamper(reader, FileOutputStream(file))
                for (i in 1..reader.numberOfPages) {
                    val item = pageList.getOrNull(i - 1)
                    if (item != null && item.rotation != 0) {
                        val dict = reader.getPageN(i)
                        val currentRot = dict.getAsNumber(PdfName.ROTATE)?.intValue() ?: 0
                        val newRot = (currentRot + item.rotation + 360) % 360
                        dict.put(PdfName.ROTATE, PdfNumber(newRot))
                    }
                }
                stamper.close(); reader.close()
                withContext(Dispatchers.Main) {
                    binding.loadingOverlay.visibility = View.GONE
                    NotificationUtils.showDownloadNotification(requireContext(), file, "application/pdf")
                    Toast.makeText(context, "Rotation Applied Successfully!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.loadingOverlay.visibility = View.GONE
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    inner class RotatePageAdapter(
        private val list: List<RotatePageModel>,
        private val onRotateClick: (Int) -> Unit
    ) : RecyclerView.Adapter<RotatePageAdapter.ViewHolder>() {

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
            holder.ivThumbnail.setImageBitmap(list[position].thumbnail)
            holder.tvPageNumber.text = (position + 1).toString()
            holder.btnDelete.visibility = View.GONE
            holder.btnRotate.setOnClickListener { onRotateClick(holder.adapterPosition) }
        }

        override fun getItemCount() = list.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
