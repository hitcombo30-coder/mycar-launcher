package com.mycar.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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

    var bearing: Float = 0f
        set(value) {
            field = ((value % 360f) + 360f) % 360f
            invalidate()
        }

    var areaLabel: String = "Kuantan"
        set(value) {
            field = value
            invalidate()
        }

    var isNightMode: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 9f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val subRoadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val arrowBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val bgColor = if (isNightMode) Color.parseColor("#0B1220") else Color.parseColor("#DCE6F0")
        val blockColor = if (isNightMode) Color.parseColor("#131F35") else Color.parseColor("#CBD8E6")
        val gridColor = if (isNightMode) Color.parseColor("#1B2C49") else Color.parseColor("#B6C7DA")
        val subRoadColor = if (isNightMode) Color.parseColor("#253C63") else Color.parseColor("#FFFFFF")
        val roadColor = if (isNightMode) Color.parseColor("#3B82F6") else Color.parseColor("#2563EB")
        val arrowColor = if (isNightMode) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
        val infoColor = if (isNightMode) Color.parseColor("#94A3B8") else Color.parseColor("#334155")

        canvas.drawColor(bgColor)

        val cols = 6
        val rows = 4
        val dx = w / cols
        val dy = h / rows

        // Subtle urban blocks around intersections
        blockPaint.color = blockColor
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if ((r + c) % 2 == 0) {
                    canvas.drawRect(
                        c * dx + 6f,
                        r * dy + 6f,
                        (c + 1) * dx - 6f,
                        (r + 1) * dy - 6f,
                        blockPaint
                    )
                }
            }
        }

        gridPaint.color = gridColor
        for (i in 1 until cols) {
            val x = i * dx
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }
        for (j in 1 until rows) {
            val y = j * dy
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        // Surrounding roads
        subRoadPaint.color = subRoadColor
        canvas.drawLine(0f, h * 0.25f, w, h * 0.25f, subRoadPaint)
        canvas.drawLine(0f, h * 0.75f, w, h * 0.75f, subRoadPaint)
        canvas.drawLine(w * 0.25f, 0f, w * 0.25f, h, subRoadPaint)
        canvas.drawLine(w * 0.75f, 0f, w * 0.75f, h, subRoadPaint)

        // Main axes through center
        roadPaint.color = roadColor
        canvas.drawLine(0f, h * 0.5f, w, h * 0.5f, roadPaint)
        canvas.drawLine(w * 0.5f, 0f, w * 0.5f, h, roadPaint)

        // Vehicle directional arrow rotated by GPS bearing
        val cx = w * 0.5f
        val cy = h * 0.5f
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(bearing)

        val arrowPath = Path().apply {
            moveTo(0f, -18f)
            lineTo(14f, 14f)
            lineTo(0f, 7f)
            lineTo(-14f, 14f)
            close()
        }
        arrowFillPaint.color = arrowColor
        canvas.drawPath(arrowPath, arrowFillPaint)
        canvas.drawPath(arrowPath, arrowBorderPaint)
        canvas.restore()

        textPaint.color = infoColor
        val coordText = if (latitude != null && longitude != null) {
            String.format("%.4f, %.4f  •  %.0f°", latitude, longitude, bearing)
        } else {
            "GPS Standby"
        }
        canvas.drawText(coordText, 12f, h - 12f, textPaint)
    }
}
