package com.retrocam.app.director.model

/**
 * Verified physical lens types.
 * Rule: Label a camera ultra-wide, main, or telephoto ONLY when supported
 * by reliable hardware information. Otherwise, use CURRENT_CAMERA.
 */
enum class PhysicalLensType(val displayName: String) {
    ULTRA_WIDE("Ultra-Wide"),
    MAIN("Main Camera"),
    TELEPHOTO("Telephoto"),
    CURRENT_CAMERA("Current Camera")
}

/**
 * Information about a verified physical or logical camera lens.
 */
data class PhysicalLensInfo(
    val lensType: PhysicalLensType,
    val focalLengthMm: Float?,
    val equivalent35mmMm: Float?,
    val fovDegrees: Float?,
    val isVerified: Boolean,
    val cameraId: String,
    val minZoom: Float = 1.0f,
    val maxZoom: Float = 1.0f
) {
    fun getLabel(): String = when {
        !isVerified -> "Current Camera"
        equivalent35mmMm != null -> "${lensType.displayName} (~${equivalent35mmMm.toInt()}mm)"
        focalLengthMm != null -> "${lensType.displayName} (${String.format("%.1f", focalLengthMm)}mm)"
        else -> lensType.displayName
    }
}

/**
 * Real device camera capabilities detected at runtime.
 * Never invent camera hardware or hardcode zoom ranges.
 */
data class CameraCapabilities(
    val isBackCamera: Boolean,
    val minZoomRatio: Float,
    val maxZoomRatio: Float,
    val currentZoomRatio: Float,
    val verifiedLenses: List<PhysicalLensInfo>,
    val activeLens: PhysicalLensInfo,
    val canSwitchPhysicalLenses: Boolean,
    val hasFlashUnit: Boolean,
    val isManualFocusSupported: Boolean,
    val sensorWidthMm: Float?,
    val sensorHeightMm: Float?
) {
    /**
     * Checks if a recommended zoom ratio is supported by the device camera.
     */
    fun isZoomSupported(ratio: Float): Boolean {
        return ratio in minZoomRatio..maxZoomRatio
    }

    /**
     * Clamps a zoom ratio strictly to the device's verified hardware zoom bounds.
     */
    fun clampZoom(ratio: Float): Float {
        return ratio.coerceIn(minZoomRatio, maxZoomRatio)
    }

    companion object {
        val DEFAULT_FALLBACK = CameraCapabilities(
            isBackCamera = true,
            minZoomRatio = 1.0f,
            maxZoomRatio = 1.0f,
            currentZoomRatio = 1.0f,
            verifiedLenses = listOf(
                PhysicalLensInfo(
                    lensType = PhysicalLensType.CURRENT_CAMERA,
                    focalLengthMm = null,
                    equivalent35mmMm = null,
                    fovDegrees = null,
                    isVerified = false,
                    cameraId = "0"
                )
            ),
            activeLens = PhysicalLensInfo(
                lensType = PhysicalLensType.CURRENT_CAMERA,
                focalLengthMm = null,
                equivalent35mmMm = null,
                fovDegrees = null,
                isVerified = false,
                cameraId = "0"
            ),
            canSwitchPhysicalLenses = false,
            hasFlashUnit = false,
            isManualFocusSupported = false,
            sensorWidthMm = null,
            sensorHeightMm = null
        )
    }
}
