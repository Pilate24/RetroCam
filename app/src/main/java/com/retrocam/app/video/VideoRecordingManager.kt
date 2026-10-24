package com.retrocam.app.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages video recording sessions, real-time filtered frame ingestion, recording state machine,
 * recording timers, and atomic MediaStore export to Movies/RetroCam.
 */
class VideoRecordingManager(
    private val context: Context,
    private val onStateChanged: (RecordingState) -> Unit
) {

    sealed class RecordingState {
        object Idle : RecordingState()
        data class Starting(val audioEnabled: Boolean) : RecordingState()
        data class Recording(val durationSeconds: Long, val audioEnabled: Boolean) : RecordingState()
        data class Paused(val durationSeconds: Long, val audioEnabled: Boolean) : RecordingState()
        object Finalizing : RecordingState()
        data class Success(val mediaStoreUri: Uri, val displayName: String, val durationMs: Long) : RecordingState()
        data class Error(val message: String, val throwable: Throwable? = null) : RecordingState()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var activeRecorder: FilteredVideoRecorder? = null
    private var currentTempFile: File? = null
    private var currentDisplayName: String = ""
    private var audioEnabledForCurrent: Boolean = false
    private var recordedDurationMs: Long = 0L

    var isRecording: Boolean = false
        private set
    var isPaused: Boolean = false
        private set

    init {
        cleanTempDirectory()
    }

    /**
     * Starts recording filtered video. Width and height are aligned to even values for encoder compatibility.
     */
    fun startRecording(
        rawWidth: Int,
        rawHeight: Int,
        fps: VideoFps = VideoFps.FPS_30,
        quality: VideoQuality = VideoQuality.FHD_1080P
    ) {
        if (activeRecorder != null || isRecording) {
            Log.w(TAG, "Recording already in progress, ignoring start request")
            return
        }

        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        audioEnabledForCurrent = hasAudioPermission

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        currentDisplayName = "VID_$timestamp.mp4"

        val videoDir = File(context.cacheDir, "videos").apply { if (!exists()) mkdirs() }
        val tempFile = File(videoDir, "staging_$currentDisplayName")
        currentTempFile = tempFile

        // Divisible by 4 and safe minimum for universal H.264 hardware encoder support
        val encWidth = ((rawWidth / 4) * 4).coerceAtLeast(320)
        val encHeight = ((rawHeight / 4) * 4).coerceAtLeast(320)
        val targetBitrate = quality.bitrateFor(fps)

        onStateChanged(RecordingState.Starting(audioEnabledForCurrent))

        try {
            val recorder = FilteredVideoRecorder(
                outputFile = tempFile,
                width = encWidth,
                height = encHeight,
                targetFps = fps.fps,
                targetBitrate = targetBitrate,
                audioEnabled = audioEnabledForCurrent,
                onDurationUpdate = { durationMs ->
                    recordedDurationMs = durationMs
                    val seconds = durationMs / 1000L
                    mainHandler.post {
                        if (isRecording && !isPaused) {
                            onStateChanged(RecordingState.Recording(seconds, audioEnabledForCurrent))
                        }
                    }
                },
                onError = { message, throwable ->
                    mainHandler.post {
                        onRecordingError(message, throwable)
                    }
                }
            )

            recorder.start()
            activeRecorder = recorder
            isRecording = true
            isPaused = false
            onStateChanged(RecordingState.Recording(0L, audioEnabledForCurrent))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording session", e)
            isRecording = false
            isPaused = false
            activeRecorder = null
            tempFile.delete()
            onStateChanged(RecordingState.Error("Failed to start recording: ${e.message}", e))
        }
    }

    /**
     * Enqueues a filtered bitmap frame to the active video recorder.
     */
    fun onNewFrame(bitmap: Bitmap) {
        if (isRecording && !isPaused) {
            activeRecorder?.encodeFrame(bitmap)
        }
    }

    /**
     * Pauses the active recording session.
     */
    fun pauseRecording() {
        if (isRecording && !isPaused) {
            isPaused = true
            activeRecorder?.pause()
            val seconds = recordedDurationMs / 1000L
            onStateChanged(RecordingState.Paused(seconds, audioEnabledForCurrent))
        }
    }

    /**
     * Resumes the paused recording session.
     */
    fun resumeRecording() {
        if (isRecording && isPaused) {
            isPaused = false
            activeRecorder?.resume()
            val seconds = recordedDurationMs / 1000L
            onStateChanged(RecordingState.Recording(seconds, audioEnabledForCurrent))
        }
    }

    /**
     * Stops the active recording session and publishes output to MediaStore.
     */
    fun stopRecording() {
        val recorder = activeRecorder ?: return
        isRecording = false
        isPaused = false
        activeRecorder = null
        onStateChanged(RecordingState.Finalizing)

        ioExecutor.execute {
            try {
                val finalDurationMs = recorder.stop()
                val durationMs = if (finalDurationMs > 0) finalDurationMs else recordedDurationMs

                val file = currentTempFile
                val displayName = currentDisplayName

                if (file == null || !file.exists() || file.length() == 0L) {
                    Log.e(TAG, "Recording finalized but output file is missing or empty")
                    mainHandler.post {
                        onStateChanged(RecordingState.Error("Recorded file is empty or corrupted"))
                    }
                    return@execute
                }

                val uri = VideoStore.saveToMediaStore(
                    context.contentResolver,
                    file,
                    displayName,
                    durationMs
                )
                file.delete()
                currentTempFile = null

                mainHandler.post {
                    if (uri != null) {
                        onStateChanged(
                            RecordingState.Success(uri, displayName, durationMs)
                        )
                    } else {
                        onStateChanged(
                            RecordingState.Error("Failed to save recording to gallery")
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error finalizing recording", e)
                mainHandler.post {
                    onStateChanged(RecordingState.Error("Failed to finalize video: ${e.message}", e))
                }
            }
        }
    }

    private fun onRecordingError(message: String, throwable: Throwable?) {
        isRecording = false
        isPaused = false
        activeRecorder?.release()
        activeRecorder = null
        currentTempFile?.delete()
        currentTempFile = null
        onStateChanged(RecordingState.Error(message, throwable))
    }

    /**
     * Cleans up orphaned temporary files from cache.
     */
    private fun cleanTempDirectory() {
        ioExecutor.execute {
            try {
                val videoDir = File(context.cacheDir, "videos")
                if (videoDir.exists() && videoDir.isDirectory) {
                    videoDir.listFiles()?.forEach { file ->
                        if (file.isFile && file.name.startsWith("staging_")) {
                            file.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed cleaning cache video directory", e)
            }
        }
    }

    fun release() {
        if (isRecording) {
            stopRecording()
        }
        activeRecorder?.release()
        activeRecorder = null
        ioExecutor.shutdown()
    }

    companion object {
        private const val TAG = "VideoRecordingManager"
    }
}
