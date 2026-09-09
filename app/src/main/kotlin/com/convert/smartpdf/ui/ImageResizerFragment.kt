package com.convert.smartpdf.ui

import android.graphics.*
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.convert.smartpdf.NotificationUtils
import com.convert.smartpdf.R
import com.convert.smartpdf.databinding.FragmentImageResizerBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class ImageResizerFragment : Fragment(R.layout.fragment_image_resizer) {

    private var _binding: FragmentImageResizerBinding? = null
    private val binding get() = _binding!!
    private var selectedBitmap: Bitmap? = null
    private var aspectRatio = 1.0

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val inputStream = requireContext().contentResolver.openInputStream(it)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                bitmap?.let { b ->
                    selectedBitmap = b
                    aspectRatio = b.width.toDouble() / b.height.toDouble()
                    binding.ivPreview.setImageBitmap(b)
                    binding.tvOriginalInfo.text = "Original: ${b.width}x${b.height} px"
                    binding.etWidth.setText(b.width.toString())
                    binding.etHeight.setText(b.height.toString())
                    binding.aiActionLayout.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentImageResizerBinding.bind(view)

        setupAspectRatioLogic()

        binding.btnSelectImage.setOnClickListener { pickImageLauncher.launch("image/*") }
        
        binding.btnRemoveBg.setOnClickListener { removeBackgroundWithAI() }

        binding.btnResize.setOnClickListener {
            if (selectedBitmap != null) processAiResize()
            else Toast.makeText(context, "Select image first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeBackgroundWithAI() {
        val bitmap = selectedBitmap ?: return
        binding.progressBar.visibility = View.VISIBLE
        Toast.makeText(context, "AI is removing background...", Toast.LENGTH_SHORT).show()

        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        
        // ফিক্স: SelfieSegmentation এর জায়গায় শুধু Segmentation ব্যবহার করা হয়েছে
        val segmenter = Segmentation.getClient(options)
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val mask: SegmentationMask = segmenter.process(inputImage).await()
                val maskBuffer = mask.buffer
                val maskWidth = mask.width
                val maskHeight = mask.height

                val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(maskWidth * maskHeight)
                bitmap.getPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

                for (y in 0 until maskHeight) {
                    for (x in 0 until maskWidth) {
                        val confidence = maskBuffer.float
                        if (confidence <= 0.8) {
                            pixels[y * maskWidth + x] = Color.TRANSPARENT
                        }
                    }
                }
                resultBitmap.setPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)
                maskBuffer.rewind()

                withContext(Dispatchers.Main) {
                    selectedBitmap = resultBitmap
                    binding.ivPreview.setImageBitmap(resultBitmap)
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(context, "Background Removed!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(context, "AI Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun processAiResize() {
        val width = binding.etWidth.text.toString().toIntOrNull() ?: return
        val height = binding.etHeight.text.toString().toIntOrNull() ?: return
        val targetKB = binding.etTargetSize.text.toString().toIntOrNull()

        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var resized = Bitmap.createScaledBitmap(selectedBitmap!!, width, height, true)
                
                val bitmap = Bitmap.createBitmap(resized.width, resized.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint()
                val matrix = ColorMatrix()
                matrix.setSaturation(1.1f) 
                paint.colorFilter = ColorMatrixColorFilter(matrix)
                canvas.drawBitmap(resized, 0f, 0f, paint)
                
                val stream = ByteArrayOutputStream()
                var quality = 100
                
                do {
                    stream.reset()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                    if (targetKB == null || stream.size() / 1024 <= targetKB) break
                    quality -= 5
                } while (quality > 10)

                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AI_Resized_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { it.write(stream.toByteArray()) }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    NotificationUtils.showDownloadNotification(requireContext(), file, "image/*")
                    Toast.makeText(context, "Saved Successfully!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupAspectRatioLogic() {
        binding.etWidth.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (binding.etWidth.hasFocus() && binding.cbLockAspect.isChecked) {
                    val w = s.toString().toIntOrNull() ?: return
                    binding.etHeight.setText((w / aspectRatio).toInt().toString())
                }
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
