package com.retrocam.app.pro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.Log

/**
 * Long Exposure Modes supported by the computational and hardware long exposure engine.
 */
enum class LongExposureMode(val id: String, val title: String, val description: String) {
    LIGHT_TRAILS("light_trails", "Light Trails", "Traffic streaks, sparklers & fireworks"),
    SILKY_WATER("silky_water", "Silky Water", "Waterfalls, waves, fountain mist & crowd blur"),
    STAR_TRAILS("star_trails", "Star Trails", "Night sky & low-light astrophotography"),
    TIMED_BULB("timed_bulb", "Classic Bulb", "Continuous classic exposure stacking")
}

/**
 * Progress event emitted during an active long exposure capture.
 */
data class LongExposureProgress(
    val elapsedMs: Long,
    val targetDurationMs: Long,
    val frameCount: Int,
    val isBulb: Boolean,
    val progressRatio: Float // 0.0 to 1.0 (clamped, or indeterminate for bulb)
)

/**
 * Engine for computational long exposure photographic capture.
 *
 * Simulates optical neutral density (ND) filters and continuous shutter opening by accumulating
 * multiple sensor frames with specialized mathematical blending:
 * 1. Light Trails: Maximum intensity luminance blending to retain bright light streaks.
 * 2. Silky Water: Cumulative moving temporal average to create silky smooth motion blur without highlight blow-out.
 * 3. Star Trails: Threshold-based star amplification and background dark noise reduction.
 * 4. Classic Bulb: Balanced multi-frame integration with manual start/stop control.
 */
class LongExposureEngine {

    companion object {
        private const val TAG = "LongExposureEngine"
        val PRESET_DURATIONS_SECONDS = listOf(1f, 2f, 3f, 5f, 10f, 15f, 30f, 0f) // 0f = BULB (infinite/manual)
    }

    @Volatile
    var isExposing: Boolean = false
        private set

    var activeMode: LongExposureMode = LongExposureMode.LIGHT_TRAILS
    var targetDurationSeconds: Float = 5.0f // 0f = bulb

    private var startTimeMs: Long = 0L
    private var targetDurationMs: Long = 5000L
    private var isBulbMode: Boolean = false

    @Volatile
    var accumulatedFrameCount: Int = 0
        private set

    private var bufferWidth: Int = 0
    private var bufferHeight: Int = 0

    // Accumulation buffers
    private var lightTrailsPixels: IntArray? = null
    private var silkySumR: IntArray? = null
    private var silkySumG: IntArray? = null
    private var silkySumB: IntArray? = null

    // Reusable preview bitmap
    private var previewBitmap: Bitmap? = null
    private val previewLock = Any()

    /**
     * Listener callback for exposure progress.
     */
    var onProgressUpdate: ((LongExposureProgress) -> Unit)? = null

    /**
     * Listener callback when a timed exposure completes automatically.
     */
    var onExposureCompleted: ((Bitmap) -> Unit)? = null

    /**
     * Start a new long exposure capture session.
     */
    fun startExposure(
        mode: LongExposureMode = activeMode,
        durationSeconds: Float = targetDurationSeconds
    ) {
        synchronized(this) {
            activeMode = mode
            targetDurationSeconds = durationSeconds
            isBulbMode = (durationSeconds <= 0f)
            targetDurationMs = if (isBulbMode) 0L else (durationSeconds * 1000f).toLong()
            startTimeMs = System.currentTimeMillis()
            accumulatedFrameCount = 0
            isExposing = true

            // Reset buffers
            lightTrailsPixels = null
            silkySumR = null
            silkySumG = null
            silkySumB = null
            bufferWidth = 0
            bufferHeight = 0
        }
        Log.i(TAG, "Started Long Exposure session: mode=$mode, duration=${if (isBulbMode) "BULB" else "${durationSeconds}s"}")
    }

    /**
     * Process an incoming camera preview/analysis frame into the accumulation buffer.
     * Must be called from the camera frame processing pipeline.
     */
    fun accumulateFrame(frameBitmap: Bitmap, rotation: Int, isFront: Boolean) {
        if (!isExposing) return

        val now = System.currentTimeMillis()
        val elapsed = now - startTimeMs

        // Check if timed exposure reached target
        if (!isBulbMode && elapsed >= targetDurationMs) {
            val finalBitmap = finishExposure()
            onExposureCompleted?.invoke(finalBitmap)
            return
        }

        val uprightBitmap = if (rotation != 0 || isFront) {
            rotateAndMirrorBitmap(frameBitmap, rotation, isFront)
        } else {
            frameBitmap
        }

        val w = uprightBitmap.width
        val h = uprightBitmap.height
        val totalPixels = w * h

        val currentPixels = IntArray(totalPixels)
        uprightBitmap.getPixels(currentPixels, 0, w, 0, 0, w, h)

        accumulatePixels(currentPixels, w, h)

        if (uprightBitmap !== frameBitmap) {
            uprightBitmap.recycle()
        }

        // Notify progress
        val progressRatio = if (isBulbMode || targetDurationMs <= 0L) {
            0f
        } else {
            (elapsed.toFloat() / targetDurationMs.toFloat()).coerceIn(0f, 1f)
        }

        onProgressUpdate?.invoke(
            LongExposureProgress(
                elapsedMs = elapsed,
                targetDurationMs = targetDurationMs,
                frameCount = accumulatedFrameCount,
                isBulb = isBulbMode,
                progressRatio = progressRatio
            )
        )
    }

