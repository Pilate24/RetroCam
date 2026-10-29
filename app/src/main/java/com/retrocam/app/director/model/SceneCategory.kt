package com.retrocam.app.director.model

/**
 * Detected scene categories based on on-device analysis.
 * If scene confidence is low, falls back safely to UNKNOWN_OR_MIXED.
 */
enum class SceneCategory(
    val title: String,
    val icon: String,
    val description: String
) {
    PORTRAIT(
        title = "Portrait",
        icon = "👤",
        description = "Single person subject"
    ),
    GROUP_PHOTO(
        title = "Group Photo",
        icon = "👥",
        description = "Multiple people in scene"
    ),
    LANDSCAPE(
        title = "Landscape & Nature",
        icon = "🏞️",
        description = "Expansive outdoor vista"
    ),
    ARCHITECTURE(
        title = "Architecture",
        icon = "🏛️",
        description = "Structures and perspective lines"
    ),
    FOOD(
        title = "Food & Still Life",
        icon = "🍽️",
        description = "Close-up culinary or table scene"
    ),
    PRODUCT(
        title = "Product & Object",
        icon = "📦",
        description = "Centered isolated object"
    ),
    STREET_TRAVEL(
        title = "Street & Travel",
        icon = "🏙️",
        description = "Dynamic urban or travel scene"
    ),
    SUNSET_SUNRISE(
        title = "Sunset / Sunrise",
        icon = "🌅",
        description = "Golden hour or high-contrast horizon"
    ),
    CLOSE_UP(
        title = "Close-Up & Macro",
        icon = "🔍",
        description = "Fine texture and detail"
    ),
    UNKNOWN_OR_MIXED(
        title = "General Scene",
        icon = "📷",
        description = "General composition guidance"
    )
}
