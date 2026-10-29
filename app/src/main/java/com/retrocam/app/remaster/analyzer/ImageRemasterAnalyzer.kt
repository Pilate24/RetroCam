package com.retrocam.app.remaster.analyzer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.media.FaceDetector
import com.retrocam.app.remaster.model.RemasterAdjustments
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Real-time photographic analysis engine.
 *
 * Inspects image tonal distribution, dynamic range, color casts, sharpness, noise,
 * and facial features to synthesize an optimal, tailor-made remastering plan.
 */
object ImageRemasterAnalyzer {

    private const val ANALYSIS_MAX_DIM = 400
    private const val MAX_FACES = 5

    data class FaceInfo(
        val midPoint: PointF,
        val eyesDistance: Float,
        val confidence: Float
    )

    data class AnalysisReport(
        val meanLuminance: Float,       // 0 .. 255
        val dynamicRange: Float,        // 0 .. 255 (p95 - p5)
        val contrastStdDev: Float,      // standard deviation of luminance
        val shadowClipRatio: Float,     // fraction of pixels severely crushed
        val highlightClipRatio: Float,  // fraction of pixels blown out
        val colorTempKDelta: Float,     // estimated color temperature bias (-1.0 cool .. +1.0 warm)
        val tintDelta: Float,           // estimated green-magenta bias (-1.0 green .. +1.0 magenta)
        val sharpnessScore: Float,      // Laplacian variance (0 .. 1000+)
        val noiseScore: Float,          // High frequency noise estimate (0 .. 100+)
        val faces: List<FaceInfo>,
        val isLowLight: Boolean,
        val isOverexposed: Boolean,
        val isHazyOrLowContrast: Boolean,
        val isSoftOrBlurry: Boolean,
        val isNoisy: Boolean,
        val recommendedAdjustments: RemasterAdjustments,
        val summaryBadge: String
    )

