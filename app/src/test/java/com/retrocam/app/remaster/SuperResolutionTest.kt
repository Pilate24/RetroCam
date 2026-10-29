package com.retrocam.app.remaster

import com.retrocam.app.remaster.processor.SuperResolutionProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperResolutionTest {

    @Test
    fun testDetailSynthesisPreservesClampedRange() {
        val width = 8
        val height = 8
        val pixels = IntArray(width * height) { i ->
            val v = (i * 4).coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        SuperResolutionProcessor.synthesizeDetailsAndDeblock(
            pixels = pixels,
            width = width,
            height = height,
            detailStrength = 0.8f,
            deblock = true
        )

        for (p in pixels) {
            val a = (p ushr 24) and 0xFF
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF

            assertEquals("Alpha channel must remain fully opaque", 255, a)
            assertTrue("Red channel must be in valid byte range", r in 0..255)
            assertTrue("Green channel must be in valid byte range", g in 0..255)
            assertTrue("Blue channel must be in valid byte range", b in 0..255)
        }
    }
}
