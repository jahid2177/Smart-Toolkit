package com.convert.smartpdf

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import java.io.File

object NotificationUtils {

    fun showDownloadNotification(context: Context, file: File, mimeType: String) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            
            // এই ফাংশনটি ফাইলটিকে সিস্টেম ডাউনলোড ম্যানেজারে যুক্ত করে এবং নোটিফিকেশন দেখায়
            downloadManager.addCompletedDownload(
                file.name,              // টাইটেল
                "File Saved Successfully", // ডেসক্রিপশন
                true,                   // মিডিয়া স্ক্যানার (গ্যালারিতে দেখাবে কি না)
                mimeType,               // ফাইলের ধরন (application/pdf বা image/jpeg)
                file.absolutePath,      // ফাইলের লোকেশন
                file.length(),          // ফাইলের সাইজ
                true                    // showNotification (নোটিফিকেশন দেখাবে)
            )
        } catch (e: Exception) {
            // কিছু কিছু ফোনে এটি পারমিশন বা পাথ জনিত কারণে ফেইল হতে পারে
            Toast.makeText(context, "Saved: ${file.name}", Toast.LENGTH_LONG).show()
        }
    }
}
