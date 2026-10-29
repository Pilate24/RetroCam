package com.retrocam.app.remaster.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Interactive before-and-after split comparison view with synchronized zoom and pan.
 *
 * Features:
 * - Smooth draggable vertical divider bar with tactile grab handle.
 * - Synchronized multi-touch pinch-to-zoom (1x - 5x) and pan across both images.
 * - Double-tap to zoom in/out.
 * - Long-press to temporarily inspect full original.
 * - "ORIGINAL" and "REMASTERED" styling badges.
 */
class BeforeAfterComparisonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var originalBitmap: Bitmap? = null
    private var remasteredBitmap: Bitmap? = null

    // Split ratio between 0.0 (all remastered) and 1.0 (all original). Default 0.5 (split evenly)
    private var splitRatio = 0.5f

    // Zoom and pan state
    private var scaleFactor = 1.0f
    private var panX = 0f
    private var panY = 0f

    private var isDraggingDivider = false
    private var isTemporaryOriginalMode = false

    // Paints
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(8f, 0f, 2f, 0x88000000.toInt())
    }
    private val handleIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1A1A.toInt()
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA000000.toInt()
        style = Paint.Style.FILL
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    // Geometry rects
    private val viewRect = RectF()
    private val imageBounds = RectF()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val clipPath = Path()

    // Gestures
    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    init {
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prevScale = scaleFactor
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(1.0f, 5.0f)

                // Adjust pan to zoom toward focal point
                val focusX = detector.focusX
                val focusY = detector.focusY
                panX = focusX - (focusX - panX) * (scaleFactor / prevScale)
                panY = focusY - (focusY - panY) * (scaleFactor / prevScale)
                clampPan()
                invalidate()
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scaleFactor > 1.2f) {
                    // Reset zoom
                    scaleFactor = 1.0f
                    panX = 0f
                    panY = 0f
                } else {
                    // Zoom in to 2.5x at tap location
                    scaleFactor = 2.5f
                    panX = width / 2f - e.x * scaleFactor + width / 2f
                    panY = height / 2f - e.y * scaleFactor + height / 2f
                    clampPan()
                }
                invalidate()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!isDraggingDivider) {
                    isTemporaryOriginalMode = true
                    invalidate()
                }
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (!isDraggingDivider && scaleFactor > 1.0f) {
                    panX -= distanceX
                    panY -= distanceY
                    clampPan()
                    invalidate()
                    return true
                }
                return false
            }
        })
    }

    fun setBitmaps(original: Bitmap, remastered: Bitmap) {
        this.originalBitmap = original
        this.remasteredBitmap = remastered
        invalidate()
    }

    fun updateRemastered(remastered: Bitmap) {
        this.remasteredBitmap = remastered
        invalidate()
    }

    fun resetZoom() {
        scaleFactor = 1.0f
        panX = 0f
        panY = 0f
        splitRatio = 0.5f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewRect.set(0f, 0f, w.toFloat(), h.toFloat())
        clampPan()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val orig = originalBitmap ?: return
        val rem = remasteredBitmap ?: orig

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        // Compute aspect fit bounds for image inside view
        computeImageBounds(orig.width, orig.height, viewW, viewH)

        val dividerX = if (isTemporaryOriginalMode) viewW else viewW * splitRatio

        // Setup transformation matrix for zoom & pan
        canvas.save()
        canvas.translate(panX, panY)
        canvas.scale(scaleFactor, scaleFactor, viewW / 2f, viewH / 2f)

        srcRect.set(0, 0, rem.width, rem.height)
        dstRect.set(imageBounds)

        if (isTemporaryOriginalMode) {
            // Display full original
            srcRect.set(0, 0, orig.width, orig.height)
            canvas.drawBitmap(orig, srcRect, dstRect, bitmapPaint)
        } else {
            // Draw Remastered on the right side
            canvas.save()
            clipPath.reset()
            // Map dividerX back to unscaled canvas coordinates
            val localDividerX = mapScreenXToCanvas(dividerX, viewW)
            clipPath.addRect(localDividerX, -viewH * 2, viewW * 3, viewH * 3, Path.Direction.CW)
            canvas.clipPath(clipPath)
            canvas.drawBitmap(rem, srcRect, dstRect, bitmapPaint)
            canvas.restore()

            // Draw Original on the left side
            canvas.save()
            clipPath.reset()
            clipPath.addRect(-viewW * 2, -viewH * 2, localDividerX, viewH * 3, Path.Direction.CW)
            canvas.clipPath(clipPath)
            srcRect.set(0, 0, orig.width, orig.height)
            canvas.drawBitmap(orig, srcRect, dstRect, bitmapPaint)
            canvas.restore()
        }

        canvas.restore()

        // Draw UI overlay: Divider line, drag handle, and badge labels (drawn unscaled in screen space)
        if (!isTemporaryOriginalMode) {
            drawDividerAndHandle(canvas, dividerX, viewH)
            drawBadges(canvas, dividerX, viewW, viewH)
        }
    }

    private fun drawDividerAndHandle(canvas: Canvas, dividerX: Float, viewH: Float) {
        // Divider line
        canvas.drawLine(dividerX, 0f, dividerX, viewH, dividerPaint)

        // Center Handle pill
        val handleW = 60f
        val handleH = 60f
        val centerY = viewH / 2f
        val handleRect = RectF(
            dividerX - handleW / 2f,
            centerY - handleH / 2f,
            dividerX + handleW / 2f,
            centerY + handleH / 2f
        )

        canvas.drawRoundRect(handleRect, 30f, 30f, handlePaint)

        // Draw double arrow glyph (< | >)
        val iconY = centerY
        val arrowSize = 8f
        // Left arrow
        canvas.drawLine(dividerX - 12f, iconY, dividerX - 6f, iconY - arrowSize, handleIconPaint)
        canvas.drawLine(dividerX - 12f, iconY, dividerX - 6f, iconY + arrowSize, handleIconPaint)
        // Center line
        canvas.drawLine(dividerX, iconY - 14f, dividerX, iconY + 14f, handleIconPaint)
        // Right arrow
        canvas.drawLine(dividerX + 12f, iconY, dividerX + 6f, iconY - arrowSize, handleIconPaint)
        canvas.drawLine(dividerX + 12f, iconY, dividerX + 6f, iconY + arrowSize, handleIconPaint)
    }

    private fun drawBadges(canvas: Canvas, dividerX: Float, viewW: Float, viewH: Float) {
        val badgeTop = 24f
        val badgeHeight = 44f
        val badgePadding = 20f
        val cornerRadius = 22f

        // "ORIGINAL" Badge
        val origText = "ORIGINAL"
        val origTextW = badgeTextPaint.measureText(origText)
        val origLeft = 24f
        val origRect = RectF(origLeft, badgeTop, origLeft + origTextW + badgePadding * 2, badgeTop + badgeHeight)
        canvas.drawRoundRect(origRect, cornerRadius, cornerRadius, badgeBgPaint)
        canvas.drawText(origText, origRect.centerX(), badgeTop + badgeHeight - 12f, badgeTextPaint)

        // "REMASTERED" Badge
        val remText = "REMASTERED"
        val remTextW = badgeTextPaint.measureText(remText)
        val remRight = viewW - 24f
        val remRect = RectF(remRight - remTextW - badgePadding * 2, badgeTop, remRight, badgeTop + badgeHeight)
        canvas.drawRoundRect(remRect, cornerRadius, cornerRadius, badgeBgPaint)
        canvas.drawText(remText, remRect.centerX(), badgeTop + badgeHeight - 12f, badgeTextPaint)
    }

    private fun computeImageBounds(imgW: Int, imgH: Int, viewW: Float, viewH: Float) {
        val imgAspect = imgW.toFloat() / imgH.toFloat()
        val viewAspect = viewW / viewH

        if (imgAspect > viewAspect) {
            val dstW = viewW
            val dstH = viewW / imgAspect
            val top = (viewH - dstH) / 2f
            imageBounds.set(0f, top, dstW, top + dstH)
        } else {
            val dstH = viewH
            val dstW = viewH * imgAspect
            val left = (viewW - dstW) / 2f
            imageBounds.set(left, 0f, left + dstW, dstH)
        }
    }

    private fun mapScreenXToCanvas(screenX: Float, viewW: Float): Float {
        val centerX = viewW / 2f
        return (screenX - panX - centerX) / scaleFactor + centerX
    }

    private fun clampPan() {
        if (scaleFactor <= 1.0f) {
            panX = 0f
            panY = 0f
            return
        }
        val maxPanX = (width * (scaleFactor - 1f)) / 2f
        val maxPanY = (height * (scaleFactor - 1f)) / 2f
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val x = event.x
        val viewW = width.toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val currentDividerX = viewW * splitRatio
                val touchSlop = 60f
                if (abs(x - currentDividerX) <= touchSlop && event.pointerCount == 1) {
                    isDraggingDivider = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingDivider && event.pointerCount == 1) {
                    splitRatio = (x / viewW).coerceIn(0.02f, 0.98f)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isTemporaryOriginalMode) {
                    isTemporaryOriginalMode = false
                    invalidate()
                }
                if (isDraggingDivider) {
                    isDraggingDivider = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        return true
    }
}
