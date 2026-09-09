package com.convert.smartpdf.ui

import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.convert.smartpdf.NotificationUtils
import com.convert.smartpdf.R
import com.convert.smartpdf.databinding.FragmentTextToPdfBinding
import com.itextpdf.text.Document
import com.itextpdf.text.Element
import com.itextpdf.text.Font
import com.itextpdf.text.PageSize
import com.itextpdf.text.Paragraph
import com.itextpdf.text.pdf.PdfWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class TextToPdfFragment : Fragment(R.layout.fragment_text_to_pdf) {

    private var _binding: FragmentTextToPdfBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentTextToPdfBinding.bind(view)

        setupUI()

        binding.btnAiEnhance.setOnClickListener {
            val currentText = binding.etPdfContent.text.toString()
            if (currentText.isNotBlank()) {
                applyAiFormatting(currentText)
            } else {
                Toast.makeText(context, "Please enter some text first", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCreatePdf.setOnClickListener {
            val text = binding.etPdfContent.text.toString()
            if (text.isNotBlank()) {
                generateProfessionalPdf(text)
            } else {
                Toast.makeText(context, "Text content cannot be empty", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupUI() {
        // Alignment Spinner Setup
        val alignments = arrayOf("Left Align", "Center Align", "Right Align", "Justify")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, alignments)
        binding.spinnerAlignment.adapter = adapter

        // Font Size SeekBar logic
        binding.sbFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvFontSizeLabel.text = "$progress pt"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // 🔥 AI Text Formatting Algorithm (Smart Punctuation & Paragraphing)
    private fun applyAiFormatting(rawText: String) {
        Toast.makeText(context, "AI is formatting text...", Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch(Dispatchers.Default) {
            // ১. অতিরিক্ত স্পেস রিমুভ করা
            var formattedText = rawText.trim().replace(Regex(" {2,}"), " ")
            
            // ২. যতিচিহ্নের পর স্পেস ঠিক করা (যেমন: "word,word" -> "word, word")
            formattedText = formattedText.replace(Regex("([.,!?])([A-Za-z0-9])"), "$1 $2")
            
            // ৩. বাক্যের প্রথম অক্ষর বড় হাতের (Capitalize) করা
            val sentences = formattedText.split(Regex("(?<=[.!?])\\s+"))
            formattedText = sentences.joinToString(" ") { sentence ->
                sentence.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }

            // ৪. প্যারাগ্রাফ ব্রেক ঠিক করা
            formattedText = formattedText.replace(Regex("\n{3,}"), "\n\n")

            withContext(Dispatchers.Main) {
                binding.etPdfContent.setText(formattedText)
                binding.etPdfContent.setSelection(formattedText.length) // কার্সার শেষে নেওয়া
                Toast.makeText(context, "Text formatted for professional look!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 🔥 iText Library ব্যবহার করে মাল্টি-পেজ পিডিএফ জেনারেশন
    private fun generateProfessionalPdf(content: String) {
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.btnCreatePdf.isEnabled = false

        val fontSize = binding.sbFontSize.progress.toFloat()
        val alignment = when (binding.spinnerAlignment.selectedItemPosition) {
            1 -> Element.ALIGN_CENTER
            2 -> Element.ALIGN_RIGHT
            3 -> Element.ALIGN_JUSTIFIED
            else -> Element.ALIGN_LEFT
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileName = "Document_${System.currentTimeMillis()}.pdf"
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                
                // A4 সাইজ ডকুমেন্ট এবং মার্জিন সেটআপ
                val document = Document(PageSize.A4, 50f, 50f, 50f, 50f)
                val writer = PdfWriter.getInstance(document, FileOutputStream(file))
                document.open()

                // ফন্ট এবং স্টাইল তৈরি
                val font = Font(Font.FontFamily.HELVETICA, fontSize, Font.NORMAL)
                
                // প্যারাগ্রাফ তৈরি এবং সেটিং অ্যাপ্লাই
                val paragraph = Paragraph(content, font)
                paragraph.alignment = alignment
                
                // iText অটোমেটিক টেক্সট র‍্যাপ (Wrap) এবং নতুন পাতা তৈরি করে নেবে
                document.add(paragraph)

                document.close()
                writer.close()

                withContext(Dispatchers.Main) {
                    binding.loadingOverlay.visibility = View.GONE
                    binding.btnCreatePdf.isEnabled = true
                    NotificationUtils.showDownloadNotification(requireContext(), file, "application/pdf")
                    Toast.makeText(context, "Professional PDF Created Successfully!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.loadingOverlay.visibility = View.GONE
                    binding.btnCreatePdf.isEnabled = true
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
