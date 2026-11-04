package com.retrocam.app.booth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmStripGeneratorTest {

    @Test
    fun stripThemes_haveDistinctColors() {
        val themes = FilmStripGenerator.StripTheme.entries
        assertEquals(5, themes.size)

        val bgColors = themes.map { it.backgroundColor }.toSet()
        assertEquals(5, bgColors.size)
    }

    @Test
    fun calculateTotalHeight_correctFor4Photos() {
        val height = FilmStripGenerator.calculateTotalHeight(4)
        // 160 + 4 * 720 (2880) + 3 * 36 (108) + 280 = 3428 px
        assertEquals(3428, height)
        assertTrue(height > FilmStripGenerator.STRIP_WIDTH * 2) // Confirms vertical photobooth strip ratio (1:2.85)
    }

    @Test
    fun calculateTotalHeight_correctForPartialCounts() {
        val height1 = FilmStripGenerator.calculateTotalHeight(1)
        // 160 + 720 + 0 + 280 = 1160 px
        assertEquals(1160, height1)

        val height2 = FilmStripGenerator.calculateTotalHeight(2)
        // 160 + 1440 + 36 + 280 = 1916 px
        assertEquals(1916, height2)

        val height3 = FilmStripGenerator.calculateTotalHeight(3)
        // 160 + 2160 + 72 + 280 = 2672 px
        assertEquals(2672, height3)
    }

    @Test
    fun stripOptions_defaultsAreValid() {
        val options = FilmStripGenerator.StripOptions()
        assertEquals(FilmStripGenerator.StripTheme.CLASSIC_WHITE, options.theme)
        assertTrue(options.showDate)
        assertTrue(options.showBarcode)
        assertEquals("Retro", options.presetName)
    }
}
