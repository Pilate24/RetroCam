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

    /**
     * compose() runs on every preview frame, so the objects it needs are built once rather than
     * churned per frame. Neither Paint nor SimpleDateFormat is thread-safe, and compose() now has
     * more than one caller — the camera thread and the thumbnail renderer — so they are held
     * per-thread instead of shared.
     */
    private class StampTools {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        var formatter: SimpleDateFormat = SimpleDateFormat(DEFAULT_PATTERN, Locale.US)
        var pattern: String = DEFAULT_PATTERN
    }

    private val stampTools = ThreadLocal.withInitial { StampTools() }

    /**
     * Where the picture sits inside the composed output. Tap-to-focus has to invert this to turn a
     * screen tap back into a sensor coordinate, so the geometry lives here as the single source of
     * truth rather than being re-derived — the two drifting apart would aim the focus point at the
     * wrong part of the scene, which is invisible in code review and obvious in a blurry photo.
     */
    data class Layout(
        val cropLeft: Int,
        val cropTop: Int,
        val cropWidth: Int,
        val cropHeight: Int,
        val borderLeft: Int,
        val borderTop: Int,
        val outputWidth: Int,
        val outputHeight: Int
    )

    fun layoutFor(sourceWidth: Int, sourceHeight: Int, preset: FilterPreset): Layout {
        var cropWidth = sourceWidth
        var cropHeight = sourceHeight

        val aspect = preset.aspect
        if (aspect != null && abs(sourceWidth.toFloat() / sourceHeight - aspect) >= 0.001f) {
            if (sourceWidth.toFloat() / sourceHeight > aspect) {
                cropWidth = (sourceHeight * aspect).roundToInt().coerceAtMost(sourceWidth)
            } else {
                cropHeight = (sourceWidth / aspect).roundToInt().coerceAtMost(sourceHeight)
            }
        }

        val frame = preset.frame
        val side = frame?.let { (cropWidth * it.side).roundToInt() } ?: 0
        val top = frame?.let { (cropWidth * it.top).roundToInt() } ?: 0
        val bottom = frame?.let { (cropWidth * it.bottom).roundToInt() } ?: 0

        return Layout(
            cropLeft = (sourceWidth - cropWidth) / 2,
            cropTop = (sourceHeight - cropHeight) / 2,
            cropWidth = cropWidth,
            cropHeight = cropHeight,
            borderLeft = side,
            borderTop = top,
            outputWidth = cropWidth + side * 2,
            outputHeight = cropHeight + top + bottom
        )
    }

    /**
     * Returns the composed frame, or [source] itself when [preset] has no overlays. Callers must
     * therefore compare by identity before recycling: `if (composed !== source) source.recycle()`.
     */
    fun compose(source: Bitmap, preset: FilterPreset, date: Date): Bitmap {
        if (!preset.hasOverlays) return source

        val layout = layoutFor(source.width, source.height, preset)

        val cropped = if (layout.cropWidth == source.width && layout.cropHeight == source.height) {
            source
        } else {
            Bitmap.createBitmap(
                source, layout.cropLeft, layout.cropTop, layout.cropWidth, layout.cropHeight
            )
        }

        val output = Bitmap.createBitmap(
            layout.outputWidth, layout.outputHeight, Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(output)
        canvas.drawColor(preset.frame?.color ?: Color.BLACK)
        canvas.drawBitmap(cropped, layout.borderLeft.toFloat(), layout.borderTop.toFloat(), null)

        // Stamped inside the picture, not on the border — it's exposed onto the film, not printed.
        preset.dateStamp?.let {
            drawDateStamp(
                canvas, it, date,
                layout.borderLeft, layout.borderTop, layout.cropWidth, layout.cropHeight
            )
        }

        if (cropped !== source) cropped.recycle()
        return output
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
        val tools = stampTools.get()!!
        val paint = tools.paint.apply {
            color = stamp.color
            textSize = imageHeight * stamp.size
            // The amber bleed of a real optical date-back, not a drop shadow.
            if (stamp.glow) setShadowLayer(textSize * 0.45f, 0f, 0f, stamp.color) else clearShadowLayer()
        }

        if (stamp.format != tools.pattern) {
            tools.formatter = SimpleDateFormat(stamp.format, Locale.US)
            tools.pattern = stamp.format
        }

        val inset = imageWidth * INSET_FRACTION
        canvas.drawText(
            tools.formatter.format(date),
            imageLeft + imageWidth - inset,
            imageTop + imageHeight - inset,
            paint
        )
    }

    private const val INSET_FRACTION = 0.045f
    private const val DEFAULT_PATTERN = "yyyy M d"
}
