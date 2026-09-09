package com.convert.smartpdf.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.convert.smartpdf.R
import com.convert.smartpdf.databinding.FragmentPdfToTextBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PdfToTextFragment : Fragment(R.layout.fragment_pdf_to_text) {

    private var binding: FragmentPdfToTextBinding? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val pickPdfLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { startExtraction(it) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentPdfToTextBinding.bind(view)

        binding?.btnSelectPdf?.setOnClickListener {
            pickPdfLauncher.launch("application/pdf")
        }

        binding?.btnSaveText?.setOnClickListener {
            saveExtractedText()
        }
    }

    private fun startExtraction(uri: Uri) {
        binding?.progressBar?.visibility = View.VISIBLE
        binding?.tvExtractedText?.text = "Extracting text... Please wait."

        lifecycleScope.launch(Dispatchers.IO) {
            val fullText = StringBuilder()
            
            try {
                val fileDescriptor: ParcelFileDescriptor? = requireContext().contentResolver.openFileDescriptor(uri, "r")
                if (fileDescriptor != null) {
                    val renderer = PdfRenderer(fileDescriptor)
                    
                    // প্রতিটি পেজ প্রসেস করা
                    for (i in 0 until renderer.pageCount) {
                        val page = renderer.openPage(i)
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        
                        // ML Kit দিয়ে টেক্সট রিড করা
                        val image = InputImage.fromBitmap(bitmap, 0)
                        val result = withContext(Dispatchers.IO) {
                            try {
                                // Task-কে সিঙ্ক্রোনাসলি ওয়েট করার জন্য
                                com.google.android.gms.tasks.Tasks.await(recognizer.process(image))
                            } catch (e: Exception) { null }
                        }
                        
                        result?.let { fullText.append(it.text).append("\n\n") }
                        
                        page.close()
                    }
                    renderer.close()
                }

                withContext(Dispatchers.Main) {
                    binding?.progressBar?.visibility = View.GONE
                    binding?.tvExtractedText?.text = if (fullText.isEmpty()) "No text found in PDF." else fullText.toString()
                    binding?.btnSaveText?.isEnabled = fullText.isNotEmpty()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding?.progressBar?.visibility = View.GONE
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveExtractedText() {
        val text = binding?.tvExtractedText?.text.toString()
        try {
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), 
                "ExtractedText_${System.currentTimeMillis()}.txt")
            FileOutputStream(file).use { it.write(text.toByteArray()) }
            Toast.makeText(context, "Saved to Downloads: ${file.name}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
