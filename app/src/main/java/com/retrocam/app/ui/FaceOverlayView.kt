package com.retrocam.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Draws the tracking boxes over detected faces.
 *
 * A plain View on top of the SurfaceView rather than something drawn into the camera frame: face
 * boxes are interface, not photograph, and must never end up burned into a capture.
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FFF2C14E")
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    private var faces: List<RectF> = emptyList()

    fun setFaces(boxes: List<RectF>) {
        // Redrawing an unchanged empty list every frame is pure waste; most frames have no faces.
        if (boxes.isEmpty() && faces.isEmpty()) return
        faces = boxes
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = 6f * resources.displayMetrics.density
        for (face in faces) canvas.drawRoundRect(face, radius, radius, boxPaint)
    }
}
