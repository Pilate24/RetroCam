package com.retrocam.app.director.analyzer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.media.FaceDetector
import android.util.Log
import com.retrocam.app.director.model.SceneCategory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * On-device computer vision analyzer for camera preview frames.
 *
 * Runs strictly locally, throttled to a controlled frequency on a background thread.
 * Extracts facial geometry, luminance distribution, edge densities, and chromatic
 * composition to classify the scene and measure compositional parameters.
 */
object ShotSceneAnalyzer {

    private const val TAG = "ShotSceneAnalyzer"
    private const val TARGET_ANALYSIS_WIDTH = 180
    private const val MAX_FACES = 8

    /**
     * Analyzes an upright camera frame and returns photographic metrics.
     *
     * @param frame The camera frame bitmap (must already be oriented upright).
     */
    fun analyzeFrame(frame: Bitmap): FrameAnalysisResult {
        try {
            // 1. Prepare lightweight downsampled bitmap for efficient analysis
            val aspect = frame.height.toFloat() / frame.width.toFloat()
            var analysisWidth = TARGET_ANALYSIS_WIDTH
            if (analysisWidth % 2 != 0) analysisWidth++ // FaceDetector requires even width
            var analysisHeight = (analysisWidth * aspect).roundToInt()
            if (analysisHeight % 2 != 0) analysisHeight++

            val scaledBitmap = Bitmap.createScaledBitmap(frame, analysisWidth, analysisHeight, false)

            // 2. Face Detection via on-device FaceDetector
            val detectedFaces = detectFaces(scaledBitmap, analysisWidth, analysisHeight)

            // 3. Pixel Analysis: Luminance, Color Hues, and Spatial Edges
            val totalPixels = analysisWidth * analysisHeight
            val pixels = IntArray(totalPixels)
            scaledBitmap.getPixels(pixels, 0, analysisWidth, 0, 0, analysisWidth, analysisHeight)

            val colorStats = analyzeColorsAndTones(pixels, analysisWidth, analysisHeight)
            val edgeStats = analyzeEdges(pixels, analysisWidth, analysisHeight)

            scaledBitmap.recycle()

            // 4. Synthesize scene category and confidence
            val classification = classifyScene(
                faces = detectedFaces,
                colorStats = colorStats,
                edgeStats = edgeStats
            )

            // Headroom and spread calculations
            val headroom = if (detectedFaces.isNotEmpty()) {
                detectedFaces.minOf { it.top }.coerceIn(0f, 1f)
            } else {
                0.3f
            }

            val faceCoverage = detectedFaces.sumOf { it.area.toDouble() }.toFloat().coerceIn(0f, 1f)
            val horizontalSpread = if (detectedFaces.size > 1) {
                (detectedFaces.maxOf { it.right } - detectedFaces.minOf { it.left }).coerceIn(0f, 1f)
            } else {
                0f
            }

            return FrameAnalysisResult(
                sceneCategory = classification.first,
                confidence = classification.second,
                detectedFaces = detectedFaces,
                isSinglePortrait = detectedFaces.size == 1,
                isGroupPhoto = detectedFaces.size >= 2,
                headroomFraction = headroom,
                faceCoverageFraction = faceCoverage,
                horizontalSpreadFraction = horizontalSpread,
                meanLuminance = colorStats.meanLuma,
                contrastStdDev = colorStats.contrastStdDev,
                hasStrongHorizon = edgeStats.hasHorizon,
                horizonPositionFraction = edgeStats.horizonY,
                verticalEdgeDensity = edgeStats.verticalDensity,
                horizontalEdgeDensity = edgeStats.horizontalDensity,
                skyHueDominance = colorStats.skyDominance,
                natureHueDominance = colorStats.natureDominance,
                warmSunsetDominance = colorStats.warmDominance,
                isLowLight = colorStats.meanLuma < 45f,
                isOverexposed = colorStats.meanLuma > 210f || colorStats.highlightClipRatio > 0.20f,
                isBlurry = edgeStats.laplacianVariance < 40f
            )
        } catch (e: Exception) {
            Log.w(TAG, "Frame analysis encountered an error; falling back to default", e)
            return FrameAnalysisResult.EMPTY
        }
    }

