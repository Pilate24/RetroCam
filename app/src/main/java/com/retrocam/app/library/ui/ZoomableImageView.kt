package com.retrocam.app.library.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr),
    ScaleGestureDetector.OnScaleGestureListener,
    GestureDetector.OnDoubleTapListener {

    private val matrix = Matrix()
    private val prevMatrix = Matrix()

    private var mode = NONE

    private val start = PointF()
    private val mid = PointF()
    private var oldDist = 1f

    private var scale = 1f
    private val minScale = 1f
    private val maxScale = 5f

    private val matrixValues = FloatArray(9)
    private var origWidth = 0f
    private var origHeight = 0f
    private var viewWidth = 0
    private var viewHeight = 0

    private val scaleDetector: ScaleGestureDetector = ScaleGestureDetector(context, this)
    private val gestureDetector: GestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            return this@ZoomableImageView.onDoubleTap(e)
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        gestureDetector.setOnDoubleTapListener(this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        viewWidth = MeasureSpec.getSize(widthMeasureSpec)
        viewHeight = MeasureSpec.getSize(heightMeasureSpec)
        fitToScreen()
    }

    private fun fitToScreen() {
        val drawable = drawable ?: return
        val bmWidth = drawable.intrinsicWidth.toFloat()
        val bmHeight = drawable.intrinsicHeight.toFloat()
        if (bmWidth == 0f || bmHeight == 0f || viewWidth == 0 || viewHeight == 0) return

        val scaleX = viewWidth / bmWidth
        val scaleY = viewHeight / bmHeight
        val fitScale = scaleX.coerceAtMost(scaleY)

        matrix.setScale(fitScale, fitScale)

        val redundantYSpace = (viewHeight - fitScale * bmHeight) / 2f
        val redundantXSpace = (viewWidth - fitScale * bmWidth) / 2f
        matrix.postTranslate(redundantXSpace, redundantYSpace)

        origWidth = viewWidth - 2 * redundantXSpace
        origHeight = viewHeight - 2 * redundantYSpace
        imageMatrix = matrix
        scale = 1f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val currentPoint = PointF(event.x, event.y)

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                prevMatrix.set(matrix)
                start.set(currentPoint)
                mode = DRAG
                parent?.requestDisallowInterceptTouchEvent(scale > 1.05f)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                oldDist = spacing(event)
                if (oldDist > 10f) {
                    prevMatrix.set(matrix)
                    midPoint(mid, event)
                    mode = ZOOM
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG && scale > 1.05f) {
                    val deltaX = currentPoint.x - start.x
                    val deltaY = currentPoint.y - start.y
                    matrix.set(prevMatrix)
                    matrix.postTranslate(deltaX, deltaY)
                    fixTranslation()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
                if (scale <= 1.05f) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }

        imageMatrix = matrix
        invalidate()
        return true
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        var mScaleFactor = detector.scaleFactor
        val origScale = scale
        scale *= mScaleFactor

        if (scale > maxScale) {
            scale = maxScale
            mScaleFactor = maxScale / origScale
        } else if (scale < minScale) {
            scale = minScale
            mScaleFactor = minScale / origScale
        }

        if (origWidth * scale <= viewWidth || origHeight * scale <= viewHeight) {
            matrix.postScale(mScaleFactor, mScaleFactor, viewWidth / 2f, viewHeight / 2f)
        } else {
            matrix.postScale(mScaleFactor, mScaleFactor, detector.focusX, detector.focusY)
        }

        fixTranslation()
        return true
    }

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        mode = ZOOM
        return true
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) {
        mode = NONE
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        val targetScale = if (scale > 1.5f) minScale else 2.5f
        val factor = targetScale / scale
        scale = targetScale

        if (targetScale == minScale) {
            fitToScreen()
        } else {
            matrix.postScale(factor, factor, e.x, e.y)
            fixTranslation()
        }
        imageMatrix = matrix
        invalidate()
        return true
    }

    override fun onDoubleTapEvent(e: MotionEvent): Boolean = false
    override fun onSingleTapConfirmed(e: MotionEvent): Boolean = false

    private fun fixTranslation() {
        matrix.getValues(matrixValues)
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]

        val fixTransX = getFixTrans(transX, viewWidth.toFloat(), origWidth * scale)
        val fixTransY = getFixTrans(transY, viewHeight.toFloat(), origHeight * scale)

        if (fixTransX != 0f || fixTransY != 0f) {
            matrix.postTranslate(fixTransX, fixTransY)
        }
    }

    private fun getFixTrans(trans: Float, viewSize: Float, contentSize: Float): Float {
        val minTrans: Float
        val maxTrans: Float

        if (contentSize <= viewSize) {
            minTrans = 0f
            maxTrans = viewSize - contentSize
        } else {
            minTrans = viewSize - contentSize
            maxTrans = 0f
        }

        if (trans < minTrans) return -trans + minTrans
        if (trans > maxTrans) return -trans + maxTrans
        return 0f
    }

    private fun spacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt((x * x + y * y).toDouble()).toFloat()
    }

    private fun midPoint(point: PointF, event: MotionEvent) {
        val x = event.getX(0) + event.getX(1)
        val y = event.getY(0) + event.getY(1)
        point.set(x / 2, y / 2)
    }

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }
}
