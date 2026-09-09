package com.convert.smartpdf.ui

import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Environment
import android.text.Html
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.convert.smartpdf.NotificationUtils
import com.convert.smartpdf.R
import com.convert.smartpdf.databinding.FragmentHtmlToPdfBinding
import java.io.File
import java.io.FileOutputStream

class HtmlToPdfFragment : Fragment(R.layout.fragment_html_to_pdf) {
    private var binding: FragmentHtmlToPdfBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentHtmlToPdfBinding.bind(view)

        binding?.btnConvert?.setOnClickListener {
            val html = binding?.etHtml?.text.toString()
            if (html.isNotEmpty()) convertHtmlToPdf(html)
        }
    }

    private fun convertHtmlToPdf(htmlCode: String) {
        try {
            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 Size
            val page = pdfDocument.startPage(pageInfo)

            // একটি ভার্চুয়াল ভিউ তৈরি করে তাতে HTML রেন্ডার করা হচ্ছে
            val content = TextView(context)
            content.layout(0, 0, 595, 842)
            content.text = Html.fromHtml(htmlCode, Html.FROM_HTML_MODE_LEGACY)
            content.setTextColor(android.graphics.Color.BLACK)
            content.textSize = 12f
            
            // ক্যানভাসে ড্র করা
            content.draw(page.canvas)
            pdfDocument.finishPage(page)

            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HTML_Convert_${System.currentTimeMillis()}.pdf")
            pdfDocument.writeTo(FileOutputStream(file))
            pdfDocument.close()

            NotificationUtils.showDownloadNotification(requireContext(), file, "application/pdf")
            Toast.makeText(context, "HTML Converted Successfully!", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
