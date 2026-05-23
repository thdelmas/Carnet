package com.carnet.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Carnet HUD overlay. Draws subject / session / date / time / rec / uid on top
 * of the camera preview. The HUD is the only on-screen element added to the
 * recording — it shows metadata, not branding (per manifesto).
 *
 * Repaints once per second to advance the clock and blink the REC indicator.
 */
class HudOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var subject: String = "subject"
        set(value) { field = value; invalidate() }
    var session: String = "V--_SCAFFOLD"
        set(value) { field = value; invalidate() }
    var uid: String = "--------"
        set(value) { field = value; invalidate() }
    var recording: Boolean = false
        set(value) { field = value; invalidate() }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    // Drop shadow on text paints so the HUD stays legible against any camera frame
    // (sky, white walls, skin tones). 4dp radius black halo is enough contrast without
    // looking like a UI flourish.
    private val shadowRadius = dp(3f)
    private val shadowColor = Color.BLACK

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.carnet_text)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = sp(16f)
        letterSpacing = 0.05f
        setShadowLayer(shadowRadius, 0f, 0f, shadowColor)
    }
    private val dimPaint = Paint(textPaint).apply {
        color = ContextCompat.getColor(context, R.color.carnet_text_dim)
        setShadowLayer(shadowRadius, 0f, 0f, shadowColor)
    }
    private val recPaint = Paint(textPaint).apply {
        color = ContextCompat.getColor(context, R.color.carnet_rec)
        setShadowLayer(shadowRadius, 0f, 0f, shadowColor)
    }
    private val recDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.carnet_rec)
        style = Paint.Style.FILL
        setShadowLayer(shadowRadius, 0f, 0f, shadowColor)
    }

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            handler?.postDelayed(this, TICK_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler?.removeCallbacks(ticker)
    }

    override fun onDraw(canvas: Canvas) {
        val now = Date()
        val pad = dp(16f)
        val lineGap = dp(4f)
        val ascent = -textPaint.ascent()
        val descent = textPaint.descent()
        val lineHeight = ascent + descent

        // Top-left: SUBJ
        canvas.drawText("SUBJ ${subject.uppercase()}", pad, pad + ascent, textPaint)

        // Top-right: SESSION
        val sessionLabel = "SESSION ${session.uppercase()}"
        canvas.drawText(
            sessionLabel,
            width - pad - textPaint.measureText(sessionLabel),
            pad + ascent,
            textPaint,
        )

        // Bottom-left: DATE / TIME
        val timeY = height - pad - descent
        val dateY = timeY - lineHeight - lineGap
        canvas.drawText(dateFormat.format(now), pad, dateY, dimPaint)
        canvas.drawText(timeFormat.format(now), pad, timeY, textPaint)

        // Bottom-right: REC + UID
        val uidLabel = "UID ${uid.uppercase()}"
        canvas.drawText(
            uidLabel,
            width - pad - textPaint.measureText(uidLabel),
            timeY,
            dimPaint,
        )
        drawRecIndicator(canvas, now, pad, dateY, ascent)
    }

    private fun drawRecIndicator(canvas: Canvas, now: Date, pad: Float, baselineY: Float, ascent: Float) {
        if (recording) {
            // Blink at 1 Hz: on for even seconds, off for odd seconds.
            val onPhase = (now.time / 1000L) % 2L == 0L
            val label = "REC"
            val labelWidth = recPaint.measureText(label)
            val dotRadius = ascent * 0.32f
            val dotGap = dp(6f)
            val rightEdge = width - pad
            canvas.drawText(label, rightEdge - labelWidth, baselineY, recPaint)
            if (onPhase) {
                val dotCx = rightEdge - labelWidth - dotGap - dotRadius
                val dotCy = baselineY - ascent * 0.35f
                canvas.drawCircle(dotCx, dotCy, dotRadius, recDotPaint)
            }
        } else {
            val label = "STBY"
            val labelWidth = dimPaint.measureText(label)
            canvas.drawText(label, width - pad - labelWidth, baselineY, dimPaint)
        }
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    companion object {
        private const val TICK_MS = 1000L
    }
}
