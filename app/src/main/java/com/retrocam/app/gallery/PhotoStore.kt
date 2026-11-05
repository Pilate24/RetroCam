package com.retrocam.app.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.retrocam.app.R

enum class GalleryFolder(val titleResId: Int) {
    ALL(R.string.folder_all),
    PHOTOS(R.string.folder_photos),
    VIDEOS(R.string.folder_videos),
    LONG_EXP(R.string.folder_long_exp),
    PHOTOBOOTH(R.string.folder_booth),
    RAW(R.string.folder_raw),
    REMASTERED(R.string.folder_remastered),
    FAVORITES(R.string.folder_favorites)
}

data class Photo(
    val id: Long,
    val uri: Uri? = null,
    val name: String,
    val isVideo: Boolean = false,
    val durationMs: Long = 0L,
    val dateAdded: Long = 0L,
    val sizeBytes: Long = 0L,
    val mimeType: String = if (isVideo) "video/mp4" else "image/jpeg",
    val relativePath: String = if (isVideo) "Movies/RetroCam" else "Pictures/RetroCam",
    val isFavorite: Boolean = false
) {
    val folder: GalleryFolder
        get() = when {
            isVideo -> GalleryFolder.VIDEOS
            name.contains("RAW", ignoreCase = true) || name.endsWith(".dng", ignoreCase = true) -> GalleryFolder.RAW
            name.contains("LongExp", ignoreCase = true) || name.contains("Long_Exp", ignoreCase = true) -> GalleryFolder.LONG_EXP
            name.contains("Booth", ignoreCase = true) -> GalleryFolder.PHOTOBOOTH
            name.startsWith("Remastered", ignoreCase = true) -> GalleryFolder.REMASTERED
            else -> GalleryFolder.PHOTOS
        }

    fun matchesFolder(target: GalleryFolder): Boolean {
        return when (target) {
            GalleryFolder.ALL -> true
            GalleryFolder.PHOTOS -> !isVideo && this.folder == GalleryFolder.PHOTOS
            GalleryFolder.VIDEOS -> isVideo
            GalleryFolder.LONG_EXP -> this.folder == GalleryFolder.LONG_EXP
            GalleryFolder.PHOTOBOOTH -> this.folder == GalleryFolder.PHOTOBOOTH
            GalleryFolder.RAW -> this.folder == GalleryFolder.RAW
            GalleryFolder.REMASTERED -> this.folder == GalleryFolder.REMASTERED
            GalleryFolder.FAVORITES -> isFavorite
        }
    }
}

/**
 * Lists the photos and videos RetroCam itself saved, newest first.
 * Scoped to our own folders (Pictures/RetroCam and Movies/RetroCam).
 */
object PhotoStore {

    private const val TAG = "RetroCam"
    const val PHOTO_FOLDER = "Pictures/RetroCam"
    const val VIDEO_FOLDER = "Movies/RetroCam"

    fun query(resolver: ContentResolver, context: Context? = null): List<Photo> {
        val items = mutableListOf<Photo>()
        val favoriteUris = if (context != null) FavoriteStore.getFavorites(context) else emptySet()

        // 1. Query Photos
        val photoProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.RELATIVE_PATH
        )
        val photoSelection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val photoArgs = arrayOf("%$PHOTO_FOLDER%")

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
                val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                val mimeCol = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val pathCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val dateAdded = if (dateCol != -1) cursor.getLong(dateCol) else 0L
                    val size = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
                    val mime = if (mimeCol != -1) cursor.getString(mimeCol) ?: "image/jpeg" else "image/jpeg"
                    val path = if (pathCol != -1) cursor.getString(pathCol) ?: PHOTO_FOLDER else PHOTO_FOLDER
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    items += Photo(
                        id = id,
                        uri = uri,
                        name = cursor.getString(nameCol),
                        isVideo = false,
                        dateAdded = dateAdded,
                        sizeBytes = size,
                        mimeType = mime,
                        relativePath = path,
                        isFavorite = favoriteUris.contains(uri.toString())
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not query saved photos", e)
        }

        // 2. Query Videos
        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.RELATIVE_PATH
        )
        val videoSelection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val videoArgs = arrayOf("%$VIDEO_FOLDER%")

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
                val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val dateCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val mimeCol = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                val pathCol = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val duration = if (durCol != -1) cursor.getLong(durCol) else 0L
                    val dateAdded = if (dateCol != -1) cursor.getLong(dateCol) else 0L
                    val size = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
                    val mime = if (mimeCol != -1) cursor.getString(mimeCol) ?: "video/mp4" else "video/mp4"
                    val path = if (pathCol != -1) cursor.getString(pathCol) ?: VIDEO_FOLDER else VIDEO_FOLDER
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                    items += Photo(
                        id = id,
                        uri = uri,
                        name = cursor.getString(nameCol),
                        isVideo = true,
                        durationMs = duration,
                        dateAdded = dateAdded,
                        sizeBytes = size,
                        mimeType = mime,
                        relativePath = path,
                        isFavorite = favoriteUris.contains(uri.toString())
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not query saved videos", e)
        }

        // Newest first across both photos and videos
        items.sortByDescending { it.dateAdded }
        return items
    }

    /**
     * Deletes a media item from MediaStore and cleans up its favorite state.
     */
    fun delete(resolver: ContentResolver, uri: Uri, context: Context? = null): Boolean {
        return try {
            val count = resolver.delete(uri, null, null)
            if (count > 0 && context != null) {
                FavoriteStore.remove(context, uri)
            }
            count > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete media item: $uri", e)
            false
        }
    }

    /**
     * Deletes multiple media items from MediaStore.
     */
    fun deleteMultiple(resolver: ContentResolver, uris: Collection<Uri>, context: Context? = null): Int {
        var count = 0
        for (uri in uris) {
            if (delete(resolver, uri, context)) {
                count++
            }
        }
        return count
    }

    /**
     * Filters a list of photos by the specified folder.
     */
    fun filter(items: List<Photo>, folder: GalleryFolder): List<Photo> {
        return items.filter { it.matchesFolder(folder) }
    }

    /**
     * Counts the number of items for each folder.
     */
    fun getFolderCounts(items: List<Photo>): Map<GalleryFolder, Int> {
        val map = mutableMapOf<GalleryFolder, Int>()
        for (folder in GalleryFolder.entries) {
            map[folder] = items.count { it.matchesFolder(folder) }
        }
        return map
    }
}
