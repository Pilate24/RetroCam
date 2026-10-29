package com.retrocam.app.director.model

/**
 * High-level categorization of the recommended shot framing.
 */
enum class ShotType(val displayName: String) {
    BALANCED("Balanced Shot"),
    WIDE("Wide Environmental"),
    PORTRAIT("Portrait Focus"),
    DETAIL("Detail / Close-Up"),
    SYMMETRIC("Symmetric Architecture"),
    CINEMATIC("Cinematic Framing")
}

/**
 * Coordinated recommendation produced by the AI Shot Director.
 * Coordinates verified lens, zoom, aspect ratio, camera position, and composition.
 */
data class ShotRecommendation(
    val sceneCategory: SceneCategory,
    val shotType: ShotType,
    val recommendedLens: PhysicalLensInfo,
    val recommendedZoom: Float?,
    val recommendedAspect: AspectRatioOption,
    val cameraPositionAdvice: String,
    val compositionTip: String,
    val reason: String,
    val confidence: Float,
    val alternativeIdeas: List<ShotIdea>,
    val cropWarning: String? = null,
    val requiresPhysicalMovement: Boolean = false,
    val physicalMovementAdvice: String? = null
) {
    /**
     * Confidence formatted as percentage string (e.g. "87%").
     */
    fun formattedConfidence(): String {
        return "${(confidence * 100).toInt().coerceIn(0, 100)}%"
    }

    /**
     * Whether a specific zoom setting is recommended and can be applied.
     */
    val hasApplicableZoom: Boolean
        get() = recommendedZoom != null
}
