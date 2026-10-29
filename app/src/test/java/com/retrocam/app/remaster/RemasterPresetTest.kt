package com.retrocam.app.remaster

import com.retrocam.app.remaster.model.RemasterPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemasterPresetTest {

    @Test
    fun testAllTwelvePresetsExist() {
        val presets = RemasterPreset.values()
        assertEquals(12, presets.size)

        val expectedIds = setOf(
            "natural",
            "hdr_pro",
            "portrait_pro",
            "landscape_pro",
            "night_fix",
            "low_light_recovery",
            "vintage_restoration",
            "hd_enhance",
            "cinematic",
            "social_ready",
            "product_photo",
            "bw_pro"
        )
        val actualIds = presets.map { it.id }.toSet()
        assertEquals(expectedIds, actualIds)
    }

    @Test
    fun testPresetTitlesAndDescriptionsAreNonEmpty() {
        for (preset in RemasterPreset.values()) {
            assertTrue("Preset ${preset.id} title should not be empty", preset.title.isNotBlank())
            assertTrue("Preset ${preset.id} description should not be empty", preset.description.isNotBlank())
            val adj = preset.createAdjustments()
            assertNotNull(adj)
            assertFalse("Preset ${preset.id} should have non-default adjustments", adj.isDefault())
        }
    }

    @Test
    fun testBlackAndWhitePresetIsMonochrome() {
        val bwAdj = RemasterPreset.BW_PRO.createAdjustments()
        assertTrue("B&W Pro should set isMonochrome to true", bwAdj.isMonochrome)
        assertTrue("B&W Pro should have contrast boost", bwAdj.contrast > 0.1f)
    }

    @Test
    fun testPortraitPresetHasSkinAndFaceEnhancements() {
        val portraitAdj = RemasterPreset.PORTRAIT_PRO.createAdjustments()
        assertTrue("Portrait Pro should have skin smoothing", portraitAdj.portraitSkinSmoothing > 0.3f)
        assertTrue("Portrait Pro should have face illumination", portraitAdj.portraitFaceIllumination > 0.2f)
        assertTrue("Portrait Pro should have eye enhancement", portraitAdj.portraitEyeEnhancement > 0.2f)
        assertTrue("Portrait Pro should enable red eye correction", portraitAdj.portraitRedEyeCorrection)
    }

    @Test
    fun testNightFixHasSubstantialNoiseReduction() {
        val nightAdj = RemasterPreset.NIGHT_FIX.createAdjustments()
        assertTrue("Night Fix should lift exposure", nightAdj.exposure > 0.15f)
        assertTrue("Night Fix should lift shadows", nightAdj.shadows > 0.30f)
        assertTrue("Night Fix should have strong noise reduction", nightAdj.noiseReduction >= 0.40f)
    }

    @Test
    fun testHdrProHasBroadDynamicRangeAdjustments() {
        val hdrAdj = RemasterPreset.HDR_PRO.createAdjustments()
        assertTrue("HDR Pro should compress highlights", hdrAdj.highlights < -0.2f)
        assertTrue("HDR Pro should lift shadows", hdrAdj.shadows > 0.25f)
        assertTrue("HDR Pro should increase vibrance", hdrAdj.vibrance > 0.15f)
    }
}
