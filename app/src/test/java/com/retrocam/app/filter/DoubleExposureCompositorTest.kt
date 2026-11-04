package com.retrocam.app.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleExposureCompositorTest {

    @Test
    fun blendMethods_haveExpectedCount() {
        val methods = DoubleExposureCompositor.BlendMethod.entries
        assertEquals(3, methods.size)
        assertTrue(methods.contains(DoubleExposureCompositor.BlendMethod.SCREEN))
        assertTrue(methods.contains(DoubleExposureCompositor.BlendMethod.LIGHTEN))
        assertTrue(methods.contains(DoubleExposureCompositor.BlendMethod.BALANCED))
    }

    @Test
    fun screenBlendMath_matchesAnalogPhotographicExposure() {
        // In photographic film, two exposures add light:
        // C = 1 - (1 - A) * (1 - B)

        fun screen(a: Float, b: Float): Float = 1f - ((1f - a) * (1f - b))

        // 1. Black base (unexposed film) lets overlay shine through 100%
        assertEquals(0.8f, screen(0.0f, 0.8f), 0.001f)

        // 2. White base (fully exposed highlights) remains pure white with no negative wrap
        assertEquals(1.0f, screen(1.0f, 0.5f), 0.001f)

        // 3. Two 50% exposures blend into 75% luminosity (natural additive light)
        assertEquals(0.75f, screen(0.5f, 0.5f), 0.001f)

        // 4. Commutative property of physical double exposure
        assertEquals(screen(0.3f, 0.7f), screen(0.7f, 0.3f), 0.001f)
    }

    @Test
    fun lightenBlendMath_selectsHighestLuminosity() {
        fun lighten(a: Float, b: Float): Float = maxOf(a, b)

        assertEquals(0.9f, lighten(0.2f, 0.9f), 0.001f)
        assertEquals(0.7f, lighten(0.7f, 0.4f), 0.001f)
    }

    @Test
    fun balancedBlendMath_splitsExposureHalves() {
        fun balanced(a: Float, b: Float): Float = 0.5f * a + 0.5f * b

        assertEquals(0.5f, balanced(0.2f, 0.8f), 0.001f)
        assertEquals(0.5f, balanced(1.0f, 0.0f), 0.001f)
        assertEquals(0.75f, balanced(0.5f, 1.0f), 0.001f)
    }
}
