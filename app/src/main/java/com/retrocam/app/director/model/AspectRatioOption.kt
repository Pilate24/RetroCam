package com.retrocam.app.director.model

/**
 * Aspect ratio options supported by Shot Director and RetroCam.
 * Distinguishes between native sensor capture and precision post-capture crop.
 */
enum class AspectRatioOption(
    val id: String,
    val displayLabel: String,
    val portraitRatio: Float,    // width / height in portrait orientation
    val landscapeRatio: Float,   // width / height in landscape orientation
    val isNativeSensorDefault: Boolean,
    val intendedUse: String
) {
    RATIO_4_3(
        id = "4:3",
        displayLabel = "4:3",
        portraitRatio = 3f / 4f,     // 0.75
        landscapeRatio = 4f / 3f,    // 1.333
        isNativeSensorDefault = true,
        intendedUse = "General photography & native sensor standard"
    ),
    RATIO_3_2(
        id = "3:2",
        displayLabel = "3:2",
        portraitRatio = 2f / 3f,     // 0.667
        landscapeRatio = 3f / 2f,    // 1.5
        isNativeSensorDefault = false,
        intendedUse = "Classic 35mm film & balanced landscape framing"
    ),
    RATIO_1_1(
        id = "1:1",
        displayLabel = "1:1",
        portraitRatio = 1.0f,
        landscapeRatio = 1.0f,
        isNativeSensorDefault = false,
        intendedUse = "Square format, polaroid aesthetic & profile posts"
    ),
    RATIO_4_5(
        id = "4:5",
        displayLabel = "4:5",
        portraitRatio = 4f / 5f,     // 0.80
        landscapeRatio = 5f / 4f,    // 1.25
        isNativeSensorDefault = false,
        intendedUse = "Portrait social media posts (maximum vertical screen real estate)"
    ),
    RATIO_16_9(
        id = "16:9",
        displayLabel = "16:9",
        portraitRatio = 9f / 16f,    // In portrait, widescreen is 9:16 vertical or letterbox 16:9
        landscapeRatio = 16f / 9f,
        isNativeSensorDefault = false,
        intendedUse = "Cinematic widescreen & landscape vistas"
    ),
    RATIO_9_16(
        id = "9:16",
        displayLabel = "9:16",
        portraitRatio = 9f / 16f,    // 0.5625
        landscapeRatio = 16f / 9f,
        isNativeSensorDefault = false,
        intendedUse = "Vertical Stories, Reels & full-screen vertical capture"
    );

    /**
     * Gets the target aspect ratio (width / height) based on whether the image is upright portrait.
     */
    fun getRatioForOrientation(isUprightPortrait: Boolean): Float {
        return if (isUprightPortrait) portraitRatio else landscapeRatio
    }

    /**
     * Returns true if this ratio requires post-capture crop on standard 4:3 phone sensors.
     */
    fun requiresPostCaptureCrop(): Boolean {
        return !isNativeSensorDefault
    }
}
