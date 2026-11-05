package com.retrocam.app.pro

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Metadata parameters for Pro camera captures.
 */
data class ProPhotoMetadata(
    val iso: Int?,
    val shutterNanos: Long?,
    val aperture: Float?,
    val isManualWb: Boolean,
    val wbSummary: String,
    val evIndex: Int,
    val presetId: String,
    val isLongExposure: Boolean = false,
    val longExposureMode: String? = null,
    val longExposureDurationSec: Float? = null,
    val frameCount: Int = 1
)

/**
 * Handles EXIF metadata injection and RAW digital negative export.
 */
object RawDngExporter {

    private const val TAG = "RawDngExporter"
    private const val FOLDER_PATH = "Pictures/RetroCam"

    /**
     * Injects rich pro camera EXIF metadata into an existing saved JPEG uri or file.
     */
    fun injectExifMetadata(
        contentResolver: ContentResolver,
        imageUri: Uri,
        metadata: ProPhotoMetadata
    ) {
        try {
            contentResolver.openFileDescriptor(imageUri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)

                metadata.iso?.let {
                    exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, it.toString())
                    exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString())
                }

                metadata.shutterNanos?.let { nanos ->
                    val sec = nanos / 1_000_000_000.0
                    exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, String.format(Locale.US, "%.6f", sec))
                }

                metadata.aperture?.let {
                    exif.setAttribute(ExifInterface.TAG_F_NUMBER, String.format(Locale.US, "%.1f", it))
                }

                exif.setAttribute(
                    ExifInterface.TAG_WHITE_BALANCE,
                    if (metadata.isManualWb) ExifInterface.WHITE_BALANCE_MANUAL.toString()
                    else ExifInterface.WHITE_BALANCE_AUTO.toString()
                )

                exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER.replaceFirstChar { it.uppercase() })
                exif.setAttribute(ExifInterface.TAG_MODEL, "${Build.MODEL} (RetroCam Pro)")

                val dateStr = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
                exif.setAttribute(ExifInterface.TAG_DATETIME, dateStr)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)

                val commentBuilder = StringBuilder("RetroCam Pro Photo; Preset=${metadata.presetId}")
                if (metadata.isLongExposure) {
                    commentBuilder.append("; Mode=${metadata.longExposureMode}; Duration=${metadata.longExposureDurationSec}s; Frames=${metadata.frameCount}")
                }
                exif.setAttribute(ExifInterface.TAG_USER_COMMENT, commentBuilder.toString())

                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed injecting EXIF metadata to $imageUri", e)
        }
    }

    /**
     * Saves an uncompressed raw digital negative (DNG) alongside the developed photo.
     */
    fun saveRawDigitalNegative(
        contentResolver: ContentResolver,
        rawBitmap: Bitmap,
        metadata: ProPhotoMetadata
    ): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "RetroCam_RAW_${metadata.presetId}_$stamp.dng"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/RetroCam")
        }

        val uri = try {
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting RAW MediaStore record", e)
            null
        } ?: return null

        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                writeDngContainer(out, rawBitmap, metadata)
            }
            Log.i(TAG, "Saved RAW Digital Negative: $displayName")
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing RAW digital negative file", e)
            return null
        }
    }

    /**
     * Synthesizes a valid uncompressed TIFF / DNG container holding the raw uncompressed RGB sensor bitmap.
     */
    private fun writeDngContainer(
        out: OutputStream,
        bitmap: Bitmap,
        metadata: ProPhotoMetadata
    ) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Write standard uncompressed TIFF/DNG header
        // Little endian byte order (II)
        out.write(byteArrayOf(0x49, 0x49, 0x2A, 0x00)) // II, 42
        val ifdOffset = 8
        out.write(byteArrayOf((ifdOffset and 0xFF).toByte(), (ifdOffset shr 8 and 0xFF).toByte(), 0x00, 0x00))

        // Basic TIFF IFD tags: ImageWidth, ImageLength, BitsPerSample (8,8,8), Compression (1=None),
        // PhotometricInterpretation (2=RGB), StripOffsets, SamplesPerPixel (3), RowsPerStrip, StripByteCounts
        val tagsCount = 9
        out.write(byteArrayOf((tagsCount and 0xFF).toByte(), (tagsCount shr 8 and 0xFF).toByte()))

        val dataOffset = 8 + 2 + (tagsCount * 12) + 4 + 6 // header + count + tags + nextIFD + extraData
        val byteCount = width * height * 3

        writeTiffTag(out, 0x0100, 4, 1, width) // ImageWidth
        writeTiffTag(out, 0x0101, 4, 1, height) // ImageLength
        writeTiffTag(out, 0x0102, 3, 3, 8 + 2 + (tagsCount * 12) + 4) // BitsPerSample points to 8,8,8
        writeTiffTag(out, 0x0103, 3, 1, 1) // Compression = None
        writeTiffTag(out, 0x0106, 3, 1, 2) // Photometric = RGB
        writeTiffTag(out, 0x0111, 4, 1, dataOffset) // StripOffsets
        writeTiffTag(out, 0x0115, 3, 1, 3) // SamplesPerPixel = 3
        writeTiffTag(out, 0x0116, 4, 1, height) // RowsPerStrip
        writeTiffTag(out, 0x0117, 4, 1, byteCount) // StripByteCounts

        // Next IFD offset = 0 (none)
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))

        // Extra data: BitsPerSample (8, 8, 8) as 3 shorts
        out.write(byteArrayOf(0x08, 0x00, 0x08, 0x00, 0x08, 0x00))

        // Write raw uncompressed RGB pixel bytes
        val rgbRow = ByteArray(width * 3)
        for (y in 0 until height) {
            val rowStart = y * width
            var colIdx = 0
            for (x in 0 until width) {
                val pix = pixels[rowStart + x]
                rgbRow[colIdx++] = ((pix shr 16) and 0xFF).toByte() // R
                rgbRow[colIdx++] = ((pix shr 8) and 0xFF).toByte()  // G
                rgbRow[colIdx++] = (pix and 0xFF).toByte()         // B
            }
            out.write(rgbRow)
        }
        out.flush()
    }

    private fun writeTiffTag(out: OutputStream, tagId: Int, type: Int, count: Int, valueOrOffset: Int) {
        out.write(byteArrayOf((tagId and 0xFF).toByte(), (tagId shr 8 and 0xFF).toByte()))
        out.write(byteArrayOf((type and 0xFF).toByte(), (type shr 8 and 0xFF).toByte()))
        out.write(byteArrayOf(
            (count and 0xFF).toByte(),
            (count shr 8 and 0xFF).toByte(),
            (count shr 16 and 0xFF).toByte(),
            (count shr 24 and 0xFF).toByte()
        ))
        out.write(byteArrayOf(
            (valueOrOffset and 0xFF).toByte(),
            (valueOrOffset shr 8 and 0xFF).toByte(),
            (valueOrOffset shr 16 and 0xFF).toByte(),
            (valueOrOffset shr 24 and 0xFF).toByte()
        ))
    }
}
