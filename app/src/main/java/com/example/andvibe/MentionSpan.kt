package com.example.andvibe

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan
import kotlin.math.roundToInt

/** Rounded chip drawn over an `@repo` token in the vibe prompt. */
class MentionSpan(
    private val fill: Int,
    private val stroke: Int,
    private val textColor: Int
) : ReplacementSpan() {
    private val padX = 10f
    private val padY = 4f
    private val radius = 8f

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        if (fm != null) {
            val metrics = paint.fontMetricsInt
            fm.ascent = metrics.ascent
            fm.descent = metrics.descent
            fm.top = metrics.top
            fm.bottom = metrics.bottom
        }
        return (paint.measureText(text, start, end) + padX * 2).roundToInt().coerceAtLeast(1)
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val width = paint.measureText(text, start, end)
        val metrics = paint.fontMetrics
        val rect = RectF(
            x,
            y + metrics.ascent - padY,
            x + width + padX * 2,
            y + metrics.descent + padY
        )
        val oldColor = paint.color
        val oldStyle = paint.style
        val oldStroke = paint.strokeWidth
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = stroke
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.FILL
        paint.color = textColor
        canvas.drawText(text, start, end, x + padX, y.toFloat(), paint)
        paint.color = oldColor
        paint.style = oldStyle
        paint.strokeWidth = oldStroke
    }
}
