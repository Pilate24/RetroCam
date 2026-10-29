package com.retrocam.app.director

import com.retrocam.app.director.analyzer.FaceBox
import com.retrocam.app.director.analyzer.FrameAnalysisResult
import com.retrocam.app.director.engine.ShotDirectorEngine
import com.retrocam.app.director.model.AspectRatioOption
import com.retrocam.app.director.model.CameraCapabilities
import com.retrocam.app.director.model.PhysicalLensInfo
import com.retrocam.app.director.model.PhysicalLensType
import com.retrocam.app.director.model.SceneCategory
import com.retrocam.app.director.model.ShotType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShotDirectorEngineTest {

    // --- Helper fixtures ---

    private fun createSingleCameraCapabilities(
        minZoom: Float = 1.0f,
        maxZoom: Float = 3.0f
    ): CameraCapabilities {
        val singleLens = PhysicalLensInfo(
            lensType = PhysicalLensType.MAIN,
            focalLengthMm = 4.38f,
            equivalent35mmMm = 26f,
            fovDegrees = 78f,
            isVerified = true,
            cameraId = "0"
        )
        return CameraCapabilities(
            isBackCamera = true,
            minZoomRatio = minZoom,
            maxZoomRatio = maxZoom,
            currentZoomRatio = 1.0f,
            verifiedLenses = listOf(singleLens),
            activeLens = singleLens,
            canSwitchPhysicalLenses = false,
            hasFlashUnit = true,
            isManualFocusSupported = true,
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f
        )
    }

    private fun createMultiCameraCapabilities(): CameraCapabilities {
        val ultraWide = PhysicalLensInfo(
            lensType = PhysicalLensType.ULTRA_WIDE,
            focalLengthMm = 1.8f,
            equivalent35mmMm = 14f,
            fovDegrees = 114f,
            isVerified = true,
            cameraId = "2"
        )
        val main = PhysicalLensInfo(
            lensType = PhysicalLensType.MAIN,
            focalLengthMm = 5.4f,
            equivalent35mmMm = 24f,
            fovDegrees = 84f,
            isVerified = true,
            cameraId = "0"
        )
        val telephoto = PhysicalLensInfo(
            lensType = PhysicalLensType.TELEPHOTO,
            focalLengthMm = 15.0f,
            equivalent35mmMm = 70f,
            fovDegrees = 34f,
            isVerified = true,
            cameraId = "3"
        )

        return CameraCapabilities(
            isBackCamera = true,
            minZoomRatio = 0.5f,
            maxZoomRatio = 10.0f,
            currentZoomRatio = 1.0f,
            verifiedLenses = listOf(ultraWide, main, telephoto),
            activeLens = main,
            canSwitchPhysicalLenses = true,
            hasFlashUnit = true,
            isManualFocusSupported = true,
            sensorWidthMm = 9.6f,
            sensorHeightMm = 7.2f
        )
    }

    private fun createAnalysis(
        scene: SceneCategory,
        confidence: Float = 0.85f,
        faces: List<FaceBox> = emptyList(),
        headroom: Float = 0.25f,
        horizonY: Float? = null,
        vertDensity: Float = 0.2f
    ): FrameAnalysisResult {
        return FrameAnalysisResult(
            sceneCategory = scene,
            confidence = confidence,
            detectedFaces = faces,
            isSinglePortrait = faces.size == 1,
            isGroupPhoto = faces.size >= 2,
            headroomFraction = headroom,
            faceCoverageFraction = faces.sumOf { it.area.toDouble() }.toFloat(),
            horizontalSpreadFraction = if (faces.size > 1) faces.maxOf { it.right } - faces.minOf { it.left } else 0f,
            meanLuminance = 120f,
            contrastStdDev = 50f,
            hasStrongHorizon = horizonY != null,
            horizonPositionFraction = horizonY,
            verticalEdgeDensity = vertDensity,
            horizontalEdgeDensity = 0.2f,
            skyHueDominance = if (scene == SceneCategory.LANDSCAPE) 0.35f else 0.05f,
            natureHueDominance = if (scene == SceneCategory.LANDSCAPE) 0.30f else 0.05f,
            warmSunsetDominance = if (scene == SceneCategory.SUNSET_SUNRISE) 0.40f else 0.05f,
            isLowLight = false,
            isOverexposed = false,
            isBlurry = false
        )
    }

    // --- Unit Tests ---

    @Test
    fun testSingleCameraDeviceNeverRecommendsUnavailableLenses() {
        val caps = createSingleCameraCapabilities()
        val analysis = createAnalysis(SceneCategory.LANDSCAPE)

        val rec = ShotDirectorEngine.generateRecommendation(caps, analysis, isUprightPortrait = true)

        // For single camera, must recommend MAIN (verified) and NOT invent an ultra-wide
        assertEquals(PhysicalLensType.MAIN, rec.recommendedLens.lensType)
        assertFalse("Single camera phone must not claim multi-lens switching", caps.canSwitchPhysicalLenses)
    }

    @Test
    fun testMultiCameraDeviceSelectsAppropriatePhysicalLens() {
        val caps = createMultiCameraCapabilities()

        // Landscape should prefer Ultra-Wide
        val landscapeAnalysis = createAnalysis(SceneCategory.LANDSCAPE)
        val landscapeRec = ShotDirectorEngine.generateRecommendation(caps, landscapeAnalysis, isUprightPortrait = true)
        assertEquals(PhysicalLensType.ULTRA_WIDE, landscapeRec.recommendedLens.lensType)

        // Portrait should prefer Telephoto
        val face = FaceBox(left = 0.35f, top = 0.20f, right = 0.65f, bottom = 0.60f, centerX = 0.5f, centerY = 0.4f, eyeDistance = 0.15f, confidence = 0.88f)
        val portraitAnalysis = createAnalysis(SceneCategory.PORTRAIT, faces = listOf(face))
        val portraitRec = ShotDirectorEngine.generateRecommendation(caps, portraitAnalysis, isUprightPortrait = true)
        assertEquals(PhysicalLensType.TELEPHOTO, portraitRec.recommendedLens.lensType)
    }

    @Test
    fun testZoomRecommendationNeverExceedsSupportedRange() {
        // Device with limited zoom range: 1.0x to 1.8x
        val caps = createSingleCameraCapabilities(minZoom = 1.0f, maxZoom = 1.8f)
        val face = FaceBox(left = 0.4f, top = 0.25f, right = 0.6f, bottom = 0.55f, centerX = 0.5f, centerY = 0.4f, eyeDistance = 0.1f, confidence = 0.9f)
        val analysis = createAnalysis(SceneCategory.PORTRAIT, faces = listOf(face))

        val rec = ShotDirectorEngine.generateRecommendation(caps, analysis, isUprightPortrait = true)

        assertNotNull(rec.recommendedZoom)
        assertTrue("Recommended zoom must be <= maxZoomRatio (1.8x)", rec.recommendedZoom!! <= 1.8f)
        assertTrue("Recommended zoom must be >= minZoomRatio (1.0x)", rec.recommendedZoom!! >= 1.0f)
    }

    @Test
    fun testFixedZoomCameraDoesNotProduceFalselyPreciseZoom() {
        // Device with no zoom capability (min == max)
        val caps = createSingleCameraCapabilities(minZoom = 1.0f, maxZoom = 1.0f)
        val analysis = createAnalysis(SceneCategory.PORTRAIT)

        val rec = ShotDirectorEngine.generateRecommendation(caps, analysis, isUprightPortrait = true)
        assertNull("Fixed 1.0x camera must not produce arbitrary zoom value", rec.recommendedZoom)
    }

    @Test
    fun testMissingCameraMetadataFallsBackSafely() {
        val fallbackCaps = CameraCapabilities.DEFAULT_FALLBACK
        val analysis = createAnalysis(SceneCategory.UNKNOWN_OR_MIXED)

        val rec = ShotDirectorEngine.generateRecommendation(fallbackCaps, analysis, isUprightPortrait = true)

        assertEquals("Current Camera", rec.recommendedLens.getLabel())
        assertFalse(rec.recommendedLens.isVerified)
        assertEquals(SceneCategory.UNKNOWN_OR_MIXED, rec.sceneCategory)
    }

    @Test
    fun testCrowdedGroupPhotoTriggersPhysicalMovementAdvice() {
        val caps = createSingleCameraCapabilities()
        // Two faces spread wide across edges: left face touches 0.05, right face touches 0.95
        val face1 = FaceBox(0.04f, 0.3f, 0.20f, 0.6f, 0.12f, 0.45f, 0.08f, 0.85f)
        val face2 = FaceBox(0.80f, 0.3f, 0.96f, 0.6f, 0.88f, 0.45f, 0.08f, 0.85f)
        val analysis = createAnalysis(SceneCategory.GROUP_PHOTO, faces = listOf(face1, face2))

        val rec = ShotDirectorEngine.generateRecommendation(caps, analysis, isUprightPortrait = true)

        assertTrue("Crowded group must trigger physical movement advice", rec.requiresPhysicalMovement)
        assertNotNull(rec.physicalMovementAdvice)
        assertTrue(rec.physicalMovementAdvice!!.contains("Step back"))
    }

    @Test
    fun testCloseUpMacroTriggersPhysicalDistanceAdjustment() {
        val caps = createSingleCameraCapabilities()
        val analysis = createAnalysis(SceneCategory.CLOSE_UP)

        val rec = ShotDirectorEngine.generateRecommendation(caps, analysis, isUprightPortrait = true)

        assertTrue(rec.requiresPhysicalMovement)
        assertNotNull(rec.physicalMovementAdvice)
    }

    @Test
    fun testDeviceRollWithSensorGeneratesLevelingNotice() {
        val caps = createSingleCameraCapabilities()
        val analysis = createAnalysis(SceneCategory.LANDSCAPE, horizonY = 0.5f)

        // Device tilted 4.2 degrees to right, with sensor available
        val recTilted = ShotDirectorEngine.generateRecommendation(
            capabilities = caps,
            analysis = analysis,
            isUprightPortrait = true,
            deviceRollDegrees = -4.2f,
            isHorizonSensorAvailable = true
        )

        assertTrue(recTilted.cameraPositionAdvice.contains("Align device with horizon line"))

        // When sensor is unavailable, must NOT make fake horizon claims
        val recNoSensor = ShotDirectorEngine.generateRecommendation(
            capabilities = caps,
            analysis = analysis,
            isUprightPortrait = true,
            deviceRollDegrees = -4.2f,
            isHorizonSensorAvailable = false
        )
        assertFalse("Without sensor, must not claim live roll measurements", recNoSensor.cameraPositionAdvice.contains("4.2°"))
    }

    @Test
    fun testGeneratesExactlyThreeDistinctValidShotIdeas() {
        val caps = createMultiCameraCapabilities()
        val face = FaceBox(0.35f, 0.25f, 0.65f, 0.65f, 0.5f, 0.45f, 0.15f, 0.9f)
        val portraitAnalysis = createAnalysis(SceneCategory.PORTRAIT, faces = listOf(face))

        val ideas = ShotDirectorEngine.generateThreeShotIdeas(caps, portraitAnalysis, isUprightPortrait = true)

        assertEquals("Must generate exactly 3 alternative shot ideas", 3, ideas.size)
        // Verify names are distinct
        val names = ideas.map { it.name }.distinct()
        assertEquals(3, names.size)

        for (idea in ideas) {
            assertTrue(idea.name.isNotBlank())
            assertTrue(idea.visualEffect.isNotBlank())
            assertTrue(idea.cameraPosition.isNotBlank())
            assertTrue(idea.compositionTip.isNotBlank())
            assertTrue(idea.explanation.isNotBlank())
            if (idea.zoomRatio != null) {
                assertTrue(caps.isZoomSupported(idea.zoomRatio!!))
            }
        }
    }
}
