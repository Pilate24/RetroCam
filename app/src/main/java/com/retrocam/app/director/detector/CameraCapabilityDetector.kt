package com.retrocam.app.director.detector

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.SizeF
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import com.retrocam.app.director.model.CameraCapabilities
import com.retrocam.app.director.model.PhysicalLensInfo
import com.retrocam.app.director.model.PhysicalLensType
import kotlin.math.atan
import kotlin.math.sqrt

/**
 * Detects real camera hardware capabilities on the current Android device.
 * Follows strict rules: never invents hardware or lens configurations.
 */
object CameraCapabilityDetector {

    private const val TAG = "CameraCapabilityDetect"
    private const val DIAGONAL_35MM = 43.27f // 36mm x 24mm standard frame diagonal

    /**
     * Inspects device camera hardware and returns verified capabilities.
     *
     * @param context Application context for CameraManager access.
     * @param camera Active CameraX Camera instance, if initialized.
     * @param isBackCamera Whether the current camera selector is targeting the rear camera.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun detectCapabilities(
        context: Context,
        camera: Camera?,
        isBackCamera: Boolean
    ): CameraCapabilities {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager == null) {
            Log.w(TAG, "CameraManager unavailable; returning safe fallback capabilities")
            return CameraCapabilities.DEFAULT_FALLBACK
        }

        val targetFacing = if (isBackCamera) {
            CameraCharacteristics.LENS_FACING_BACK
        } else {
            CameraCharacteristics.LENS_FACING_FRONT
        }

        // 1. Inspect real zoom range from CameraX active camera
        val zoomState = camera?.cameraInfo?.zoomState?.value
        val minZoom = zoomState?.minZoomRatio ?: 1.0f
        val maxZoom = zoomState?.maxZoomRatio ?: 1.0f
        val currentZoom = zoomState?.zoomRatio ?: 1.0f

        // 2. Query CameraCharacteristics for the active camera
        var sensorWidth: Float? = null
        var sensorHeight: Float? = null
        var hasFlash = false
        var isManualFocusSupported = false

        if (camera != null) {
            try {
                val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)
                val sensorSize = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                sensorWidth = sensorSize?.width
                sensorHeight = sensorSize?.height
                hasFlash = camera2Info.getCameraCharacteristic(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false

                val minFocusDist = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                isManualFocusSupported = (minFocusDist != null && minFocusDist > 0f)
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading characteristics from active CameraX Camera", e)
            }
        }

        // 3. Inspect available physical/logical cameras on device
        val verifiedLenses = mutableListOf<PhysicalLensInfo>()

        try {
            val cameraIds = cameraManager.cameraIdList
            for (id in cameraIds) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                if (facing != targetFacing) continue

                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

                val lensInfo = classifyLens(
                    cameraId = id,
                    focalLengths = focalLengths,
                    sensorSize = sensorSize
                )
                verifiedLenses.add(lensInfo)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error enumerating camera IDs", e)
        }

        // If no lenses could be verified, provide a single safe CURRENT_CAMERA lens
        val lensesList = if (verifiedLenses.isNotEmpty()) {
            verifiedLenses
        } else {
            listOf(
                PhysicalLensInfo(
                    lensType = PhysicalLensType.CURRENT_CAMERA,
                    focalLengthMm = null,
                    equivalent35mmMm = null,
                    fovDegrees = null,
                    isVerified = false,
                    cameraId = "0"
                )
            )
        }

        // Active lens: find main lens, or first verified lens, or fallback
        val activeLens = lensesList.firstOrNull { it.lensType == PhysicalLensType.MAIN }
            ?: lensesList.first()

        // Can switch physical lenses: only true if there are distinct verified physical cameras
        // on the same facing direction that have different focal lengths
        val distinctLensesCount = lensesList.map { it.lensType }.distinct().size
        val canSwitchPhysical = distinctLensesCount > 1 && lensesList.size > 1

        return CameraCapabilities(
            isBackCamera = isBackCamera,
            minZoomRatio = minZoom,
            maxZoomRatio = maxZoom,
            currentZoomRatio = currentZoom,
            verifiedLenses = lensesList,
            activeLens = activeLens,
            canSwitchPhysicalLenses = canSwitchPhysical,
            hasFlashUnit = hasFlash,
            isManualFocusSupported = isManualFocusSupported,
            sensorWidthMm = sensorWidth,
            sensorHeightMm = sensorHeight
        )
    }

    /**
     * Classifies a lens strictly from its physical focal length and sensor size.
     * Never guesses or assigns names without hardware evidence.
     */
    fun classifyLens(
        cameraId: String,
        focalLengths: FloatArray?,
        sensorSize: SizeF?
    ): PhysicalLensInfo {
        return classifyLens(
            cameraId = cameraId,
            focalLengths = focalLengths,
            sensorWidth = sensorSize?.width,
            sensorHeight = sensorSize?.height
        )
    }

    /**
     * Pure computational lens classification from dimensions in millimeters.
     */
    fun classifyLens(
        cameraId: String,
        focalLengths: FloatArray?,
        sensorWidth: Float?,
        sensorHeight: Float?
    ): PhysicalLensInfo {
        val focalLength = focalLengths?.firstOrNull()
        if (focalLength == null || sensorWidth == null || sensorHeight == null || sensorWidth <= 0f || sensorHeight <= 0f) {
            return PhysicalLensInfo(
                lensType = PhysicalLensType.CURRENT_CAMERA,
                focalLengthMm = focalLength,
                equivalent35mmMm = null,
                fovDegrees = null,
                isVerified = false,
                cameraId = cameraId
            )
        }

        val sensorDiagonal = sqrt(
            sensorWidth * sensorWidth + sensorHeight * sensorHeight
        )
        val cropFactor = DIAGONAL_35MM / sensorDiagonal
        val eq35mm = focalLength * cropFactor
        val fov = (2f * Math.toDegrees(atan((sensorWidth / (2f * focalLength)).toDouble()))).toFloat()

        // Strict classification thresholds:
        // Ultra-wide: 35mm equivalent <= 21mm (or FOV > 85 deg)
        // Main camera: 35mm equivalent between 22mm and 36mm (standard smartphone wide)
        // Telephoto: 35mm equivalent >= 50mm (or FOV < 50 deg)
        val lensType = when {
            eq35mm <= 21f || fov > 85f -> PhysicalLensType.ULTRA_WIDE
            eq35mm in 22f..36f -> PhysicalLensType.MAIN
            eq35mm >= 50f || fov < 50f -> PhysicalLensType.TELEPHOTO
            else -> PhysicalLensType.CURRENT_CAMERA
        }

        return PhysicalLensInfo(
            lensType = lensType,
            focalLengthMm = focalLength,
            equivalent35mmMm = eq35mm,
            fovDegrees = fov,
            isVerified = true,
            cameraId = cameraId
        )
    }
}
