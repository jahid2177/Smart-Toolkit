package com.convert.smartpdf

import android.app.Dialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.convert.smartpdf.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    // আপডেটের জন্য ভেরিয়েবল
    private var downloadID: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        // 🔥 স্প্ল্যাশ স্ক্রিন ইনিশিয়ালাইজ করা হলো (অবশ্যই super.onCreate এর আগে বসাতে হবে)
        installSplashScreen()
        
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setupWithNavController(navController)

        // 🔥 Android 14 (API 34) Crash Fix: রিসিভার সঠিকভাবে চালু করা
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                onDownloadComplete,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            registerReceiver(onDownloadComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        val settingsItem = menu?.add(Menu.NONE, 100, Menu.NONE, "Settings")
        settingsItem?.setIcon(R.drawable.ic_settings_modern) 
        settingsItem?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 100) {
            showModernSettingsSheet()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showModernSettingsSheet() {
        val bottomSheet = BottomSheetDialog(this)
        bottomSheet.setContentView(R.layout.bottom_sheet_settings)

        bottomSheet.findViewById<Button>(R.id.btnCheckUpdate)?.setOnClickListener {
            bottomSheet.dismiss()
            performSmartUpdateCheck()
        }

        bottomSheet.findViewById<Button>(R.id.btnAbout)?.setOnClickListener {
            bottomSheet.dismiss()
            showAboutDialog()
        }
        bottomSheet.show()
    }

    // 🔥 Real GitHub Update Checker (OTA)
    private fun performSmartUpdateCheck() {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Checking for Updates...")
            .setMessage("Connecting to secure server...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val githubRawUrl = "https://raw.githubusercontent.com/faria2177/cricketcarnival2025.github.io/main/update.json"
                val response = URL(githubRawUrl).readText()
                val json = JSONObject(response)

                val latestVersionCode = json.getInt("latestVersionCode")
                val latestVersionName = json.getString("latestVersionName")
                val releaseNotes = json.getString("releaseNotes")
                val downloadUrl = json.getString("downloadUrl")

                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                val currentVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode.toInt()
                } else {
                    packageInfo.versionCode
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (latestVersionCode > currentVersionCode) {
                        showUpdateAvailableDialog(latestVersionName, releaseNotes, downloadUrl)
                    } else {
                        Toast.makeText(this@MainActivity, "✨ You are already using the latest version!", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Update check failed. Please check your internet connection.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 🔥 Update Available Dialog
    private fun showUpdateAvailableDialog(versionName: String, releaseNotes: String, downloadUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("New Update Available! (v$versionName)")
            .setMessage("What's New:\n$releaseNotes")
            .setPositiveButton("Download & Install") { _, _ ->
                startApkDownload(downloadUrl, versionName)
            }
            .setNegativeButton("Later", null)
            .setCancelable(false)
            .show()
    }

    // 🔥 Background Downloader Logic
    private fun startApkDownload(url: String, versionName: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Smart PDF Toolkit Update")
                .setDescription("Downloading version $versionName...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "SmartPDF_Update.apk")
                .setMimeType("application/vnd.android.package-archive")

            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadID = manager.enqueue(request)
            
            Toast.makeText(this, "Downloading update in background...", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start download.", Toast.LENGTH_SHORT).show()
        }
    }

    // 🔥 Auto Install Trigger
    private val onDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadID) {
                installApk()
            }
        }
    }

    private fun installApk() {
        try {
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = manager.getUriForDownloadedFile(downloadID)
            
            if (uri != null) {
                val installIntent = Intent(Intent.ACTION_VIEW)
                installIntent.setDataAndType(uri, "application/vnd.android.package-archive")
                installIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                startActivity(installIntent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to launch installer automatically. Check Downloads folder.", Toast.LENGTH_LONG).show()
        }
    }

    // 🔥 About Dialog
    private fun showAboutDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_about_modern)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)

        dialog.findViewById<Button>(R.id.btnWhatsApp)?.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/qr/MBVW4SWUJZZ3G1"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "WhatsApp is not installed.", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(onDownloadComplete)
        } catch (e: Exception) { }
    }
}
