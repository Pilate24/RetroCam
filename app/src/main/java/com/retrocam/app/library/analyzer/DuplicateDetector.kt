package com.retrocam.app.library.analyzer

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import com.retrocam.app.library.model.DuplicateGroup
import com.retrocam.app.library.model.DuplicateType
import com.retrocam.app.library.model.MediaItem
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.abs

object DuplicateDetector {

    private const val TAG = "DuplicateDetector"
    const val SIMILARITY_HAMMING_THRESHOLD = 10
    const val BURST_TIME_WINDOW_MS = 60_000L // 1 minute

    /**
     * Computes a fast content hash for exact byte matching.
     * Uses MD5 over the file header, middle, and tail (or entire file if small), plus size.
     */
    fun computeContentHash(resolver: ContentResolver, uri: Uri, sizeBytes: Long): String? {
        if (sizeBytes <= 0L) return null
        return try {
            val digest = MessageDigest.getInstance("MD5")
            resolver.openInputStream(uri)?.use { stream: InputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L
                val maxBytesToHash = 1024 * 1024 * 4L // Hash up to 4MB

                while (stream.read(buffer).also { bytesRead = it } != -1 && totalRead < maxBytesToHash) {
                    digest.update(buffer, 0, bytesRead)
                    totalRead += bytesRead
                }
                // Include exact size bytes in digest
                digest.update(sizeBytes.toString().toByteArray())
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to compute content hash for uri $uri", e)
            null
        }
    }

    /**
     * Computes 64-bit difference hash (dHash) from a Bitmap.
     * Downscales to 9x8, converts to grayscale, and compares horizontally adjacent pixels.
     */
    fun computeDifferenceHash(bitmap: Bitmap): Long {
        val scaled = if (bitmap.width == 9 && bitmap.height == 8) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        }

        var hash = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val pixelLeft = scaled.getPixel(x, y)
                val pixelRight = scaled.getPixel(x + 1, y)

                val lumLeft = (Color.red(pixelLeft) * 299 + Color.green(pixelLeft) * 587 + Color.blue(pixelLeft) * 114) / 1000
                val lumRight = (Color.red(pixelRight) * 299 + Color.green(pixelRight) * 587 + Color.blue(pixelRight) * 114) / 1000

                if (lumLeft > lumRight) {
                    hash = hash or (1L shl (y * 8 + x))
                }
            }
        }

        if (scaled !== bitmap) {
            scaled.recycle()
        }

        return hash
    }

    /**
     * Calculates the Hamming distance between two 64-bit perceptual hashes.
     * Returns a value between 0 (identical) and 64 (completely opposite).
     */
    fun hammingDistance(hash1: Long, hash2: Long): Int {
        return java.lang.Long.bitCount(hash1 xor hash2)
    }

    /**
     * Finds and groups exact duplicates and visually similar photos from a list of media items.
     */
    fun findDuplicates(items: List<MediaItem>): List<DuplicateGroup> {
        val groups = mutableListOf<DuplicateGroup>()
        val processedIds = mutableSetOf<String>()

        // 1. Exact byte duplicates (same contentHash and same sizeBytes)
        val hashGroups = items.filter { !it.contentHash.isNullOrEmpty() && it.sizeBytes > 0 }
            .groupBy { "${it.sizeBytes}_${it.contentHash}" }

        for ((key, matchingItems) in hashGroups) {
            if (matchingItems.size > 1) {
                val groupItems = matchingItems.sortedBy { it.dateTaken }
                val keepItem = groupItems.first()
                val duplicates = groupItems.drop(1)
                val reclaimable = duplicates.sumOf { it.sizeBytes }

                groups.add(
                    DuplicateGroup(
                        groupId = "exact_$key",
                        type = DuplicateType.EXACT_BYTE,
                        reason = "Exact duplicate (${matchingItems.size} identical files)",
                        items = groupItems,
                        reclaimableBytes = reclaimable
                    )
                )
                processedIds.addAll(matchingItems.map { it.id })
            }
        }

        // 2. Visually similar / burst photos (photos with valid perceptualHash)
        val photosWithHash = items.filter {
            !it.isVideo && it.perceptualHash != null && !processedIds.contains(it.id)
        }

        val visitedSimilar = mutableSetOf<String>()

        for (i in photosWithHash.indices) {
            val base = photosWithHash[i]
            if (visitedSimilar.contains(base.id)) continue

            val similarMatches = mutableListOf(base)

            for (j in (i + 1) until photosWithHash.size) {
                val candidate = photosWithHash[j]
                if (visitedSimilar.contains(candidate.id)) continue

                val distance = hammingDistance(base.perceptualHash!!, candidate.perceptualHash!!)
                val timeDiff = abs(base.dateTaken - candidate.dateTaken)

                if (distance <= SIMILARITY_HAMMING_THRESHOLD) {
                    similarMatches.add(candidate)
                    visitedSimilar.add(candidate.id)
                } else if (distance <= 14 && timeDiff in 0..BURST_TIME_WINDOW_MS) {
                    similarMatches.add(candidate)
                    visitedSimilar.add(candidate.id)
                }
            }

            if (similarMatches.size > 1) {
                visitedSimilar.add(base.id)
                val sorted = similarMatches.sortedBy { it.dateTaken }
                val isBurst = sorted.zipWithNext().all { (a, b) ->
                    abs(a.dateTaken - b.dateTaken) <= BURST_TIME_WINDOW_MS
                }

                val reclaimable = sorted.drop(1).sumOf { it.sizeBytes }
                val reason = if (isBurst) {
                    "Burst shot (${sorted.size} photos captured seconds apart)"
                } else {
                    "Visually similar photos (${sorted.size} photos)"
                }

                groups.add(
                    DuplicateGroup(
                        groupId = "similar_${base.id}",
                        type = if (isBurst) DuplicateType.BURST_SHOT else DuplicateType.VISUALLY_SIMILAR,
                        reason = reason,
                        items = sorted,
                        reclaimableBytes = reclaimable
                    )
                )
            }
        }

        return groups
    }
}
