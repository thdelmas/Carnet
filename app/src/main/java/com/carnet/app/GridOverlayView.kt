package com.carnet.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Rule-of-thirds composition guide. Drawn on top of the camera preview as a
 * framing aid — explicitly NOT part of the recorded video. The instrument is
 * helping the subject frame themselves; the recording itself stays clean of
 * compositional scaffolding.
 *
 * The lines are confined to the camera frame's actual content rect inside the
 * View (computed FIT_CENTER from [contentAspect]) so the grid doesn't stretch
 * into PreviewView's letterbox bars and end up off the framed image.
 */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** width / height of the displayed camera frame. 0 = fill the whole view (legacy). */
    var contentAspect: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.carnet_text)
        alpha = 64
        strokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics,
        )
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val (left, top, contentW, contentH) = contentRect(viewW, viewH)
        val right = left + contentW
        val bottom = top + contentH
        canvas.drawLine(left + contentW / 3f, top, left + contentW / 3f, bottom, linePaint)
        canvas.drawLine(left + 2f * contentW / 3f, top, left + 2f * contentW / 3f, bottom, linePaint)
        canvas.drawLine(left, top + contentH / 3f, right, top + contentH / 3f, linePaint)
        canvas.drawLine(left, top + 2f * contentH / 3f, right, top + 2f * contentH / 3f, linePaint)
    }

    private data class Rect(val left: Float, val top: Float, val width: Float, val height: Float)

    private fun contentRect(viewW: Float, viewH: Float): Rect {
        if (contentAspect <= 0f) return Rect(0f, 0f, viewW, viewH)
        val viewAspect = viewW / viewH
        return if (viewAspect > contentAspect) {
            val w = viewH * contentAspect
            Rect((viewW - w) / 2f, 0f, w, viewH)
        } else {
            val h = viewW / contentAspect
            Rect(0f, (viewH - h) / 2f, viewW, h)
        }
    }
}
