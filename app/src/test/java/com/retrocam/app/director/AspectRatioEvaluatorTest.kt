package com.retrocam.app.director

import com.retrocam.app.director.analyzer.FaceBox
import com.retrocam.app.director.analyzer.FrameAnalysisResult
import com.retrocam.app.director.engine.AspectRatioEvaluator
import com.retrocam.app.director.model.AspectRatioOption
import com.retrocam.app.director.model.SceneCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AspectRatioEvaluatorTest {

    private fun createAnalysis(
        scene: SceneCategory,
        faces: List<FaceBox> = emptyList(),
        headroom: Float = 0.25f,
        skyDominance: Float = 0.1f,
        vertDensity: Float = 0.2f
    ): FrameAnalysisResult {
        return FrameAnalysisResult(
            sceneCategory = scene,
            confidence = 0.85f,
            detectedFaces = faces,
            isSinglePortrait = faces.size == 1,
            isGroupPhoto = faces.size >= 2,
            headroomFraction = headroom,
            faceCoverageFraction = faces.sumOf { it.area.toDouble() }.toFloat(),
            horizontalSpreadFraction = if (faces.size > 1) faces.maxOf { it.right } - faces.minOf { it.left } else 0f,
            meanLuminance = 120f,
            contrastStdDev = 50f,
            hasStrongHorizon = false,
            horizonPositionFraction = null,
            verticalEdgeDensity = vertDensity,
            horizontalEdgeDensity = 0.2f,
            skyHueDominance = skyDominance,
            natureHueDominance = 0.1f,
            warmSunsetDominance = 0.05f,
            isLowLight = false,
            isOverexposed = false,
            isBlurry = false
        )
    }

    @Test
    fun testAllSixSupportedAspectRatiosAreDefinedWithAccurateMath() {
        val options = AspectRatioOption.entries

        // Verify all 6 core ratios exist
        val ids = options.map { it.id }
        assertTrue(ids.contains("4:3"))
        assertTrue(ids.contains("3:2"))
        assertTrue(ids.contains("1:1"))
        assertTrue(ids.contains("4:5"))
        assertTrue(ids.contains("16:9"))
        assertTrue(ids.contains("9:16"))

        // Verify portrait aspect values
        assertEquals(0.75f, AspectRatioOption.RATIO_4_3.portraitRatio, 0.001f)
        assertEquals(0.80f, AspectRatioOption.RATIO_4_5.portraitRatio, 0.001f)
        assertEquals(1.00f, AspectRatioOption.RATIO_1_1.portraitRatio, 0.001f)
        assertEquals(9f / 16f, AspectRatioOption.RATIO_9_16.portraitRatio, 0.001f)
        assertEquals(2f / 3f, AspectRatioOption.RATIO_3_2.portraitRatio, 0.001f)
    }

    @Test
    fun testPortraitSceneWithComfortableHeadroomRecommendsFourFive() {
        val face = FaceBox(0.35f, 0.20f, 0.65f, 0.60f, 0.5f, 0.4f, 0.15f, 0.9f)
        val analysis = createAnalysis(SceneCategory.PORTRAIT, faces = listOf(face), headroom = 0.20f)

        val recommended = AspectRatioEvaluator.recommendAspectRatio(analysis, isUprightPortrait = true)
        assertEquals(AspectRatioOption.RATIO_4_5, recommended)
    }

    @Test
    fun testFoodStillLifeRecommendsOneToOneSquare() {
        val analysis = createAnalysis(SceneCategory.FOOD)
        val recommended = AspectRatioEvaluator.recommendAspectRatio(analysis, isUprightPortrait = true)
        assertEquals(AspectRatioOption.RATIO_1_1, recommended)
    }

    @Test
    fun testLandscapeRecommendsSixteenNineInLandscapeAndNineSixteenOrFourThreeInPortrait() {
        // Landscape orientation
        val landscapeAnalysis = createAnalysis(SceneCategory.LANDSCAPE)
        val recLandscape = AspectRatioEvaluator.recommendAspectRatio(landscapeAnalysis, isUprightPortrait = false)
        assertEquals(AspectRatioOption.RATIO_16_9, recLandscape)

        // Portrait orientation with expansive sky
        val skyAnalysis = createAnalysis(SceneCategory.LANDSCAPE, skyDominance = 0.45f)
        val recPortrait = AspectRatioEvaluator.recommendAspectRatio(skyAnalysis, isUprightPortrait = true)
        assertEquals(AspectRatioOption.RATIO_9_16, recPortrait)
    }

    @Test
    fun testCropRectCalculationsForFourThreeSource() {
        // Source is 720x960 portrait -> aspect = 0.75 (4:3 sensor in portrait)
        val sourceAspect = 0.75f

        // 1. Native 4:3 (portrait 3:4 = 0.75) -> full frame, no crop
        val rect43 = AspectRatioEvaluator.computeNormalizedCropRect(AspectRatioOption.RATIO_4_3, sourceAspect)
        assertEquals(0f, rect43.left, 0.001f)
        assertEquals(0f, rect43.top, 0.001f)
        assertEquals(1f, rect43.right, 0.001f)
        assertEquals(1f, rect43.bottom, 0.001f)

        // 2. 1:1 Square -> target aspect 1.0 > 0.75 -> letterbox top and bottom
        val rect11 = AspectRatioEvaluator.computeNormalizedCropRect(AspectRatioOption.RATIO_1_1, sourceAspect)
        assertEquals(0f, rect11.left, 0.001f)
        assertEquals(1f, rect11.right, 0.001f)
        // cropHeightFraction = 0.75 / 1.0 = 0.75 -> top = (1 - 0.75) / 2 = 0.125
        assertEquals(0.125f, rect11.top, 0.005f)
        assertEquals(0.875f, rect11.bottom, 0.005f)

        // 3. 9:16 Vertical -> target aspect 0.5625 < 0.75 -> pillarbox left and right
        val rect916 = AspectRatioEvaluator.computeNormalizedCropRect(AspectRatioOption.RATIO_9_16, sourceAspect)
        assertEquals(0f, rect916.top, 0.001f)
        assertEquals(1f, rect916.bottom, 0.001f)
        // cropWidthFraction = 0.5625 / 0.75 = 0.75 -> left = (1 - 0.75) / 2 = 0.125
        assertEquals(0.125f, rect916.left, 0.005f)
        assertEquals(0.875f, rect916.right, 0.005f)
    }

    @Test
    fun testSubjectClippingDetectsFacesCutOffByNarrowCrop() {
        val sourceAspect = 0.75f

        // Group with face near left edge (0.05) and face near right edge (0.95)
        val faceLeft = FaceBox(0.04f, 0.35f, 0.18f, 0.65f, 0.11f, 0.5f, 0.07f, 0.85f)
        val faceRight = FaceBox(0.82f, 0.35f, 0.96f, 0.65f, 0.89f, 0.5f, 0.07f, 0.85f)
        val faces = listOf(faceLeft, faceRight)

        // 9:16 crop has left=0.125 and right=0.875, so both faces will be clipped!
        val warning916 = AspectRatioEvaluator.checkSubjectClipping(AspectRatioOption.RATIO_9_16, faces, sourceAspect)
        assertNotNull("Should detect subject clipping on edges for 9:16 crop", warning916)
        assertTrue(warning916!!.contains("cuts off subjects on the edges"))

        // 4:3 native crop has left=0 and right=1, so faces are safe
        val warning43 = AspectRatioEvaluator.checkSubjectClipping(AspectRatioOption.RATIO_4_3, faces, sourceAspect)
        assertNull("Native 4:3 framing should not produce clipping warning", warning43)
    }

    @Test
    fun testSubjectClippingDetectsHeadroomCutOffBySquareCrop() {
        val sourceAspect = 0.75f

        // Face very high up near frame top (top = 0.03)
        val highFace = FaceBox(0.35f, 0.03f, 0.65f, 0.40f, 0.5f, 0.2f, 0.15f, 0.9f)
        val faces = listOf(highFace)

        // 1:1 crop top starts at 0.125, which cuts off the head!
        val warning11 = AspectRatioEvaluator.checkSubjectClipping(AspectRatioOption.RATIO_1_1, faces, sourceAspect)
        assertNotNull("Should detect top headroom clipping for 1:1 crop", warning11)
        assertTrue(warning11!!.contains("headroom"))
    }
}
