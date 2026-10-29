package com.retrocam.app.trip

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripModelTest {

    @Test
    fun testTripCreationAndDefaults() {
        val trip = Trip(
            id = "trip-1",
            name = "Tokyo Autumn",
            destination = "Tokyo, Japan",
            startDate = 1729000000000L,
            endDate = 1729500000000L
        )

        assertEquals("trip-1", trip.id)
        assertEquals("Tokyo Autumn", trip.name)
        assertEquals("Tokyo, Japan", trip.destination)
        assertEquals(1729000000000L, trip.startDate)
        assertEquals(1729500000000L, trip.endDate)
        assertNull(trip.coverUri)
        assertEquals(0, trip.mediaCount)
    }

    @Test
    fun testTripMediaProperties() {
        val media = TripMedia(
            id = "media-1",
            tripId = "trip-1",
            uriString = "content://media/external/images/media/42",
            displayName = "RetroCam_kodak_gold.jpg",
            isVideo = false,
            durationMs = 0L,
            dateTaken = 1729100000000L,
            contributorName = "Alice"
        )

        assertEquals("media-1", media.id)
        assertEquals("trip-1", media.tripId)
        assertEquals("content://media/external/images/media/42", media.uriString)
        assertEquals("RetroCam_kodak_gold.jpg", media.displayName)
        assertFalse(media.isVideo)
        assertEquals("Alice", media.contributorName)
    }

    @Test
    fun testTripMediaChronologicalOrdering() {
        val media1 = TripMedia("1", "trip-1", "uri1", "first.jpg", false, 0L, 1000L)
        val media2 = TripMedia("2", "trip-1", "uri2", "second.jpg", false, 0L, 2000L)
        val media3 = TripMedia("3", "trip-1", "uri3", "third.mp4", true, 5000L, 3000L)

        val unsortedList = listOf(media3, media1, media2)
        val sortedList = unsortedList.sortedBy { it.dateTaken }

        assertEquals("1", sortedList[0].id)
        assertEquals("2", sortedList[1].id)
        assertEquals("3", sortedList[2].id)
        assertTrue(sortedList[2].isVideo)
    }

    @Test
    fun testTripMediaIsolation() {
        val mediaTripA = listOf(
            TripMedia("1", "trip-A", "uri1", "picA1.jpg", false, 0L, 1000L),
            TripMedia("2", "trip-A", "uri2", "picA2.jpg", false, 0L, 2000L)
        )
        val mediaTripB = listOf(
            TripMedia("3", "trip-B", "uri3", "picB1.jpg", true, 4000L, 3000L)
        )

        val allMedia = mediaTripA + mediaTripB

        val filteredA = allMedia.filter { it.tripId == "trip-A" }
        val filteredB = allMedia.filter { it.tripId == "trip-B" }

        assertEquals(2, filteredA.size)
        assertEquals(1, filteredB.size)
        assertFalse(filteredA.any { it.tripId == "trip-B" })
        assertFalse(filteredB.any { it.tripId == "trip-A" })
    }
}