    /**
     * Accumulate raw pixels into the buffer.
     */
    fun accumulatePixels(currentPixels: IntArray, w: Int, h: Int) {
        val totalPixels = w * h
        synchronized(this) {
            if (!isExposing) return

            // Initialize or re-initialize buffers if dimensions changed
            if (bufferWidth != w || bufferHeight != h) {
                bufferWidth = w
                bufferHeight = h
                lightTrailsPixels = IntArray(totalPixels)
                silkySumR = IntArray(totalPixels)
                silkySumG = IntArray(totalPixels)
                silkySumB = IntArray(totalPixels)
                accumulatedFrameCount = 0
            }

            when (activeMode) {
                LongExposureMode.LIGHT_TRAILS -> {
                    val acc = lightTrailsPixels ?: return
                    if (accumulatedFrameCount == 0) {
                        System.arraycopy(currentPixels, 0, acc, 0, totalPixels)
                    } else {
                        for (i in 0 until totalPixels) {
                            val newPix = currentPixels[i]
                            val rNew = (newPix shr 16) and 0xFF
                            val gNew = (newPix shr 8) and 0xFF
                            val bNew = newPix and 0xFF
                            val lumaNew = (rNew * 299 + gNew * 587 + bNew * 114) / 1000

                            val accPix = acc[i]
                            val rAcc = (accPix shr 16) and 0xFF
                            val gAcc = (accPix shr 8) and 0xFF
                            val bAcc = accPix and 0xFF
                            val lumaAcc = (rAcc * 299 + gAcc * 587 + bAcc * 114) / 1000

                            if (lumaNew > lumaAcc) {
                                // Keep the brighter light trail
                                acc[i] = (0xFF shl 24) or
                                        (maxOf(rAcc, rNew) shl 16) or
                                        (maxOf(gAcc, gNew) shl 8) or
                                        maxOf(bAcc, bNew)
                            } else {
                                // Slight temporal smoothing for stable background
                                val r = (rAcc * 97 + rNew * 3) / 100
                                val g = (gAcc * 97 + gNew * 3) / 100
                                val b = (bAcc * 97 + bNew * 3) / 100
                                acc[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        }
                    }
                }

                LongExposureMode.SILKY_WATER -> {
                    val sR = silkySumR ?: return
                    val sG = silkySumG ?: return
                    val sB = silkySumB ?: return

                    for (i in 0 until totalPixels) {
                        val pix = currentPixels[i]
                        sR[i] += (pix shr 16) and 0xFF
                        sG[i] += (pix shr 8) and 0xFF
                        sB[i] += pix and 0xFF
                    }
                }

                LongExposureMode.STAR_TRAILS -> {
                    val acc = lightTrailsPixels ?: return
                    val sR = silkySumR ?: return
                    val sG = silkySumG ?: return
                    val sB = silkySumB ?: return

                    if (accumulatedFrameCount == 0) {
                        System.arraycopy(currentPixels, 0, acc, 0, totalPixels)
                    }
                    for (i in 0 until totalPixels) {
                        val pix = currentPixels[i]
                        val rNew = (pix shr 16) and 0xFF
                        val gNew = (pix shr 8) and 0xFF
                        val bNew = pix and 0xFF
                        val lumaNew = (rNew * 299 + gNew * 587 + bNew * 114) / 1000

                        sR[i] += rNew
                        sG[i] += gNew
                        sB[i] += bNew

                        val accPix = acc[i]
                        val rAcc = (accPix shr 16) and 0xFF
                        val gAcc = (accPix shr 8) and 0xFF
                        val bAcc = accPix and 0xFF
                        val lumaAcc = (rAcc * 299 + gAcc * 587 + bAcc * 114) / 1000

                        // Star threshold boost
                        if (lumaNew > 70 && lumaNew > lumaAcc) {
                            acc[i] = (0xFF shl 24) or (rNew shl 16) or (gNew shl 8) or bNew
                        } else {
                            val count = accumulatedFrameCount + 1
                            val avgR = sR[i] / count
                            val avgG = sG[i] / count
                            val avgB = sB[i] / count
                            acc[i] = (0xFF shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
                        }
                    }
                }

                LongExposureMode.TIMED_BULB -> {
                    val sR = silkySumR ?: return
                    val sG = silkySumG ?: return
                    val sB = silkySumB ?: return
                    val acc = lightTrailsPixels ?: return

                    if (accumulatedFrameCount == 0) {
                        System.arraycopy(currentPixels, 0, acc, 0, totalPixels)
                    }
                    for (i in 0 until totalPixels) {
                        val pix = currentPixels[i]
                        val rNew = (pix shr 16) and 0xFF
                        val gNew = (pix shr 8) and 0xFF
                        val bNew = pix and 0xFF
                        sR[i] += rNew
                        sG[i] += gNew
                        sB[i] += bNew

                        val accPix = acc[i]
                        val rAcc = (accPix shr 16) and 0xFF
                        val gAcc = (accPix shr 8) and 0xFF
                        val bAcc = accPix and 0xFF

                        // Weighted composite: 60% temporal average + 40% maximum light
                        val count = accumulatedFrameCount + 1
                        val avgR = sR[i] / count
                        val avgG = sG[i] / count
                        val avgB = sB[i] / count

                        val maxR = maxOf(rAcc, rNew)
                        val maxG = maxOf(gAcc, gNew)
                        val maxB = maxOf(bAcc, bNew)

                        val finalR = (avgR * 6 + maxR * 4) / 10
                        val finalG = (avgG * 6 + maxG * 4) / 10
                        val finalB = (avgB * 6 + maxB * 4) / 10

                        acc[i] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
                    }
                }
            }

            accumulatedFrameCount++

            // Update live preview bitmap
            updateLivePreviewBitmap(w, h)
        }
    }

    /**
     * Renders current accumulation buffer into a preview bitmap.
     */
    private fun updateLivePreviewBitmap(width: Int, height: Int) {
        val count = accumulatedFrameCount.coerceAtLeast(1)
        val total = width * height
        val outPixels = IntArray(total)

        when (activeMode) {
            LongExposureMode.LIGHT_TRAILS, LongExposureMode.STAR_TRAILS, LongExposureMode.TIMED_BULB -> {
                lightTrailsPixels?.let { System.arraycopy(it, 0, outPixels, 0, total) }
            }
            LongExposureMode.SILKY_WATER -> {
                val sR = silkySumR ?: return
                val sG = silkySumG ?: return
                val sB = silkySumB ?: return
                for (i in 0 until total) {
                    val r = (sR[i] / count).coerceIn(0, 255)
                    val g = (sG[i] / count).coerceIn(0, 255)
                    val b = (sB[i] / count).coerceIn(0, 255)
                    outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        synchronized(previewLock) {
            val bmp = previewBitmap
            if (bmp == null || bmp.width != width || bmp.height != height || bmp.isRecycled) {
                bmp?.recycle()
                previewBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            previewBitmap?.setPixels(outPixels, 0, width, 0, 0, width, height)
        }
    }

    /**
     * Returns the current live accumulated exposure bitmap for viewfinder overlay.
     */
    fun getLivePreviewBitmap(): Bitmap? {
        synchronized(previewLock) {
            return previewBitmap?.takeIf { !it.isRecycled }
        }
    }

    /**
     * Stop and finalize the long exposure capture, returning the high-quality final composite bitmap.
     */
    fun finishExposure(): Bitmap {
        val w = bufferWidth.coerceAtLeast(1)
        val h = bufferHeight.coerceAtLeast(1)
        val finalPixels = finishExposurePixels()
        val finalBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        finalBitmap.setPixels(finalPixels, 0, w, 0, 0, w, h)
        return finalBitmap
    }

    /**
     * Stop and finalize the long exposure capture, returning the synthesized pixel array.
     */
    fun finishExposurePixels(): IntArray {
        synchronized(this) {
            isExposing = false
            val w = bufferWidth.coerceAtLeast(1)
            val h = bufferHeight.coerceAtLeast(1)
            val count = accumulatedFrameCount.coerceAtLeast(1)
            val total = w * h
            val finalPixels = IntArray(total)

            when (activeMode) {
                LongExposureMode.LIGHT_TRAILS, LongExposureMode.STAR_TRAILS, LongExposureMode.TIMED_BULB -> {
                    lightTrailsPixels?.let { System.arraycopy(it, 0, finalPixels, 0, total) }
                }
                LongExposureMode.SILKY_WATER -> {
                    val sR = silkySumR
                    val sG = silkySumG
                    val sB = silkySumB
                    if (sR != null && sG != null && sB != null) {
                        for (i in 0 until total) {
                            val r = (sR[i] / count).coerceIn(0, 255)
                            val g = (sG[i] / count).coerceIn(0, 255)
                            val b = (sB[i] / count).coerceIn(0, 255)
                            finalPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                }
            }

            // Clear buffers
            lightTrailsPixels = null
            silkySumR = null
            silkySumG = null
            silkySumB = null

            Log.i(TAG, "Finished Long Exposure: $count frames synthesized into ${w}x$h pixels")
            return finalPixels
        }
    }

    /**
     * Cancel an active session without saving.
     */
    fun cancelExposure() {
        synchronized(this) {
            isExposing = false
            lightTrailsPixels = null
            silkySumR = null
            silkySumG = null
            silkySumB = null
            synchronized(previewLock) {
                previewBitmap?.recycle()
                previewBitmap = null
            }
        }
    }

    private fun rotateAndMirrorBitmap(src: Bitmap, degrees: Int, mirrorHorizontal: Boolean): Bitmap {
        if (degrees == 0 && !mirrorHorizontal) return src
        val matrix = Matrix()
        if (mirrorHorizontal) {
            matrix.postScale(-1f, 1f)
        }
        if (degrees != 0) {
            matrix.postRotate(degrees.toFloat())
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }
}
