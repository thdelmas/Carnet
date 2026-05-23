package com.carnet.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Paints the Carnet HUD (subject / session / date / time / rec / uid) into an
 * arbitrary Canvas — independent of any View. Called from CameraX's
 * OverlayEffect onDrawListener so the HUD lands in both the live preview and
 * the encoded video stream.
 *
 * Sizes are expressed as fractions of the frame's shortest dimension so the
 * HUD renders at the same visual weight regardless of capture resolution
 * (1080p, 4K, etc.) or device DPI.
 */
class HudPainter(context: Context) {

    var subject: String = "subject"
    var session: String = "V--_SCAFFOLD"
    var uid: String = "--------"
    var recording: Boolean = false

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val textColor = ContextCompat.getColor(context, R.color.carnet_text)
    private val dimColor = ContextCompat.getColor(context, R.color.carnet_text_dim)
    private val recColor = ContextCompat.getColor(context, R.color.carnet_rec)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.05f
    }
    private val dimPaint = Paint(textPaint).apply { color = dimColor }
    private val recPaint = Paint(textPaint).apply { color = recColor }
    private val recDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = recColor
        style = Paint.Style.FILL
    }

    fun draw(canvas: Canvas, width: Int, height: Int) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val shortSide = minOf(width, height).toFloat()
        val textSize = shortSide * TEXT_FRACTION
        val pad = shortSide * PAD_FRACTION
        val lineGap = shortSide * LINE_GAP_FRACTION
        val shadowRadius = shortSide * SHADOW_FRACTION

        applySize(textPaint, textSize, shadowRadius)
        applySize(dimPaint, textSize, shadowRadius)
        applySize(recPaint, textSize, shadowRadius)
        recDotPaint.setShadowLayer(shadowRadius, 0f, 0f, Color.BLACK)

        val now = Date()
        val ascent = -textPaint.ascent()
        val descent = textPaint.descent()
        val lineHeight = ascent + descent

        // Top-left: SUBJ.
        canvas.drawText("SUBJ ${subject.uppercase()}", pad, pad + ascent, textPaint)

        // Top-right: SESSION.
        val sessionLabel = "SESSION ${session.uppercase()}"
        canvas.drawText(
            sessionLabel,
            width - pad - textPaint.measureText(sessionLabel),
            pad + ascent,
            textPaint,
        )

        // Bottom-left: DATE / TIME.
        val timeY = height - pad - descent
        val dateY = timeY - lineHeight - lineGap
        canvas.drawText(dateFormat.format(now), pad, dateY, dimPaint)
        canvas.drawText(timeFormat.format(now), pad, timeY, textPaint)

        // Bottom-right: UID / REC|STBY.
        val uidLabel = "UID ${uid.uppercase()}"
        canvas.drawText(
            uidLabel,
            width - pad - textPaint.measureText(uidLabel),
            timeY,
            dimPaint,
        )
        drawRecIndicator(canvas, now, width.toFloat(), pad, dateY, ascent)
    }

    private fun applySize(paint: Paint, textSize: Float, shadowRadius: Float) {
        paint.textSize = textSize
        paint.setShadowLayer(shadowRadius, 0f, 0f, Color.BLACK)
    }

    private fun drawRecIndicator(
        canvas: Canvas,
        now: Date,
        viewWidth: Float,
        pad: Float,
        baselineY: Float,
        ascent: Float,
    ) {
        val rightEdge = viewWidth - pad
        if (recording) {
            // Blink at 1 Hz: on even seconds, off odd seconds.
            val onPhase = (now.time / 1000L) % 2L == 0L
            val label = "REC"
            val labelWidth = recPaint.measureText(label)
            val dotRadius = ascent * 0.32f
            val dotGap = pad * 0.35f
            canvas.drawText(label, rightEdge - labelWidth, baselineY, recPaint)
            if (onPhase) {
                val dotCx = rightEdge - labelWidth - dotGap - dotRadius
                val dotCy = baselineY - ascent * 0.35f
                canvas.drawCircle(dotCx, dotCy, dotRadius, recDotPaint)
            }
        } else {
            val label = "STBY"
            val labelWidth = dimPaint.measureText(label)
            canvas.drawText(label, rightEdge - labelWidth, baselineY, dimPaint)
        }
    }

    companion object {
        // Fractions of the shortest frame side.
        private const val TEXT_FRACTION = 0.022f
        private const val PAD_FRACTION = 0.025f
        private const val LINE_GAP_FRACTION = 0.006f
        private const val SHADOW_FRACTION = 0.004f
    }
}
