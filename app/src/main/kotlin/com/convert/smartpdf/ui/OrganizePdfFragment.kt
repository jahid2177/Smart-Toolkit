package com.convert.smartpdf.ui

import android.app.ProgressDialog
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Matrix
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
import com.itextpdf.text.Document
import com.itextpdf.text.pdf.PdfCopy
import com.itextpdf.text.pdf.PdfDictionary
import com.itextpdf.text.pdf.PdfName
import com.itextpdf.text.pdf.PdfNumber
import com.itextpdf.text.pdf.PdfReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

class OrganizePdfFragment : Fragment(R.layout.fragment_organize_pdf) {

    // ডেটা মডেল
    data class PdfPageModel(
        val originalPageNum: Int, // 1-based index for iText
        var thumbnail: Bitmap,
        var totalRotation: Int = 0 // Rotation angle (0, 90, 180, 270)
    )

    private val pageList = mutableListOf<PdfPageModel>()
    private lateinit var adapter: OrganizeAdapter
    private var selectedPdfUri: Uri? = null

    private val pickPdfLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedPdfUri = it
            loadPdfPages(it)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnSelect = view.findViewById<View>(R.id.btnSelectPdf)
        val btnSave = view.findViewById<View>(R.id.btnSave)
        val rvPages = view.findViewById<RecyclerView>(R.id.rvPages)
        val tvSubtitle = view.findViewById<View>(R.id.tvSubtitle)

        view.findViewById<View>(R.id.btnBack).setOnClickListener { requireActivity().onBackPressed() }

        btnSelect.setOnClickListener { pickPdfLauncher.launch("application/pdf") }

        // Adapter & RecyclerView Setup
        adapter = OrganizeAdapter(pageList,
            onRotateClick = { position ->
                val item = pageList[position]
                // থাম্বনেইল রোটেশন
                val matrix = Matrix()
                matrix.postRotate(90f)
                item.thumbnail = Bitmap.createBitmap(item.thumbnail, 0, 0, item.thumbnail.width, item.thumbnail.height, matrix, true)
                item.totalRotation = (item.totalRotation + 90) % 360
                adapter.notifyItemChanged(position)
            },
            onDeleteClick = { position ->
                pageList.removeAt(position)
                adapter.notifyItemRemoved(position)
                if (pageList.isEmpty()) Toast.makeText(context, "No pages left!", Toast.LENGTH_SHORT).show()
            }
        )

        rvPages.layoutManager = GridLayoutManager(context, 3) // 3 Columns
        rvPages.adapter = adapter

        // Drag & Drop Logic (ItemTouchHelper)
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val fromPos = viewHolder.adapterPosition
                val toPos = target.adapterPosition
                Collections.swap(pageList, fromPos, toPos)
                adapter.notifyItemMoved(fromPos, toPos)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        itemTouchHelper.attachToRecyclerView(rvPages)

        btnSave.setOnClickListener {
            if (pageList.isNotEmpty()) saveOrganizedPdf()
        }
    }

    private fun loadPdfPages(uri: Uri) {
        val progressDialog = ProgressDialog(requireContext()).apply {
            setMessage("Extracting Pages..."); setCancelable(false); show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                pageList.clear()
                val fd = requireContext().contentResolver.openFileDescriptor(uri, "r") ?: return@launch
                val renderer = PdfRenderer(fd)

                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    // Memory বাঁচাতে থাম্বনেইল সাইজ ছোট করে রেন্ডার করা হচ্ছে
                    val bitmap = Bitmap.createBitmap(400, 560, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pageList.add(PdfPageModel(originalPageNum = i + 1, thumbnail = bitmap))
                    page.close()
                }
                renderer.close()

                withContext(Dispatchers.Main) {
                    view?.findViewById<View>(R.id.btnSelectPdf)?.visibility = View.GONE
                    view?.findViewById<View>(R.id.rvPages)?.visibility = View.VISIBLE
                    view?.findViewById<View>(R.id.btnSave)?.visibility = View.VISIBLE
                    view?.findViewById<View>(R.id.tvSubtitle)?.visibility = View.VISIBLE
                    adapter.notifyDataSetChanged()
                    progressDialog.dismiss()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(context, "Failed to load PDF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveOrganizedPdf() {
        val progressDialog = ProgressDialog(requireContext()).apply {
            setMessage("Saving Organized PDF..."); setCancelable(false); show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resolver = requireContext().contentResolver
                val inputStream = resolver.openInputStream(selectedPdfUri!!) ?: throw Exception()
                val reader = PdfReader(inputStream)
                
                val fileName = "Organized_${System.currentTimeMillis()}.pdf"
                var outputStream: java.io.OutputStream? = null
                var savedFile: File? = null

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val outputUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    outputStream = outputUri?.let { resolver.openOutputStream(it) }
                    savedFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    savedFile = File(dir, fileName)
                    outputStream = java.io.FileOutputStream(savedFile)
                }

                val document = Document()
                val copy = PdfCopy(document, outputStream)
                document.open()

                // লিস্টে যেভাবে আছে, সেই সিরিয়ালে iText দিয়ে পেজ কপি করা হবে
                for (item in pageList) {
                    val pageDict: PdfDictionary = reader.getPageN(item.originalPageNum)
                    
                    // Rotation যুক্ত করা (যদি ইউজার রোটেট করে থাকে)
                    if (item.totalRotation != 0) {
                        val currentRotation = reader.getPageRotation(item.originalPageNum)
                        val newRotation = (currentRotation + item.totalRotation) % 360
                        pageDict.put(PdfName.ROTATE, PdfNumber(newRotation))
                    }

                    val importedPage = copy.getImportedPage(reader, item.originalPageNum)
                    copy.addPage(importedPage)
                }

                document.close()
                reader.close()

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    savedFile?.let { NotificationUtils.showDownloadNotification(requireContext(), it, "application/pdf") }
                    Toast.makeText(requireContext(), "PDF Saved to Downloads!", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(requireContext(), "Save Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // RecyclerView Adapter
    inner class OrganizeAdapter(
        private val list: List<PdfPageModel>,
        private val onRotateClick: (Int) -> Unit,
        private val onDeleteClick: (Int) -> Unit
    ) : RecyclerView.Adapter<OrganizeAdapter.ViewHolder>() {

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
            val item = list[position]
            holder.ivThumbnail.setImageBitmap(item.thumbnail)
            holder.tvPageNumber.text = item.originalPageNum.toString() // অরিজিনাল পেজ নাম্বার ব্যাজ

            holder.btnRotate.setOnClickListener { onRotateClick(holder.adapterPosition) }
            holder.btnDelete.setOnClickListener { onDeleteClick(holder.adapterPosition) }
        }

        override fun getItemCount() = list.size
    }
}