    private fun detectFaces(bitmap: Bitmap, width: Int, height: Int): List<FaceBox> {
        val rgb565 = if (bitmap.config == Bitmap.Config.RGB_565) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.RGB_565, false) ?: return emptyList()
        }

        val facesArray = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
        val detector = FaceDetector(width, height, MAX_FACES)
        val count = try {
            detector.findFaces(rgb565, facesArray)
        } catch (e: Exception) {
            0
        }

        if (rgb565 !== bitmap) {
            rgb565.recycle()
        }

        if (count <= 0) return emptyList()

        val results = mutableListOf<FaceBox>()
        for (i in 0 until count) {
            val face = facesArray[i] ?: continue
            val confidence = face.confidence()
            if (confidence < 0.45f) continue

            val midPoint = PointF()
            face.getMidPoint(midPoint)
            val eyeDist = face.eyesDistance()

            // Calculate normalized face bounding box
            val normEyeDist = eyeDist / width
            val normCenterX = midPoint.x / width
            val normCenterY = midPoint.y / height

            // Approximate facial boundaries from eye distance and midpoint
            val halfWidth = normEyeDist * 1.1f
            val topDist = normEyeDist * 1.3f
            val bottomDist = normEyeDist * 1.5f

            val left = (normCenterX - halfWidth).coerceIn(0f, 1f)
            val right = (normCenterX + halfWidth).coerceIn(0f, 1f)
            val top = (normCenterY - topDist).coerceIn(0f, 1f)
            val bottom = (normCenterY + bottomDist).coerceIn(0f, 1f)

            results.add(
                FaceBox(
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    centerX = normCenterX,
                    centerY = normCenterY,
                    eyeDistance = normEyeDist,
                    confidence = confidence
                )
            )
        }

        return results
    }

    private data class ColorStats(
        val meanLuma: Float,
        val contrastStdDev: Float,
        val highlightClipRatio: Float,
        val skyDominance: Float,
        val natureDominance: Float,
        val warmDominance: Float
    )

    private fun analyzeColorsAndTones(pixels: IntArray, width: Int, height: Int): ColorStats {
        val total = pixels.size
        var lumaSum = 0.0
        var highlightClips = 0

        var skyPixelsTop = 0
        var naturePixels = 0
        var warmPixels = 0

        val topHalfLimit = (height * 0.45f).toInt() * width
        val hsv = FloatArray(3)

        for (i in 0 until total) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF

            val luma = (0.2126 * r + 0.7152 * g + 0.0722 * b).toFloat()
            lumaSum += luma
            if (luma > 245f) highlightClips++

            Color.colorToHSV(c, hsv)
            val hue = hsv[0]
            val sat = hsv[1]
            val valB = hsv[2]

            // Sky: Cyan/Blue (185-250 deg), decent brightness, in upper 45% of image
            if (i < topHalfLimit && sat > 0.15f && valB > 0.35f && hue in 185f..255f) {
                skyPixelsTop++
            }

            // Nature / Foliage: Green hues (70-165 deg), moderate saturation
            if (sat > 0.20f && valB in 0.15f..0.85f && hue in 70f..165f) {
                naturePixels++
            }

            // Warm sunset tones: Red/Orange/Yellow (10-45 deg), high saturation, decent brightness
            if (sat > 0.35f && valB > 0.40f && (hue in 10f..48f || hue >= 350f)) {
                warmPixels++
            }
        }

        val meanLuma = (lumaSum / total).toFloat()

        // Variance / Contrast
        var varSum = 0.0
        for (i in 0 until total) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val d = luma - meanLuma
            varSum += d * d
        }
        val contrastStdDev = sqrt(varSum / total).toFloat()

        val topHalfPixels = max(1, topHalfLimit)
        val skyDominance = (skyPixelsTop.toFloat() / topHalfPixels).coerceIn(0f, 1f)
        val natureDominance = (naturePixels.toFloat() / total).coerceIn(0f, 1f)
        val warmDominance = (warmPixels.toFloat() / total).coerceIn(0f, 1f)

        return ColorStats(
            meanLuma = meanLuma,
            contrastStdDev = contrastStdDev,
            highlightClipRatio = highlightClips.toFloat() / total,
            skyDominance = skyDominance,
            natureDominance = natureDominance,
            warmDominance = warmDominance
        )
    }

    private data class EdgeStats(
        val verticalDensity: Float,
        val horizontalDensity: Float,
        val laplacianVariance: Float,
        val hasHorizon: Boolean,
        val horizonY: Float?
    )

    private fun analyzeEdges(pixels: IntArray, width: Int, height: Int): EdgeStats {
        var vertEdgeSum = 0f
        var horizEdgeSum = 0f
        var laplacianSum = 0.0
        var laplacianCount = 0

        val rowHorizontalGrad = FloatArray(height)

        for (y in 1 until height - 1) {
            val yOffset = y * width
            var rowGrad = 0f
            for (x in 1 until width - 1) {
                val idx = yOffset + x

                // Approximate luminance
                val c = pixels[idx]
                val luma = (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF))

                val cLeft = pixels[idx - 1]
                val lumaLeft = (0.2126f * ((cLeft shr 16) and 0xFF) + 0.7152f * ((cLeft shr 8) and 0xFF) + 0.0722f * (cLeft and 0xFF))

                val cRight = pixels[idx + 1]
                val lumaRight = (0.2126f * ((cRight shr 16) and 0xFF) + 0.7152f * ((cRight shr 8) and 0xFF) + 0.0722f * (cRight and 0xFF))

                val cUp = pixels[idx - width]
                val lumaUp = (0.2126f * ((cUp shr 16) and 0xFF) + 0.7152f * ((cUp shr 8) and 0xFF) + 0.0722f * (cUp and 0xFF))

                val cDown = pixels[idx + width]
                val lumaDown = (0.2126f * ((cDown shr 16) and 0xFF) + 0.7152f * ((cDown shr 8) and 0xFF) + 0.0722f * (cDown and 0xFF))

                val dx = abs(lumaRight - lumaLeft)
                val dy = abs(lumaDown - lumaUp)

                vertEdgeSum += dx
                horizEdgeSum += dy
                rowGrad += dy

                // 4-neighbor Laplacian
                val lap = (lumaUp + lumaDown + lumaLeft + lumaRight) - 4f * luma
                laplacianSum += lap * lap
                laplacianCount++
            }
            rowHorizontalGrad[y] = rowGrad / width
        }

        val totalInner = (width - 2) * (height - 2)
        val vDensity = (vertEdgeSum / (totalInner * 255f)).coerceIn(0f, 1f)
        val hDensity = (horizEdgeSum / (totalInner * 255f)).coerceIn(0f, 1f)
        val lapVariance = if (laplacianCount > 0) (laplacianSum / laplacianCount).toFloat() else 0f

        // Detect prominent horizon line: look for a sharp horizontal transition in the middle 60% of the frame
        var maxRowGrad = 0f
        var bestRow = -1
        val startY = (height * 0.20f).toInt()
        val endY = (height * 0.80f).toInt()
        for (y in startY..endY) {
            if (rowHorizontalGrad[y] > maxRowGrad) {
                maxRowGrad = rowHorizontalGrad[y]
                bestRow = y
            }
        }

        val hasHorizon = maxRowGrad > 25f && (maxRowGrad > hDensity * 255f * 1.5f)
        val horizonY = if (hasHorizon && bestRow > 0) bestRow.toFloat() / height else null

        return EdgeStats(
            verticalDensity = vDensity,
            horizontalDensity = hDensity,
            laplacianVariance = lapVariance,
            hasHorizon = hasHorizon,
            horizonY = horizonY
        )
    }

    private fun classifyScene(
        faces: List<FaceBox>,
        colorStats: ColorStats,
        edgeStats: EdgeStats
    ): Pair<SceneCategory, Float> {
        // Rule 1: People
        if (faces.size == 1) {
            val face = faces[0]
            val conf = (face.confidence * 0.5f + (face.area * 4f).coerceAtMost(0.5f)).coerceIn(0.65f, 0.95f)
            return Pair(SceneCategory.PORTRAIT, conf)
        } else if (faces.size >= 2) {
            val avgConf = faces.map { it.confidence }.average().toFloat().coerceIn(0.70f, 0.96f)
            return Pair(SceneCategory.GROUP_PHOTO, avgConf)
        }

        // Rule 2: Sunset / Sunrise
        if (colorStats.warmDominance > 0.22f && (colorStats.contrastStdDev > 40f || edgeStats.hasHorizon)) {
            val conf = (colorStats.warmDominance * 2.5f).coerceIn(0.60f, 0.90f)
            return Pair(SceneCategory.SUNSET_SUNRISE, conf)
        }

        // Rule 3: Landscape & Nature
        if (colorStats.skyDominance > 0.18f || (colorStats.natureDominance > 0.22f && edgeStats.hasHorizon)) {
            val conf = ((colorStats.skyDominance + colorStats.natureDominance) * 1.4f).coerceIn(0.60f, 0.90f)
            return Pair(SceneCategory.LANDSCAPE, conf)
        }

        // Rule 4: Architecture
        if (edgeStats.verticalDensity > 0.22f && edgeStats.verticalDensity > edgeStats.horizontalDensity * 1.1f) {
            val conf = (edgeStats.verticalDensity * 3.0f).coerceIn(0.55f, 0.88f)
            return Pair(SceneCategory.ARCHITECTURE, conf)
        }

        // Rule 5: Food or Close-up / Macro
        if (edgeStats.laplacianVariance > 80f && colorStats.contrastStdDev > 45f && !edgeStats.hasHorizon) {
            val conf = (edgeStats.laplacianVariance / 200f).coerceIn(0.55f, 0.82f)
            return Pair(SceneCategory.FOOD, conf)
        }

        // Rule 6: Fallback general scene
        return Pair(SceneCategory.UNKNOWN_OR_MIXED, 0.50f)
    }
}
