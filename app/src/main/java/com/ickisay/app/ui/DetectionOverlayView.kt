package com.ickisay.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.ickisay.app.R
import com.ickisay.app.detection.DrinkItem

class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = ContextCompat.getColor(context, R.color.overlay_box)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.cream_text)
        textSize = 36f
        style = Paint.Style.FILL
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x660B1220
    }

    private var items: List<DrinkItem> = emptyList()
    private var imageWidth = 1
    private var imageHeight = 1

    fun setDetections(items: List<DrinkItem>, imageWidth: Int, imageHeight: Int) {
        this.items = items
        this.imageWidth = imageWidth.coerceAtLeast(1)
        this.imageHeight = imageHeight.coerceAtLeast(1)
        invalidate()
    }

    fun clear() {
        items = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return

        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight

        for (item in items) {
            val mapped = RectF(
                item.box.left * scaleX,
                item.box.top * scaleY,
                item.box.right * scaleX,
                item.box.bottom * scaleY
            )
            canvas.drawRect(mapped, boxPaint)
            val label = "${item.turkishLabel} ${(item.score * 100).toInt()}%"
            val textWidth = textPaint.measureText(label)
            val top = (mapped.top - 48f).coerceAtLeast(0f)
            canvas.drawRect(mapped.left, top, mapped.left + textWidth + 16f, top + 44f, fillPaint)
            canvas.drawText(label, mapped.left + 8f, top + 32f, textPaint)
        }
    }
}
