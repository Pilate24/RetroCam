package com.retrocam.app.booth

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

object FilmStripGenerator {

    enum class StripTheme(
        val label: String,
        val backgroundColor: Int,
        val textColor: Int,
        val accentColor: Int,
        val frameBorderColor: Int
    ) {
        CLASSIC_WHITE(
            "Classic White",
            0xFFFFFFFF.toInt(),
            0xFF1A1A1A.toInt(),
            0xFFD97706.toInt(),
            0x15000000.toInt()
        ),
        RETRO_BLACK(
            "Matte Black",
            0xFF141414.toInt(),
            0xFFF3F4F6.toInt(),
            0xFFFBBF24.toInt(),
            0x25FFFFFF.toInt()
        ),
        CREAM_PAPER(
            "Vintage Cream",
            0xFFF7F2E7.toInt(),
            0xFF292524.toInt(),
            0xFFB45309.toInt(),
            0x15292524.toInt()
        ),
        Y2K_PINK(
            "Pastel Pink",
            0xFFFDE8ED.toInt(),
            0xFF831843.toInt(),
            0xFFBE185D.toInt(),
            0x15831843.toInt()
        ),
        LAVENDER(
            "90s Lavender",
            0xFFE8EAF6.toInt(),
            0xFF1E1B4B.toInt(),
            0xFF4338CA.toInt(),
            0x151E1B4B.toInt()
        )
    }

    data class StripOptions(
        val theme: StripTheme = StripTheme.CLASSIC_WHITE,
        val showDate: Boolean = true,
        val showBarcode: Boolean = true,
        val headerText: String = "★ RETROCAM PHOTO BOOTH ★",
        val presetName: String = "Retro"
    )

    const val STRIP_WIDTH = 1200
    const val MARGIN_HORIZONTAL = 60
    const val FRAME_GAP = 36
    const val HEADER_HEIGHT = 160
    const val FOOTER_HEIGHT = 280
    const val CORNER_RADIUS = 12f

    fun calculateTotalHeight(count: Int): Int {
        val safeCount = count.coerceIn(1, 4)
        val photoWidth = STRIP_WIDTH - (MARGIN_HORIZONTAL * 2)
        val photoHeight = (photoWidth * 2) / 3
        return HEADER_HEIGHT + (photoHeight * safeCount) + (FRAME_GAP * (safeCount - 1)) + FOOTER_HEIGHT
    }

    fun generate(bitmaps: List<Bitmap>, options: StripOptions, date: Date = Date()): Bitmap {
        val count = bitmaps.size.coerceAtMost(4)
        val photoWidth = STRIP_WIDTH - (MARGIN_HORIZONTAL * 2) // 1080 px
        val photoHeight = (photoWidth * 2) / 3 // 720 px (3:2 classic landscape ratio)

        val totalHeight = HEADER_HEIGHT + (photoHeight * count) + (FRAME_GAP * (count - 1).coerceAtLeast(0)) + FOOTER_HEIGHT

        val strip = Bitmap.createBitmap(STRIP_WIDTH, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(strip)

        // 1. Background
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = options.theme.backgroundColor
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, STRIP_WIDTH.toFloat(), totalHeight.toFloat(), bgPaint)

        // 2. Header
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = options.theme.textColor
            textSize = 38f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.15f
        }
        canvas.drawText(options.headerText, STRIP_WIDTH / 2f, 105f, headerPaint)

