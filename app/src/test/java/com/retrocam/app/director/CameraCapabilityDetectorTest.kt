package com.retrocam.app.director

import android.util.SizeF
import com.retrocam.app.director.detector.CameraCapabilityDetector
import com.retrocam.app.director.model.CameraCapabilities
import com.retrocam.app.director.model.PhysicalLensInfo
import com.retrocam.app.director.model.PhysicalLensType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilityDetectorTest {

    @Test
    fun testClassifyLensIdentifiesUltraWideCorrectly() {
        // Standard smartphone ultra-wide: f = 1.8mm, sensor = 5.6mm x 4.2mm
        // Diagonal = 7.0mm -> crop factor = 43.27 / 7.0 = 6.18 -> eq35mm = 11.1mm (<= 21mm)
        val focalLengths = floatArrayOf(1.8f)

        val lens = CameraCapabilityDetector.classifyLens(
            cameraId = "2",
            focalLengths = focalLengths,
            sensorWidth = 5.6f,
            sensorHeight = 4.2f
        )

        assertEquals(PhysicalLensType.ULTRA_WIDE, lens.lensType)
        assertTrue(lens.isVerified)
        assertTrue("35mm equivalent must be <= 21mm", lens.equivalent35mmMm!! <= 21f)
    }

    @Test
    fun testClassifyLensIdentifiesMainCameraCorrectly() {
        // Standard smartphone main camera: f = 5.4mm, sensor = 7.0mm x 5.25mm
        // Diagonal = 8.75mm -> crop factor = 43.27 / 8.75 = 4.94 -> eq35mm = 26.7mm (22mm - 36mm)
        val focalLengths = floatArrayOf(5.4f)

        val lens = CameraCapabilityDetector.classifyLens(
            cameraId = "0",
            focalLengths = focalLengths,
            sensorWidth = 7.0f,
            sensorHeight = 5.25f
        )

        assertEquals(PhysicalLensType.MAIN, lens.lensType)
        assertTrue(lens.isVerified)
        assertTrue("35mm equivalent must be between 22mm and 36mm", lens.equivalent35mmMm!! in 22f..36f)
    }

    @Test
    fun testClassifyLensIdentifiesTelephotoCorrectly() {
        // Standard smartphone telephoto: f = 15.0mm, sensor = 6.4mm x 4.8mm
        // Diagonal = 8.0mm -> crop factor = 43.27 / 8.0 = 5.4 -> eq35mm = 81mm (>= 50mm)
        val focalLengths = floatArrayOf(15.0f)

        val lens = CameraCapabilityDetector.classifyLens(
            cameraId = "3",
            focalLengths = focalLengths,
            sensorWidth = 6.4f,
            sensorHeight = 4.8f
        )

        assertEquals(PhysicalLensType.TELEPHOTO, lens.lensType)
        assertTrue(lens.isVerified)
        assertTrue("35mm equivalent must be >= 50mm", lens.equivalent35mmMm!! >= 50f)
    }

    @Test
    fun testClassifyLensWithMissingMetadataFallsBackSafely() {
        // Null focal lengths or null sensor size
        val lensNullFocal = CameraCapabilityDetector.classifyLens(
            cameraId = "unknown",
            focalLengths = null,
            sensorWidth = 6.4f,
            sensorHeight = 4.8f
        )
        assertEquals(PhysicalLensType.CURRENT_CAMERA, lensNullFocal.lensType)
        assertFalse(lensNullFocal.isVerified)

        val lensNullSensor = CameraCapabilityDetector.classifyLens(
            cameraId = "unknown",
            focalLengths = floatArrayOf(4.5f),
            sensorWidth = null,
            sensorHeight = null
        )
        assertEquals(PhysicalLensType.CURRENT_CAMERA, lensNullSensor.lensType)
        assertFalse(lensNullSensor.isVerified)
    }

    @Test
    fun testCameraCapabilitiesZoomClamping() {
        val caps = CameraCapabilities(
            isBackCamera = true,
            minZoomRatio = 0.6f,
            maxZoomRatio = 8.0f,
            currentZoomRatio = 1.0f,
            verifiedLenses = emptyList(),
            activeLens = PhysicalLensInfo(
                lensType = PhysicalLensType.CURRENT_CAMERA,
                focalLengthMm = null,
                equivalent35mmMm = null,
                fovDegrees = null,
                isVerified = false,
                cameraId = "0"
            ),
            canSwitchPhysicalLenses = false,
            hasFlashUnit = true,
            isManualFocusSupported = true,
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f
        )

        // Within range
        assertTrue(caps.isZoomSupported(1.5f))
        assertEquals(1.5f, caps.clampZoom(1.5f), 0.001f)

        // Below minimum -> clamped to 0.6f
        assertFalse(caps.isZoomSupported(0.3f))
        assertEquals(0.6f, caps.clampZoom(0.3f), 0.001f)

        // Above maximum -> clamped to 8.0f
        assertFalse(caps.isZoomSupported(15.0f))
        assertEquals(8.0f, caps.clampZoom(15.0f), 0.001f)
    }
}
