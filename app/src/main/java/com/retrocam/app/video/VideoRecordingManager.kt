package com.retrocam.app.video

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages CameraX VideoCapture, active Recording sessions, recording timers, and MediaStore export.
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

    val videoCapture: VideoCapture<Recorder>

    private var activeRecording: Recording? = null
    private var currentTempFile: File? = null
    private var currentDisplayName: String = ""
    private var audioEnabledForCurrent: Boolean = false
    private var recordedDurationMs: Long = 0L

    var isRecording: Boolean = false
        private set
    var isPaused: Boolean = false
        private set

    init {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.HIGHEST,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                )
            )
            .build()
        videoCapture = VideoCapture.withOutput(recorder)
        cleanTempDirectory()
    }

    /**
     * Starts video recording to a staging file in context.cacheDir/videos.
     * Records audio if RECORD_AUDIO permission is granted; records silent video otherwise.
     */
    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (activeRecording != null || isRecording) {
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

        val outputOptions = FileOutputOptions.Builder(tempFile).build()
        val pending = videoCapture.output.prepareRecording(context, outputOptions)

        if (hasAudioPermission) {
            try {
                pending.withAudioEnabled()
            } catch (e: SecurityException) {
                Log.w(TAG, "Microphone permission revoked between check and start", e)
                audioEnabledForCurrent = false
            }
        }

        onStateChanged(RecordingState.Starting(audioEnabledForCurrent))

        try {
            activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                handleRecordEvent(event)
            }
            isRecording = true
            isPaused = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording session", e)
            isRecording = false
            isPaused = false
            activeRecording = null
            tempFile.delete()
            onStateChanged(RecordingState.Error("Failed to start recording: ${e.message}", e))
        }
    }

    /**
     * Pauses the active recording session if supported.
     */
    fun pauseRecording() {
        val recording = activeRecording ?: return
        if (isRecording && !isPaused) {
            try {
                recording.pause()
            } catch (e: Exception) {
                Log.w(TAG, "Pause failed", e)
            }
        }
    }

    /**
     * Resumes the paused recording session.
     */
    fun resumeRecording() {
        val recording = activeRecording ?: return
        if (isRecording && isPaused) {
            try {
                recording.resume()
            } catch (e: Exception) {
                Log.w(TAG, "Resume failed", e)
            }
        }
    }

    /**
     * Stops the active recording session. Output will be processed in Finalize event.
     */
    fun stopRecording() {
        val recording = activeRecording ?: return
        isRecording = false
        isPaused = false
        onStateChanged(RecordingState.Finalizing)
        try {
            recording.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Stop recording failed", e)
            onStateChanged(RecordingState.Error("Failed to stop recording: ${e.message}", e))
        }
    }

    private fun handleRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                isRecording = true
                isPaused = false
                onStateChanged(RecordingState.Recording(0L, audioEnabledForCurrent))
            }

            is VideoRecordEvent.Status -> {
                val durationNanos = event.recordingStats.recordedDurationNanos
                val seconds = durationNanos / 1_000_000_000L
                recordedDurationMs = durationNanos / 1_000_000L
                if (!isPaused) {
                    onStateChanged(RecordingState.Recording(seconds, audioEnabledForCurrent))
                }
            }

            is VideoRecordEvent.Pause -> {
                isPaused = true
                val seconds = recordedDurationMs / 1000L
                onStateChanged(RecordingState.Paused(seconds, audioEnabledForCurrent))
            }

            is VideoRecordEvent.Resume -> {
                isPaused = false
                val seconds = recordedDurationMs / 1000L
                onStateChanged(RecordingState.Recording(seconds, audioEnabledForCurrent))
            }

            is VideoRecordEvent.Finalize -> {
                isRecording = false
                isPaused = false
                activeRecording = null

                if (event.hasError()) {
                    Log.e(TAG, "Recording finalized with error code: ${event.error}", event.cause)
                    currentTempFile?.delete()
                    currentTempFile = null
                    onStateChanged(
                        RecordingState.Error("Recording error (${event.error})", event.cause)
                    )
                } else {
                    val file = currentTempFile
                    val displayName = currentDisplayName
                    val durationMs = recordedDurationMs

                    if (file == null || !file.exists() || file.length() == 0L) {
                        Log.e(TAG, "Recording finalized but output file is missing or empty")
                        onStateChanged(RecordingState.Error("Recorded file is empty or corrupted"))
                        return
                    }

                    // Save to MediaStore Movies/RetroCam on background thread
                    ioExecutor.execute {
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
                    }
                }
            }
        }
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

    /**
     * Call when leaving the screen or activity onDestroy.
     */
    fun release() {
        if (isRecording) {
            stopRecording()
        }
        ioExecutor.shutdown()
    }

    companion object {
        private const val TAG = "VideoRecordingManager"
    }
}
