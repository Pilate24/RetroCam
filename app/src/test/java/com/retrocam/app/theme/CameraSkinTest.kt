package com.retrocam.app.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSkinTest {

    @Test
    fun verifyAllFourSkinsExist() {
        val skins = CameraSkin.entries
        assertEquals("Expected exactly 4 skeuomorphic retro camera skins", 4, skins.size)

        val ids = skins.map { it.id }.toSet()
        assertTrue("Contains 1970s Classic", ids.contains("classic_1970s"))
        assertTrue("Contains 1990s Translucent Neon", ids.contains("translucent_neon_90s"))
        assertTrue("Contains Disposable Cardboard Wrap", ids.contains("disposable_cardboard"))
        assertTrue("Contains Golden Brass Rangefinder", ids.contains("golden_brass"))
    }

    @Test
    fun fromIdResolvesAccurately() {
        assertEquals(CameraSkin.CLASSIC_1970S, CameraSkin.fromId("classic_1970s"))
        assertEquals(CameraSkin.TRANSLUCENT_NEON_90S, CameraSkin.fromId("translucent_neon_90s"))
        assertEquals(CameraSkin.DISPOSABLE_CARDBOARD, CameraSkin.fromId("disposable_cardboard"))
        assertEquals(CameraSkin.GOLDEN_BRASS, CameraSkin.fromId("golden_brass"))

        // Case insensitivity
        assertEquals(CameraSkin.CLASSIC_1970S, CameraSkin.fromId("CLASSIC_1970S"))
        assertEquals(CameraSkin.TRANSLUCENT_NEON_90S, CameraSkin.fromId("Translucent_Neon_90s"))

        // Fallbacks
        assertEquals(CameraSkin.DEFAULT, CameraSkin.fromId(null))
        assertEquals(CameraSkin.DEFAULT, CameraSkin.fromId("unknown_skin_id"))
        assertEquals(CameraSkin.DEFAULT, CameraSkin.fromId(""))
    }

    @Test
    fun verifyDrawablesAndColorsConfigured() {
        for (skin in CameraSkin.entries) {
            assertNotNull(skin.displayName)
            assertTrue(skin.displayName.isNotBlank())
            assertTrue(skin.description.isNotBlank())
            assertTrue(skin.era.isNotBlank())

            assertNotEquals(0, skin.bodyBackgroundRes)
            assertNotEquals(0, skin.topScrimRes)
            assertNotEquals(0, skin.shutterButtonRes)
            assertNotEquals(0, skin.modePillSelectedRes)
            assertNotEquals(0, skin.modeSelectorBgRes)
            assertNotEquals(0, skin.readoutBgRes)
            assertNotEquals(0, skin.proChipBgRes)

            // Verify non-zero alpha in colors (high 8 bits non-zero)
            assertNotEquals(0, (skin.accentColor.toLong() and 0xFF000000L).toInt())
            assertNotEquals(0, (skin.sliderThumbColor.toLong() and 0xFF000000L).toInt())
            assertNotEquals(0, (skin.iconTint.toLong() and 0xFF000000L).toInt())
            assertNotEquals(0, (skin.selectedPillTextColor.toLong() and 0xFF000000L).toInt())
            assertNotEquals(0, (skin.unselectedPillTextColor.toLong() and 0xFF000000L).toInt())
            assertNotEquals(0, (skin.readoutTextColor.toLong() and 0xFF000000L).toInt())
        }
    }

    @Test
    fun verifyDistinctAesthetics() {
        val displayNames = CameraSkin.entries.map { it.displayName }.toSet()
        assertEquals("Each skin must have a unique display name", 4, displayNames.size)

        val accentColors = CameraSkin.entries.map { it.accentColor }.toSet()
        assertEquals("Each skin must have its unique aesthetic accent color", 4, accentColors.size)

        val shutterDrawables = CameraSkin.entries.map { it.shutterButtonRes }.toSet()
        assertEquals("Each skin must have its dedicated tactile shutter button drawable", 4, shutterDrawables.size)
    }
}
