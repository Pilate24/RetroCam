package com.retrocam.app.video

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera

/**
 * Manages manual pro camera controls (ISO, Shutter Speed, Focus, Aperture/EV, White Balance)
 * using Camera2Interop over CameraX.
 */
class ProCameraManager {

    companion object {
        private const val TAG = "ProCameraManager"

        val COMMON_ISOS = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)

        data class ShutterPreset(val label: String, val nanos: Long)
        val COMMON_SHUTTERS = listOf(
            ShutterPreset("1/1000", 1_000_000L),
            ShutterPreset("1/500", 2_000_000L),
            ShutterPreset("1/250", 4_000_000L),
            ShutterPreset("1/125", 8_000_000L),
            ShutterPreset("1/60", 16_666_666L),
            ShutterPreset("1/30", 33_333_333L),
            ShutterPreset("1/15", 66_666_666L),
            ShutterPreset("1/8", 125_000_000L),
            ShutterPreset("1/4", 250_000_000L),
            ShutterPreset("1/2", 500_000_000L),
            ShutterPreset("1s", 1_000_000_000L)
        )

        data class WbPreset(val label: String, val mode: Int, val kelvin: String)
        val COMMON_WB_MODES = listOf(
            WbPreset("Incandescent", CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, "2800K"),
            WbPreset("Fluorescent", CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, "4000K"),
            WbPreset("Daylight", CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, "5500K"),
            WbPreset("Cloudy", CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, "6500K"),
            WbPreset("Shade", CaptureRequest.CONTROL_AWB_MODE_SHADE, "7500K")
        )
    }

    // Current State
    var isManualIso: Boolean = false
        private set
    var manualIso: Int = 100
        private set

    var isManualShutter: Boolean = false
        private set
    var manualShutterNanos: Long = 16_666_666L // 1/60s default
        private set

    var isManualFocus: Boolean = false
        private set
    var manualFocusDistance: Float = 0.0f // 0 = infinity
        private set

    var evIndex: Int = 0
        private set

    var isManualWb: Boolean = false
        private set
    var wbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO
        private set

    var selectedAperture: Float? = null
        private set

    // Hardware Sensor Capabilities
    var isoRange: Range<Int> = Range(100, 3200)
        private set
    var shutterRange: Range<Long> = Range(100_000L, 1_000_000_000L)
        private set
    var minFocusDistance: Float = 10.0f
        private set
    var availableApertures: List<Float> = emptyList()
        private set
    var isManualFocusSupported: Boolean = true
        private set

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun updateCapabilities(camera: Camera) {
        try {
            val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)

            camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
                isoRange = it
            }

            camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                shutterRange = it
            }

            val maxDiopters = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            if (maxDiopters != null && maxDiopters > 0f) {
                minFocusDistance = maxDiopters
                isManualFocusSupported = true
            } else {
                minFocusDistance = 0f
                isManualFocusSupported = false
            }

            camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.let { apertures ->
                availableApertures = apertures.toList()
                if (selectedAperture == null && availableApertures.isNotEmpty()) {
                    selectedAperture = availableApertures.first()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying camera characteristics", e)
        }
    }

    fun setIso(iso: Int?, camera: Camera?) {
        if (iso == null) {
            isManualIso = false
        } else {
            isManualIso = true
            manualIso = iso.coerceIn(isoRange.lower, isoRange.upper)
        }
        camera?.let { apply(it) }
    }

    fun setShutterNanos(nanos: Long?, camera: Camera?) {
        if (nanos == null) {
            isManualShutter = false
        } else {
            isManualShutter = true
            manualShutterNanos = nanos.coerceIn(shutterRange.lower, shutterRange.upper)
        }
        camera?.let { apply(it) }
    }

    fun setFocusDistance(distance: Float?, camera: Camera?) {
        if (distance == null || !isManualFocusSupported) {
            isManualFocus = false
        } else {
            isManualFocus = true
            manualFocusDistance = distance.coerceIn(0.0f, minFocusDistance)
        }
        camera?.let { apply(it) }
    }

    fun setEvIndex(index: Int, camera: Camera?) {
        evIndex = index
        camera?.let { apply(it) }
    }

    fun setWbMode(mode: Int?, camera: Camera?) {
        if (mode == null || mode == CaptureRequest.CONTROL_AWB_MODE_AUTO) {
            isManualWb = false
            wbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
        } else {
            isManualWb = true
            wbMode = mode
        }
        camera?.let { apply(it) }
    }

    fun setAperture(aperture: Float?, camera: Camera?) {
        selectedAperture = aperture
        camera?.let { apply(it) }
    }

    fun resetToAuto(camera: Camera?) {
        isManualIso = false
        isManualShutter = false
        isManualFocus = false
        isManualWb = false
        wbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
        evIndex = 0
        camera?.let { apply(it) }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun apply(camera: Camera) {
        try {
            val camera2Control = Camera2CameraControl.from(camera.cameraControl)
            val builder = CaptureRequestOptions.Builder()

            // 1. Exposure (ISO & Shutter)
            if (isManualIso || isManualShutter) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, manualShutterNanos)
            } else {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                try {
                    camera.cameraControl.setExposureCompensationIndex(evIndex)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set exposure compensation index", e)
                }
            }

            // 2. Focus
            if (isManualFocus && isManualFocusSupported) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance)
            } else {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
            }

            // 3. White Balance
            if (isManualWb) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, wbMode)
            } else {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_AUTO
                )
            }

            // 4. Aperture (if supported)
            if (selectedAperture != null && availableApertures.size > 1) {
                builder.setCaptureRequestOption(CaptureRequest.LENS_APERTURE, selectedAperture!!)
            }

            camera2Control.setCaptureRequestOptions(builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Error applying pro camera settings", e)
        }
    }

    fun getIsoSummary(): String = if (isManualIso) "$manualIso" else "AUTO"

    fun getShutterSummary(): String {
        if (!isManualShutter) return "AUTO"
        val sec = manualShutterNanos / 1_000_000_000.0
        return if (sec >= 1.0) {
            String.format("%.1fs", sec)
        } else {
            val fraction = (1.0 / sec).toInt()
            "1/${fraction}s"
        }
    }

    fun getFocusSummary(): String {
        if (!isManualFocus || !isManualFocusSupported) return "AF"
        if (minFocusDistance <= 0f) return "AF"
        val ratio = manualFocusDistance / minFocusDistance
        return if (ratio < 0.1f) "∞" else if (ratio > 0.9f) "MACRO" else String.format("%.1fm", 1f / manualFocusDistance)
    }

    fun getWbSummary(): String {
        if (!isManualWb) return "AUTO"
        return when (wbMode) {
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> "2800K"
            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> "4000K"
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> "5500K"
            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "6500K"
            CaptureRequest.CONTROL_AWB_MODE_SHADE -> "7500K"
            else -> "MANUAL"
        }
    }

    fun getApertureSummary(): String {
        return if (availableApertures.isNotEmpty()) {
            String.format("f/%.1f", selectedAperture ?: availableApertures.first())
        } else {
            "f/1.8"
        }
    }
}
