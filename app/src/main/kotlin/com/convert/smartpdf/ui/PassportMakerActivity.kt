package com.convert.smartpdf.ui

import android.app.AlertDialog
import android.content.ContentValues
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.convert.smartpdf.R
import com.convert.smartpdf.databinding.ActivityPassportMakerBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

class PassportMakerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPassportMakerBinding
    private var selectedImageUri: Uri? = null
    private var currentBitmap: Bitmap? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedImageUri = it
            loadImage(it)
        }
    }

    private val manualCropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            resultUri?.let {
                val bitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(it))
                currentBitmap = bitmap
                binding.ivPreview.setImageBitmap(currentBitmap)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPassportMakerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnUpload.setOnClickListener { pickImageLauncher.launch("image/*") }
        binding.btnCropSize.setOnClickListener { showSizeSelectionDialog() }
        binding.btnBackground.setOnClickListener { showBackgroundSelectionDialog() }
        binding.btnEnhance.setOnClickListener { applyAiStudioEnhance() }
        binding.btnSave.setOnClickListener { showSaveOptionsDialog() }
    }

    private fun loadImage(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
                withContext(Dispatchers.Main) {
                    currentBitmap = bitmap
                    binding.ivPreview.setImageBitmap(bitmap)
                    binding.ivPreview.imageTintList = null
                    binding.tvInstruction.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@PassportMakerActivity, "Error loading image", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    // 🔥 AI Studio Enhance: Brightness, Contrast & Skin Balancing
    private fun applyAiStudioEnhance() {
        val bitmap = currentBitmap ?: return
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.tvLoadingProgress.text = "AI Enhancing Studio Quality..."

        lifecycleScope.launch(Dispatchers.Default) {
            val cm = ColorMatrix(floatArrayOf(
                1.1f, 0f, 0f, 0f, 10f,
                0f, 1.1f, 0f, 0f, 10f,
                0f, 0f, 1.1f, 0f, 10f,
                0f, 0f, 0f, 1f, 0f
            ))
            
            val enhancedBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
            val canvas = Canvas(enhancedBitmap)
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
            canvas.drawBitmap(bitmap, 0f, 0f, paint)

            withContext(Dispatchers.Main) {
                currentBitmap = enhancedBitmap
                binding.ivPreview.setImageBitmap(currentBitmap)
                binding.loadingOverlay.visibility = View.GONE
                Toast.makeText(this@PassportMakerActivity, "Studio Quality Applied! ✨", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSizeSelectionDialog() {
        if (currentBitmap == null) { Toast.makeText(this, "Upload image first", Toast.LENGTH_SHORT).show(); return }
        val sizes = arrayOf("Standard Passport (35x45mm) [AI]", "US Visa (2x2 inch) [AI]", "Stamp Size [AI]", "Modern Manual Crop ✂️")
        AlertDialog.Builder(this).setItems(sizes) { _, which ->
            when (which) {
                0 -> startAiAutoCrop(35f, 45f)
                1 -> startAiAutoCrop(2f, 2f)
                2 -> startAiAutoCrop(20f, 25f)
                3 -> startManualCrop()
            }
        }.show()
    }

    private fun startAiAutoCrop(aspectW: Float, aspectH: Float) {
        val bitmap = currentBitmap ?: return
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.tvLoadingProgress.text = "AI Detecting Face..."

        val options = FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
        val detector = FaceDetection.getClient(options)

        detector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    // AI Check: Head Tilt
                    if (Math.abs(face.headEulerAngleZ) > 10) {
                        Toast.makeText(this, "⚠️ Warning: Keep your head straight!", Toast.LENGTH_LONG).show()
                    }
                    currentBitmap = autoCropWithFace(bitmap, face.boundingBox, aspectW, aspectH)
                    binding.ivPreview.setImageBitmap(currentBitmap)
                    binding.loadingOverlay.visibility = View.GONE
                } else {
                    binding.loadingOverlay.visibility = View.GONE
                    Toast.makeText(this, "No face detected!", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun autoCropWithFace(bitmap: Bitmap, face: Rect, aspectW: Float, aspectH: Float): Bitmap {
        val targetHeight = face.height() * 2.5f
        val targetWidth = targetHeight * (aspectW / aspectH)
        
        var left = face.centerX() - (targetWidth / 2).toInt()
        var top = face.top - (targetHeight * 0.3f).toInt()
        
        left = Math.max(0, Math.min(left, bitmap.width - targetWidth.toInt()))
        top = Math.max(0, Math.min(top, bitmap.height - targetHeight.toInt()))
        
        return Bitmap.createBitmap(bitmap, left, top, targetWidth.toInt(), targetHeight.toInt())
    }

    private fun showBackgroundSelectionDialog() {
        val colors = arrayOf("Studio Blue", "White", "Grey", "Custom Red")
        AlertDialog.Builder(this).setItems(colors) { _, which ->
            val color = when (which) {
                0 -> Color.parseColor("#1DA1F2")
                1 -> Color.WHITE
                2 -> Color.LTGRAY
                else -> Color.parseColor("#E0245E")
            }
            applyAiBackground(color)
        }.show()
    }

    private fun applyAiBackground(bgColor: Int) {
        val bitmap = currentBitmap ?: return
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.tvLoadingProgress.text = "AI Removing Background..."

        val segmenter = Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val mask = segmenter.process(InputImage.fromBitmap(bitmap, 0)).await()
                val maskBuffer = mask.buffer
                val width = mask.width
                val height = mask.height
                
                val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                val bgR = Color.red(bgColor)
                val bgG = Color.green(bgColor)
                val bgB = Color.blue(bgColor)

                maskBuffer.rewind()
                for (i in pixels.indices) {
                    val confidence = maskBuffer.float
                    // 🔥 AI Edge Smoothing: Linear Interpolation for natural look
                    if (confidence < 0.9) {
                        val r = (Color.red(pixels[i]) * confidence + bgR * (1 - confidence)).toInt()
                        val g = (Color.green(pixels[i]) * confidence + bgG * (1 - confidence)).toInt()
                        val b = (Color.blue(pixels[i]) * confidence + bgB * (1 - confidence)).toInt()
                        pixels[i] = Color.rgb(r, g, b)
                    }
                }
                resultBitmap.setPixels(pixels, 0, width, 0, 0, width, height)

                withContext(Dispatchers.Main) {
                    currentBitmap = resultBitmap
                    binding.ivPreview.setImageBitmap(currentBitmap)
                    binding.loadingOverlay.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { binding.loadingOverlay.visibility = View.GONE }
            }
        }
    }

    private fun startManualCrop() {
        selectedImageUri?.let {
            val destinationUri = Uri.fromFile(File(cacheDir, "crop_${System.currentTimeMillis()}.jpg"))
            val uCrop = UCrop.of(it, destinationUri)
                .withAspectRatio(3.5f, 4.5f)
                .withOptions(UCrop.Options().apply {
                    setToolbarColor(Color.BLACK)
                    setActiveControlsWidgetColor(Color.parseColor("#FF9800"))
                })
            manualCropLauncher.launch(uCrop.getIntent(this))
        }
    }

    private fun showSaveOptionsDialog() {
        val options = arrayOf("Save Single Photo", "Save A4 Sheet (25 Photos) with Cut-Marks")
        AlertDialog.Builder(this).setItems(options) { _, which ->
            if (which == 0) lifecycleScope.launch(Dispatchers.IO) { saveImage(currentBitmap!!, "Passport") }
            else generateA4Sheet()
        }.show()
    }

    private fun generateA4Sheet() {
        val photo = currentBitmap ?: return
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.tvLoadingProgress.text = "Generating A4 Print Sheet..."

        lifecycleScope.launch(Dispatchers.IO) {
            val a4 = Bitmap.createBitmap(2480, 3508, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(a4)
            canvas.drawColor(Color.WHITE)

            val scaled = Bitmap.createScaledBitmap(photo, 413, 531, true) // 35x45mm at 300DPI
            val paint = Paint().apply { 
                color = Color.parseColor("#CCCCCC")
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }

            var x = 150f
            var y = 150f
            for (i in 0 until 25) {
                canvas.drawBitmap(scaled, x, y, null)
                // 🔥 Cut-Marks (Borders)
                canvas.drawRect(x, y, x + scaled.width, y + scaled.height, paint)
                
                x += scaled.width + 50f
                if (x + scaled.width > 2480 - 150f) {
                    x = 150f
                    y += scaled.height + 50f
                }
            }
            saveImage(a4, "Passport_A4")
        }
    }

    private suspend fun saveImage(bitmap: Bitmap, prefix: String) {
        val fileName = "${prefix}_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
            }
            withContext(Dispatchers.Main) {
                binding.loadingOverlay.visibility = View.GONE
                Toast.makeText(this@PassportMakerActivity, "Saved to Downloads!", Toast.LENGTH_LONG).show()
            }
        }
    }
}
