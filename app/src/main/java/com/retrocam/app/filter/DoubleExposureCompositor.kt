package com.retrocam.app.filter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect

object DoubleExposureCompositor {

    enum class BlendMethod {
        SCREEN,   // Classic analog emulsion (additive light, avoids highlight clipping)
        LIGHTEN,  // Highest channel luminosity (great for neon lights / light painting)
        BALANCED  // 50/50 exposure average (half-stop split)
    }

    /**
     * Blends two photographic exposures into a single analog double-exposure frame.
     *
     * In traditional analog photography, exposing the same frame of film twice allows
     * the second exposure to burn into the unexposed shadow areas of the first.
     * [BlendMethod.SCREEN] mathematically models this physical light exposure:
     * C = 1 - (1 - A) * (1 - B)
     */
    fun blend(
        base: Bitmap,
        overlay: Bitmap,
        method: BlendMethod = BlendMethod.SCREEN,
        overlayWeight: Float = 1.0f
    ): Bitmap {
        val width = base.width
        val height = base.height

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Draw base shot
        canvas.drawBitmap(base, 0f, 0f, null)

        // Configure blending paint
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            when (method) {
                BlendMethod.SCREEN -> {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                    alpha = (overlayWeight.coerceIn(0f, 1f) * 255).toInt()
                }
                BlendMethod.LIGHTEN -> {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
                    alpha = (overlayWeight.coerceIn(0f, 1f) * 255).toInt()
                }
                BlendMethod.BALANCED -> {
                    alpha = (0.5f * 255).toInt()
                }
            }
        }

        // Scale overlay to match base dimensions if they differ
        if (overlay.width == width && overlay.height == height) {
            canvas.drawBitmap(overlay, 0f, 0f, paint)
        } else {
            val srcRect = Rect(0, 0, overlay.width, overlay.height)
            val dstRect = Rect(0, 0, width, height)
            canvas.drawBitmap(overlay, srcRect, dstRect, paint)
        }

        return result
    }
}
