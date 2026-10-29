package com.retrocam.app.library

import com.retrocam.app.library.analyzer.DuplicateDetector
import com.retrocam.app.library.model.DuplicateType
import com.retrocam.app.library.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateDetectorTest {

    @Test
    fun testHammingDistance() {
        val hash1 = 0b11001100L
        val hash2 = 0b11001100L
        assertEquals(0, DuplicateDetector.hammingDistance(hash1, hash2))

        val hash3 = 0b11001111L // 2 bits different from hash1
        assertEquals(2, DuplicateDetector.hammingDistance(hash1, hash3))

        val hashAllZeros = 0L
        val hashAllOnes = -1L // 64 bits of 1
        assertEquals(64, DuplicateDetector.hammingDistance(hashAllZeros, hashAllOnes))
    }

    @Test
    fun testFindExactDuplicates() {
        val item1 = MediaItem(
            id = "1",
            displayName = "IMG_001.jpg",
            sizeBytes = 2048576L,
            contentHash = "d41d8cd98f00b204e9800998ecf8427e",
            dateTaken = 1000L
        )
        val item2 = MediaItem(
            id = "2",
            displayName = "IMG_001_copy.jpg",
            sizeBytes = 2048576L,
            contentHash = "d41d8cd98f00b204e9800998ecf8427e",
            dateTaken = 2000L
        )
        val item3 = MediaItem(
            id = "3",
            displayName = "IMG_002.jpg",
            sizeBytes = 1048576L,
            contentHash = "different_hash",
            dateTaken = 3000L
        )

        val groups = DuplicateDetector.findDuplicates(listOf(item1, item2, item3))
        assertEquals(1, groups.size)
        val group = groups[0]
        assertEquals(DuplicateType.EXACT_BYTE, group.type)
        assertEquals(2, group.items.size)
        assertEquals(item2.sizeBytes, group.reclaimableBytes)
    }

    @Test
    fun testFindBurstDuplicates() {
        val baseHash = 0x123456789ABCDEF0L
        val nearHash = 0x123456789ABCDEF1L // Hamming distance = 1

        val item1 = MediaItem(
            id = "1",
            displayName = "BURST_01.jpg",
            sizeBytes = 3000000L,
            perceptualHash = baseHash,
            dateTaken = 100000L
        )
        val item2 = MediaItem(
            id = "2",
            displayName = "BURST_02.jpg",
            sizeBytes = 3000000L,
            perceptualHash = nearHash,
            dateTaken = 102000L // 2 seconds later
        )

        val groups = DuplicateDetector.findDuplicates(listOf(item1, item2))
        assertEquals(1, groups.size)
        val group = groups[0]
        assertTrue(group.type == DuplicateType.BURST_SHOT || group.type == DuplicateType.VISUALLY_SIMILAR)
        assertEquals(2, group.items.size)
    }

    @Test
    fun testNonDuplicatesNotGrouped() {
        val item1 = MediaItem(
            id = "1",
            displayName = "A.jpg",
            sizeBytes = 1000L,
            contentHash = "hashA",
            perceptualHash = 0x0000000000000000L,
            dateTaken = 1000L
        )
        val item2 = MediaItem(
            id = "2",
            displayName = "B.jpg",
            sizeBytes = 2000L,
            contentHash = "hashB",
            perceptualHash = 0x1111111111111111L, // 16 bits different (>10)
            dateTaken = 2000L
        )

        val groups = DuplicateDetector.findDuplicates(listOf(item1, item2))
        assertEquals(0, groups.size)
    }
}
