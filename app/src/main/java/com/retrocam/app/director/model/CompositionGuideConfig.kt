package com.retrocam.app.director.model

/**
 * Configuration options for independently toggling preview overlays.
 */
data class CompositionGuideConfig(
    val showRuleOfThirds: Boolean = true,
    val showCenterSymmetry: Boolean = false,
    val showHorizonGuide: Boolean = true,
    val showPortraitGuide: Boolean = false,
    val showCropBoundaries: Boolean = true,
    val showSubjectBoxes: Boolean = true,
    val isCompareRatiosActive: Boolean = false,
    val compareRatioOptions: List<AspectRatioOption> = listOf(
        AspectRatioOption.RATIO_4_3,
        AspectRatioOption.RATIO_4_5,
        AspectRatioOption.RATIO_9_16
    )
)
