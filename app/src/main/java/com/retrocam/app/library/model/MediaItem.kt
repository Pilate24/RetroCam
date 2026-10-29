package com.retrocam.app.library.model

import android.net.Uri

/**
 * Core model representing an indexed photo or video in the Smart Media Library.
 */
data class MediaItem(
    val id: String,
    val uri: Uri? = null,
    val displayName: String,
    val isVideo: Boolean = false,
    val mimeType: String = if (isVideo) "video/mp4" else "image/jpeg",
    val dateTaken: Long = 0L,
    val dateAdded: Long = 0L,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    val isFavorite: Boolean = false,
    val isEdited: Boolean = false,
    val originalMediaId: String? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val focalLength: Float? = null,
    val iso: Int? = null,
    val exposureTime: Float? = null,
    val aperture: Float? = null,
    val relativePath: String? = null,
    val contentHash: String? = null,
    val perceptualHash: Long? = null,
    val lastIndexed: Long = System.currentTimeMillis()
) {
    val durationFormatted: String
        get() {
            if (!isVideo || durationMs <= 0) return ""
            val totalSec = (durationMs / 1000).coerceAtLeast(0)
            val min = totalSec / 60
            val sec = totalSec % 60
            val hrs = min / 60
            return if (hrs > 0) {
                String.format(java.util.Locale.US, "%d:%02d:%02d", hrs, min % 60, sec)
            } else {
                String.format(java.util.Locale.US, "%02d:%02d", min, sec)
            }
        }

    val fileSizeFormatted: String
        get() {
            if (sizeBytes <= 0) return ""
            val kb = sizeBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(java.util.Locale.US, "%.2f GB", gb)
                mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
                else -> String.format(java.util.Locale.US, "%.0f KB", kb)
            }
        }

    val resolutionFormatted: String
        get() {
            return if (width > 0 && height > 0) {
                "${width} × ${height}"
            } else {
                ""
            }
        }
}
