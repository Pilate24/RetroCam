package com.retrocam.app.pro

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * Fast edge-gradient detector for manual focus peaking.
 * Identifies high-frequency in-focus contours and highlights them with a vibrant neon color.
 */
object FocusPeakingDetector {

    private const val DEFAULT_PEAK_COLOR = 0xFF00FF66.toInt() // Neon Electric Mint Green
    private const val THRESHOLD_GRADIENT = 42

    fun detectEdges(bitmap: Bitmap, peakColor: Int = DEFAULT_PEAK_COLOR): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        val outPixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Compute horizontal & vertical luminance gradients
        for (y in 1 until height - 1) {
            val rowOffset = y * width
            val rowAbove = (y - 1) * width
            val rowBelow = (y + 1) * width

            for (x in 1 until width - 1) {
                val pix = pixels[rowOffset + x]
                val lumaCenter = ((pix shr 16 and 0xFF) * 299 + (pix shr 8 and 0xFF) * 587 + (pix and 0xFF) * 114) / 1000

                val pixRight = pixels[rowOffset + x + 1]
                val lumaRight = ((pixRight shr 16 and 0xFF) * 299 + (pixRight shr 8 and 0xFF) * 587 + (pixRight and 0xFF) * 114) / 1000

                val pixDown = pixels[rowBelow + x]
                val lumaDown = ((pixDown shr 16 and 0xFF) * 299 + (pixDown shr 8 and 0xFF) * 587 + (pixDown and 0xFF) * 114) / 1000

                val gradX = abs(lumaRight - lumaCenter)
                val gradY = abs(lumaDown - lumaCenter)
                val grad = gradX + gradY

                if (grad >= THRESHOLD_GRADIENT) {
                    outPixels[rowOffset + x] = peakColor
                } else {
                    outPixels[rowOffset + x] = 0 // Transparent
                }
            }
        }

        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return output
    }
}
