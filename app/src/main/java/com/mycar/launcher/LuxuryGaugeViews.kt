package com.mycar.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class LuxuryAnalogClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var ringColor: Int = Color.parseColor("#88E5C158")
        set(value) {
            field = value
            invalidate()
        }

    var tickColor: Int = Color.parseColor("#CCFFFFFF")
        set(value) {
            field = value
            invalidate()
        }

    var handAccentColor: Int = Color.parseColor("#E5C158")
        set(value) {
            field = value
            invalidate()
        }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - 8f
        if (radius <= 12f) return

        ringPaint.color = ringColor
        ringPaint.strokeWidth = 3f
        canvas.drawCircle(cx, cy, radius, ringPaint)

        for (i in 0 until 12) {
            val angleRad = Math.toRadians((i * 30 - 90).toDouble())
            val outerR = radius - 4f
            val innerR = if (i % 3 == 0) radius - 14f else radius - 9f
            tickPaint.color = if (i % 3 == 0) handAccentColor else tickColor
            tickPaint.strokeWidth = if (i % 3 == 0) 3.5f else 2f
            val x1 = cx + (innerR * cos(angleRad)).toFloat()
            val y1 = cy + (innerR * sin(angleRad)).toFloat()
            val x2 = cx + (outerR * cos(angleRad)).toFloat()
            val y2 = cy + (outerR * sin(angleRad)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)

        val hourAngle = (hour + minute / 60f) * 30f - 90f
        val minAngle = (minute + second / 60f) * 6f - 90f

        // Jarum jam
        val hRad = Math.toRadians(hourAngle.toDouble())
        handPaint.color = tickColor
        handPaint.strokeWidth = 5f
        canvas.drawLine(
            cx,
            cy,
            cx + (radius * 0.52f * cos(hRad)).toFloat(),
            cy + (radius * 0.52f * sin(hRad)).toFloat(),
            handPaint
        )

        // Jarum minit
        val mRad = Math.toRadians(minAngle.toDouble())
        handPaint.color = handAccentColor
        handPaint.strokeWidth = 3.5f
        canvas.drawLine(
            cx,
            cy,
            cx + (radius * 0.76f * cos(mRad)).toFloat(),
            cy + (radius * 0.76f * sin(mRad)).toFloat(),
            handPaint
        )

        centerPaint.color = handAccentColor
        canvas.drawCircle(cx, cy, 5f, centerPaint)
    }
}

class LuxurySpeedGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var speedKmh: Float = 0f
        set(value) {
            field = value.coerceIn(0f, maxScaleKmh)
            invalidate()
        }

    var maxScaleKmh: Float = 200f
        set(value) {
            field = value.coerceAtLeast(100f)
            invalidate()
        }

    var trackColor: Int = Color.parseColor("#33FFFFFF")
        set(value) {
            field = value
            invalidate()
        }

    var activeArcColor: Int = Color.parseColor("#E5C158")
        set(value) {
            field = value
            invalidate()
        }

    var needleColor: Int = Color.parseColor("#F8FAFC")
        set(value) {
            field = value
            invalidate()
        }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height * 0.68f
        val radius = min(cx - 14f, cy - 10f)
        if (radius <= 16f) return

        val startAngle = 145f
        val sweepTotal = 250f

        val left = cx - radius
        val top = cy - radius
        val right = cx + radius
        val bottom = cy + radius

        // Latar lengkungan meter
        arcPaint.color = trackColor
        arcPaint.strokeWidth = 7f
        canvas.drawArc(left, top, right, bottom, startAngle, sweepTotal, false, arcPaint)

        // Lengkungan aktif ikut kelajuan semasa
        val ratio = (speedKmh / maxScaleKmh).coerceIn(0f, 1f)
        arcPaint.color = activeArcColor
        arcPaint.strokeWidth = 7f
        canvas.drawArc(left, top, right, bottom, startAngle, sweepTotal * ratio, false, arcPaint)

        // Senggatan meter
        val ticks = 10
        for (i in 0..ticks) {
            val tRatio = i.toFloat() / ticks
            val deg = startAngle + (sweepTotal * tRatio)
            val rad = Math.toRadians(deg.toDouble())
            val outerR = radius - 10f
            val innerR = if (i % 2 == 0) radius - 20f else radius - 15f
            tickPaint.color = if (tRatio <= ratio) activeArcColor else trackColor
            tickPaint.strokeWidth = if (i % 2 == 0) 3f else 2f
            canvas.drawLine(
                cx + (innerR * cos(rad)).toFloat(),
                cy + (innerR * sin(rad)).toFloat(),
                cx + (outerR * cos(rad)).toFloat(),
                cy + (outerR * sin(rad)).toFloat(),
                tickPaint
            )
        }

        // Jarum penunjuk kelajuan
        val needleDeg = startAngle + (sweepTotal * ratio)
        val nRad = Math.toRadians(needleDeg.toDouble())
        needlePaint.color = needleColor
        needlePaint.strokeWidth = 4f
        canvas.drawLine(
            cx,
            cy,
            cx + (radius * 0.78f * cos(nRad)).toFloat(),
            cy + (radius * 0.78f * sin(nRad)).toFloat(),
            needlePaint
        )

        hubPaint.color = activeArcColor
        canvas.drawCircle(cx, cy, 6f, hubPaint)
    }
}
