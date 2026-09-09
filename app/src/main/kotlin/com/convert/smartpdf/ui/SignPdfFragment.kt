package com.convert.smartpdf.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.convert.smartpdf.NotificationUtils
import com.convert.smartpdf.R
import com.convert.smartpdf.databinding.FragmentSignPdfBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.itextpdf.text.Image
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.PdfStamper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.FileOutputStream


class SignPdfFragment : Fragment(R.layout.fragment_sign_pdf) {

    private var _binding: FragmentSignPdfBinding? = null
    private val binding get() = _binding!!
    
    private var pdfRenderer: PdfRenderer? = null
    private var selectedUri: Uri? = null
    private var selectedSignatureBitmap: Bitmap? = null
    private var isPlacementMode = false

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Notification permission required to show saved file", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickPdfLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { 
            selectedUri = it
            setupPdfViewer(it) 
        }
    }

    private val importImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val inputStream = requireContext().contentResolver.openInputStream(it)
                    val originalBitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                    
                    if (originalBitmap != null) {
                        // 🔥 AI Ink Extractor কল করা হচ্ছে
                        val extractedInk = extractInkWithAI(originalBitmap, isBlueInk = true)
                        withContext(Dispatchers.Main) { showExtractedSignatureDialog(extractedInk) }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Error loading image", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSignPdfBinding.bind(view)
        
        checkNotificationPermission()

        binding.btnSelectPdf.setOnClickListener { pickPdfLauncher.launch("application/pdf") }
        binding.btnSignAction.setOnClickListener { showModernBottomSheet() }
        binding.btnSavePdf.setOnClickListener { saveFinalPdf() }
        
        setupPlacementListener()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // 🔥 AI Algorithm: অ্যাডাপ্টিভ থ্রেশহোল্ডিং ব্যবহার করে কাগজের ছায়া সরিয়ে কলমের কালি আলাদা করা
    private fun extractInkWithAI(bitmap: Bitmap, isBlueInk: Boolean): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // ছবির এভারেজ ব্রাইটনেস মাপা (Lighting Analysis)
        var sumLightness = 0L
        for (pixel in pixels) {
            sumLightness += (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
        }
        val avgLightness = (sumLightness / pixels.size).toInt()
        
        // AI Threshold: গড় আলোর চেয়ে গাঢ় অংশগুলোকে কালি হিসেবে ধরা হবে
        val threshold = (avgLightness * 0.85).toInt() 

        for (i in pixels.indices) {
            val r = Color.red(pixels[i])
            val g = Color.green(pixels[i])
            val b = Color.blue(pixels[i])
            val luminance = (r * 0.299 + g * 0.587 + b * 0.114).toInt()

            if (luminance > threshold) {
                pixels[i] = Color.TRANSPARENT // কাগজের ব্যাকগ্রাউন্ড স্বচ্ছ করে দেওয়া হলো
            } else {
                // কালিকে প্রো-লেভেল ব্লু বা ব্ল্যাকে রঙ করা
                pixels[i] = if (isBlueInk) Color.argb(255, 0, 51, 153) else Color.BLACK
            }
        }
        outBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return outBitmap
    }

    private fun showExtractedSignatureDialog(extractedBitmap: Bitmap) {
        if (!isAdded) return
        val dialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_draw_signature) // আপনার আগের লেআউটটিই ব্যবহার হবে
        
        val preview = dialog.findViewById<ImageView>(R.id.ivPreview)
        val pad = dialog.findViewById<View>(R.id.signaturePad)
        val seekBar = dialog.findViewById<SeekBar>(R.id.thresholdSeekBar)
        val tvLabel = dialog.findViewById<TextView>(R.id.tvThresholdLabel)
        
        pad.visibility = View.GONE
        preview.visibility = View.VISIBLE
        seekBar.visibility = View.GONE // AI কাজ করায় এর আর দরকার নেই
        tvLabel?.text = "✨ AI Extracted Signature"
        
        preview.setImageBitmap(extractedBitmap)
        
        dialog.findViewById<Button>(R.id.btnDone).setOnClickListener {
            selectedSignatureBitmap = extractedBitmap
            isPlacementMode = true
            Toast.makeText(context, "Tap anywhere on the PDF to place signature", Toast.LENGTH_LONG).show()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun setupPdfViewer(uri: Uri) {
        if (!isAdded) return
        try {
            val fd = requireContext().contentResolver.openFileDescriptor(uri, "r") ?: return
            pdfRenderer = PdfRenderer(fd)
            
            binding.pdfViewPager.visibility = View.VISIBLE
            // আপনার তৈরি করা PdfPageAdapter এখানে যুক্ত হচ্ছে
            binding.pdfViewPager.adapter = PdfPageAdapter(pdfRenderer!!) 
            binding.tvPageIndicator.visibility = View.VISIBLE
            binding.tvPageIndicator.text = "Page 1 of ${pdfRenderer!!.pageCount}"
            
            binding.btnSelectPdf.visibility = View.GONE
            binding.bottomBar.visibility = View.VISIBLE
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open PDF", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPlacementListener() {
        binding.pdfViewPager.getChildAt(0).setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && isPlacementMode) {
                placeSignature(event.x, event.y)
                isPlacementMode = false
            }
            false
        }
    }

    private fun placeSignature(x: Float, y: Float) {
        val bitmap = selectedSignatureBitmap ?: return
        
        val signatureView = DraggableSignatureView(requireContext(), bitmap) { viewToRemove ->
            binding.signatureContainer.removeView(viewToRemove)
        }
        
        signatureView.x = x - (signatureView.sigWidth / 2)
        signatureView.y = y - (signatureView.sigHeight / 2)
        
        binding.signatureContainer.addView(signatureView)
    }

    private fun showModernBottomSheet() {
        val options = arrayOf("✍️ Draw New Signature", "📸 Import from Gallery (AI Scan)")
        AlertDialog.Builder(requireContext())
            .setTitle("Add Signature")
            .setItems(options) { _, which ->
                when(which) {
                    0 -> showDrawDialog()
                    1 -> importImageLauncher.launch("image/*")
                }
            }.show()
    }

    private fun showDrawDialog() {
        if (!isAdded) return
        val drawDialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        drawDialog.setContentView(R.layout.dialog_draw_signature)
        
        val pad = drawDialog.findViewById<com.convert.smartpdf.ui.SignatureView>(R.id.signaturePad)
        drawDialog.findViewById<SeekBar>(R.id.thresholdSeekBar).visibility = View.GONE
        
        drawDialog.findViewById<Button>(R.id.btnDone).setOnClickListener {
            selectedSignatureBitmap = pad.getSignatureBitmap()
            isPlacementMode = true
            Toast.makeText(context, "Tap anywhere on the PDF to place signature", Toast.LENGTH_LONG).show()
            drawDialog.dismiss()
        }
        drawDialog.show()
    }

    // 🔥 iText দিয়ে নিখুঁত কোঅর্ডিনেট সিঙ্ক করে পিডিএফ সেভ করা
    private fun saveFinalPdf() {
        val uri = selectedUri ?: return
        val container = binding.signatureContainer
        if (container.childCount == 0) {
            Toast.makeText(context, "No signatures placed!", Toast.LENGTH_SHORT).show()
            return
        }

        binding.loadingOverlay.visibility = View.VISIBLE
        binding.btnSavePdf.isEnabled = false

        val signatureDataList = mutableListOf<Triple<Bitmap, FloatArray, FloatArray>>()
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is DraggableSignatureView) {
                val finalX = child.x + child.padding
                val finalY = child.y + child.padding
                signatureDataList.add(Triple(child.signatureBitmap, floatArrayOf(finalX, finalY), floatArrayOf(child.sigWidth, child.sigHeight)))
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resolver = requireContext().contentResolver
                val inputStream = resolver.openInputStream(uri) ?: throw Exception("Cannot read original PDF")
                val reader = PdfReader(inputStream)
                
                val fileName = "AI_Signed_${System.currentTimeMillis()}.pdf"
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val savedFile = File(downloadsDir, fileName)
                
                val outputStream = FileOutputStream(savedFile)
                val stamper = PdfStamper(reader, outputStream)
                
                var currentPageIndex = 0
                var viewPagerWidth = 1f
                var viewPagerHeight = 1f
                
                withContext(Dispatchers.Main) { 
                    currentPageIndex = binding.pdfViewPager.currentItem
                    viewPagerWidth = binding.pdfViewPager.width.toFloat()
                    viewPagerHeight = binding.pdfViewPager.height.toFloat()
                }

                val pdfPageSize = reader.getPageSize(currentPageIndex + 1)
                val scaleX = pdfPageSize.width / viewPagerWidth
                val scaleY = pdfPageSize.height / viewPagerHeight
                val overContent = stamper.getOverContent(currentPageIndex + 1)

                for (data in signatureDataList) {
                    val bitmap = data.first
                    val pos = data.second
                    val size = data.third

                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    val iTextImage = Image.getInstance(stream.toByteArray())

                    // Android-এর (0,0) টপ-লেফট, কিন্তু PDF-এর (0,0) বটম-লেফট। তাই Y অ্যাক্সিস রিভার্স করা হয়েছে।
                    val finalX = pos[0] * scaleX
                    val finalY = (viewPagerHeight - (pos[1] + size[1])) * scaleY

                    iTextImage.setAbsolutePosition(finalX, finalY)
                    iTextImage.scaleAbsolute(size[0] * scaleX, size[1] * scaleY)
                    overContent.addImage(iTextImage)
                }

                stamper.close()
                reader.close()
                inputStream.close()

                withContext(Dispatchers.Main) { 
                    binding.loadingOverlay.visibility = View.GONE
                    binding.btnSavePdf.isEnabled = true
                    container.removeAllViews()
                    NotificationUtils.showDownloadNotification(requireContext(), savedFile, "application/pdf")
                    Toast.makeText(context, "Digitally Signed & Saved Successfully!", Toast.LENGTH_LONG).show() 
                }
            } catch (t: Throwable) { 
                withContext(Dispatchers.Main) {
                    binding.loadingOverlay.visibility = View.GONE
                    binding.btnSavePdf.isEnabled = true
                    Toast.makeText(context, "Error: ${t.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pdfRenderer?.close()
        pdfRenderer = null
        _binding = null
    }

    // =========================================================================
    // Advanced Draggable Signature View
    // =========================================================================
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    inner class DraggableSignatureView(
        context: Context,
        val signatureBitmap: Bitmap,
        private val onDelete: (DraggableSignatureView) -> Unit
    ) : View(context) {

        var sigWidth = 350f
        var sigHeight = 0f
        val padding = 60f 
        private val aspectRatio = signatureBitmap.height.toFloat() / signatureBitmap.width.toFloat()

        private var lastX = 0f
        private var lastY = 0f
        private var mode = 0 

        private val borderPaint = Paint().apply {
            color = Color.parseColor("#00BFA5") 
            style = Paint.Style.STROKE
            strokeWidth = 4f
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }
        private val closeBgPaint = Paint().apply { color = Color.parseColor("#E53935"); style = Paint.Style.FILL }
        private val resizeBgPaint = Paint().apply { color = Color.parseColor("#FF9800"); style = Paint.Style.FILL }
        private val iconPaint = Paint().apply { color = Color.WHITE; strokeWidth = 5f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

        init { sigHeight = sigWidth * aspectRatio }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension((sigWidth + padding * 2).toInt(), (sigHeight + padding * 2).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val destRect = RectF(padding, padding, padding + sigWidth, padding + sigHeight)
            canvas.drawBitmap(signatureBitmap, null, destRect, null)
            canvas.drawRect(destRect, borderPaint)

            // Close Button
            canvas.drawCircle(padding, padding, 35f, closeBgPaint)
            canvas.drawLine(padding - 12f, padding - 12f, padding + 12f, padding + 12f, iconPaint)
            canvas.drawLine(padding + 12f, padding - 12f, padding - 12f, padding + 12f, iconPaint)

            // Resize Button
            canvas.drawCircle(padding + sigWidth, padding + sigHeight, 35f, resizeBgPaint)
            canvas.drawLine(padding + sigWidth - 12f, padding + sigHeight - 12f, padding + sigWidth + 12f, padding + sigHeight + 12f, iconPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val ex = event.x; val ey = event.y
            val rawX = event.rawX; val rawY = event.rawY

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (isInsideCircle(ex, ey, padding, padding, 60f)) { onDelete(this); return true }
                    if (isInsideCircle(ex, ey, padding + sigWidth, padding + sigHeight, 80f)) {
                        mode = 2; lastX = rawX; lastY = rawY; return true
                    }
                    if (ex >= padding && ex <= padding + sigWidth && ey >= padding && ey <= padding + sigHeight) {
                        mode = 1; lastX = rawX; lastY = rawY; return true
                    }
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = rawX - lastX
                    val dy = rawY - lastY
                    if (mode == 1) { this.x += dx; this.y += dy } 
                    else if (mode == 2) { 
                        sigWidth += dx; if (sigWidth < 150f) sigWidth = 150f
                        sigHeight = sigWidth * aspectRatio; requestLayout() 
                    }
                    lastX = rawX; lastY = rawY; invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
            }
            return true
        }

        private fun isInsideCircle(x: Float, y: Float, cx: Float, cy: Float, radius: Float) = ((x - cx) * (x - cx) + (y - cy) * (y - cy)) <= (radius * radius)
    }
}
