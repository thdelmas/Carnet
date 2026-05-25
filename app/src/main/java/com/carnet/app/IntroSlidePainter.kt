package com.carnet.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Paints one full-screen intro slide onto the OverlayEffect canvas. Opaque black
 * background so the camera frame underneath is hidden — the slide is what gets
 * recorded for the first few seconds of each take.
 *
 * Layout (vertical, top→bottom):
 *   - LABEL (small, dim)            ~15% from top
 *   - VALUE (huge, bright)          ~38%
 *   - UNIT  (small, dim)            ~52%
 *   - DELTA (small, dim) "+12 vs 60 at start" — hidden on first take
 *   - GRAPH line (50% width)        ~70–85% — hidden when history has <2 points
 *   - FOOTER (small, dim) "SERIES · YYYY-MM-DD"   bottom
 *
 * Sizes are fractions of the frame's shortest side so the slide reads identically
 * on 1080p and 4K outputs without DPI guesswork.
 */
class IntroSlidePainter(context: Context) {

    private val textColor = ContextCompat.getColor(context, R.color.carnet_text)
    private val dimColor = ContextCompat.getColor(context, R.color.carnet_text_dim)

    private val labelPaint = paint(dimColor, align = Paint.Align.CENTER, letterSpacing = 0.08f)
    private val valuePaint = paint(textColor, align = Paint.Align.CENTER)
    private val unitPaint = paint(dimColor, align = Paint.Align.CENTER, letterSpacing = 0.05f)
    private val deltaPaint = paint(dimColor, align = Paint.Align.CENTER, letterSpacing = 0.04f)
    private val footerPaint = paint(dimColor, align = Paint.Align.CENTER, letterSpacing = 0.04f)
    private val graphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val graphDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        style = Paint.Style.FILL
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        slide: IntroSlide,
        seriesName: String?,
        capturedAtMillis: Long,
    ) {
        canvas.drawColor(Color.BLACK)
        val w = width.toFloat()
        val h = height.toFloat()
        val shortSide = minOf(w, h)

        labelPaint.textSize = shortSide * LABEL_FRACTION
        valuePaint.textSize = shortSide * VALUE_FRACTION
        unitPaint.textSize = shortSide * UNIT_FRACTION
        deltaPaint.textSize = shortSide * DELTA_FRACTION
        footerPaint.textSize = shortSide * FOOTER_FRACTION
        graphPaint.strokeWidth = shortSide * GRAPH_STROKE_FRACTION

        val cx = w / 2f

        when (slide) {
            is IntroSlide.Metric -> drawMetric(canvas, w, h, cx, slide)
            is IntroSlide.Metadata -> drawMetadata(canvas, w, h, cx, slide, shortSide)
        }

        val footer = buildString {
            if (!seriesName.isNullOrBlank()) append(seriesName.uppercase()).append("  ·  ")
            append(dateFormat.format(Date(capturedAtMillis)))
        }
        canvas.drawText(footer, cx, h * 0.94f, footerPaint)
    }

    private fun drawMetric(canvas: Canvas, w: Float, h: Float, cx: Float, slide: IntroSlide.Metric) {
        canvas.drawText(slide.label, cx, h * 0.18f, labelPaint)
        canvas.drawText(slide.value, cx, h * 0.42f, valuePaint)
        canvas.drawText(slide.unit, cx, h * 0.52f, unitPaint)
        if (slide.deltaText != null) {
            canvas.drawText(slide.deltaText, cx, h * 0.62f, deltaPaint)
        }
        if (slide.history.size >= 2) {
            val gw = w * 0.6f
            val gh = h * 0.14f
            drawGraph(canvas, x = (w - gw) / 2f, y = h * 0.72f, w = gw, h = gh, values = slide.history)
        }
    }

    private fun drawMetadata(
        canvas: Canvas,
        @Suppress("UNUSED_PARAMETER") w: Float,
        h: Float,
        cx: Float,
        slide: IntroSlide.Metadata,
        shortSide: Float,
    ) {
        // Identity slide. Two-line stack: SERIES name big, then a quartet of dim
        // identifying rows (subject / session / experiment / take tag) so the take
        // is identifiable even when the filename is stripped.
        val seriesPaint = valuePaint
        seriesPaint.textSize = shortSide * 0.10f
        canvas.drawText(slide.seriesName.uppercase(), cx, h * 0.34f, seriesPaint)

        val rowPaint = deltaPaint
        rowPaint.textSize = shortSide * 0.045f
        val rowYBase = h * 0.50f
        val rowGap = shortSide * 0.075f
        listOf(
            "SUBJECT  ${slide.subject.uppercase()}",
            "SESSION  ${slide.sessionLabel.uppercase()}",
            "EXPERIMENT  ${slide.experimentLabel.uppercase()}",
            "TAKE  ${slide.sessionTag}",
        ).forEachIndexed { i, line ->
            canvas.drawText(line, cx, rowYBase + rowGap * i, rowPaint)
        }
    }

    private fun drawGraph(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, values: List<Double>) {
        val min = values.min()
        val max = values.max()
        val range = (max - min).takeIf { it > 0.0 } ?: 1.0
        // Pad vertically so a flat line doesn't slam against the box edge.
        val pad = h * 0.10f
        val plotH = h - pad * 2
        val path = Path()
        val dotR = graphPaint.strokeWidth * 1.4f
        values.forEachIndexed { i, v ->
            val px = if (values.size == 1) x + w / 2f
                else x + (i.toFloat() / (values.size - 1)) * w
            val py = y + pad + (plotH - ((v - min) / range).toFloat() * plotH)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            canvas.drawCircle(px, py, dotR, graphDotPaint)
        }
        canvas.drawPath(path, graphPaint)
    }

    private fun paint(
        color: Int,
        align: Paint.Align,
        letterSpacing: Float = 0f,
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = align
        this.letterSpacing = letterSpacing
    }

    companion object {
        private const val LABEL_FRACTION = 0.06f
        private const val VALUE_FRACTION = 0.24f
        private const val UNIT_FRACTION = 0.05f
        private const val DELTA_FRACTION = 0.04f
        private const val FOOTER_FRACTION = 0.035f
        private const val GRAPH_STROKE_FRACTION = 0.006f
    }
}

/**
 * One full-screen intro slide. Either a per-metric data slide (label/value/unit + the
 * optional delta + history graph for trend), or a one-shot metadata slide identifying
 * the take: series, session, subject. The metadata slide leads the deck so the take
 * is identifiable on its own when clipped or shared without the filename.
 */
sealed interface IntroSlide {
    data class Metric(
        val label: String,
        val value: String,
        val unit: String,
        val startValue: String?,
        val deltaText: String?,
        val history: List<Double>,
    ) : IntroSlide

    data class Metadata(
        val seriesName: String,
        val subject: String,
        val sessionLabel: String,
        val sessionTag: String,
        val experimentLabel: String,
    ) : IntroSlide
}
