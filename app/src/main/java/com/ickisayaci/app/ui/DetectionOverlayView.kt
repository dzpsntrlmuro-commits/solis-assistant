package com.ickisayaci.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.ickisayaci.app.detection.DrinkDetection
import com.ickisayaci.app.R

class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var detections: List<DrinkDetection> = emptyList()

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = context.getColor(R.color.amber)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.amber_soft)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink)
        textSize = 36f
        isFakeBoldText = true
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.amber)
    }

    fun setDetections(items: List<DrinkDetection>) {
        detections = items
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        for (item in detections) {
            val rect = RectF(
                item.left * w,
                item.top * h,
                item.right * w,
                item.bottom * h
            )
            canvas.drawRect(rect, fillPaint)
            canvas.drawRect(rect, boxPaint)

            val label = item.turkishLabel
            val textWidth = textPaint.measureText(label)
            val pad = 12f
            val labelTop = (rect.top - 48f).coerceAtLeast(0f)
            val labelRect = RectF(
                rect.left,
                labelTop,
                rect.left + textWidth + pad * 2,
                labelTop + 44f
            )
            canvas.drawRoundRect(labelRect, 10f, 10f, labelBgPaint)
            canvas.drawText(label, labelRect.left + pad, labelRect.bottom - 12f, textPaint)
        }
    }
}
