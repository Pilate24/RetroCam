package com.retrocam.app.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistogramAnalyzerTest {

    @Test
    fun testHistogram256Bins() {
        val pixels = IntArray(64) { 0xFF888888.toInt() }
        val data = HistogramAnalyzer.analyze(pixels, 8, 8)

        assertNotNull(data)
        assertEquals(256, data.redBins.size)
        assertEquals(256, data.greenBins.size)
        assertEquals(256, data.blueBins.size)
        assertEquals(256, data.lumaBins.size)
    }

    @Test
    fun testHighlightClippingDetection() {
        val width = 16
        val height = 16
        val whitePixels = IntArray(width * height) { 0xFFFFFFFF.toInt() }

        val data = HistogramAnalyzer.analyze(whitePixels, width, height)
        assertTrue("All-white frame must trigger highlight clipping", data.hasHighlightClipping)
        assertFalse("All-white frame should not trigger shadow clipping", data.hasShadowClipping)
        assertEquals(255f, data.averageLuminance, 1.0f)
    }

    @Test
    fun testShadowClippingDetection() {
        val width = 16
        val height = 16
        val blackPixels = IntArray(width * height) { 0xFF000000.toInt() }

        val data = HistogramAnalyzer.analyze(blackPixels, width, height)
        assertTrue("All-black frame must trigger shadow clipping", data.hasShadowClipping)
        assertFalse("All-black frame should not trigger highlight clipping", data.hasHighlightClipping)
        assertEquals(0f, data.averageLuminance, 1.0f)
    }
}
