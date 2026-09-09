package com.convert.smartpdf.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

@SuppressLint("ViewConstructor")
class DraggableSignatureView(context: Context, private val signatureBitmap: Bitmap, private val onDelete: (DraggableSignatureView) -> Unit) : View(context) {

    private var posX = 100f
    private var posY = 100f
    private var sigWidth = 300f
    private var sigHeight = 0f
    private val aspectRatio: Float = signatureBitmap.height.toFloat() / signatureBitmap.width.toFloat()

    private var lastX = 0f
    private var lastY = 0f
    private var mode = MODE_NONE

    // বর্ডার এবং আইকন স্টাইল
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#00BFA5") // Teal Color
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(15f, 10f), 0f) // ড্যাশড লাইন
    }

    private val closePaint = Paint().apply { color = Color.RED; style = Paint.Style.FILL }
    private val resizePaint = Paint().apply { color = Color.parseColor("#FF9800"); style = Paint.Style.FILL }
    private val iconLinePaint = Paint().apply { color = Color.WHITE; strokeWidth = 5f; style = Paint.Style.STROKE }

    private val iconRadius = 30f

    init {
        sigHeight = sigWidth * aspectRatio
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // ১. সিগনেচার ছবি আঁকা
        val destRect = RectF(posX, posY, posX + sigWidth, posY + sigHeight)
        canvas.drawBitmap(signatureBitmap, null, destRect, null)

        // ২. ড্যাশড বর্ডার আঁকা
        canvas.drawRect(destRect, borderPaint)

        // ৩. ক্লোজ বাটন (Top-Left)
        canvas.drawCircle(posX, posY, iconRadius, closePaint)
        canvas.drawLine(posX - 10f, posY - 10f, posX + 10f, posY + 10f, iconLinePaint)
        canvas.drawLine(posX + 10f, posY - 10f, posX - 10f, posY + 10f, iconLinePaint)

        // ৪. রিসাইজ হ্যান্ডেল (Bottom-Right)
        canvas.drawCircle(posX + sigWidth, posY + sigHeight, iconRadius, resizePaint)
        canvas.drawLine(posX + sigWidth - 10f, posY + sigHeight - 10f, posX + sigWidth + 10f, posY + sigHeight + 10f, iconLinePaint)
        canvas.drawLine(posX + sigWidth - 10f, posY + sigHeight + 10f, posX + sigWidth + 10f, posY + sigHeight - 10f, iconLinePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // ক্লোজ বাটনে ক্লিক চেক
                if (isPointInsideCircle(x, y, posX, posY, iconRadius * 2)) {
                    onDelete(this)
                    return true
                }
                // রিসাইজ বাটনে ক্লিক চেক
                if (isPointInsideCircle(x, y, posX + sigWidth, posY + sigHeight, iconRadius * 2)) {
                    mode = MODE_RESIZE
                } 
                // ইমেজের ওপর ক্লিক চেক (ড্র্যাগ করার জন্য)
                else if (x >= posX && x <= posX + sigWidth && y >= posY && y <= posY + sigHeight) {
                    mode = MODE_DRAG
                } else {
                    return false
                }
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY

                if (mode == MODE_DRAG) {
                    posX += dx
                    posY += dy
                } else if (mode == MODE_RESIZE) {
                    sigWidth += dx
                    if (sigWidth < 100f) sigWidth = 100f // মিনিমাম সাইজ
                    sigHeight = sigWidth * aspectRatio // রেশিও ঠিক রাখা
                }
                lastX = x
                lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = MODE_NONE
            }
        }
        return true
    }

    private fun isPointInsideCircle(x: Float, y: Float, cx: Float, cy: Float, radius: Float): Boolean {
        val dx = x - cx
        val dy = y - cy
        return (dx * dx + dy * dy) <= (radius * radius)
    }

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_DRAG = 1
        private const val MODE_RESIZE = 2
    }
}
