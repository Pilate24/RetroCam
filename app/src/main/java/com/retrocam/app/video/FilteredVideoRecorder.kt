package com.retrocam.app.video

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encodes filtered video frames and synchronized microphone audio into an MP4 file using
 * hardware-accelerated MediaCodec and MediaMuxer.
 *
 * Frames are drawn directly to the encoder's input Surface via lockHardwareCanvas(), ensuring
 * complete visual parity with the live AGSL shader preview.
 */
class FilteredVideoRecorder(
    private val outputFile: File,
    val width: Int,
    val height: Int,
    val targetFps: Int = 30,
    val targetBitrate: Int = 6_000_000,
    val audioEnabled: Boolean,
    private val onDurationUpdate: (durationMs: Long) -> Unit,
    private val onError: (message: String, throwable: Throwable?) -> Unit
) {

    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    // Video components
    private var videoEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var videoTrackIndex = -1
    private val videoBufferInfo = MediaCodec.BufferInfo()

    // Audio components
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrackIndex = -1
    private val audioBufferInfo = MediaCodec.BufferInfo()
    private var audioThread: Thread? = null

    // Muxer
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private val muxerLock = Any()
    private val expectedTracks = if (audioEnabled) 2 else 1
    private var numTracksAdded = 0

    // Timing and synchronization
    private var startNanoTime = 0L
    private var pauseStartNanoTime = 0L
    private var accumulatedPauseDurationNano = 0L
    private var lastEncodedFrameNanoTime = 0L
    private var lastVideoPtsUs = 0L
    private var lastAudioPtsUs = 0L
    private var totalRecordedDurationMs = 0L

    // Drawing transform matrix
    private val drawMatrix = Matrix()

    companion object {
        private const val TAG = "FilteredVideoRecorder"
        private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val I_FRAME_INTERVAL = 1 // 1 keyframe per second
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_BIT_RATE = 128_000
        private const val TIMEOUT_USEC = 10_000L
    }

    /**
     * Initializes and starts the video and audio encoding pipelines.
     */
    fun start() {
        try {
            // Setup Video Encoder
            val videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }

            val vEncoder = MediaCodec.createEncoderByType(VIDEO_MIME)
            vEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = vEncoder.createInputSurface()
            vEncoder.start()
            videoEncoder = vEncoder

            // Setup MediaMuxer
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Setup Audio Encoder & AudioRecord if enabled
            if (audioEnabled) {
                setupAudioPipeline()
            }

            isRecording.set(true)
            isPaused.set(false)
            startNanoTime = System.nanoTime()
            accumulatedPauseDurationNano = 0L

            Log.i(TAG, "FilteredVideoRecorder started successfully: ${width}x${height}, audio=$audioEnabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FilteredVideoRecorder", e)
            release()
            onError("Failed to initialize video recorder: ${e.message}", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupAudioPipeline() {
        try {
            val audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME, AUDIO_SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            val aEncoder = MediaCodec.createEncoderByType(AUDIO_MIME)
            aEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aEncoder.start()
            audioEncoder = aEncoder

            val minBufferSize = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val aRecord = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize * 2
            )

            if (aRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord could not be initialized, falling back to silent video")
                aRecord.release()
                return
            }

            aRecord.startRecording()
            audioRecord = aRecord

            // Spawn audio recording thread
            val aThread = Thread({ audioRecordingLoop() }, "FilteredAudioRecordThread")
            audioThread = aThread
            aThread.start()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to setup audio recording pipeline, continuing without audio", e)
        }
    }

    private val frameLock = Any()

    /**
     * Feeds a rendered frame into the video encoder.
     */
    fun encodeFrame(bitmap: Bitmap) {
        if (!isRecording.get() || isPaused.get()) return

        val now = System.nanoTime()
        val minIntervalNano = (1_000_000_000L / targetFps) - 3_000_000L
        if (lastEncodedFrameNanoTime != 0L && (now - lastEncodedFrameNanoTime) < minIntervalNano) {
            return
        }

        val surface = inputSurface ?: return

        synchronized(frameLock) {
            if (!isRecording.get() || isPaused.get()) return
            try {
                val canvas = surface.lockHardwareCanvas() ?: return
                try {
                    canvas.drawColor(Color.BLACK)
                    val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
                    val dx = (width - bitmap.width * scale) / 2f
                    val dy = (height - bitmap.height * scale) / 2f
                    drawMatrix.setScale(scale, scale)
                    drawMatrix.postTranslate(dx, dy)
                    canvas.drawBitmap(bitmap, drawMatrix, null)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }

                lastEncodedFrameNanoTime = now
                drainVideoEncoder(endOfStream = false)

                // Update duration
                val elapsedNano = now - startNanoTime - accumulatedPauseDurationNano
                val durationMs = (elapsedNano / 1_000_000L).coerceAtLeast(0L)
                totalRecordedDurationMs = durationMs
                onDurationUpdate(durationMs)
            } catch (e: Exception) {
                Log.w(TAG, "Failed encoding frame", e)
            }
        }
    }

    /**
     * Pauses recording.
     */
    fun pause() {
        if (isRecording.get() && !isPaused.get()) {
            isPaused.set(true)
            pauseStartNanoTime = System.nanoTime()
            Log.i(TAG, "Recording paused at $totalRecordedDurationMs ms")
        }
    }

    /**
     * Resumes recording.
     */
    fun resume() {
        if (isRecording.get() && isPaused.get()) {
            val pausedNanos = System.nanoTime() - pauseStartNanoTime
            accumulatedPauseDurationNano += pausedNanos
            isPaused.set(false)
            Log.i(TAG, "Recording resumed, accumulated pause duration: ${accumulatedPauseDurationNano / 1_000_000} ms")
        }
    }

    /**
     * Stops recording and finalizes the MP4 file.
     * Returns total recorded duration in milliseconds.
     */
    fun stop(): Long {
        if (!isRecording.get()) return totalRecordedDurationMs
        isRecording.set(false)
        isPaused.set(false)

        synchronized(frameLock) {
            try {
                Log.i(TAG, "Stopping FilteredVideoRecorder...")

                // Signal end of stream to video encoder
                videoEncoder?.signalEndOfInputStream()
                drainVideoEncoder(endOfStream = true)

                // Stop audio recording thread
                audioRecord?.let {
                    try {
                        it.stop()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error stopping audioRecord", e)
                    }
                }
                audioThread?.join(1000)

                // Signal end of stream to audio encoder
                audioEncoder?.let {
                    drainAudioEncoder(endOfStream = true)
                }

                // Stop muxer
                synchronized(muxerLock) {
                    if (muxerStarted) {
                        try {
                            muxer?.stop()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error stopping MediaMuxer", e)
                        }
                        muxerStarted = false
                    }
                }

                Log.i(TAG, "FilteredVideoRecorder finalized. Total duration: $totalRecordedDurationMs ms")
            } catch (e: Exception) {
                Log.e(TAG, "Error finalizing recording", e)
                onError("Failed finalizing video recording: ${e.message}", e)
            } finally {
                release()
            }
        }

        return totalRecordedDurationMs
    }

    private fun drainVideoEncoder(endOfStream: Boolean) {
        val encoder = videoEncoder ?: return
        while (true) {
            val encoderStatus = encoder.dequeueOutputBuffer(videoBufferInfo, TIMEOUT_USEC)
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    Log.w(TAG, "Video format changed twice, ignoring")
                } else {
                    val newFormat = encoder.outputFormat
                    synchronized(muxerLock) {
                        videoTrackIndex = muxer?.addTrack(newFormat) ?: -1
                        numTracksAdded++
                        Log.i(TAG, "Video track added index: $videoTrackIndex. Tracks added: $numTracksAdded/$expectedTracks")
                        if (numTracksAdded >= expectedTracks) {
                            muxer?.start()
                            muxerStarted = true
                            Log.i(TAG, "MediaMuxer started by video track")
                        }
                    }
                }
            } else if (encoderStatus >= 0) {
                val encodedData = encoder.getOutputBuffer(encoderStatus)
                    ?: error("video encoder outputBuffer $encoderStatus was null")

                if ((videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    videoBufferInfo.size = 0
                }

                if (videoBufferInfo.size != 0) {
                    synchronized(muxerLock) {
                        if (!muxerStarted) {
                            Log.w(TAG, "muxer has not started yet, dropping video sample")
                        } else {
                            // Adjust presentation timestamp
                            val pts = computeEffectivePts(videoBufferInfo.presentationTimeUs, isVideo = true)
                            videoBufferInfo.presentationTimeUs = pts
                            encodedData.position(videoBufferInfo.offset)
                            encodedData.limit(videoBufferInfo.offset + videoBufferInfo.size)
                            muxer?.writeSampleData(videoTrackIndex, encodedData, videoBufferInfo)
                        }
                    }
                }

                encoder.releaseOutputBuffer(encoderStatus, false)

                if ((videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.i(TAG, "Video end of stream reached")
                    break
                }
            }
        }
    }

    private fun audioRecordingLoop() {
        val aRecord = audioRecord ?: return
        val aEncoder = audioEncoder ?: return
        val buffer = ByteArray(2048)

        while (isRecording.get()) {
            if (isPaused.get()) {
                try {
                    Thread.sleep(20)
                } catch (ignored: InterruptedException) {
                }
                continue
            }

            val readBytes = aRecord.read(buffer, 0, buffer.size)
            if (readBytes > 0 && isRecording.get() && !isPaused.get()) {
                val inputIndex = aEncoder.dequeueInputBuffer(TIMEOUT_USEC)
                if (inputIndex >= 0) {
                    val inputBuffer = aEncoder.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        inputBuffer.put(buffer, 0, readBytes)
                        val nowNano = System.nanoTime()
                        val ptsUs = (nowNano - startNanoTime - accumulatedPauseDurationNano) / 1000L
                        aEncoder.queueInputBuffer(inputIndex, 0, readBytes, ptsUs.coerceAtLeast(0L), 0)
                    }
                }
                drainAudioEncoder(endOfStream = false)
            }
        }
    }

    private fun drainAudioEncoder(endOfStream: Boolean) {
        val encoder = audioEncoder ?: return
        while (true) {
            val encoderStatus = encoder.dequeueOutputBuffer(audioBufferInfo, TIMEOUT_USEC)
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted && audioTrackIndex != -1) {
                    Log.w(TAG, "Audio format changed twice, ignoring")
                } else {
                    val newFormat = encoder.outputFormat
                    synchronized(muxerLock) {
                        audioTrackIndex = muxer?.addTrack(newFormat) ?: -1
                        numTracksAdded++
                        Log.i(TAG, "Audio track added index: $audioTrackIndex. Tracks added: $numTracksAdded/$expectedTracks")
                        if (numTracksAdded >= expectedTracks && !muxerStarted) {
                            muxer?.start()
                            muxerStarted = true
                            Log.i(TAG, "MediaMuxer started by audio track")
                        }
                    }
                }
            } else if (encoderStatus >= 0) {
                val encodedData = encoder.getOutputBuffer(encoderStatus)
                    ?: error("audio encoder outputBuffer $encoderStatus was null")

                if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    audioBufferInfo.size = 0
                }

                if (audioBufferInfo.size != 0) {
                    synchronized(muxerLock) {
                        if (!muxerStarted) {
                            Log.w(TAG, "muxer has not started yet, dropping audio sample")
                        } else {
                            val pts = computeEffectivePts(audioBufferInfo.presentationTimeUs, isVideo = false)
                            audioBufferInfo.presentationTimeUs = pts
                            encodedData.position(audioBufferInfo.offset)
                            encodedData.limit(audioBufferInfo.offset + audioBufferInfo.size)
                            muxer?.writeSampleData(audioTrackIndex, encodedData, audioBufferInfo)
                        }
                    }
                }

                encoder.releaseOutputBuffer(encoderStatus, false)

                if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.i(TAG, "Audio end of stream reached")
                    break
                }
            }
        }
    }

    private fun computeEffectivePts(ptsUs: Long, isVideo: Boolean): Long {
        return if (isVideo) {
            val adjusted = if (ptsUs <= lastVideoPtsUs) lastVideoPtsUs + 1_000L else ptsUs
            lastVideoPtsUs = adjusted
            adjusted
        } else {
            val adjusted = if (ptsUs <= lastAudioPtsUs) lastAudioPtsUs + 500L else ptsUs
            lastAudioPtsUs = adjusted
            adjusted
        }
    }

    fun release() {
        try {
            videoEncoder?.stop()
        } catch (ignored: Exception) {
        }
        try {
            videoEncoder?.release()
        } catch (ignored: Exception) {
        }
        videoEncoder = null

        try {
            inputSurface?.release()
        } catch (ignored: Exception) {
        }
        inputSurface = null

        try {
            audioRecord?.stop()
        } catch (ignored: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (ignored: Exception) {
        }
        audioRecord = null

        try {
            audioEncoder?.stop()
        } catch (ignored: Exception) {
        }
        try {
            audioEncoder?.release()
        } catch (ignored: Exception) {
        }
        audioEncoder = null

        try {
            muxer?.release()
        } catch (ignored: Exception) {
        }
        muxer = null
    }
}
