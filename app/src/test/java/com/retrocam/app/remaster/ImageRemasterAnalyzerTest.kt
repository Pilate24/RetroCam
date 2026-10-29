package com.retrocam.app.remaster

import com.retrocam.app.remaster.analyzer.ImageRemasterAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageRemasterAnalyzerTest {

    @Test
    fun testLaplacianVarianceDistinguishesFlatFromDetailedImage() {
        val width = 16
        val height = 16

        // 1. Flat image: all pixels identical mid-gray
        val flatPixels = IntArray(width * height) { 0xFF808080.toInt() }
        val flatVariance = ImageRemasterAnalyzer.computeLaplacianVariance(flatPixels, width, height)
        assertEquals(0f, flatVariance, 0.001f)

        // 2. High-contrast edge pattern (vertical split edge)
        val edgePixels = IntArray(width * height) { index ->
            val x = index % width
            if (x < width / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val edgeVariance = ImageRemasterAnalyzer.computeLaplacianVariance(edgePixels, width, height)
        assertTrue("Edge pattern should produce positive Laplacian variance, got $edgeVariance", edgeVariance > 50f)
    }

    @Test
    fun testLowLightSceneTriggersExposureAndShadowLift() {
        val adjustments = ImageRemasterAnalyzer.calculateAutoAdjustments(
            meanLuma = 40f,
            contrastStdDev = 30f,
            dynamicRange = 100f,
            shadowClipRatio = 0.35f,
            highlightClipRatio = 0.01f,
            colorTempDelta = 0.1f,
            tintDelta = 0.0f,
            sharpnessScore = 120f,
            noiseScore = 25f,
            faceCount = 0,
            isLowLight = true,
            isOverexposed = false,
            isSoft = true
        )

        assertTrue("Low light photo must receive positive exposure compensation", adjustments.exposure > 0.15f)
        assertTrue("Low light photo must lift crushed shadows", adjustments.shadows > 0.25f)
        assertTrue("Noisy low light photo must receive noise reduction", adjustments.noiseReduction > 0.20f)
    }

    @Test
    fun testOverexposedSceneCompensatesHighlights() {
        val adjustments = ImageRemasterAnalyzer.calculateAutoAdjustments(
            meanLuma = 195f,
            contrastStdDev = 55f,
            dynamicRange = 210f,
            shadowClipRatio = 0.0f,
            highlightClipRatio = 0.25f,
            colorTempDelta = -0.05f,
            tintDelta = 0.0f,
            sharpnessScore = 300f,
            noiseScore = 5f,
            faceCount = 0,
            isLowLight = false,
            isOverexposed = true,
            isSoft = false
        )

        assertTrue("Overexposed photo must reduce exposure", adjustments.exposure < 0f)
        assertTrue("Overexposed photo must roll off highlights", adjustments.highlights < -0.15f)
    }

    @Test
    fun testFaceDetectionEngagesPortraitEnhancements() {
        val adjustments = ImageRemasterAnalyzer.calculateAutoAdjustments(
            meanLuma = 120f,
            contrastStdDev = 50f,
            dynamicRange = 180f,
            shadowClipRatio = 0.05f,
            highlightClipRatio = 0.05f,
            colorTempDelta = 0.0f,
            tintDelta = 0.0f,
            sharpnessScore = 200f,
            noiseScore = 8f,
            faceCount = 2,
            isLowLight = false,
            isOverexposed = false,
            isSoft = false
        )

        assertTrue("Photos with faces must activate skin smoothing", adjustments.skinSmoothing > 0.3f)
        assertTrue("Photos with faces must activate portrait skin smoothing", adjustments.portraitSkinSmoothing > 0.3f)
        assertTrue("Photos with faces must activate face illumination", adjustments.portraitFaceIllumination > 0.2f)
        assertTrue("Photos with faces must enable red eye correction", adjustments.portraitRedEyeCorrection)
    }

    @Test
    fun testSummaryBadgeFormatting() {
        val badgeLowLight = ImageRemasterAnalyzer.buildSummaryBadge(
            isLowLight = true,
            isOverexposed = false,
            isSoft = true,
            isNoisy = true,
            faceCount = 1
        )

        assertTrue("Badge should mention portrait face", badgeLowLight.contains("1 Portrait Face"))
        assertTrue("Badge should mention low light", badgeLowLight.contains("Low Light Boosted"))
        assertTrue("Badge should mention clarity/edges", badgeLowLight.contains("Clarity & Edges Recovered"))
        assertTrue("Badge should mention noise", badgeLowLight.contains("Noise Filtered"))
    }
}
