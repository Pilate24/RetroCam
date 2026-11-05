package com.retrocam.app.pro

import com.retrocam.app.video.ProCameraManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProCameraManagerTest {

    @Test
    fun testCommonShuttersContainsFastAndLongPresets() {
        val presets = ProCameraManager.COMMON_SHUTTERS
        assertTrue("Must include fast shutter 1/8000s", presets.any { it.label == "1/8000" })
        assertTrue("Must include standard 1/60s", presets.any { it.label == "1/60" })
        assertTrue("Must include 1s", presets.any { it.label == "1s" })
        assertTrue("Must include long exposure 4s", presets.any { it.label == "4s" })
        assertTrue("Must include long exposure 30s", presets.any { it.label == "30s" })
    }

    @Test
    fun testCommonIsos() {
        val isos = ProCameraManager.COMMON_ISOS
        assertTrue(isos.contains(50))
        assertTrue(isos.contains(100))
        assertTrue(isos.contains(800))
        assertTrue(isos.contains(6400))
    }

    @Test
    fun testAeAndAfLockState() {
        val manager = ProCameraManager()
        assertFalse(manager.isAeLocked)
        assertFalse(manager.isAfLocked)

        manager.setAeLock(true, null)
        assertTrue(manager.isAeLocked)

        manager.setAfLock(true, null)
        assertTrue(manager.isAfLocked)

        manager.resetToAuto(null)
        assertFalse(manager.isAeLocked)
        assertFalse(manager.isAfLocked)
    }

    @Test
    fun testProToggles() {
        val manager = ProCameraManager()
        assertFalse(manager.isFocusPeakingEnabled)
        assertFalse(manager.isHistogramEnabled)
        assertFalse(manager.isRawCaptureEnabled)

        manager.isFocusPeakingEnabled = true
        manager.isHistogramEnabled = true
        manager.isRawCaptureEnabled = true

        assertTrue(manager.isFocusPeakingEnabled)
        assertTrue(manager.isHistogramEnabled)
        assertTrue(manager.isRawCaptureEnabled)
    }

    @Test
    fun testMetadataSynthesis() {
        val manager = ProCameraManager()
        manager.setIso(400, null)
        manager.setShutterNanos(16_666_666L, null)

        val meta = manager.createMetadata(
            presetId = "porta_400",
            isLongExp = true,
            longExpMode = "Light Trails",
            longExpDuration = 5.0f,
            frameCount = 45
        )

        assertNotNull(meta)
        assertEquals(400, meta.iso)
        assertEquals(16_666_666L, meta.shutterNanos)
        assertEquals("porta_400", meta.presetId)
        assertTrue(meta.isLongExposure)
        assertEquals("Light Trails", meta.longExposureMode)
        assertEquals(5.0f, meta.longExposureDurationSec ?: 0f, 0.001f)
        assertEquals(45, meta.frameCount)
    }
}
