package com.retrocam.app.library.analyzer

import android.content.ContentResolver
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

data class ExtractedMetadata(
    val width: Int = 0,
    val height: Int = 0,
    val dateTaken: Long = 0L,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val focalLength: Float? = null,
    val iso: Int? = null,
    val exposureTime: Float? = null,
    val aperture: Float? = null,
    val durationMs: Long = 0L
)

object MetadataExtractor {

    private const val TAG = "MetadataExtractor"

    fun extract(resolver: ContentResolver, uri: Uri, isVideo: Boolean): ExtractedMetadata {
        return if (isVideo) {
            extractVideo(resolver, uri)
        } else {
            extractPhoto(resolver, uri)
        }
    }

    private fun extractPhoto(resolver: ContentResolver, uri: Uri): ExtractedMetadata {
        var width = 0
        var height = 0
        var dateTaken = 0L
        var make: String? = null
        var model: String? = null
        var focalLength: Float? = null
        var iso: Int? = null
        var exposureTime: Float? = null
        var aperture: Float? = null

        try {
            resolver.openInputStream(uri)?.use { stream: InputStream ->
                val exif = ExifInterface(stream)

                width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)

                // If orientation is 90 or 270, swap width and height
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270) {
                    val tmp = width
                    width = height
                    height = tmp
                }

                make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf { it.isNotEmpty() }
                model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() }

                val flDouble = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                if (flDouble > 0.0) focalLength = flDouble.toFloat()

                val isoInt = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
                if (isoInt > 0) iso = isoInt

                val expTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                if (expTime > 0.0) exposureTime = expTime.toFloat()

                val fn = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
                if (fn > 0.0) aperture = fn.toFloat()

                val dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                if (dateTime != null) {
                    try {
                        val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                        val parsed = format.parse(dateTime)
                        if (parsed != null) dateTaken = parsed.time
                    } catch (e: Exception) {
                        // ignore parse errors
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse EXIF for uri $uri", e)
        }

        return ExtractedMetadata(
            width = width,
            height = height,
            dateTaken = dateTaken,
            cameraMake = make,
            cameraModel = model,
            focalLength = focalLength,
            iso = iso,
            exposureTime = exposureTime,
            aperture = aperture
        )
    }

    private fun extractVideo(resolver: ContentResolver, uri: Uri): ExtractedMetadata {
        var width = 0
        var height = 0
        var durationMs = 0L
        var dateTaken = 0L

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(resolver.openFileDescriptor(uri, "r")?.fileDescriptor)

            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val dateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)

            var w = widthStr?.toIntOrNull() ?: 0
            var h = heightStr?.toIntOrNull() ?: 0
            val rotation = rotationStr?.toIntOrNull() ?: 0

            if (rotation == 90 || rotation == 270) {
                val temp = w
                w = h
                h = temp
            }

            width = w
            height = h
            durationMs = durStr?.toLongOrNull() ?: 0L

            if (dateStr != null) {
                try {
                    val format = SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US)
                    val parsed = format.parse(dateStr)
                    if (parsed != null) dateTaken = parsed.time
                } catch (e: Exception) {
                    // Try alternative formats
                    try {
                        val format = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
                        val parsed = format.parse(dateStr)
                        if (parsed != null) dateTaken = parsed.time
                    } catch (e2: Exception) {
                        // ignore
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse video metadata for uri $uri", e)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignore
            }
        }

        return ExtractedMetadata(
            width = width,
            height = height,
            durationMs = durationMs,
            dateTaken = dateTaken
        )
    }
}
