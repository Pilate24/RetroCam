package com.retrocam.app.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LongExposureEngineTest {

    @Test
    fun testModeDefinitions() {
        val modes = LongExposureMode.entries
        assertEquals(4, modes.size)
        assertTrue(modes.any { it == LongExposureMode.LIGHT_TRAILS })
        assertTrue(modes.any { it == LongExposureMode.SILKY_WATER })
        assertTrue(modes.any { it == LongExposureMode.STAR_TRAILS })
        assertTrue(modes.any { it == LongExposureMode.TIMED_BULB })
    }

    @Test
    fun testSessionLifecycleAndProgress() {
        val engine = LongExposureEngine()
        assertFalse(engine.isExposing)

        // Start a 5s session
        engine.startExposure(LongExposureMode.LIGHT_TRAILS, 5.0f)
        assertTrue(engine.isExposing)
        assertEquals(LongExposureMode.LIGHT_TRAILS, engine.activeMode)
        assertEquals(5.0f, engine.targetDurationSeconds, 0.001f)
        assertEquals(0, engine.accumulatedFrameCount)

        // 4x4 mock pixels (all dark grey)
        val frame1 = IntArray(16) { 0xFF333333.toInt() }
        engine.accumulatePixels(frame1, 4, 4)
        assertEquals(1, engine.accumulatedFrameCount)

        // Frame 2 with bright light trail at index 5
        val frame2 = IntArray(16) { 0xFF333333.toInt() }
        frame2[5] = 0xFFFFFFFF.toInt()
        engine.accumulatePixels(frame2, 4, 4)
        assertEquals(2, engine.accumulatedFrameCount)

        // Finish exposure
        val finalPixels = engine.finishExposurePixels()
        assertFalse(engine.isExposing)
        assertNotNull(finalPixels)
        assertEquals(16, finalPixels.size)
        // Verify light trail at index 5 was preserved
        assertEquals(0xFFFFFFFF.toInt(), finalPixels[5])
    }

    @Test
    fun testBulbMode() {
        val engine = LongExposureEngine()
        // 0s duration represents manual BULB mode
        engine.startExposure(LongExposureMode.TIMED_BULB, 0f)
        assertTrue(engine.isExposing)

        val frame = IntArray(4) { 0xFF444444.toInt() }
        engine.accumulatePixels(frame, 2, 2)
        assertEquals(1, engine.accumulatedFrameCount)

        engine.cancelExposure()
        assertFalse(engine.isExposing)
    }

    @Test
    fun testSilkyWaterAccumulation() {
        val engine = LongExposureEngine()
        engine.startExposure(LongExposureMode.SILKY_WATER, 3.0f)

        // Frame 1: brightness 100
        val frame1 = IntArray(4) { (0xFF shl 24) or (100 shl 16) or (100 shl 8) or 100 }
        // Frame 2: brightness 200
        val frame2 = IntArray(4) { (0xFF shl 24) or (200 shl 16) or (200 shl 8) or 200 }

        engine.accumulatePixels(frame1, 2, 2)
        engine.accumulatePixels(frame2, 2, 2)

        assertEquals(2, engine.accumulatedFrameCount)
        val result = engine.finishExposurePixels()
        assertNotNull(result)
        assertEquals(4, result.size)

        // Average of 100 and 200 is 150
        val r = (result[0] shr 16) and 0xFF
        val g = (result[0] shr 8) and 0xFF
        val b = result[0] and 0xFF
        assertEquals(150, r)
        assertEquals(150, g)
        assertEquals(150, b)
    }
}
