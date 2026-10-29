package com.retrocam.app.remaster.model

/**
 * 12 dedicated enhancement presets for AI Photo Remaster.
 *
 * Each preset provides an engineered baseline balancing lighting, color, dynamic range,
 * and detail recovery tailored to specific photographic genres.
 */
enum class RemasterPreset(
    val id: String,
    val title: String,
    val description: String,
    val iconResName: String
) {
    NATURAL(
        id = "natural",
        title = "Natural",
        description = "Realistic colors and balanced lighting",
        iconResName = "ic_preset_natural"
    ) {
        override fun createAdjustments() = RemasterAdjustments(
            brightness = 0.05f,
            exposure = 0.04f,
            contrast = 0.08f,
            highlights = -0.05f,
            shadows = 0.12f,
            saturation = 0.06f,
            vibrance = 0.12f,
            sharpness = 0.18f,
            clarity = 0.10f,
            noiseReduction = 0.10f,
            detailRecovery = 0.15f
        )
    },

    HDR_PRO(
        id = "hdr_pro",
        title = "HDR Pro",
        description = "Enhanced highlights, shadows & dynamic range",
        iconResName = "ic_preset_hdr"
    ) {
        override fun createAdjustments() = RemasterAdjustments(
            exposure = 0.06f,
            contrast = 0.14f,
            highlights = -0.30f,
            shadows = 0.35f,
            saturation = 0.15f,
            vibrance = 0.22f,
            sharpness = 0.25f,
            clarity = 0.25f,
            detailRecovery = 0.30f
        )
    },

    PORTRAIT_PRO(
        id = "portrait_pro",
        title = "Portrait Pro",
        description = "Natural skin tones, facial clarity & depth",
        iconResName = "ic_preset_portrait"
    ) {
        override fun createAdjustments() = RemasterAdjustments(
            brightness = 0.08f,
            exposure = 0.05f,
            contrast = 0.06f,
            highlights = -0.10f,
            shadows = 0.15f,
            saturation = 0.04f,
            vibrance = 0.10f,
            temperature = 0.04f,
            sharpness = 0.14f,
            clarity = 0.08f,
            noiseReduction = 0.20f,
            skinSmoothing = 0.45f,
            portraitSkinSmoothing = 0.50f,
            portraitFaceIllumination = 0.35f,
            portraitEyeEnhancement = 0.40f,
            portraitBackgroundBlur = 0.25f,
            portraitRedEyeCorrection = true
        )
    },

    LANDSCAPE_PRO(
        id = "landscape_pro",
        title = "Landscape Pro",
        description = "Rich scenery, foliage, sky & nature detail",
        iconResName = "ic_preset_landscape"
    ) {
        override fun createAdjustments() = RemasterAdjustments(
            exposure = 0.04f,
            contrast = 0.18f,
            highlights = -0.25f,
            shadows = 0.20f,
            saturation = 0.20f,
            vibrance = 0.28f,
            temperature = -0.03f,
            sharpness = 0.35f,
            clarity = 0.30f,
            detailRecovery = 0.35f,
            vignette = -0.08f
        )
    },

    NIGHT_FIX(
        id = "night_fix",
        title = "Night Fix",
        description = "Reduce noise, improve visibility & exposure",
        iconResName = "ic_preset_night"
    ) {
        override fun createAdjustments() = RemasterAdjustments(
            brightness = 0.18f,
            exposure = 0.22f,
            contrast = 0.12f,
            highlights = -0.22f,
            shadows = 0.40f,
            saturation = 0.08f,
            vibrance = 0.14f,
            sharpness = 0.15f,
            clarity = 0.18f,
            noiseReduction = 0.50f,
            detailRecovery = 0.20f
        )
    },

    LOW_LIGHT_RECOVERY(
        id = "low_light_recovery",
        title = "Low-Light Recovery",
        description = "Brighten dark images while minimizing noise",
        iconResName = "ic_preset_lowlight"
    ) {
        override fun createAdjustments() = RemasterAdjustments(
            brightness = 0.28f,
            exposure = 0.35f,
            contrast = 0.08f,
            highlights = -0.15f,
            shadows = 0.50f,
            saturation = 0.10f,
            vibrance = 0.16f,
            temperature = 0.05f,
            sharpness = 0.12f,
            clarity = 0.15f,
            noiseReduction = 0.55f,
            detailRecovery = 0.25f
        )
    },

    VINTAGE_RESTORATION(
        id = "vintage_restoration",
        title = "Vintage Restoration",
        description = "Revitalize old, faded or damaged photos",
        iconResName = "ic_preset_vintage"
    ) {
        override fun createAdjustments() = RemasterAdjustments(
            brightness = 0.05f,
            contrast = 0.22f,
            highlights = -0.18f,
            shadows = 0.25f,
            saturation = 0.18f,
            vibrance = 0.25f,
            sharpness = 0.30f,
            clarity = 0.28f,
            noiseReduction = 0.30f,
            detailRecovery = 0.40f,
            removeArtifacts = true
        )
    },

    HD_ENHANCE(
        id = "hd_enhance",
        title = "HD Enhance",
        description = "Maximum perceived sharpness & fine edges",
        iconResName = "ic_preset_hd"
    ) {
        override fun createAdjustments() = RemasterAdjustments(
            contrast = 0.12f,
            highlights = -0.10f,
            shadows = 0.10f,
            vibrance = 0.10f,
            sharpness = 0.50f,
            clarity = 0.35f,
            detailRecovery = 0.50f,
            aiDetailRecovery = 0.45f
        )
    },

    CINEMATIC(
        id = "cinematic",
        title = "Cinematic",
        description = "Film-inspired color grading & contrast",
        iconResName = "ic_preset_cinematic"
    ) {
        override fun createAdjustments() = RemasterAdjustments(
            brightness = -0.02f,
            exposure = 0.02f,
            contrast = 0.20f,
            highlights = -0.20f,
            shadows = 0.10f,
            saturation = -0.05f,
            vibrance = 0.12f,
            temperature = 0.08f,
            tint = -0.04f,
            sharpness = 0.22f,
            clarity = 0.18f,
            vignette = -0.22f
        )
    },

    SOCIAL_READY(
        id = "social_ready",
        title = "Social Ready",
        description = "Polished, vibrant photos ready to share",
        iconResName = "ic_preset_social"
    ) {
        override fun createAdjustments() = RemasterAdjustments(
            brightness = 0.06f,
            exposure = 0.05f,
            contrast = 0.15f,
            highlights = -0.15f,
            shadows = 0.18f,
            saturation = 0.16f,
            vibrance = 0.24f,
            sharpness = 0.28f,
            clarity = 0.20f,
            noiseReduction = 0.15f,
            skinSmoothing = 0.25f,
            vignette = -0.06f
        )
    },

    PRODUCT_PHOTO(
        id = "product_photo",
        title = "Product Photo",
        description = "Clean colors, crisp edges & neutral lighting",
        iconResName = "ic_preset_product"
    ) {
        override fun createAdjustments() = RemasterAdjustments(
            brightness = 0.10f,
            exposure = 0.08f,
            contrast = 0.16f,
            highlights = -0.20f,
            shadows = 0.22f,
            saturation = 0.08f,
            vibrance = 0.14f,
            temperature = 0.0f,
            tint = 0.0f,
            sharpness = 0.40f,
            clarity = 0.30f,
            detailRecovery = 0.35f,
            noiseReduction = 0.20f
        )
    },

    BW_PRO(
        id = "bw_pro",
        title = "B&W Pro",
        description = "Deep tonal range, rich contrast & fine grain",
        iconResName = "ic_preset_bw"
    ) {
        override fun createAdjustments() = RemasterAdjustments(
            brightness = 0.02f,
            contrast = 0.28f,
            highlights = -0.15f,
            shadows = 0.18f,
            sharpness = 0.32f,
            clarity = 0.35f,
            detailRecovery = 0.30f,
            vignette = -0.15f,
            isMonochrome = true
        )
    };

    abstract fun createAdjustments(): RemasterAdjustments
}
