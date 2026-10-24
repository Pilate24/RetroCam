package com.retrocam.app.video

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAdded: Long
)

/**
 * Manages queries and storage for videos saved to Movies/RetroCam.
 */
object VideoStore {

    private const val TAG = "RetroCam"
    const val FOLDER = "Movies/RetroCam"

    /**
     * Lists videos saved by RetroCam, newest first.
     */
    fun query(resolver: ContentResolver): List<VideoItem> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%$FOLDER%")
        val order = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        val videos = mutableListOf<VideoItem>()
        try {
            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                order
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val duration = if (durationCol != -1) cursor.getLong(durationCol) else 0L
                    val size = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
                    val dateAdded = if (dateCol != -1) cursor.getLong(dateCol) else 0L

                    videos += VideoItem(
                        id = id,
                        uri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            id
                        ),
                        name = cursor.getString(nameCol),
                        durationMs = duration,
                        sizeBytes = size,
                        dateAdded = dateAdded
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not query saved videos", e)
        }
        return videos
    }

    /**
     * Saves a temporary video file to MediaStore under Movies/RetroCam.
     * Uses IS_PENDING during write so other apps do not read incomplete data.
     */
    fun saveToMediaStore(
        resolver: ContentResolver,
        sourceFile: File,
        displayName: String,
        durationMs: Long
    ): Uri? {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            Log.e(TAG, "Cannot save invalid or empty video file: ${sourceFile.absolutePath}")
            return null
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RetroCam")
            put(MediaStore.Video.Media.DURATION, durationMs)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri == null) {
            Log.e(TAG, "Failed to create MediaStore entry for video")
            return null
        }

        try {
            resolver.openOutputStream(uri)?.use { outStream ->
                FileInputStream(sourceFile).use { inStream ->
                    inStream.copyTo(outStream)
                }
            } ?: run {
                resolver.delete(uri, null, null)
                return null
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write video data to MediaStore uri: $uri", e)
            try {
                resolver.delete(uri, null, null)
            } catch (ignored: Exception) {}
            return null
        }
    }

    /**
     * Deletes a video from MediaStore.
     */
    fun delete(resolver: ContentResolver, uri: Uri): Boolean {
        return try {
            resolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete video: $uri", e)
            false
        }
    }
}
