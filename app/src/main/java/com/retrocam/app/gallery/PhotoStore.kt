package com.retrocam.app.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

data class Photo(val id: Long, val uri: Uri, val name: String)

/**
 * Lists the shots RetroCam itself saved, newest first.
 *
 * No storage permission is requested: an app can always read the media it created, and this query
 * is scoped to our own folder. That means photos taken before a full uninstall won't appear —
 * MediaStore drops the owner record — which is the right trade for not asking to read every image
 * on the device.
 */
object PhotoStore {

    private const val TAG = "RetroCam"
    const val FOLDER = "Pictures/RetroCam"

    fun query(resolver: ContentResolver): List<Photo> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%$FOLDER%")
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val photos = mutableListOf<Photo>()
        try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, args, order
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    photos += Photo(
                        id = id,
                        uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                        ),
                        name = cursor.getString(nameColumn)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not query saved photos", e)
        }
        return photos
    }
}
