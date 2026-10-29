package com.retrocam.app.director.analyzer

import com.retrocam.app.director.model.SceneCategory

/**
 * Normalized bounding box for a detected face in normalized (0.0 to 1.0) coordinates.
 */
data class FaceBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val centerX: Float,
    val centerY: Float,
    val eyeDistance: Float,
    val confidence: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val area: Float get() = width * height
}

/**
 * Detailed photographic scene analysis metrics computed from a live camera frame.
 */
data class FrameAnalysisResult(
    val sceneCategory: SceneCategory,
    val confidence: Float,
    val detectedFaces: List<FaceBox>,
    val isSinglePortrait: Boolean,
    val isGroupPhoto: Boolean,
    val headroomFraction: Float,           // 0.0 to 1.0 distance from frame top to topmost face
    val faceCoverageFraction: Float,        // Total face area / frame area
    val horizontalSpreadFraction: Float,    // Spread across width
    val meanLuminance: Float,               // 0 .. 255
    val contrastStdDev: Float,
    val hasStrongHorizon: Boolean,
    val horizonPositionFraction: Float?,    // 0.0 (top) to 1.0 (bottom)
    val verticalEdgeDensity: Float,
    val horizontalEdgeDensity: Float,
    val skyHueDominance: Float,             // 0.0 to 1.0
    val natureHueDominance: Float,          // 0.0 to 1.0
    val warmSunsetDominance: Float,         // 0.0 to 1.0
    val isLowLight: Boolean,
    val isOverexposed: Boolean,
    val isBlurry: Boolean
) {
    companion object {
        val EMPTY = FrameAnalysisResult(
            sceneCategory = SceneCategory.UNKNOWN_OR_MIXED,
            confidence = 0.5f,
            detectedFaces = emptyList(),
            isSinglePortrait = false,
            isGroupPhoto = false,
            headroomFraction = 0.3f,
            faceCoverageFraction = 0f,
            horizontalSpreadFraction = 0f,
            meanLuminance = 128f,
            contrastStdDev = 50f,
            hasStrongHorizon = false,
            horizonPositionFraction = null,
            verticalEdgeDensity = 0.2f,
            horizontalEdgeDensity = 0.2f,
            skyHueDominance = 0f,
            natureHueDominance = 0f,
            warmSunsetDominance = 0f,
            isLowLight = false,
            isOverexposed = false,
            isBlurry = false
        )
    }
}
