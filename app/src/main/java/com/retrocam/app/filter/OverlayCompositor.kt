package com.retrocam.app.filter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The overlays that change a frame's *shape* rather than its pixels: aspect crop, the Polaroid
 * border, and the burned-in date stamp. These can't live in [FilterShader] — a shader writes a
 * fixed-size buffer, and a border makes the output bigger than the input.
 *
 * Runs on both the preview and the capture, at their respective resolutions. Every size is a
 * fraction of the image, never a pixel count, so the two agree.
 */
object OverlayCompositor {

    // compose() runs on every preview frame, so the objects it needs are built once. Paint and
    // SimpleDateFormat in particular are expensive to construct and were being churned per frame.
    //
    // Neither is thread-safe, which is fine only because both callers — the preview analyzer and
    // the capture callback — run on MainActivity's single-threaded cameraExecutor. Give capture
    // its own executor and this needs a lock or a ThreadLocal.
    private val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.08f
    }
    private var formatter = SimpleDateFormat("yyyy M d", Locale.US)
    private var formatterPattern = "yyyy M d"

    /**
     * Returns the composed frame, or [source] itself when [preset] has no overlays. Callers must
     * therefore compare by identity before recycling: `if (composed !== source) source.recycle()`.
     */
    fun compose(source: Bitmap, preset: FilterPreset, date: Date): Bitmap {
        if (!preset.hasOverlays) return source

        val cropped = preset.aspect?.let { cropToAspect(source, it) } ?: source
        val frame = preset.frame

        val side = frame?.let { (cropped.width * it.side).roundToInt() } ?: 0
        val top = frame?.let { (cropped.width * it.top).roundToInt() } ?: 0
        val bottom = frame?.let { (cropped.width * it.bottom).roundToInt() } ?: 0

        val output = Bitmap.createBitmap(
            cropped.width + side * 2,
            cropped.height + top + bottom,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(output)
        canvas.drawColor(frame?.color ?: Color.BLACK)
        canvas.drawBitmap(cropped, side.toFloat(), top.toFloat(), null)

        // Stamped inside the picture, not on the border — it's exposed onto the film, not printed.
        preset.dateStamp?.let {
            drawDateStamp(canvas, it, date, side, top, cropped.width, cropped.height)
        }

        if (cropped !== source) cropped.recycle()
        return output
    }

    private fun cropToAspect(source: Bitmap, aspect: Float): Bitmap {
        val sourceAspect = source.width.toFloat() / source.height
        if (abs(sourceAspect - aspect) < 0.001f) return source

        return if (sourceAspect > aspect) {
            val width = (source.height * aspect).roundToInt().coerceAtMost(source.width)
            Bitmap.createBitmap(source, (source.width - width) / 2, 0, width, source.height)
        } else {
            val height = (source.width / aspect).roundToInt().coerceAtMost(source.height)
            Bitmap.createBitmap(source, 0, (source.height - height) / 2, source.width, height)
        }
    }

    private fun drawDateStamp(
        canvas: Canvas,
        stamp: DateStamp,
        date: Date,
        imageLeft: Int,
        imageTop: Int,
        imageWidth: Int,
        imageHeight: Int
    ) {
        val paint = stampPaint.apply {
            color = stamp.color
            textSize = imageHeight * stamp.size
            // The amber bleed of a real optical date-back, not a drop shadow.
            if (stamp.glow) setShadowLayer(textSize * 0.45f, 0f, 0f, stamp.color) else clearShadowLayer()
        }

        if (stamp.format != formatterPattern) {
            formatter = SimpleDateFormat(stamp.format, Locale.US)
            formatterPattern = stamp.format
        }

        val inset = imageWidth * INSET_FRACTION
        canvas.drawText(
            formatter.format(date),
            imageLeft + imageWidth - inset,
            imageTop + imageHeight - inset,
            paint
        )
    }

    private const val INSET_FRACTION = 0.045f
}
