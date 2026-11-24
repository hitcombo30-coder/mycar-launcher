package com.mycar.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class HeadingUpMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var headingDeg: Float = 0f
        set(value) {
            field = ((value % 360f) + 360f) % 360f
            invalidate()
        }

    var isNightMode: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var areaTitle: String = "Sekitar Kuantan • Heading-Up ↑"
        set(value) {
            field = value
            invalidate()
        }

    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 18f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        textAlign = Paint.Align.CENTER
    }

    private val carPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.5f
        val cy = h * 0.62f

        canvas.drawColor(if (isNightMode) Color.parseColor("#0F1724") else Color.parseColor("#E6ECF2"))
        roadPaint.color = if (isNightMode) Color.parseColor("#2A374C") else Color.parseColor("#FFFFFF")
        labelPaint.color = if (isNightMode) Color.parseColor("#CBD5E1") else Color.parseColor("#1E293B")

        canvas.save()
        canvas.translate(cx, cy)
        // Rotate map opposite to heading so movement direction stays UP
        canvas.rotate(-headingDeg)

        val extent = maxOf(w, h) * 1.4f
        canvas.drawLine(0f, extent, 0f, -extent, roadPaint)
        canvas.drawLine(-extent, 0f, extent, 0f, roadPaint)
        canvas.drawLine(-extent, -160f, extent, -160f, roadPaint)
        canvas.drawLine(180f, extent, 180f, -extent, roadPaint)

        canvas.drawText("Arah Hadapan ↑", 0f, -190f, labelPaint)
        canvas.drawText("Simpang Utama", 180f, -25f, labelPaint)
        canvas.restore()

        // Fixed car chevron pointing straight UP
        carPaint.color = if (isNightMode) Color.parseColor("#38BDF8") else Color.parseColor("#0F766E")
        val chevron = Path().apply {
            moveTo(cx, cy - 28f)
            lineTo(cx + 22f, cy + 20f)
            lineTo(cx, cy + 10f)
            lineTo(cx - 22f, cy + 20f)
            close()
        }
        canvas.drawPath(chevron, carPaint)
        canvas.drawText(areaTitle, w * 0.5f, 38f, labelPaint)
    }
}
