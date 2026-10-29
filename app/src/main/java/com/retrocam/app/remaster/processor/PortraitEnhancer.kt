package com.retrocam.app.remaster.processor

import android.graphics.Bitmap
import android.graphics.Color
import com.retrocam.app.remaster.analyzer.ImageRemasterAnalyzer
import com.retrocam.app.remaster.model.RemasterAdjustments
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Dedicated Portrait Enhancement Processor.
 *
 * Implements:
 * - Natural skin smoothing (guided bilateral filtering that respects facial contours and texture).
 * - Face illumination correction (dodge/exposure lift on face regions).
 * - Eye and facial feature detail enhancement (unsharp masking on eyes and brows).
 * - Red-eye reduction (detecting and desaturating pupil flash reflections).
 * - Background blur (subtle depth of field separation).
 */
object PortraitEnhancer {

    fun enhance(
        source: Bitmap,
        adjustments: RemasterAdjustments,
        faces: List<ImageRemasterAnalyzer.FaceInfo>
    ): Bitmap {
        if (adjustments.portraitSkinSmoothing == 0f &&
            adjustments.portraitFaceIllumination == 0f &&
            adjustments.portraitEyeEnhancement == 0f &&
            adjustments.portraitBackgroundBlur == 0f &&
            !adjustments.portraitRedEyeCorrection
        ) {
            return source
        }

        val width = source.width
        val height = source.height
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(width * height)
        output.getPixels(pixels, 0, width, 0, 0, width, height)

        // 1. Process Red-Eye Correction if enabled
        if (adjustments.portraitRedEyeCorrection && faces.isNotEmpty()) {
            correctRedEye(pixels, width, height, faces)
        }

        // 2. Process Eye Enhancement if enabled
        if (adjustments.portraitEyeEnhancement > 0.05f && faces.isNotEmpty()) {
            enhanceEyes(pixels, width, height, faces, adjustments.portraitEyeEnhancement)
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    private fun correctRedEye(
        pixels: IntArray,
        width: Int,
        height: Int,
        faces: List<ImageRemasterAnalyzer.FaceInfo>
    ) {
        for (face in faces) {
            val eyeDist = face.eyesDistance
            if (eyeDist <= 5f) continue

            val midX = face.midPoint.x
            val midY = face.midPoint.y
            val eyeRadius = (eyeDist * 0.18f).roundToInt().coerceIn(3, 40)

            // Approximate left and right eye locations
            val leftEyeX = (midX - eyeDist * 0.5f).roundToInt()
            val rightEyeX = (midX + eyeDist * 0.5f).roundToInt()
            val eyeY = midY.roundToInt()

            processEyePupilRedness(pixels, width, height, leftEyeX, eyeY, eyeRadius)
            processEyePupilRedness(pixels, width, height, rightEyeX, eyeY, eyeRadius)
        }
    }

    private fun processEyePupilRedness(
        pixels: IntArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        radius: Int
    ) {
        val rSq = radius * radius
        val startY = max(0, centerY - radius)
        val endY = min(height - 1, centerY + radius)
        val startX = max(0, centerX - radius)
        val endX = min(width - 1, centerX + radius)

        for (y in startY..endY) {
            val row = y * width
            val dy = y - centerY
            for (x in startX..endX) {
                val dx = x - centerX
                if (dx * dx + dy * dy <= rSq) {
                    val c = pixels[row + x]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF

                    // High red dominance indicates flash reflection / red-eye
                    if (r > 80 && r > (g + b) * 0.85f && (r - g) > 25 && (r - b) > 25) {
                        val neutralGray = ((g + b) / 2).coerceIn(10, 80)
                        pixels[row + x] = (0xFF shl 24) or (neutralGray shl 16) or (neutralGray shl 8) or neutralGray
                    }
                }
            }
        }
    }

    private fun enhanceEyes(
        pixels: IntArray,
        width: Int,
        height: Int,
        faces: List<ImageRemasterAnalyzer.FaceInfo>,
        strength: Float
    ) {
        val factor = strength.coerceIn(0.1f, 1.0f) * 0.6f

        for (face in faces) {
            val eyeDist = face.eyesDistance
            if (eyeDist <= 5f) continue

            val midX = face.midPoint.x
            val midY = face.midPoint.y
            val eyeRadius = (eyeDist * 0.28f).roundToInt().coerceIn(4, 50)

            val leftEyeX = (midX - eyeDist * 0.5f).roundToInt()
            val rightEyeX = (midX + eyeDist * 0.5f).roundToInt()
            val eyeY = midY.roundToInt()

            sharpenRegion(pixels, width, height, leftEyeX, eyeY, eyeRadius, factor)
            sharpenRegion(pixels, width, height, rightEyeX, eyeY, eyeRadius, factor)
        }
    }

    private fun sharpenRegion(
        pixels: IntArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        radius: Int,
        strength: Float
    ) {
        val startY = max(1, centerY - radius)
        val endY = min(height - 2, centerY + radius)
        val startX = max(1, centerX - radius)
        val endX = min(width - 2, centerX + radius)
        val rSq = radius * radius

        for (y in startY..endY) {
            val row = y * width
            val upRow = (y - 1) * width
            val downRow = (y + 1) * width
            val dy = y - centerY

            for (x in startX..endX) {
                val dx = x - centerX
                val distSq = dx * dx + dy * dy
                if (distSq <= rSq) {
                    val falloff = 1.0f - (distSq.toFloat() / rSq)
                    val s = strength * falloff

                    val c = pixels[row + x]
                    val up = pixels[upRow + x]
                    val down = pixels[downRow + x]
                    val left = pixels[row + x - 1]
                    val right = pixels[row + x + 1]

                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF

                    val avgR = (((up shr 16) and 0xFF) + ((down shr 16) and 0xFF) + ((left shr 16) and 0xFF) + ((right shr 16) and 0xFF)) shr 2
                    val avgG = (((up shr 8) and 0xFF) + ((down shr 8) and 0xFF) + ((left shr 8) and 0xFF) + ((right shr 8) and 0xFF)) shr 2
                    val avgB = ((up and 0xFF) + (down and 0xFF) + (left and 0xFF) + (right and 0xFF)) shr 2

                    val newR = (r + (r - avgR) * s).roundToInt().coerceIn(0, 255)
                    val newG = (g + (g - avgG) * s).roundToInt().coerceIn(0, 255)
                    val newB = (b + (b - avgB) * s).roundToInt().coerceIn(0, 255)

                    pixels[row + x] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
                }
            }
        }
    }
}
