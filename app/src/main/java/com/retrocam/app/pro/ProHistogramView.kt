package com.retrocam.app.pro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Custom hardware-accelerated View rendering a live RGB & Luminance histogram.
 */
class ProHistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class DisplayMode { LUMINANCE, RGB }

    var displayMode: DisplayMode = DisplayMode.LUMINANCE
        set(value) {
            field = value
            invalidate()
        }

    private var currentData: HistogramData? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA111827.toInt() // Semi-transparent dark slate
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val lumaFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44E8A33D.toInt() // Amber fill
        style = Paint.Style.FILL
    }

    private val lumaStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8A33D.toInt() // Amber stroke
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
    }

    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCEF4444.toInt() // Red
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
    }

    private val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC10B981.toInt() // Emerald Green
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
    }

    private val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC3B82F6.toInt() // Blue
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
    }

    private val clipWarningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = 20f
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private val bgRect = RectF()
    private val lumaPath = Path()
    private val redPath = Path()
    private val greenPath = Path()
    private val bluePath = Path()

    init {
        setOnClickListener {
            displayMode = if (displayMode == DisplayMode.LUMINANCE) DisplayMode.RGB else DisplayMode.LUMINANCE
        }
    }

    fun updateHistogram(data: HistogramData) {
        currentData = data
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = currentData ?: return

        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 8f
        val innerW = w - pad * 2f
        val innerH = h - pad * 2f

        bgRect.set(0f, 0f, w, h)
        val cornerRadius = 16f
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, borderPaint)

        val bottom = h - pad
        val top = pad + 16f // Leave room for mode label and clipping dots
        val drawH = bottom - top

        when (displayMode) {
            DisplayMode.LUMINANCE -> {
                lumaPath.reset()
                lumaPath.moveTo(pad, bottom)

                val bins = data.lumaBins
                for (i in 0 until 256) {
                    val x = pad + (i / 255f) * innerW
                    val y = bottom - (bins[i] * drawH)
                    if (i == 0) lumaPath.lineTo(x, y) else lumaPath.lineTo(x, y)
                }
                lumaPath.lineTo(pad + innerW, bottom)
                lumaPath.close()

                canvas.drawPath(lumaPath, lumaFillPaint)
                canvas.drawPath(lumaPath, lumaStrokePaint)
            }

            DisplayMode.RGB -> {
                drawChannelPath(canvas, data.redBins, redPath, redPaint, pad, bottom, innerW, drawH)
                drawChannelPath(canvas, data.greenBins, greenPath, greenPaint, pad, bottom, innerW, drawH)
                drawChannelPath(canvas, data.blueBins, bluePath, bluePaint, pad, bottom, innerW, drawH)
            }
        }

        // Mode label
        val label = if (displayMode == DisplayMode.LUMINANCE) "LUMA" else "RGB"
        canvas.drawText(label, pad + 4f, pad + 14f, textPaint)

        // Shadow clipping warning (Blue dot on left)
        if (data.hasShadowClipping) {
            clipWarningPaint.color = 0xFF3B82F6.toInt()
            canvas.drawCircle(pad + 48f, pad + 10f, 4f, clipWarningPaint)
        }

        // Highlight clipping warning (Red dot on right)
        if (data.hasHighlightClipping) {
            clipWarningPaint.color = 0xFFEF4444.toInt()
            canvas.drawCircle(w - pad - 6f, pad + 10f, 4f, clipWarningPaint)
        }
    }

    private fun drawChannelPath(
        canvas: Canvas,
        bins: FloatArray,
        path: Path,
        paint: Paint,
        pad: Float,
        bottom: Float,
        innerW: Float,
        drawH: Float
    ) {
        path.reset()
        for (i in 0 until 256) {
            val x = pad + (i / 255f) * innerW
            val y = bottom - (bins[i] * drawH)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}
