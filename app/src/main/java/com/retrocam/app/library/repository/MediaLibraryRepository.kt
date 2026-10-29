package com.retrocam.app.library.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import com.retrocam.app.library.analyzer.DuplicateDetector
import com.retrocam.app.library.analyzer.MetadataExtractor
import com.retrocam.app.library.model.AlbumItem
import com.retrocam.app.library.model.DuplicateGroup
import com.retrocam.app.library.model.MediaItem
import com.retrocam.app.library.model.StorageStats
import com.retrocam.app.library.search.ParsedSearchCriteria
import com.retrocam.app.library.search.SearchQueryParser
import com.retrocam.app.trip.TripDatabaseHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class LibraryTab {
    ALL,
    PHOTOS,
    VIDEOS,
    FAVORITES,
    ALBUMS,
    EDITED
}

enum class SortOrder {
    DATE_DESC,
    DATE_ASC,
    SIZE_DESC,
    NAME_ASC
}

class MediaLibraryRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val dbHelper = TripDatabaseHelper(appContext)
    private val indexExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    private val _itemsFlow = MutableStateFlow<List<MediaItem>>(emptyList())
    val itemsFlow: StateFlow<List<MediaItem>> = _itemsFlow.asStateFlow()

    private val _isIndexingFlow = MutableStateFlow(false)
    val isIndexingFlow: StateFlow<Boolean> = _isIndexingFlow.asStateFlow()

    private val _albumsFlow = MutableStateFlow<List<AlbumItem>>(emptyList())
    val albumsFlow: StateFlow<List<AlbumItem>> = _albumsFlow.asStateFlow()

    init {
        refreshFromDatabase()
        refreshAlbums()
    }

    fun refreshFromDatabase() {
        val items = queryItemsInternal()
        _itemsFlow.value = items
    }

    fun refreshAlbums() {
        _albumsFlow.value = queryAllAlbums()
    }

    /**
     * Queries media items with tab filtering, search query parsing, and sorting.
     */
    fun queryMedia(
        tab: LibraryTab = LibraryTab.ALL,
        searchQuery: String = "",
        sortOrder: SortOrder = SortOrder.DATE_DESC,
        albumId: String? = null
    ): List<MediaItem> {
        val criteria = if (searchQuery.isNotBlank()) SearchQueryParser.parse(searchQuery) else null
        return queryItemsInternal(tab, criteria, sortOrder, albumId)
    }

    private fun queryItemsInternal(
        tab: LibraryTab = LibraryTab.ALL,
        criteria: ParsedSearchCriteria? = null,
        sortOrder: SortOrder = SortOrder.DATE_DESC,
        albumId: String? = null
    ): List<MediaItem> {
        val db = dbHelper.readableDatabase
        val whereClauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        // Tab conditions
        when (tab) {
            LibraryTab.ALL -> {}
            LibraryTab.PHOTOS -> {
                whereClauses.add("${TripDatabaseHelper.COL_INDEX_IS_VIDEO} = 0")
            }
            LibraryTab.VIDEOS -> {
                whereClauses.add("${TripDatabaseHelper.COL_INDEX_IS_VIDEO} = 1")
            }
            LibraryTab.FAVORITES -> {
                whereClauses.add("${TripDatabaseHelper.COL_INDEX_IS_FAVORITE} = 1")
            }
            LibraryTab.EDITED -> {
                whereClauses.add("${TripDatabaseHelper.COL_INDEX_IS_EDITED} = 1")
            }
            LibraryTab.ALBUMS -> {}
        }

        // Album condition
        if (!albumId.isNullOrEmpty()) {
            whereClauses.add(
                "${TripDatabaseHelper.COL_INDEX_ID} IN (SELECT ${TripDatabaseHelper.COL_AM_MEDIA_ID} FROM ${TripDatabaseHelper.TABLE_ALBUM_MEDIA} WHERE ${TripDatabaseHelper.COL_AM_ALBUM_ID} = ?)"
            )
            args.add(albumId)
        }

        // Search Criteria conditions
        if (criteria != null) {
            if (criteria.isVideoFilter != null) {
                whereClauses.add("${TripDatabaseHelper.COL_INDEX_IS_VIDEO} = ?")
                args.add(if (criteria.isVideoFilter) "1" else "0")
            }
            if (criteria.isFavoriteFilter != null && criteria.isFavoriteFilter) {
                whereClauses.add("${TripDatabaseHelper.COL_INDEX_IS_FAVORITE} = 1")
            }
            if (criteria.isEditedFilter != null && criteria.isEditedFilter) {
                whereClauses.add("${TripDatabaseHelper.COL_INDEX_IS_EDITED} = 1")
            }
            if (criteria.startTimestamp != null && criteria.endTimestamp != null) {
                whereClauses.add("${TripDatabaseHelper.COL_INDEX_DATE_TAKEN} BETWEEN ? AND ?")
                args.add(criteria.startTimestamp.toString())
                args.add(criteria.endTimestamp.toString())
            }
            if (criteria.isoFilter != null) {
                whereClauses.add("${TripDatabaseHelper.COL_INDEX_ISO} = ?")
                args.add(criteria.isoFilter.toString())
            }
            for (term in criteria.textTerms) {
                whereClauses.add(
                    "(${TripDatabaseHelper.COL_INDEX_NAME} LIKE ? OR ${TripDatabaseHelper.COL_INDEX_CAMERA_MAKE} LIKE ? OR ${TripDatabaseHelper.COL_INDEX_CAMERA_MODEL} LIKE ? OR ${TripDatabaseHelper.COL_INDEX_RELATIVE_PATH} LIKE ?)"
                )
                val wild = "%$term%"
                args.add(wild)
                args.add(wild)
                args.add(wild)
                args.add(wild)
            }
        }

        val whereSql = if (whereClauses.isNotEmpty()) {
            "WHERE " + whereClauses.joinToString(" AND ")
        } else ""

        val orderSql = when (sortOrder) {
            SortOrder.DATE_DESC -> "ORDER BY ${TripDatabaseHelper.COL_INDEX_DATE_TAKEN} DESC, ${TripDatabaseHelper.COL_INDEX_DATE_ADDED} DESC"
            SortOrder.DATE_ASC -> "ORDER BY ${TripDatabaseHelper.COL_INDEX_DATE_TAKEN} ASC, ${TripDatabaseHelper.COL_INDEX_DATE_ADDED} ASC"
            SortOrder.SIZE_DESC -> "ORDER BY ${TripDatabaseHelper.COL_INDEX_SIZE_BYTES} DESC"
            SortOrder.NAME_ASC -> "ORDER BY ${TripDatabaseHelper.COL_INDEX_NAME} ASC"
        }

        val sql = "SELECT * FROM ${TripDatabaseHelper.TABLE_MEDIA_INDEX} $whereSql $orderSql"
        val items = mutableListOf<MediaItem>()

        try {
            db.rawQuery(sql, if (args.isNotEmpty()) args.toTypedArray() else null).use { cursor ->
                while (cursor.moveToNext()) {
                    items.add(cursorToMediaItem(cursor))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying media_index", e)
        }

        return items
    }

    private fun cursorToMediaItem(cursor: Cursor): MediaItem {
        val id = cursor.getString(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_INDEX_ID))
        val uriStr = cursor.getString(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_INDEX_URI))
        val name = cursor.getString(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_INDEX_NAME))
        val isVideo = cursor.getInt(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_INDEX_IS_VIDEO)) == 1
        val mime = cursor.getString(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_INDEX_MIME_TYPE))
        val dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_INDEX_DATE_TAKEN))
        val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_INDEX_DATE_ADDED))
        val durationMs = cursor.getLong(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_INDEX_DURATION_MS))
        val width = cursor.getInt(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_INDEX_WIDTH))
        val height = cursor.getInt(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_INDEX_HEIGHT))
        val sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_INDEX_SIZE_BYTES))
        val isFavorite = cursor.getInt(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_INDEX_IS_FAVORITE)) == 1
        val isEdited = cursor.getInt(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_INDEX_IS_EDITED)) == 1

        val origIdCol = cursor.getColumnIndex(TripDatabaseHelper.COL_INDEX_ORIGINAL_ID)
        val originalId = if (origIdCol != -1 && !cursor.isNull(origIdCol)) cursor.getString(origIdCol) else null

        val makeCol = cursor.getColumnIndex(TripDatabaseHelper.COL_INDEX_CAMERA_MAKE)
        val cameraMake = if (makeCol != -1 && !cursor.isNull(makeCol)) cursor.getString(makeCol) else null

        val modelCol = cursor.getColumnIndex(TripDatabaseHelper.COL_INDEX_CAMERA_MODEL)
        val cameraModel = if (modelCol != -1 && !cursor.isNull(modelCol)) cursor.getString(modelCol) else null

        val flCol = cursor.getColumnIndex(TripDatabaseHelper.COL_INDEX_FOCAL_LENGTH)
        val focalLength = if (flCol != -1 && !cursor.isNull(flCol)) cursor.getFloat(flCol) else null

        val isoCol = cursor.getColumnIndex(TripDatabaseHelper.COL_INDEX_ISO)
        val iso = if (isoCol != -1 && !cursor.isNull(isoCol)) cursor.getInt(isoCol) else null

        val expCol = cursor.getColumnIndex(TripDatabaseHelper.COL_INDEX_EXPOSURE_TIME)
        val exposureTime = if (expCol != -1 && !cursor.isNull(expCol)) cursor.getFloat(expCol) else null

        val apCol = cursor.getColumnIndex(TripDatabaseHelper.COL_INDEX_APERTURE)
        val aperture = if (apCol != -1 && !cursor.isNull(apCol)) cursor.getFloat(apCol) else null

        val pathCol = cursor.getColumnIndex(TripDatabaseHelper.COL_INDEX_RELATIVE_PATH)
        val relativePath = if (pathCol != -1 && !cursor.isNull(pathCol)) cursor.getString(pathCol) else null

        val hashCol = cursor.getColumnIndex(TripDatabaseHelper.COL_INDEX_CONTENT_HASH)
        val contentHash = if (hashCol != -1 && !cursor.isNull(hashCol)) cursor.getString(hashCol) else null

        val pHashCol = cursor.getColumnIndex(TripDatabaseHelper.COL_INDEX_PERCEPTUAL_HASH)
        val pHash = if (pHashCol != -1 && !cursor.isNull(pHashCol)) cursor.getLong(pHashCol) else null

        val indexedCol = cursor.getColumnIndex(TripDatabaseHelper.COL_INDEX_LAST_INDEXED)
        val lastIndexed = if (indexedCol != -1) cursor.getLong(indexedCol) else 0L

        return MediaItem(
            id = id,
            uri = Uri.parse(uriStr),
            displayName = name,
            isVideo = isVideo,
            mimeType = mime,
            dateTaken = dateTaken,
            dateAdded = dateAdded,
            durationMs = durationMs,
            width = width,
            height = height,
            sizeBytes = sizeBytes,
            isFavorite = isFavorite,
            isEdited = isEdited,
            originalMediaId = originalId,
            cameraMake = cameraMake,
            cameraModel = cameraModel,
            focalLength = focalLength,
            iso = iso,
            exposureTime = exposureTime,
            aperture = aperture,
            relativePath = relativePath,
            contentHash = contentHash,
            perceptualHash = pHash,
            lastIndexed = lastIndexed
        )
    }

    /**
     * Toggles favorite status in the database.
     */
    fun toggleFavorite(mediaId: String): Boolean {
        val db = dbHelper.writableDatabase
        var newState = false
        db.beginTransaction()
        try {
            db.rawQuery(
                "SELECT ${TripDatabaseHelper.COL_INDEX_IS_FAVORITE} FROM ${TripDatabaseHelper.TABLE_MEDIA_INDEX} WHERE ${TripDatabaseHelper.COL_INDEX_ID} = ?",
                arrayOf(mediaId)
            ).use { cursor ->
                if (cursor.moveToNext()) {
                    val current = cursor.getInt(0) == 1
                    newState = !current
                    val values = ContentValues().apply {
                        put(TripDatabaseHelper.COL_INDEX_IS_FAVORITE, if (newState) 1 else 0)
                    }
                    db.update(
                        TripDatabaseHelper.TABLE_MEDIA_INDEX,
                        values,
                        "${TripDatabaseHelper.COL_INDEX_ID} = ?",
                        arrayOf(mediaId)
                    )
                }
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling favorite for $mediaId", e)
        } finally {
            db.endTransaction()
        }
        refreshFromDatabase()
        return newState
    }

    /**
     * Records an edited copy associated with an original media item.
     */
    fun recordEditedCopy(originalId: String, editedUri: Uri, editedName: String) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(TripDatabaseHelper.COL_INDEX_IS_EDITED, 1)
            put(TripDatabaseHelper.COL_INDEX_ORIGINAL_ID, originalId)
        }
        db.update(
            TripDatabaseHelper.TABLE_MEDIA_INDEX,
            values,
            "${TripDatabaseHelper.COL_INDEX_URI} = ?",
            arrayOf(editedUri.toString())
        )
        refreshFromDatabase()
    }

    /**
     * Finds original item associated with an edited item, or returns null.
     */
    fun getOriginalItem(editedItem: MediaItem): MediaItem? {
        if (!editedItem.originalMediaId.isNullOrEmpty()) {
            return getItemById(editedItem.originalMediaId)
        }
        // Fallback: match by timestamp and name pattern
        if (editedItem.displayName.startsWith("Remastered_")) {
            val db = dbHelper.readableDatabase
            val sql = "SELECT * FROM ${TripDatabaseHelper.TABLE_MEDIA_INDEX} WHERE ${TripDatabaseHelper.COL_INDEX_IS_EDITED} = 0 ORDER BY ABS(${TripDatabaseHelper.COL_INDEX_DATE_TAKEN} - ?) ASC LIMIT 1"
            db.rawQuery(sql, arrayOf(editedItem.dateTaken.toString())).use { cursor ->
                if (cursor.moveToNext()) {
                    return cursorToMediaItem(cursor)
                }
            }
        }
        return null
    }

    fun getItemById(mediaId: String): MediaItem? {
        val db = dbHelper.readableDatabase
        val sql = "SELECT * FROM ${TripDatabaseHelper.TABLE_MEDIA_INDEX} WHERE ${TripDatabaseHelper.COL_INDEX_ID} = ?"
        db.rawQuery(sql, arrayOf(mediaId)).use { cursor ->
            if (cursor.moveToNext()) {
                return cursorToMediaItem(cursor)
            }
        }
        return null
    }

    /**
     * Album Management
     */
    fun createAlbum(name: String, coverUri: Uri? = null): AlbumItem {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(TripDatabaseHelper.COL_ALBUM_ID, id)
            put(TripDatabaseHelper.COL_ALBUM_NAME, name)
            put(TripDatabaseHelper.COL_ALBUM_COVER_URI, coverUri?.toString())
            put(TripDatabaseHelper.COL_ALBUM_CREATED_AT, now)
            put(TripDatabaseHelper.COL_ALBUM_UPDATED_AT, now)
        }
        dbHelper.writableDatabase.insertWithOnConflict(
            TripDatabaseHelper.TABLE_ALBUMS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        refreshAlbums()
        return AlbumItem(id, name, coverUri, 0, now, now)
    }

    fun renameAlbum(albumId: String, newName: String) {
        val values = ContentValues().apply {
            put(TripDatabaseHelper.COL_ALBUM_NAME, newName)
            put(TripDatabaseHelper.COL_ALBUM_UPDATED_AT, System.currentTimeMillis())
        }
        dbHelper.writableDatabase.update(
            TripDatabaseHelper.TABLE_ALBUMS,
            values,
            "${TripDatabaseHelper.COL_ALBUM_ID} = ?",
            arrayOf(albumId)
        )
        refreshAlbums()
    }

    fun deleteAlbum(albumId: String) {
        dbHelper.writableDatabase.delete(
            TripDatabaseHelper.TABLE_ALBUMS,
            "${TripDatabaseHelper.COL_ALBUM_ID} = ?",
            arrayOf(albumId)
        )
        refreshAlbums()
    }

    fun addMediaToAlbum(albumId: String, mediaIds: List<String>) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (mId in mediaIds) {
                val values = ContentValues().apply {
                    put(TripDatabaseHelper.COL_AM_ALBUM_ID, albumId)
                    put(TripDatabaseHelper.COL_AM_MEDIA_ID, mId)
                }
                db.insertWithOnConflict(
                    TripDatabaseHelper.TABLE_ALBUM_MEDIA,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refreshAlbums()
    }

    fun removeMediaFromAlbum(albumId: String, mediaIds: List<String>) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (mId in mediaIds) {
                db.delete(
                    TripDatabaseHelper.TABLE_ALBUM_MEDIA,
                    "${TripDatabaseHelper.COL_AM_ALBUM_ID} = ? AND ${TripDatabaseHelper.COL_AM_MEDIA_ID} = ?",
                    arrayOf(albumId, mId)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refreshAlbums()
    }

    fun queryAllAlbums(): List<AlbumItem> {
        val list = mutableListOf<AlbumItem>()
        val db = dbHelper.readableDatabase
        val sql = """
            SELECT a.${TripDatabaseHelper.COL_ALBUM_ID},
                   a.${TripDatabaseHelper.COL_ALBUM_NAME},
                   a.${TripDatabaseHelper.COL_ALBUM_COVER_URI},
                   a.${TripDatabaseHelper.COL_ALBUM_CREATED_AT},
                   a.${TripDatabaseHelper.COL_ALBUM_UPDATED_AT},
                   COUNT(m.${TripDatabaseHelper.COL_AM_MEDIA_ID}) as media_count
            FROM ${TripDatabaseHelper.TABLE_ALBUMS} a
            LEFT JOIN ${TripDatabaseHelper.TABLE_ALBUM_MEDIA} m
                   ON a.${TripDatabaseHelper.COL_ALBUM_ID} = m.${TripDatabaseHelper.COL_AM_ALBUM_ID}
            GROUP BY a.${TripDatabaseHelper.COL_ALBUM_ID}
            ORDER BY a.${TripDatabaseHelper.COL_ALBUM_CREATED_AT} DESC
        """.trimIndent()

        try {
            db.rawQuery(sql, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1)
                    val coverStr = cursor.getString(2)
                    val createdAt = cursor.getLong(3)
                    val updatedAt = cursor.getLong(4)
                    val count = cursor.getInt(5)
                    list.add(
                        AlbumItem(
                            id = id,
                            name = name,
                            coverUri = if (!coverStr.isNullOrEmpty()) Uri.parse(coverStr) else null,
                            mediaCount = count,
                            createdAt = createdAt,
                            updatedAt = updatedAt
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying albums", e)
        }
        return list
    }

    /**
     * Storage Statistics and Dashboard Calculation
     */
    fun computeStorageStats(): StorageStats {
        val allItems = queryItemsInternal()
        val photos = allItems.filter { !it.isVideo }
        val videos = allItems.filter { it.isVideo }
        val edited = allItems.filter { it.isEdited }

        val totalBytes = allItems.sumOf { it.sizeBytes }
        val photoBytes = photos.sumOf { it.sizeBytes }
        val videoBytes = videos.sumOf { it.sizeBytes }

        val largestVideos = videos.sortedByDescending { it.sizeBytes }.take(10)
        val largestPhotos = photos.sortedByDescending { it.sizeBytes }.take(10)

        val duplicates = DuplicateDetector.findDuplicates(allItems)
        val duplicateCount = duplicates.size
        val reclaimableBytes = duplicates.sumOf { it.reclaimableBytes }

        return StorageStats(
            totalCount = allItems.size,
            photoCount = photos.size,
            videoCount = videos.size,
            editedCount = edited.size,
            totalSizeBytes = totalBytes,
            photoSizeBytes = photoBytes,
            videoSizeBytes = videoBytes,
            largestVideos = largestVideos,
            largestPhotos = largestPhotos,
            potentialDuplicateGroupsCount = duplicateCount,
            potentialReclaimableBytes = reclaimableBytes,
            thumbnailCacheSizeBytes = 0L
        )
    }

    fun findDuplicates(): List<DuplicateGroup> {
        val allItems = queryItemsInternal()
        return DuplicateDetector.findDuplicates(allItems)
    }

    /**
     * Incremental Sync and Indexing against MediaStore
     */
    fun syncWithMediaStore(hasBroadStoragePermission: Boolean, onComplete: (() -> Unit)? = null) {
        if (_isIndexingFlow.value) return
        _isIndexingFlow.value = true

        indexExecutor.execute {
            try {
                performSync(hasBroadStoragePermission)
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing with MediaStore", e)
            } finally {
                _isIndexingFlow.value = false
                refreshFromDatabase()
                onComplete?.let { indexExecutor.execute(it) }
            }
        }
    }

    private fun performSync(hasBroadStoragePermission: Boolean) {
        val resolver = appContext.contentResolver
        val existingMedia = mutableMapOf<String, Long>() // id to lastIndexed

        dbHelper.readableDatabase.rawQuery(
            "SELECT ${TripDatabaseHelper.COL_INDEX_ID}, ${TripDatabaseHelper.COL_INDEX_LAST_INDEXED} FROM ${TripDatabaseHelper.TABLE_MEDIA_INDEX}",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                existingMedia[cursor.getString(0)] = cursor.getLong(1)
            }
        }

        val discoveredIds = mutableSetOf<String>()
        val itemsToInsertOrUpdate = mutableListOf<MediaItem>()

        // 1. Scan Photos
        val photoProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.RELATIVE_PATH
        )

        val photoSelection: String?
        val photoArgs: Array<String>?
        if (!hasBroadStoragePermission) {
            photoSelection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            photoArgs = arrayOf("%Pictures/RetroCam%")
        } else {
            photoSelection = null
            photoArgs = null
        }

        try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                photoProjection,
                photoSelection,
                photoArgs,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val dateTakenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                val pathCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)

                while (cursor.moveToNext()) {
                    val idLong = cursor.getLong(idCol)
                    val id = "photo_$idLong"
                    discoveredIds.add(id)

                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, idLong)
                    val name = cursor.getString(nameCol) ?: "IMG_$idLong.jpg"
                    val mime = if (mimeCol != -1) cursor.getString(mimeCol) ?: "image/jpeg" else "image/jpeg"
                    val dateTakenRaw = if (dateTakenCol != -1) cursor.getLong(dateTakenCol) else 0L
                    val dateAddedSec = if (dateAddedCol != -1) cursor.getLong(dateAddedCol) else 0L
                    val dateAddedMs = if (dateAddedSec > 0) dateAddedSec * 1000L else System.currentTimeMillis()
                    val dateTaken = if (dateTakenRaw > 0) dateTakenRaw else dateAddedMs
                    val sizeBytes = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
                    val relPath = if (pathCol != -1) cursor.getString(pathCol) else ""
                    val isEdited = name.startsWith("Remastered_") || (relPath?.contains("Edited") == true)

                    val existingLastIndexed = existingMedia[id]
                    if (existingLastIndexed == null) {
                        // Extract metadata and hashes
                        val meta = MetadataExtractor.extract(resolver, uri, isVideo = false)
                        val contentHash = DuplicateDetector.computeContentHash(resolver, uri, sizeBytes)
                        var pHash: Long? = null

                        // Compute perceptual hash via thumbnail
                        try {
                            val thumb = resolver.loadThumbnail(uri, Size(96, 96), null)
                            pHash = DuplicateDetector.computeDifferenceHash(thumb)
                            thumb.recycle()
                        } catch (e: Exception) {
                            // ignore thumbnail generation failure
                        }

                        itemsToInsertOrUpdate.add(
                            MediaItem(
                                id = id,
                                uri = uri,
                                displayName = name,
                                isVideo = false,
                                mimeType = mime,
                                dateTaken = if (meta.dateTaken > 0) meta.dateTaken else dateTaken,
                                dateAdded = dateAddedMs,
                                width = meta.width,
                                height = meta.height,
                                sizeBytes = sizeBytes,
                                isEdited = isEdited,
                                cameraMake = meta.cameraMake,
                                cameraModel = meta.cameraModel,
                                focalLength = meta.focalLength,
                                iso = meta.iso,
                                exposureTime = meta.exposureTime,
                                aperture = meta.aperture,
                                relativePath = relPath,
                                contentHash = contentHash,
                                perceptualHash = pHash,
                                lastIndexed = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning MediaStore images", e)
        }

        // 2. Scan Videos
        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.RELATIVE_PATH
        )

        val videoSelection: String?
        val videoArgs: Array<String>?
        if (!hasBroadStoragePermission) {
            videoSelection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            videoArgs = arrayOf("%Movies/RetroCam%")
        } else {
            videoSelection = null
            videoArgs = null
        }

        try {
            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoProjection,
                videoSelection,
                videoArgs,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                val dateTakenCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
                val dateAddedCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val pathCol = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)

                while (cursor.moveToNext()) {
                    val idLong = cursor.getLong(idCol)
                    val id = "video_$idLong"
                    discoveredIds.add(id)

                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, idLong)
                    val name = cursor.getString(nameCol) ?: "VID_$idLong.mp4"
                    val mime = if (mimeCol != -1) cursor.getString(mimeCol) ?: "video/mp4" else "video/mp4"
                    val dateTakenRaw = if (dateTakenCol != -1) cursor.getLong(dateTakenCol) else 0L
                    val dateAddedSec = if (dateAddedCol != -1) cursor.getLong(dateAddedCol) else 0L
                    val dateAddedMs = if (dateAddedSec > 0) dateAddedSec * 1000L else System.currentTimeMillis()
                    val dateTaken = if (dateTakenRaw > 0) dateTakenRaw else dateAddedMs
                    val sizeBytes = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
                    val durationMs = if (durCol != -1) cursor.getLong(durCol) else 0L
                    val relPath = if (pathCol != -1) cursor.getString(pathCol) else ""

                    val existingLastIndexed = existingMedia[id]
                    if (existingLastIndexed == null) {
                        val meta = MetadataExtractor.extract(resolver, uri, isVideo = true)
                        val contentHash = DuplicateDetector.computeContentHash(resolver, uri, sizeBytes)

                        itemsToInsertOrUpdate.add(
                            MediaItem(
                                id = id,
                                uri = uri,
                                displayName = name,
                                isVideo = true,
                                mimeType = mime,
                                dateTaken = if (meta.dateTaken > 0) meta.dateTaken else dateTaken,
                                dateAdded = dateAddedMs,
                                durationMs = if (meta.durationMs > 0) meta.durationMs else durationMs,
                                width = meta.width,
                                height = meta.height,
                                sizeBytes = sizeBytes,
                                relativePath = relPath,
                                contentHash = contentHash,
                                lastIndexed = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning MediaStore videos", e)
        }

        // 3. Batch insert new/updated records
        if (itemsToInsertOrUpdate.isNotEmpty()) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                for (item in itemsToInsertOrUpdate) {
                    val values = ContentValues().apply {
                        put(TripDatabaseHelper.COL_INDEX_ID, item.id)
                        put(TripDatabaseHelper.COL_INDEX_URI, item.uri.toString())
                        put(TripDatabaseHelper.COL_INDEX_NAME, item.displayName)
                        put(TripDatabaseHelper.COL_INDEX_IS_VIDEO, if (item.isVideo) 1 else 0)
                        put(TripDatabaseHelper.COL_INDEX_MIME_TYPE, item.mimeType)
                        put(TripDatabaseHelper.COL_INDEX_DATE_TAKEN, item.dateTaken)
                        put(TripDatabaseHelper.COL_INDEX_DATE_ADDED, item.dateAdded)
                        put(TripDatabaseHelper.COL_INDEX_DURATION_MS, item.durationMs)
                        put(TripDatabaseHelper.COL_INDEX_WIDTH, item.width)
                        put(TripDatabaseHelper.COL_INDEX_HEIGHT, item.height)
                        put(TripDatabaseHelper.COL_INDEX_SIZE_BYTES, item.sizeBytes)
                        put(TripDatabaseHelper.COL_INDEX_IS_FAVORITE, if (item.isFavorite) 1 else 0)
                        put(TripDatabaseHelper.COL_INDEX_IS_EDITED, if (item.isEdited) 1 else 0)
                        put(TripDatabaseHelper.COL_INDEX_ORIGINAL_ID, item.originalMediaId)
                        put(TripDatabaseHelper.COL_INDEX_CAMERA_MAKE, item.cameraMake)
                        put(TripDatabaseHelper.COL_INDEX_CAMERA_MODEL, item.cameraModel)
                        put(TripDatabaseHelper.COL_INDEX_FOCAL_LENGTH, item.focalLength)
                        put(TripDatabaseHelper.COL_INDEX_ISO, item.iso)
                        put(TripDatabaseHelper.COL_INDEX_EXPOSURE_TIME, item.exposureTime)
                        put(TripDatabaseHelper.COL_INDEX_APERTURE, item.aperture)
                        put(TripDatabaseHelper.COL_INDEX_RELATIVE_PATH, item.relativePath)
                        put(TripDatabaseHelper.COL_INDEX_CONTENT_HASH, item.contentHash)
                        put(TripDatabaseHelper.COL_INDEX_PERCEPTUAL_HASH, item.perceptualHash)
                        put(TripDatabaseHelper.COL_INDEX_LAST_INDEXED, item.lastIndexed)
                    }
                    db.insertWithOnConflict(
                        TripDatabaseHelper.TABLE_MEDIA_INDEX,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        // 4. Prune media items that are no longer present on device
        val deletedIds = existingMedia.keys - discoveredIds
        if (deletedIds.isNotEmpty()) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                for (delId in deletedIds) {
                    db.delete(
                        TripDatabaseHelper.TABLE_MEDIA_INDEX,
                        "${TripDatabaseHelper.COL_INDEX_ID} = ?",
                        arrayOf(delId)
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * Deletes a media item from the local index.
     */
    fun deleteFromIndex(mediaId: String) {
        dbHelper.writableDatabase.delete(
            TripDatabaseHelper.TABLE_MEDIA_INDEX,
            "${TripDatabaseHelper.COL_INDEX_ID} = ?",
            arrayOf(mediaId)
        )
        refreshFromDatabase()
        refreshAlbums()
    }

    companion object {
        private const val TAG = "MediaLibraryRepository"

        @Volatile
        private var instance: MediaLibraryRepository? = null

        fun getInstance(context: Context): MediaLibraryRepository {
            return instance ?: synchronized(this) {
                instance ?: MediaLibraryRepository(context).also { instance = it }
            }
        }
    }
}