    /**
     * Analyzes [source] and computes tailor-made remastering adjustments.
     */
    fun analyze(source: Bitmap): AnalysisReport {
        val analysisBitmap = prepareAnalysisBitmap(source)
        val width = analysisBitmap.width
        val height = analysisBitmap.height
        val totalPixels = width * height

        val pixels = IntArray(totalPixels)
        analysisBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 1. Histogram & Channel Statistics
        val hist = IntArray(256)
        var sumLuma = 0.0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0

        for (color in pixels) {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF

            // Rec. 709 perceptual luma
            val luma = (0.2126 * r + 0.7152 * g + 0.0722 * b).roundToInt().coerceIn(0, 255)
            hist[luma]++
            sumLuma += luma
            sumR += r
            sumG += g
            sumB += b
        }

        val meanLuma = (sumLuma / totalPixels).toFloat()
        val meanR = (sumR / totalPixels).toFloat()
        val meanG = (sumG / totalPixels).toFloat()
        val meanB = (sumB / totalPixels).toFloat()

        // 2. Standard Deviation of Luminance (Contrast)
        var varianceSum = 0.0
        for (i in 0..255) {
            val count = hist[i]
            if (count > 0) {
                val diff = i - meanLuma
                varianceSum += diff * diff * count
            }
        }
        val contrastStdDev = sqrt(varianceSum / totalPixels).toFloat()

        // 3. Percentiles and Clipping
        var accumulated = 0
        var p5 = 0
        var p50 = 0
        var p95 = 0

        val targetP5 = (totalPixels * 0.05).toInt()
        val targetP50 = (totalPixels * 0.50).toInt()
        val targetP95 = (totalPixels * 0.95).toInt()

        for (i in 0..255) {
            accumulated += hist[i]
            if (p5 == 0 && accumulated >= targetP5) p5 = i
            if (p50 == 0 && accumulated >= targetP50) p50 = i
            if (p95 == 0 && accumulated >= targetP95) p95 = i
        }

        val dynamicRange = (p95 - p5).toFloat()
        var shadowClipCount = 0
        for (i in 0..12) shadowClipCount += hist[i]
        var highlightClipCount = 0
        for (i in 243..255) highlightClipCount += hist[i]

        val shadowClipRatio = shadowClipCount.toFloat() / totalPixels
        val highlightClipRatio = highlightClipCount.toFloat() / totalPixels

        // 4. White Balance Estimation (Gray-World assumption)
        val avgGray = (meanR + meanG + meanB) / 3f
        val colorTempKDelta = if (avgGray > 0) ((meanR - meanB) / avgGray).coerceIn(-1f, 1f) else 0f
        val tintDelta = if (avgGray > 0) ((meanR + meanB - 2f * meanG) / (2f * avgGray)).coerceIn(-1f, 1f) else 0f

        // 5. Sharpness Metric (Laplacian Variance on Luma)
        val sharpnessScore = computeLaplacianVariance(pixels, width, height)

        // 6. Noise Score in flat/homogenous areas
        val noiseScore = computeNoiseEstimate(pixels, width, height)

        // 7. Face Detection
        val faces = detectFaces(analysisBitmap)

        if (analysisBitmap !== source) {
            analysisBitmap.recycle()
        }

        // 8. Photographic Diagnosis
        val isLowLight = meanLuma < 85f || (meanLuma < 105f && shadowClipRatio > 0.15f)
        val isOverexposed = meanLuma > 175f || highlightClipRatio > 0.12f
        val isHazyOrLowContrast = contrastStdDev < 44f && dynamicRange < 180f
        val isSoftOrBlurry = sharpnessScore < 140f
        val isNoisy = noiseScore > 18f

        // 9. Synthesize Tailored Auto Adjustments
        val recommended = calculateAutoAdjustments(
            meanLuma = meanLuma,
            contrastStdDev = contrastStdDev,
            dynamicRange = dynamicRange,
            shadowClipRatio = shadowClipRatio,
            highlightClipRatio = highlightClipRatio,
            colorTempDelta = colorTempKDelta,
            tintDelta = tintDelta,
            sharpnessScore = sharpnessScore,
            noiseScore = noiseScore,
            faceCount = faces.size,
            isLowLight = isLowLight,
            isOverexposed = isOverexposed,
            isSoft = isSoftOrBlurry
        )

        val summaryBadge = buildSummaryBadge(
            isLowLight = isLowLight,
            isOverexposed = isOverexposed,
            isSoft = isSoftOrBlurry,
            isNoisy = isNoisy,
            faceCount = faces.size
        )

        return AnalysisReport(
            meanLuminance = meanLuma,
            dynamicRange = dynamicRange,
            contrastStdDev = contrastStdDev,
            shadowClipRatio = shadowClipRatio,
            highlightClipRatio = highlightClipRatio,
            colorTempKDelta = colorTempKDelta,
            tintDelta = tintDelta,
            sharpnessScore = sharpnessScore,
            noiseScore = noiseScore,
            faces = faces,
            isLowLight = isLowLight,
            isOverexposed = isOverexposed,
            isHazyOrLowContrast = isHazyOrLowContrast,
            isSoftOrBlurry = isSoftOrBlurry,
            isNoisy = isNoisy,
            recommendedAdjustments = recommended,
            summaryBadge = summaryBadge
        )
    }

