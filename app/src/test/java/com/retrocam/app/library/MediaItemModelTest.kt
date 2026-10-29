package com.retrocam.app.library

import com.retrocam.app.library.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaItemModelTest {

    @Test
    fun testDurationFormatted() {
        val video1 = MediaItem(
            id = "v1",
            displayName = "vid.mp4",
            isVideo = true,
            durationMs = 65000L // 1 min 5 sec
        )
        assertEquals("01:05", video1.durationFormatted)

        val videoLong = MediaItem(
            id = "v2",
            displayName = "long.mp4",
            isVideo = true,
            durationMs = 3665000L // 1 hour 1 min 5 sec
        )
        assertEquals("1:01:05", videoLong.durationFormatted)

        val photo = MediaItem(
            id = "p1",
            displayName = "pic.jpg",
            isVideo = false,
            durationMs = 50000L
        )
        assertEquals("", photo.durationFormatted)
    }

    @Test
    fun testFileSizeFormatted() {
        val small = MediaItem(
            id = "1",
            displayName = "small.jpg",
            sizeBytes = 512 * 1024L
        )
        assertEquals("512 KB", small.fileSizeFormatted)

        val medium = MediaItem(
            id = "2",
            displayName = "med.jpg",
            sizeBytes = 4500000L
        )
        assertEquals("4.3 MB", medium.fileSizeFormatted)

        val large = MediaItem(
            id = "3",
            displayName = "large.mp4",
            isVideo = true,
            sizeBytes = 2500000000L
        )
        assertEquals("2.33 GB", large.fileSizeFormatted)
    }

    @Test
    fun testResolutionFormatted() {
        val item = MediaItem(
            id = "1",
            displayName = "shot.jpg",
            width = 3840,
            height = 2160
        )
        assertEquals("3840 × 2160", item.resolutionFormatted)
    }
}
