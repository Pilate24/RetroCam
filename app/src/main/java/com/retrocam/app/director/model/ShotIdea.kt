package com.retrocam.app.director.model

/**
 * A distinct, alternative way to photograph the current scene.
 * Each idea is tailored to verified hardware capabilities and scene composition.
 */
data class ShotIdea(
    val name: String,
    val visualEffect: String,
    val lens: PhysicalLensInfo,
    val zoomRatio: Float?,
    val aspectOption: AspectRatioOption,
    val cameraPosition: String,
    val compositionTip: String,
    val explanation: String
)