    private fun prepareAnalysisBitmap(source: Bitmap): Bitmap {
        val maxDim = max(source.width, source.height)
        if (maxDim <= ANALYSIS_MAX_DIM) return source

        val scale = ANALYSIS_MAX_DIM.toFloat() / maxDim
        val targetW = max(16, (source.width * scale).roundToInt())
        val targetH = max(16, (source.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    internal fun computeLaplacianVariance(pixels: IntArray, width: Int, height: Int): Float {
        if (width < 3 || height < 3) return 0f

        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        // Discrete 3x3 Laplacian kernel:
        // [ 0  1  0]
        // [ 1 -4  1]
        // [ 0  1  0]
        val step = 2 // sample every 2nd pixel for swift execution
        for (y in 1 until height - 1 step step) {
            val rowOffset = y * width
            val upOffset = (y - 1) * width
            val downOffset = (y + 1) * width

            for (x in 1 until width - 1 step step) {
                val centerLuma = getLuma(pixels[rowOffset + x])
                val upLuma = getLuma(pixels[upOffset + x])
                val downLuma = getLuma(pixels[downOffset + x])
                val leftLuma = getLuma(pixels[rowOffset + x - 1])
                val rightLuma = getLuma(pixels[rowOffset + x + 1])

                val laplacian = upLuma + downLuma + leftLuma + rightLuma - 4 * centerLuma
                sum += laplacian
                sumSq += laplacian * laplacian
                count++
            }
        }

        if (count == 0) return 0f
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return max(0.0, variance).toFloat()
    }

    internal fun computeNoiseEstimate(pixels: IntArray, width: Int, height: Int): Float {
        if (width < 4 || height < 4) return 0f

        var noiseSum = 0.0
        var patchCount = 0

        val patchSize = 8
        for (y in 0 until height - patchSize step patchSize) {
            for (x in 0 until width - patchSize step patchSize) {
                // Compute local patch variance
                var pSum = 0.0
                var pSumSq = 0.0
                for (py in 0 until patchSize) {
                    val row = (y + py) * width
                    for (px in 0 until patchSize) {
                        val l = getLuma(pixels[row + x + px])
                        pSum += l
                        pSumSq += l * l
                    }
                }
                val n = (patchSize * patchSize).toDouble()
                val pMean = pSum / n
                val pVar = (pSumSq / n) - (pMean * pMean)

                // Only consider low-contrast flat patches (sky, walls, skin)
                if (pVar in 4.0..40.0) {
                    noiseSum += sqrt(pVar)
                    patchCount++
                }
            }
        }

        return if (patchCount > 0) (noiseSum / patchCount).toFloat() else 5f
    }

    private fun detectFaces(source: Bitmap): List<FaceInfo> {
        val detected = mutableListOf<FaceInfo>()
        try {
            // Android FaceDetector requires Bitmap.Config.RGB_565 and even width
            var evenBitmap = source
            val width = if (source.width % 2 != 0) source.width - 1 else source.width
            val height = source.height
            if (width != source.width) {
                evenBitmap = Bitmap.createBitmap(source, 0, 0, width, height)
            }

            val rgb565 = evenBitmap.copy(Bitmap.Config.RGB_565, false)
            if (evenBitmap !== source && !evenBitmap.isRecycled) {
                evenBitmap.recycle()
            }

            if (rgb565 != null) {
                val detector = FaceDetector(rgb565.width, rgb565.height, MAX_FACES)
                val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
                val count = detector.findFaces(rgb565, faces)

                for (i in 0 until count) {
                    val face = faces[i] ?: continue
                    val mid = PointF()
                    face.getMidPoint(mid)
                    detected.add(
                        FaceInfo(
                            midPoint = mid,
                            eyesDistance = face.eyesDistance(),
                            confidence = face.confidence()
                        )
                    )
                }
                rgb565.recycle()
            }
        } catch (_: Exception) {
            // Graceful fallback if device hardware fails FaceDetector
        }
        return detected
    }

    internal fun calculateAutoAdjustments(
        meanLuma: Float,
        contrastStdDev: Float,
        dynamicRange: Float,
        shadowClipRatio: Float,
        highlightClipRatio: Float,
        colorTempDelta: Float,
        tintDelta: Float,
        sharpnessScore: Float,
        noiseScore: Float,
        faceCount: Int,
        isLowLight: Boolean,
        isOverexposed: Boolean,
        isSoft: Boolean
    ): RemasterAdjustments {
        // Brightness & Exposure compensation
        var exposure = 0f
        var brightness = 0f
        var shadows = 0f
        var highlights = 0f

        if (isLowLight) {
            exposure = ((110f - meanLuma) / 150f).coerceIn(0.12f, 0.40f)
            brightness = 0.12f
            shadows = (0.20f + shadowClipRatio * 0.4f).coerceIn(0.20f, 0.55f)
            highlights = -0.15f // keep specular lights in check
        } else if (isOverexposed) {
            exposure = ((160f - meanLuma) / 150f).coerceIn(-0.35f, -0.08f)
            highlights = -0.30f
            shadows = 0.10f
        } else {
            // Balanced exposure fine-tuning
            exposure = ((128f - meanLuma) / 300f).coerceIn(-0.12f, 0.15f)
            shadows = (shadowClipRatio * 0.8f).coerceIn(0.08f, 0.28f)
            highlights = -(highlightClipRatio * 0.9f).coerceIn(0.05f, 0.25f)
        }

        // Contrast
        var contrast = 0.08f
        if (contrastStdDev < 42f) {
            contrast = ((52f - contrastStdDev) / 50f).coerceIn(0.14f, 0.32f)
        } else if (contrastStdDev > 75f) {
            contrast = -0.05f // soften overly harsh contrast
        }

        // Color & White Balance
        // Invert the detected color temperature and tint bias to neutralize cast
        val temperature = (-colorTempDelta * 0.45f).coerceIn(-0.35f, 0.35f)
        val tint = (-tintDelta * 0.35f).coerceIn(-0.25f, 0.25f)
        val saturation = if (isLowLight) 0.08f else 0.06f
        val vibrance = 0.18f // Intelligent vibrance boosts muted tones safely

        // Sharpness, Clarity & Detail Recovery
        var sharpness = 0.22f
        var clarity = 0.15f
        var detailRecovery = 0.25f

        if (isSoft) {
            sharpness = ((180f - sharpnessScore) / 180f * 0.30f + 0.25f).coerceIn(0.28f, 0.55f)
            clarity = 0.25f
            detailRecovery = 0.40f
        }

        // Noise Reduction
        var noiseReduction = 0.12f
        if (noiseScore > 16f || isLowLight) {
            noiseReduction = (noiseScore / 40f * 0.35f + (if (isLowLight) 0.25f else 0.10f)).coerceIn(0.20f, 0.55f)
        }

        // Portrait specific tuning
        val hasFaces = faceCount > 0
        val skinSmoothing = if (hasFaces) 0.42f else 0f
        val faceIllumination = if (hasFaces) (if (isLowLight) 0.38f else 0.25f) else 0f
        val eyeEnhancement = if (hasFaces) 0.35f else 0f
        val backgroundBlur = if (hasFaces) 0.20f else 0f

        return RemasterAdjustments(
            brightness = brightness,
            exposure = exposure,
            contrast = contrast,
            highlights = highlights,
            shadows = shadows,
            saturation = saturation,
            vibrance = vibrance,
            temperature = temperature,
            tint = tint,
            sharpness = sharpness,
            clarity = clarity,
            noiseReduction = noiseReduction,
            skinSmoothing = skinSmoothing,
            detailRecovery = detailRecovery,
            vignette = if (hasFaces) -0.08f else 0f,
            portraitSkinSmoothing = skinSmoothing,
            portraitFaceIllumination = faceIllumination,
            portraitEyeEnhancement = eyeEnhancement,
            portraitBackgroundBlur = backgroundBlur,
            portraitRedEyeCorrection = hasFaces,
            aiDetailRecovery = if (isSoft) 0.35f else 0.20f,
            removeArtifacts = isSoft || isLowLight
        )
    }

    internal fun buildSummaryBadge(
        isLowLight: Boolean,
        isOverexposed: Boolean,
        isSoft: Boolean,
        isNoisy: Boolean,
        faceCount: Int
    ): String {
        val traits = mutableListOf<String>()
        if (faceCount > 0) traits.add(if (faceCount == 1) "1 Portrait Face" else "$faceCount Faces")
        if (isLowLight) traits.add("Low Light Boosted")
        else if (isOverexposed) traits.add("Highlights Restored")
        if (isSoft) traits.add("Clarity & Edges Recovered")
        if (isNoisy) traits.add("Noise Filtered")
        if (traits.isEmpty()) traits.add("Balanced Lighting & Dynamic Range")
        return traits.joinToString(" • ")
    }

    private fun getLuma(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.2126 * r + 0.7152 * g + 0.0722 * b).roundToInt()
    }
}
