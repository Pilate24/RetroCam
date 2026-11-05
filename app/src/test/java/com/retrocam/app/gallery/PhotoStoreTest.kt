package com.retrocam.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoStoreTest {

    @Test
    fun testPhotoFolderClassification() {
        val standardPhoto = Photo(
            id = 1L,
            name = "RetroCam_kodak_gold_20261105_120000.jpg",
            isVideo = false
        )
        assertEquals(GalleryFolder.PHOTOS, standardPhoto.folder)

        val video = Photo(
            id = 2L,
            name = "RetroCam_Video_20261105_120100.mp4",
            isVideo = true,
            durationMs = 5000L
        )
        assertEquals(GalleryFolder.VIDEOS, video.folder)

        val longExp = Photo(
            id = 3L,
            name = "RetroCam_LongExp_cinestill_20261105_120200.jpg",
            isVideo = false
        )
        assertEquals(GalleryFolder.LONG_EXP, longExp.folder)

        val booth = Photo(
            id = 4L,
            name = "RetroCam_Booth_20261105_120300.jpg",
            isVideo = false
        )
        assertEquals(GalleryFolder.PHOTOBOOTH, booth.folder)

        val raw = Photo(
            id = 5L,
            name = "RetroCam_RAW_tri_x_20261105_120400.dng",
            isVideo = false
        )
        assertEquals(GalleryFolder.RAW, raw.folder)

        val remastered = Photo(
            id = 6L,
            name = "Remastered_20261105_120500.jpg",
            isVideo = false
        )
        assertEquals(GalleryFolder.REMASTERED, remastered.folder)
    }

    @Test
    fun testFolderMatching() {
        val photo = Photo(id = 1L, name = "RetroCam_kodak.jpg", isVideo = false)
        val video = Photo(id = 2L, name = "RetroCam_vid.mp4", isVideo = true)
        val favPhoto = Photo(id = 3L, name = "RetroCam_portra.jpg", isVideo = false, isFavorite = true)
        val longExp = Photo(id = 4L, name = "RetroCam_LongExp_fuji.jpg", isVideo = false)

        // ALL matches everything
        assertTrue(photo.matchesFolder(GalleryFolder.ALL))
        assertTrue(video.matchesFolder(GalleryFolder.ALL))
        assertTrue(favPhoto.matchesFolder(GalleryFolder.ALL))
        assertTrue(longExp.matchesFolder(GalleryFolder.ALL))

        // PHOTOS matches only standard photos
        assertTrue(photo.matchesFolder(GalleryFolder.PHOTOS))
        assertFalse(video.matchesFolder(GalleryFolder.PHOTOS))
        assertTrue(favPhoto.matchesFolder(GalleryFolder.PHOTOS))
        assertFalse(longExp.matchesFolder(GalleryFolder.PHOTOS))

        // VIDEOS matches only video
        assertFalse(photo.matchesFolder(GalleryFolder.VIDEOS))
        assertTrue(video.matchesFolder(GalleryFolder.VIDEOS))

        // LONG_EXP matches only long exposure
        assertTrue(longExp.matchesFolder(GalleryFolder.LONG_EXP))
        assertFalse(photo.matchesFolder(GalleryFolder.LONG_EXP))

        // FAVORITES matches items where isFavorite = true
        assertTrue(favPhoto.matchesFolder(GalleryFolder.FAVORITES))
        assertFalse(photo.matchesFolder(GalleryFolder.FAVORITES))
    }

    @Test
    fun testPhotoFilterAndCounts() {
        val items = listOf(
            Photo(1L, name = "RetroCam_kodak_1.jpg", isVideo = false),
            Photo(2L, name = "RetroCam_kodak_2.jpg", isVideo = false, isFavorite = true),
            Photo(3L, name = "RetroCam_Video_1.mp4", isVideo = true, durationMs = 12000L),
            Photo(4L, name = "RetroCam_LongExp_1.jpg", isVideo = false),
            Photo(5L, name = "RetroCam_Booth_1.jpg", isVideo = false),
            Photo(6L, name = "RetroCam_RAW_1.dng", isVideo = false),
            Photo(7L, name = "Remastered_1.jpg", isVideo = false, isFavorite = true)
        )

        val counts = PhotoStore.getFolderCounts(items)
        assertEquals(7, counts[GalleryFolder.ALL])
        assertEquals(2, counts[GalleryFolder.PHOTOS])
        assertEquals(1, counts[GalleryFolder.VIDEOS])
        assertEquals(1, counts[GalleryFolder.LONG_EXP])
        assertEquals(1, counts[GalleryFolder.PHOTOBOOTH])
        assertEquals(1, counts[GalleryFolder.RAW])
        assertEquals(1, counts[GalleryFolder.REMASTERED])
        assertEquals(2, counts[GalleryFolder.FAVORITES])

        val photosOnly = PhotoStore.filter(items, GalleryFolder.PHOTOS)
        assertEquals(2, photosOnly.size)

        val favoritesOnly = PhotoStore.filter(items, GalleryFolder.FAVORITES)
        assertEquals(2, favoritesOnly.size)
        assertTrue(favoritesOnly.all { it.isFavorite })

        val longExpOnly = PhotoStore.filter(items, GalleryFolder.LONG_EXP)
        assertEquals(1, longExpOnly.size)
        assertEquals(4L, longExpOnly[0].id)
    }
}
