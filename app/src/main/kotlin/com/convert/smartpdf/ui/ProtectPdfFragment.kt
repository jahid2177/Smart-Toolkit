package com.convert.smartpdf.ui

import android.app.ProgressDialog
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
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
import com.convert.smartpdf.databinding.FragmentProtectPdfBinding
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.PdfStamper
import com.itextpdf.text.pdf.PdfWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom

class ProtectPdfFragment : Fragment(R.layout.fragment_protect_pdf) {

    private var _binding: FragmentProtectPdfBinding? = null
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
        _binding = FragmentProtectPdfBinding.bind(view)

        view.findViewById<View>(R.id.btnBack).setOnClickListener { requireActivity().onBackPressed() }

        setupPasswordAI()

        binding.btnSelectPdf.setOnClickListener { pickPdfLauncher.launch("application/pdf") }
        binding.btnAiGenerate.setOnClickListener { generateAiPassword() }
        binding.btnProtect.setOnClickListener {
            val password = binding.etPassword.text.toString()
            if (password.isNotEmpty() && selectedUri != null) {
                encryptPdf(selectedUri!!, password)
            } else {
                Toast.makeText(context, "Please set a password", Toast.LENGTH_SHORT).show()
            }
        }

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

    private fun setupPasswordAI() {
        binding.etPassword.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val pass = s.toString()
                if (pass.isEmpty()) {
                    binding.strengthMeter.visibility = View.INVISIBLE
                    binding.tvStrengthLabel.visibility = View.INVISIBLE
                } else {
                    binding.strengthMeter.visibility = View.VISIBLE
                    binding.tvStrengthLabel.visibility = View.VISIBLE
                    updateStrengthUI(pass)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun updateStrengthUI(password: String) {
        val strength = calculateStrength(password)
        val params = binding.strengthMeter.layoutParams
        params.width = (binding.etPassword.width * (strength / 100f)).toInt()
        binding.strengthMeter.layoutParams = params
        when {
            strength < 40 -> {
                binding.strengthMeter.setBackgroundColor(android.graphics.Color.RED)
                binding.tvStrengthLabel.text = "Password Strength: Weak ⚠️"
                binding.tvStrengthLabel.setTextColor(android.graphics.Color.RED)
            }
            strength < 75 -> {
                binding.strengthMeter.setBackgroundColor(android.graphics.Color.YELLOW)
                binding.tvStrengthLabel.text = "Password Strength: Moderate 🆗"
                binding.tvStrengthLabel.setTextColor(android.graphics.Color.YELLOW)
            }
            else -> {
                binding.strengthMeter.setBackgroundColor(android.graphics.Color.GREEN)
                binding.tvStrengthLabel.text = "Password Strength: Strong ✨"
                binding.tvStrengthLabel.setTextColor(android.graphics.Color.GREEN)
            }
        }
    }

    private fun calculateStrength(password: String): Int {
        var score = 0
        if (password.length > 8) score += 30
        if (password.any { it.isDigit() }) score += 20
        if (password.any { it.isUpperCase() }) score += 25
        if (password.any { !it.isLetterOrDigit() }) score += 25
        return score
    }

    private fun generateAiPassword() {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*"
        val random = SecureRandom()
        val sb = StringBuilder()
        for (i in 0 until 12) sb.append(chars[random.nextInt(chars.length)])
        binding.etPassword.setText(sb.toString())
        Toast.makeText(context, "AI Generated a Secure Password!", Toast.LENGTH_SHORT).show()
    }

    private fun encryptPdf(uri: Uri, password: String) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                val reader = PdfReader(inputStream)
                val fileName = "AI_Protected_${System.currentTimeMillis()}.pdf"
                val outputFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                val stamper = PdfStamper(reader, FileOutputStream(outputFile))
                if (binding.cbWipeMetadata.isChecked) {
                    val info = reader.info
                    info.clear()
                    stamper.moreInfo = hashMapOf("Author" to "Smart PDF AI", "Creator" to "Smart PDF Toolkit")
                }
                stamper.setEncryption(password.toByteArray(), password.toByteArray(), PdfWriter.ALLOW_PRINTING, PdfWriter.ENCRYPTION_AES_256)
                stamper.close(); reader.close()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    NotificationUtils.showDownloadNotification(requireContext(), outputFile, "application/pdf")
                    Toast.makeText(context, "PDF Secured with AI Encryption!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
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
        _binding = null
    }
}
