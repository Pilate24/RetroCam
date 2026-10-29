package com.retrocam.app.director.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.retrocam.app.R
import com.retrocam.app.director.analyzer.FaceBox
import com.retrocam.app.director.engine.AspectRatioEvaluator
import com.retrocam.app.director.model.AspectRatioOption
import com.retrocam.app.director.model.CompositionGuideConfig
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-performance hardware-accelerated preview overlay rendering composition aids.
 *
 * All aids (rule-of-thirds, center symmetry, horizon level, portrait silhouette,
 * aspect crop scrim, compare ratios, and face brackets) are drawn solely on this view
 * and are NEVER written to captured image bitmaps.
 */
class ShotDirectorOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var config: CompositionGuideConfig = CompositionGuideConfig()
        set(value) {
            field = value
            invalidate()
        }

    var activeAspectOption: AspectRatioOption = AspectRatioOption.RATIO_4_3
        set(value) {
            field = value
            invalidate()
        }

    var detectedFaces: List<FaceBox> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var horizonRollDegrees: Float = 0f
        set(value) {
            field = value
            if (config.showHorizonGuide) invalidate()
        }

    var isHorizonSensorAvailable: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val amberColor = ContextCompat.getColor(context, R.color.amber)
    private val readoutColor = ContextCompat.getColor(context, R.color.readout)

    // Paints
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(85, 255, 255, 255)
        strokeWidth = resources.displayMetrics.density * 1.0f
    }

    private val gridIntersectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(130, 232, 163, 61) // amber accent
    }

    private val symmetryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(90, 232, 163, 61)
        strokeWidth = resources.displayMetrics.density * 1.0f
        pathEffect = DashPathEffect(floatArrayOf(12f, 12f), 0f)
    }

    private val horizonNormalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(190, 242, 228, 206) // readout color
        strokeWidth = resources.displayMetrics.density * 2.0f
    }

    private val horizonLevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(255, 232, 163, 61) // amber gold level
        strokeWidth = resources.displayMetrics.density * 2.5f
    }

    private val horizonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(220, 242, 228, 206)
        textSize = resources.displayMetrics.scaledDensity * 10f
        typeface = android.graphics.Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }

    private val portraitGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(100, 232, 163, 61)
        strokeWidth = resources.displayMetrics.density * 1.5f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(140, 0, 0, 0) // darkened letterbox scrim
    }

    private val cropOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(180, 232, 163, 61)
        strokeWidth = resources.displayMetrics.density * 1.5f
    }

    private val cropLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(220, 232, 163, 61)
        textSize = resources.displayMetrics.scaledDensity * 10f
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private val compareOutlinePaint1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 232, 163, 61) // Amber (4:3)
        strokeWidth = resources.displayMetrics.density * 1.5f
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }

    private val compareOutlinePaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 77, 208, 225) // Cyan (4:5)
        strokeWidth = resources.displayMetrics.density * 1.5f
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }

    private val compareOutlinePaint3 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 255, 138, 101) // Coral (9:16)
        strokeWidth = resources.displayMetrics.density * 1.5f
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }

    private val faceBracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(175, 232, 163, 61)
        strokeWidth = resources.displayMetrics.density * 1.5f
    }

    private val ovalRect = RectF()
    private val cropRectCache = RectF()
    private val pathCache = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val sourceAspect = w / h

        // 1. Aspect Ratio Scrim & Crop Boundary
        if (config.showCropBoundaries && !config.isCompareRatiosActive) {
            drawAspectCrop(canvas, w, h, sourceAspect)
        }

        // 2. Compare Ratios Multi-Overlay
        if (config.isCompareRatiosActive) {
            drawCompareRatios(canvas, w, h, sourceAspect)
        }

        // 3. Rule of Thirds Grid
        if (config.showRuleOfThirds) {
            drawRuleOfThirds(canvas, w, h)
        }

        // 4. Center Alignment & Symmetry Crosshair
        if (config.showCenterSymmetry) {
            drawCenterSymmetry(canvas, w, h)
        }

        // 5. Portrait Framing Silhouette Guide
        if (config.showPortraitGuide) {
            drawPortraitGuide(canvas, w, h)
        }

        // 6. Horizon Level Gauge (only if hardware sensor is verified available!)
        if (config.showHorizonGuide && isHorizonSensorAvailable) {
            drawHorizonLevel(canvas, w, h)
        }

        // 7. Detected Subject / Face Brackets
        if (config.showSubjectBoxes && detectedFaces.isNotEmpty()) {
            drawSubjectBrackets(canvas, w, h)
        }
    }

    private fun drawRuleOfThirds(canvas: Canvas, w: Float, h: Float) {
        val x1 = w / 3f
        val x2 = w * 2f / 3f
        val y1 = h / 3f
        val y2 = h * 2f / 3f

        canvas.drawLine(x1, 0f, x1, h, gridPaint)
        canvas.drawLine(x2, 0f, x2, h, gridPaint)
        canvas.drawLine(0f, y1, w, y1, gridPaint)
        canvas.drawLine(0f, y2, w, y2, gridPaint)

        // Golden intersection accents
        val radius = 3.5f * resources.displayMetrics.density
        canvas.drawCircle(x1, y1, radius, gridIntersectionPaint)
        canvas.drawCircle(x2, y1, radius, gridIntersectionPaint)
        canvas.drawCircle(x1, y2, radius, gridIntersectionPaint)
        canvas.drawCircle(x2, y2, radius, gridIntersectionPaint)
    }

    private fun drawCenterSymmetry(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h / 2f
        val arm = minOf(w, h) * 0.25f

        canvas.drawLine(cx - arm, cy, cx + arm, cy, symmetryPaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, symmetryPaint)

        val circleRadius = minOf(w, h) * 0.12f
        canvas.drawCircle(cx, cy, circleRadius, symmetryPaint)
    }

    private fun drawPortraitGuide(canvas: Canvas, w: Float, h: Float) {
        // Oval representing head and shoulders placed at upper third
        val cx = w / 2f
        val headY = h * 0.35f
        val headRadiusX = w * 0.18f
        val headRadiusY = h * 0.15f

        ovalRect.set(cx - headRadiusX, headY - headRadiusY, cx + headRadiusX, headY + headRadiusY)
        canvas.drawOval(ovalRect, portraitGuidePaint)

        // Eye line across the upper third
        canvas.drawLine(cx - headRadiusX * 1.1f, headY, cx + headRadiusX * 1.1f, headY, portraitGuidePaint)

        // Shoulders arc
        pathCache.reset()
        val shoulderY = headY + headRadiusY * 1.1f
        pathCache.moveTo(cx - w * 0.32f, shoulderY + h * 0.18f)
        pathCache.quadTo(cx, shoulderY, cx + w * 0.32f, shoulderY + h * 0.18f)
        canvas.drawPath(pathCache, portraitGuidePaint)
    }

    private fun drawHorizonLevel(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h / 2f
        val lineHalfLen = minOf(w, h) * 0.28f

        val roll = horizonRollDegrees
        val isLevel = abs(roll) <= 1.0f

        val rad = Math.toRadians(-roll.toDouble())
        val dx = (lineHalfLen * cos(rad)).toFloat()
        val dy = (lineHalfLen * sin(rad)).toFloat()

        val paint = if (isLevel) horizonLevelPaint else horizonNormalPaint

        // Center reticle
        val reticleLen = 14f * resources.displayMetrics.density
        canvas.drawLine(cx - reticleLen, cy, cx + reticleLen, cy, paint)
        canvas.drawLine(cx, cy - 6f, cx, cy + 6f, paint)

        // Dynamic tilt line
        canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, paint)

        // Readout badge
        val badge = if (isLevel) "0.0° LEVEL" else "${String.format("%+.1f", roll)}°"
        canvas.drawText(badge, cx, cy + 24f * resources.displayMetrics.density, horizonTextPaint)
    }

    private fun drawAspectCrop(canvas: Canvas, w: Float, h: Float, sourceAspect: Float) {
        val normRect = AspectRatioEvaluator.computeNormalizedCropRect(activeAspectOption, sourceAspect)
        val l = normRect.left * w
        val t = normRect.top * h
        val r = normRect.right * w
        val b = normRect.bottom * h

        // Darken areas outside the crop rectangle (letterbox / pillarbox)
        if (t > 0f) canvas.drawRect(0f, 0f, w, t, scrimPaint)
        if (b < h) canvas.drawRect(0f, b, w, h, scrimPaint)
        if (l > 0f) canvas.drawRect(0f, t, l, b, scrimPaint)
        if (r < w) canvas.drawRect(r, t, w, b, scrimPaint)

        // Crop outline
        cropRectCache.set(l, t, r, b)
        canvas.drawRect(cropRectCache, cropOutlinePaint)

        // Corner crop label
        val label = "[ ${activeAspectOption.displayLabel} ]"
        canvas.drawText(label, l + 12f, t + 22f * resources.displayMetrics.density, cropLabelPaint)
    }

    private fun drawCompareRatios(canvas: Canvas, w: Float, h: Float, sourceAspect: Float) {
        val ratios = config.compareRatioOptions
        val paints = listOf(compareOutlinePaint1, compareOutlinePaint2, compareOutlinePaint3)

        ratios.forEachIndexed { idx, opt ->
            val p = paints.getOrElse(idx) { compareOutlinePaint1 }
            val normRect = AspectRatioEvaluator.computeNormalizedCropRect(opt, sourceAspect)
            val l = normRect.left * w
            val t = normRect.top * h
            val r = normRect.right * w
            val b = normRect.bottom * h

            canvas.drawRect(l, t, r, b, p)

            cropLabelPaint.color = p.color
            val label = opt.displayLabel
            canvas.drawText(label, l + 10f + (idx * 50f), t + 20f * resources.displayMetrics.density, cropLabelPaint)
        }
    }

    private fun drawSubjectBrackets(canvas: Canvas, w: Float, h: Float) {
        val bracketSize = 14f * resources.displayMetrics.density

        for (face in detectedFaces) {
            val l = face.left * w
            val t = face.top * h
            val r = face.right * w
            val b = face.bottom * h

            // Top-Left corner
            canvas.drawLine(l, t, l + bracketSize, t, faceBracketPaint)
            canvas.drawLine(l, t, l, t + bracketSize, faceBracketPaint)

            // Top-Right corner
            canvas.drawLine(r, t, r - bracketSize, t, faceBracketPaint)
            canvas.drawLine(r, t, r, t + bracketSize, faceBracketPaint)

            // Bottom-Left corner
            canvas.drawLine(l, b, l + bracketSize, b, faceBracketPaint)
            canvas.drawLine(l, b, l, b - bracketSize, faceBracketPaint)

            // Bottom-Right corner
            canvas.drawLine(r, b, r - bracketSize, b, faceBracketPaint)
            canvas.drawLine(r, b, r, b - bracketSize, faceBracketPaint)
        }
    }
}
