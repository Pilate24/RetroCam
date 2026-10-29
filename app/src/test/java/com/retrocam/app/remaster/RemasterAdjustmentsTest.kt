package com.retrocam.app.remaster

import com.retrocam.app.remaster.model.RemasterAdjustments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemasterAdjustmentsTest {

    @Test
    fun testDefaultAdjustments() {
        val def = RemasterAdjustments.DEFAULT
        assertTrue(def.isDefault())
        assertEquals(0f, def.brightness, 0.0001f)
        assertEquals(0f, def.exposure, 0.0001f)
        assertEquals(0f, def.contrast, 0.0001f)
        assertEquals(0f, def.highlights, 0.0001f)
        assertEquals(0f, def.shadows, 0.0001f)
        assertEquals(0f, def.saturation, 0.0001f)
        assertEquals(0f, def.vibrance, 0.0001f)
        assertEquals(0f, def.temperature, 0.0001f)
        assertEquals(0f, def.tint, 0.0001f)
        assertEquals(0f, def.sharpness, 0.0001f)
        assertEquals(0f, def.clarity, 0.0001f)
        assertEquals(0f, def.noiseReduction, 0.0001f)
        assertEquals(0f, def.skinSmoothing, 0.0001f)
        assertEquals(0f, def.detailRecovery, 0.0001f)
        assertEquals(0f, def.vignette, 0.0001f)
        assertFalse(def.portraitRedEyeCorrection)
        assertFalse(def.aiUpscale)
        assertFalse(def.isMonochrome)
    }

    @Test
    fun testModifiedAdjustmentIsNotDefault() {
        val adj1 = RemasterAdjustments.DEFAULT.copy(brightness = 0.2f)
        assertFalse(adj1.isDefault())

        val adj2 = RemasterAdjustments.DEFAULT.copy(isMonochrome = true)
        assertFalse(adj2.isDefault())

        val adj3 = RemasterAdjustments.DEFAULT.copy(aiUpscale = true)
        assertFalse(adj3.isDefault())
    }

    @Test
    fun testClampingHelper() {
        assertEquals(0.5f, RemasterAdjustments.clamp(0.5f, -1.0f, 1.0f), 0.0001f)
        assertEquals(1.0f, RemasterAdjustments.clamp(1.5f, -1.0f, 1.0f), 0.0001f)
        assertEquals(-1.0f, RemasterAdjustments.clamp(-2.0f, -1.0f, 1.0f), 0.0001f)
        assertEquals(0f, RemasterAdjustments.clamp(-0.2f, 0.0f, 1.0f), 0.0001f)
    }
}
