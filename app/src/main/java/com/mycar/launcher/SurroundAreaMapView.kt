package com.mycar.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class SurroundAreaMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var latitude: Double? = null
        set(value) {
            field = value
            invalidate()
        }

    var longitude: Double? = null
        set(value) {
            field = value
            invalidate()
        }

    var areaLabel: String = "Menunggu GPS..."
        set(value) {
            field = value
            invalidate()
        }

    var isNightMode: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 10f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val subRoadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
    }

    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(if (isNightMode) Color.parseColor("#080E1A") else Color.parseColor("#DCE6F0"))

        gridPaint.color = if (isNightMode) Color.parseColor("#142238") else Color.parseColor("#C5D3E2")
        subRoadPaint.color = if (isNightMode) Color.parseColor("#1E3252") else Color.parseColor("#FFFFFF")
        roadPaint.color = if (isNightMode) Color.parseColor("#2E4D7B") else Color.parseColor("#93C5FD")
        textPaint.color = if (isNightMode) Color.parseColor("#CBD5E1") else Color.parseColor("#1E293B")

        val step = 64f
        var x = (w * 0.5f) % step
        while (x < w) {
            canvas.drawLine(x, 0f, x, h, gridPaint)
            x += step
        }
        var y = (h * 0.5f) % step
        while (y < h) {
            canvas.drawLine(0f, y, w, y, gridPaint)
            y += step
        }

        // Surrounding block roads
        canvas.drawLine(w * 0.15f, h * 0.30f, w * 0.85f, h * 0.30f, subRoadPaint)
        canvas.drawLine(w * 0.15f, h * 0.72f, w * 0.85f, h * 0.72f, subRoadPaint)
        canvas.drawLine(w * 0.28f, h * 0.18f, w * 0.28f, h * 0.86f, subRoadPaint)
        canvas.drawLine(w * 0.72f, h * 0.18f, w * 0.72f, h * 0.86f, roadPaint)

        val cx = w * 0.5f
        val cy = h * 0.52f

        // Current position dot (no direction arrow or ahead text)
        pinPaint.color = if (isNightMode) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
        canvas.drawCircle(cx, cy, 12f, pinPaint)

        val coordText = if (latitude != null && longitude != null) {
            String.format("%.4f, %.4f", latitude, longitude)
        } else {
            "GPS: Scanning..."
        }
        canvas.drawText(coordText, 16f, h - 16f, textPaint)
    }
}
