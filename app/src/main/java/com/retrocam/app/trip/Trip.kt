package com.retrocam.app.trip

import android.net.Uri

/**
 * Represents a user-created travel trip.
 */
data class Trip(
    val id: String,
    val name: String,
    val destination: String,
    val startDate: Long,
    val endDate: Long? = null,
    val coverUri: String? = null,
    val mediaCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Represents a photo or video associated with a trip.
 */
data class TripMedia(
    val id: String,
    val tripId: String,
    val uriString: String,
    val displayName: String,
    val isVideo: Boolean = false,
    val durationMs: Long = 0L,
    val dateTaken: Long = System.currentTimeMillis(),
    val contributorName: String? = null
) {
    val uri: Uri
        get() = Uri.parse(uriString)
}
