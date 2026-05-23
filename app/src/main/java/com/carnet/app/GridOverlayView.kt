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
 */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.carnet_text)
        alpha = 64
        strokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics,
        )
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // Two vertical lines at 1/3 and 2/3.
        canvas.drawLine(w / 3f, 0f, w / 3f, h, linePaint)
        canvas.drawLine(2f * w / 3f, 0f, 2f * w / 3f, h, linePaint)
        // Two horizontal lines at 1/3 and 2/3.
        canvas.drawLine(0f, h / 3f, w, h / 3f, linePaint)
        canvas.drawLine(0f, 2f * h / 3f, w, 2f * h / 3f, linePaint)
    }
}
