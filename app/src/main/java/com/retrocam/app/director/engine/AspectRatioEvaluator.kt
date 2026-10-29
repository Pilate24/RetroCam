package com.retrocam.app.director.engine

import com.retrocam.app.director.analyzer.FaceBox
import com.retrocam.app.director.analyzer.FrameAnalysisResult
import com.retrocam.app.director.model.AspectRatioOption
import com.retrocam.app.director.model.NormalizedCropRect
import com.retrocam.app.director.model.SceneCategory
import kotlin.math.abs

/**
 * Evaluates scene geometry to recommend the optimal photographic aspect ratio
 * and verifies that crop boundaries do not clip detected subjects or faces.
 */
object AspectRatioEvaluator {

    /**
     * Recommends the best aspect ratio based on scene type, faces, and orientation.
     */
    fun recommendAspectRatio(
        analysis: FrameAnalysisResult,
        isUprightPortrait: Boolean
    ): AspectRatioOption {
        return when (analysis.sceneCategory) {
            SceneCategory.PORTRAIT -> {
                // If headroom is adequate and face is centered, 4:5 is ideal for portrait orientation
                if (isUprightPortrait && analysis.headroomFraction in 0.12f..0.45f) {
                    AspectRatioOption.RATIO_4_5
                } else {
                    AspectRatioOption.RATIO_4_3
                }
            }
            SceneCategory.GROUP_PHOTO -> {
                // Group photos are wide: 9:16 or narrow crops cut off people on edges
                if (analysis.horizontalSpreadFraction > 0.55f) {
                    if (isUprightPortrait) AspectRatioOption.RATIO_4_3 else AspectRatioOption.RATIO_16_9
                } else {
                    AspectRatioOption.RATIO_4_3
                }
            }
            SceneCategory.LANDSCAPE -> {
                if (isUprightPortrait) {
                    // Vertical landscape / vista looks stunning in 9:16 or 4:3
                    if (analysis.skyHueDominance > 0.35f) AspectRatioOption.RATIO_9_16 else AspectRatioOption.RATIO_4_3
                } else {
                    AspectRatioOption.RATIO_16_9
                }
            }
            SceneCategory.SUNSET_SUNRISE -> {
                if (isUprightPortrait) AspectRatioOption.RATIO_9_16 else AspectRatioOption.RATIO_16_9
            }
            SceneCategory.ARCHITECTURE -> {
                // Tall structures look great in 9:16 or 3:2; symmetrical facades in 1:1 or 4:3
                if (analysis.verticalEdgeDensity > 0.35f) {
                    AspectRatioOption.RATIO_9_16
                } else {
                    AspectRatioOption.RATIO_1_1
                }
            }
            SceneCategory.FOOD, SceneCategory.PRODUCT, SceneCategory.CLOSE_UP -> {
                // Square format is the gold standard for centered food and products
                AspectRatioOption.RATIO_1_1
            }
            SceneCategory.STREET_TRAVEL -> {
                AspectRatioOption.RATIO_3_2
            }
            SceneCategory.UNKNOWN_OR_MIXED -> {
                AspectRatioOption.RATIO_4_3
            }
        }
    }

    /**
     * Calculates the normalized crop boundaries [0.0..1.0] for a given aspect ratio
     * on an upright frame with source ratio (sourceWidth / sourceHeight).
     */
    fun computeNormalizedCropRect(
        targetOption: AspectRatioOption,
        sourceAspect: Float // sourceWidth / sourceHeight (e.g. 0.75 for 720x960 portrait)
    ): NormalizedCropRect {
        val targetAspect = targetOption.getRatioForOrientation(sourceAspect < 1.0f)
        if (abs(sourceAspect - targetAspect) < 0.005f) {
            return NormalizedCropRect(0f, 0f, 1f, 1f)
        }

        var left = 0f
        var top = 0f
        var right = 1f
        var bottom = 1f

        if (sourceAspect > targetAspect) {
            // Source is wider than target -> pillarbox / crop sides
            val cropWidthFraction = targetAspect / sourceAspect
            left = (1f - cropWidthFraction) / 2f
            right = 1f - left
        } else {
            // Source is taller than target -> letterbox / crop top and bottom
            val cropHeightFraction = sourceAspect / targetAspect
            top = (1f - cropHeightFraction) / 2f
            bottom = 1f - top
        }

        return NormalizedCropRect(left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f))
    }

    /**
     * Inspects whether the chosen aspect ratio's crop rectangle would cut off any detected faces.
     *
     * @return A descriptive warning string if clipping occurs, or null if framing is safe.
     */
    fun checkSubjectClipping(
        targetOption: AspectRatioOption,
        faces: List<FaceBox>,
        sourceAspect: Float
    ): String? {
        if (faces.isEmpty()) return null
        val cropRect = computeNormalizedCropRect(targetOption, sourceAspect)

        var clippedCount = 0
        var clippedSides = false
        var clippedHeadroom = false

        for (face in faces) {
            // Check if face extends outside crop rectangle with a small safety margin
            val safetyMargin = 0.02f
            val isLeftClipped = face.left < cropRect.left + safetyMargin
            val isRightClipped = face.right > cropRect.right - safetyMargin
            val isTopClipped = face.top < cropRect.top + safetyMargin
            val isBottomClipped = face.bottom > cropRect.bottom - safetyMargin

            if (isLeftClipped || isRightClipped) clippedSides = true
            if (isTopClipped) clippedHeadroom = true

            if (isLeftClipped || isRightClipped || isTopClipped || isBottomClipped) {
                clippedCount++
            }
        }

        return when {
            clippedHeadroom && clippedSides -> {
                "Crop will clip subjects on sides and reduce headroom. Switch to 4:3 or step back."
            }
            clippedSides -> {
                "Selected ${targetOption.displayLabel} crop cuts off subjects on the edges. Recommend wider 4:3 framing."
            }
            clippedHeadroom -> {
                "Selected ${targetOption.displayLabel} crop cuts off top headroom. Tilt camera up or step back."
            }
            clippedCount > 0 -> {
                "Subject is close to ${targetOption.displayLabel} crop boundaries. Adjust framing for safety."
            }
            else -> null
        }
    }
}
