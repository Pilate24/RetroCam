package com.retrocam.app.theme

import androidx.annotation.DrawableRes
import com.retrocam.app.R

/**
 * Skeuomorphic Retro Camera Body Themes.
 *
 * Customizes the physical look and tactile feel of the camera UI across four iconic retro eras:
 * 1. 1970s Classic: Matte black leatherette with brushed chrome metal knobs & ruby shutter dot.
 * 2. 1990s Translucent Neon: Transparent colorful plastic casing (iMac G3 / Game Boy Color).
 * 3. Disposable Cardboard Wrap: Graphic printed cardboard box look with bright yellow/red accents.
 * 4. Golden Brass Rangefinder: Vintage Leica-inspired brass body with etched dials & antique patina.
 */
enum class CameraSkin(
    val id: String,
    val displayName: String,
    val description: String,
    val era: String,
    @get:DrawableRes val bodyBackgroundRes: Int,
    @get:DrawableRes val topScrimRes: Int,
    @get:DrawableRes val shutterButtonRes: Int,
    @get:DrawableRes val modePillSelectedRes: Int,
    @get:DrawableRes val modeSelectorBgRes: Int,
    @get:DrawableRes val readoutBgRes: Int,
    @get:DrawableRes val proChipBgRes: Int,
    val accentColor: Int,
    val sliderThumbColor: Int,
    val sliderTrackActiveColor: Int,
    val sliderTrackInactiveColor: Int,
    val iconTint: Int,
    val selectedPillTextColor: Int,
    val unselectedPillTextColor: Int,
    val readoutTextColor: Int,
    val chipTextColor: Int,
    val previewPrimaryColor: Int,
    val previewAccentColor: Int
) {
    CLASSIC_1970S(
        id = "classic_1970s",
        displayName = "1970s Classic",
        description = "Matte black leatherette with brushed chrome metal knobs and ruby soft-release.",
        era = "1970s SLR",
        bodyBackgroundRes = R.drawable.panel_bg_classic,
        topScrimRes = R.drawable.scrim_top_classic,
        shutterButtonRes = R.drawable.shutter_button_classic,
        modePillSelectedRes = R.drawable.mode_pill_classic,
        modeSelectorBgRes = R.drawable.zoom_pill_classic,
        readoutBgRes = R.drawable.readout_bg_classic,
        proChipBgRes = R.drawable.pro_chip_bg_classic,
        accentColor = 0xFFE8A33D.toInt(),             // Warm Amber
        sliderThumbColor = 0xFFE8A33D.toInt(),
        sliderTrackActiveColor = 0xFFE8A33D.toInt(),
        sliderTrackInactiveColor = 0x33E8A33D.toInt(),
        iconTint = 0xFFF3F4F6.toInt(),                 // Brushed silver
        selectedPillTextColor = 0xFF111827.toInt(),    // Crisp mechanical black
        unselectedPillTextColor = 0x8CF2E4CE.toInt(),  // Milled silver-warm
        readoutTextColor = 0xFFFFF2E4.toInt(),
        chipTextColor = 0xFFF3F4F6.toInt(),
        previewPrimaryColor = 0xFF18181A.toInt(),
        previewAccentColor = 0xFFE5E7EB.toInt()
    ),

    TRANSLUCENT_NEON_90S(
        id = "translucent_neon_90s",
        displayName = "1990s Translucent Neon",
        description = "Transparent colorful plastic casing (like the iMac G3 or Game Boy Color).",
        era = "1990s Cyber",
        bodyBackgroundRes = R.drawable.panel_bg_neon,
        topScrimRes = R.drawable.scrim_top_neon,
        shutterButtonRes = R.drawable.shutter_button_neon,
        modePillSelectedRes = R.drawable.mode_pill_neon,
        modeSelectorBgRes = R.drawable.zoom_pill_neon,
        readoutBgRes = R.drawable.readout_bg_neon,
        proChipBgRes = R.drawable.pro_chip_bg_neon,
        accentColor = 0xFF00E5FF.toInt(),             // Neon Electric Cyan
        sliderThumbColor = 0xFF00E5FF.toInt(),
        sliderTrackActiveColor = 0xFF00E5FF.toInt(),
        sliderTrackInactiveColor = 0x4000E5FF.toInt(),
        iconTint = 0xFF00E5FF.toInt(),                 // Electric Cyan
        selectedPillTextColor = 0xFF00363A.toInt(),    // Deep Dark Atomic Teal
        unselectedPillTextColor = 0x9980DEEA.toInt(),  // Translucent Electric Aqua
        readoutTextColor = 0xFF00E5FF.toInt(),
        chipTextColor = 0xFF80DEEA.toInt(),
        previewPrimaryColor = 0xFF004D5A.toInt(),
        previewAccentColor = 0xFFFF007F.toInt()
    ),

    DISPOSABLE_CARDBOARD(
        id = "disposable_cardboard",
        displayName = "Disposable Cardboard Wrap",
        description = "Graphic printed cardboard box look with bright yellow and red packaging accents.",
        era = "Single-Use Box",
        bodyBackgroundRes = R.drawable.panel_bg_cardboard,
        topScrimRes = R.drawable.scrim_top_cardboard,
        shutterButtonRes = R.drawable.shutter_button_cardboard,
        modePillSelectedRes = R.drawable.mode_pill_cardboard,
        modeSelectorBgRes = R.drawable.zoom_pill_cardboard,
        readoutBgRes = R.drawable.readout_bg_cardboard,
        proChipBgRes = R.drawable.pro_chip_bg_cardboard,
        accentColor = 0xFFDC2626.toInt(),             // Packaging Racing Red
        sliderThumbColor = 0xFFDC2626.toInt(),
        sliderTrackActiveColor = 0xFFDC2626.toInt(),
        sliderTrackInactiveColor = 0x40DC2626.toInt(),
        iconTint = 0xFFFFFFFF.toInt(),                 // Packaging White
        selectedPillTextColor = 0xFFFFFFFF.toInt(),    // Crisp Printed White
        unselectedPillTextColor = 0xCCFDE68A.toInt(),  // Cardboard Amber-Yellow
        readoutTextColor = 0xFFFDE68A.toInt(),
        chipTextColor = 0xFFFDE68A.toInt(),
        previewPrimaryColor = 0xFFF59E0B.toInt(),
        previewAccentColor = 0xFFDC2626.toInt()
    ),

    GOLDEN_BRASS(
        id = "golden_brass",
        displayName = "Golden Brass Rangefinder",
        description = "Vintage Leica-inspired brass body with etched dials, aged patina and knurled controls.",
        era = "1930s-1950s Rangefinder",
        bodyBackgroundRes = R.drawable.panel_bg_brass,
        topScrimRes = R.drawable.scrim_top_brass,
        shutterButtonRes = R.drawable.shutter_button_brass,
        modePillSelectedRes = R.drawable.mode_pill_brass,
        modeSelectorBgRes = R.drawable.zoom_pill_brass,
        readoutBgRes = R.drawable.readout_bg_brass,
        proChipBgRes = R.drawable.pro_chip_bg_brass,
        accentColor = 0xFFD4AF37.toInt(),             // Polished Golden Brass
        sliderThumbColor = 0xFFD4AF37.toInt(),
        sliderTrackActiveColor = 0xFFD4AF37.toInt(),
        sliderTrackInactiveColor = 0x40D4AF37.toInt(),
        iconTint = 0xFFFEF3C7.toInt(),                 // Antique Ivory
        selectedPillTextColor = 0xFF1C1004.toInt(),    // Deep Antique Sepia Enamel
        unselectedPillTextColor = 0x99FEF3C7.toInt(),  // Burnished Ivory
        readoutTextColor = 0xFFFEF3C7.toInt(),
        chipTextColor = 0xFFFEF3C7.toInt(),
        previewPrimaryColor = 0xFF855718.toInt(),
        previewAccentColor = 0xFFD4AF37.toInt()
    );

    companion object {
        val DEFAULT = CLASSIC_1970S

        fun fromId(id: String?): CameraSkin {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
        }
    }
}
