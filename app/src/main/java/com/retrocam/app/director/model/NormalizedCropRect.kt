package com.retrocam.app.director.model

/**
 * Normalized crop rectangle bounds in [0.0..1.0] coordinates.
 * Pure Kotlin data class with zero Android framework dependencies,
 * ensuring high performance and clean testability on JVM.
 */
data class NormalizedCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}
