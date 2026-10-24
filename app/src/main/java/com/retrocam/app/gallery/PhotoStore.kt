package com.retrocam.app.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

data class Photo(
    val id: Long,
    val uri: Uri,
    val name: String,
    val isVideo: Boolean = false,
    val durationMs: Long = 0L,
    val dateAdded: Long = 0L
)

/**
 * Lists the photos and videos RetroCam itself saved, newest first.
 *
 * No storage permission is requested: an app can always read the media it created, and this query
 * is scoped to our own folders (Pictures/RetroCam and Movies/RetroCam).
 */
object PhotoStore {

    private const val TAG = "RetroCam"
    const val PHOTO_FOLDER = "Pictures/RetroCam"
    const val VIDEO_FOLDER = "Movies/RetroCam"

    fun query(resolver: ContentResolver): List<Photo> {
        val items = mutableListOf<Photo>()

        // 1. Query Photos
        val photoProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
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

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val dateAdded = if (dateCol != -1) cursor.getLong(dateCol) else 0L
                    items += Photo(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        name = cursor.getString(nameCol),
                        isVideo = false,
                        dateAdded = dateAdded
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
            MediaStore.Video.Media.DATE_ADDED
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

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val duration = if (durCol != -1) cursor.getLong(durCol) else 0L
                    val dateAdded = if (dateCol != -1) cursor.getLong(dateCol) else 0L
                    items += Photo(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        name = cursor.getString(nameCol),
                        isVideo = true,
                        durationMs = duration,
                        dateAdded = dateAdded
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
}