        // Subtle header decorative accent dots
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = options.theme.accentColor
            style = Paint.Style.FILL
        }
        val dotY = 135f
        for (i in -2..2) {
            canvas.drawCircle((STRIP_WIDTH / 2f) + (i * 30f), dotY, 4f, dotPaint)
        }

        // 3. Draw Photos
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = options.theme.frameBorderColor
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        for (i in 0 until count) {
            val src = bitmaps[i]
            val top = HEADER_HEIGHT + i * (photoHeight + FRAME_GAP)
            val rect = RectF(
                MARGIN_HORIZONTAL.toFloat(),
                top.toFloat(),
                (MARGIN_HORIZONTAL + photoWidth).toFloat(),
                (top + photoHeight).toFloat()
            )

            // Draw photo with center crop
            drawCenterCrop(canvas, src, rect, CORNER_RADIUS, photoPaint)

            // Draw frame stroke
            canvas.drawRoundRect(rect, CORNER_RADIUS, CORNER_RADIUS, borderPaint)

            // Draw analog frame index on top-left of each photo
            val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = options.theme.textColor
                textSize = 24f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                alpha = 140
            }
            canvas.drawText(
                String.format(Locale.US, "#%02d", i + 1),
                (MARGIN_HORIZONTAL + 16).toFloat(),
                (top - 10).toFloat(),
                numPaint
            )
        }

        // 4. Footer Info Line
        val footerStartY = HEADER_HEIGHT + (photoHeight * count) + (FRAME_GAP * (count - 1).coerceAtLeast(0))
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = options.theme.textColor
            textSize = 28f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.1f
        }

        val dateStr = SimpleDateFormat("yyyy.MM.dd · HH:mm", Locale.US).format(date)
        val infoLine = if (options.showDate) {
            "$dateStr  |  ${options.presetName.uppercase(Locale.US)}"
        } else {
            "RETROCAM 4-CUT  |  ${options.presetName.uppercase(Locale.US)}"
        }
        canvas.drawText(infoLine, STRIP_WIDTH / 2f, footerStartY + 65f, textPaint)

        // 5. Stylized Analog Barcode & Serial Number
        if (options.showBarcode) {
            val barcodeY = footerStartY + 115f
            val barcodeHeight = 65f
            drawBarcode(canvas, STRIP_WIDTH / 2f, barcodeY, barcodeHeight, options.theme.textColor)

            val codeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = options.theme.textColor
                textSize = 20f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
                letterSpacing = 0.2f
                alpha = 180
            }
            canvas.drawText("NO. 8420-BOOTH-4CUTS", STRIP_WIDTH / 2f, barcodeY + barcodeHeight + 35f, codeTextPaint)
        }

        return strip
    }

    private fun drawCenterCrop(canvas: Canvas, bitmap: Bitmap, dst: RectF, radius: Float, paint: Paint) {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val dstW = dst.width()
        val dstH = dst.height()

        val scale = max(dstW / srcW.toFloat(), dstH / srcH.toFloat())
        val cropW = (dstW / scale).toInt()
        val cropH = (dstH / scale).toInt()

        val cropX = ((srcW - cropW) / 2).coerceAtLeast(0)
        val cropY = ((srcH - cropH) / 2).coerceAtLeast(0)

        val srcRect = Rect(cropX, cropY, cropX + cropW, cropY + cropH)

        canvas.save()
        val path = Path().apply {
            addRoundRect(dst, radius, radius, Path.Direction.CW)
        }
        canvas.clipPath(path)
        canvas.drawBitmap(bitmap, srcRect, dst, paint)
        canvas.restore()
    }

    private fun drawBarcode(canvas: Canvas, centerX: Float, startY: Float, height: Float, color: Int) {
        val paint = Paint().apply {
            this.color = color
            style = Paint.Style.FILL
            isAntiAlias = false
        }

        // Deterministic sequence of bar widths
        val pattern = intArrayOf(
            3, 2, 6, 3, 2, 4, 7, 2, 3, 5, 2, 7, 3, 2, 4, 3, 6, 2, 4, 3, 7, 2, 4, 2, 6, 3,
            2, 5, 7, 2, 3, 4, 6, 3, 2, 5, 2, 7, 3, 2, 4, 6, 2, 5, 3, 7, 2, 3, 4, 2, 6, 3
        )
        val totalPatternWidth = pattern.sum() + (pattern.size * 3)
        var curX = centerX - (totalPatternWidth / 2f)

        for ((idx, w) in pattern.withIndex()) {
            if (idx % 2 == 0) {
                canvas.drawRect(curX, startY, curX + w, startY + height, paint)
            }
            curX += w + 3f
        }
    }
}
