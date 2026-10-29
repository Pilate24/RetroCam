package com.retrocam.app.library.model

data class StorageStats(
    val totalCount: Int,
    val photoCount: Int,
    val videoCount: Int,
    val editedCount: Int,
    val totalSizeBytes: Long,
    val photoSizeBytes: Long,
    val videoSizeBytes: Long,
    val largestVideos: List<MediaItem>,
    val largestPhotos: List<MediaItem>,
    val potentialDuplicateGroupsCount: Int,
    val potentialReclaimableBytes: Long,
    val thumbnailCacheSizeBytes: Long
)
