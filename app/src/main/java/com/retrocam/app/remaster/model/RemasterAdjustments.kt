package com.retrocam.app.remaster.model

/**
 * Encapsulates all manual and automated photo remastering parameters.
 *
 * Values are normalized floats:
 * - Signed parameters typically range from -1.0f to +1.0f (0.0f = neutral / untouched).
 * - Unsigned parameters typically range from 0.0f to 1.0f (0.0f = disabled / neutral).
 */
data class RemasterAdjustments(
    // --- Light & Tone ---
    val brightness: Float = 0f,         // -1.0 .. +1.0 (neutral 0.0)
    val exposure: Float = 0f,           // -1.0 .. +1.0 (neutral 0.0)
    val contrast: Float = 0f,           // -1.0 .. +1.0 (neutral 0.0)
    val highlights: Float = 0f,         // -1.0 .. +1.0 (neutral 0.0, positive recovers/boosts)
    val shadows: Float = 0f,            // -1.0 .. +1.0 (neutral 0.0, positive lifts shadows)

    // --- Color ---
    val saturation: Float = 0f,         // -1.0 .. +1.0 (neutral 0.0, -1.0 = B&W)
    val vibrance: Float = 0f,           // -1.0 .. +1.0 (smart saturation, protects skin/saturated hues)
    val temperature: Float = 0f,        // -1.0 .. +1.0 (negative = cooler, positive = warmer)
    val tint: Float = 0f,               // -1.0 .. +1.0 (negative = green, positive = magenta)

    // --- Detail & Optics ---
    val sharpness: Float = 0f,          // 0.0 .. 1.0 (0.0 = untouched, 1.0 = max edge sharpness)
    val clarity: Float = 0f,            // -1.0 .. +1.0 (local mid-tone micro-contrast)
    val noiseReduction: Float = 0f,     // 0.0 .. 1.0 (edge-preserving spatial smoothing)
    val skinSmoothing: Float = 0f,      // 0.0 .. 1.0 (skin smoothing)
    val detailRecovery: Float = 0f,     // 0.0 .. 1.0 (high-frequency texture synthesis)
    val vignette: Float = 0f,           // -1.0 .. +1.0 (negative = darker edges, positive = lighter edges)

    // --- Portrait Enhancement ---
    val portraitSkinSmoothing: Float = 0f,      // 0.0 .. 1.0 (bilateral skin tone filter)
    val portraitFaceIllumination: Float = 0f,   // 0.0 .. 1.0 (localized face exposure lift)
    val portraitEyeEnhancement: Float = 0f,     // 0.0 .. 1.0 (iris clarity & catchlight boost)
    val portraitBackgroundBlur: Float = 0f,     // 0.0 .. 1.0 (depth-of-field separation)
    val portraitRedEyeCorrection: Boolean = false,

    // --- AI Detail & Resolution ---
    val aiDetailRecovery: Float = 0f,           // 0.0 .. 1.0 (AI fine texture reconstruction)
    val aiUpscale: Boolean = false,             // true = 2x super resolution on export
    val removeArtifacts: Boolean = false,       // JPEG blockiness / compression artifact removal

    // --- Special Modes ---
    val isMonochrome: Boolean = false,          // Black & white pro tonal mapping
    val vintageTone: Float = 0f                 // 0.0 .. 1.0 (sepia / aged film tone curve)
) {

    fun isDefault(): Boolean =
        brightness == 0f &&
        exposure == 0f &&
        contrast == 0f &&
        highlights == 0f &&
        shadows == 0f &&
        saturation == 0f &&
        vibrance == 0f &&
        temperature == 0f &&
        tint == 0f &&
        sharpness == 0f &&
        clarity == 0f &&
        noiseReduction == 0f &&
        skinSmoothing == 0f &&
        detailRecovery == 0f &&
        vignette == 0f &&
        portraitSkinSmoothing == 0f &&
        portraitFaceIllumination == 0f &&
        portraitEyeEnhancement == 0f &&
        portraitBackgroundBlur == 0f &&
        !portraitRedEyeCorrection &&
        aiDetailRecovery == 0f &&
        !aiUpscale &&
        !removeArtifacts &&
        !isMonochrome &&
        vintageTone == 0f

    companion object {
        val DEFAULT = RemasterAdjustments()

        fun clamp(value: Float, min: Float, max: Float): Float =
            if (value < min) min else if (value > max) max else value
    }
}
