package com.retrocam.app.video

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.retrocam.app.R
import com.retrocam.app.databinding.ActivityVideoReviewBinding
import java.util.Locale

/**
 * Review, preview playback, and management screen for recorded videos.
 */
class VideoReviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoReviewBinding
    private var videoUri: Uri? = null
    private var videoName: String = ""
    private var durationMs: Long = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var isTrackingTouch = false

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            if (binding.videoView.isPlaying && !isTrackingTouch) {
                val current = binding.videoView.currentPosition
                val total = binding.videoView.duration.coerceAtLeast(1)
                binding.videoSeekBar.progress = current
                binding.timeReadout.text = "${formatTime(current.toLong())} / ${formatTime(total.toLong())}"
            }
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uri = intentUri()
        if (uri == null) {
            Toast.makeText(this, "Video not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        videoUri = uri
        videoName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)

        binding.videoTitle.text = videoName
        displayFileSize(uri)

        setUpVideoPlayback(uri)
        setUpButtons()
    }

    @Suppress("DEPRECATION")
    private fun intentUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_URI, Uri::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_URI)
        }

    private fun displayFileSize(uri: Uri) {
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val sizeBytes = pfd.statSize
                if (sizeBytes > 0) {
                    val sizeMb = sizeBytes / (1024f * 1024f)
                    binding.fileSizeBadge.text = String.format(Locale.US, "%.1f MB", sizeMb)
                }
            }
        } catch (e: Exception) {
            binding.fileSizeBadge.text = ""
        }
    }

    private fun setUpVideoPlayback(uri: Uri) {
        binding.videoView.setVideoURI(uri)

        binding.videoView.setOnPreparedListener { mp ->
            val totalDuration = if (durationMs > 0) durationMs.toInt() else mp.duration
            binding.videoSeekBar.max = totalDuration
            binding.timeReadout.text = "00:00 / ${formatTime(totalDuration.toLong())}"
            binding.videoView.seekTo(100) // Show first frame
            updatePlayPauseButton(false)
        }

        binding.videoView.setOnCompletionListener {
            updatePlayPauseButton(false)
            binding.videoSeekBar.progress = binding.videoSeekBar.max
            handler.removeCallbacks(updateProgressRunnable)
        }

        binding.videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val total = binding.videoSeekBar.max
                    binding.timeReadout.text = "${formatTime(progress.toLong())} / ${formatTime(total.toLong())}"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isTrackingTouch = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isTrackingTouch = false
                val target = seekBar?.progress ?: 0
                binding.videoView.seekTo(target)
            }
        })

        binding.playPauseOverlay.setOnClickListener {
            togglePlayback()
        }

        binding.videoView.setOnClickListener {
            togglePlayback()
        }
    }

    private fun togglePlayback() {
        if (binding.videoView.isPlaying) {
            binding.videoView.pause()
            updatePlayPauseButton(false)
            handler.removeCallbacks(updateProgressRunnable)
        } else {
            binding.videoView.start()
            updatePlayPauseButton(true)
            handler.post(updateProgressRunnable)
        }
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        binding.playPauseOverlay.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        binding.playPauseOverlay.contentDescription =
            getString(if (isPlaying) R.string.pause else R.string.play)
        if (isPlaying) {
            binding.playPauseOverlay.animate().alpha(0f).setDuration(400).withEndAction {
                binding.playPauseOverlay.isVisible = false
            }.start()
        } else {
            binding.playPauseOverlay.isVisible = true
            binding.playPauseOverlay.animate().alpha(1f).setDuration(150).start()
        }
    }

    private fun setUpButtons() {
        binding.backButton.setOnClickListener { finish() }
        binding.doneButton.setOnClickListener { finish() }

        binding.shareButton.setOnClickListener {
            val uri = videoUri ?: return@setOnClickListener
            shareVideo(uri)
        }

        binding.retakeButton.setOnClickListener {
            // Confirm retake
            AlertDialog.Builder(this)
                .setTitle(R.string.retake_button)
                .setMessage(R.string.discard_confirm_message)
                .setPositiveButton(R.string.retake_button) { _, _ ->
                    val result = Intent().putExtra(EXTRA_RETAKE, true)
                    setResult(RESULT_OK, result)
                    finish()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.deleteButton.setOnClickListener {
            val uri = videoUri ?: return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.delete_confirm_message)
                .setPositiveButton(R.string.delete_confirm_action) { _, _ ->
                    val deleted = VideoStore.delete(contentResolver, uri)
                    if (deleted) {
                        Toast.makeText(this, "Video deleted", Toast.LENGTH_SHORT).show()
                        val result = Intent().putExtra(EXTRA_DELETED, true)
                        setResult(RESULT_OK, result)
                        finish()
                    } else {
                        Toast.makeText(this, "Could not delete video", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun shareVideo(uri: Uri) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.share_video)))
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hours = minutes / 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes % 60, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    override fun onPause() {
        super.onPause()
        if (binding.videoView.isPlaying) {
            binding.videoView.pause()
            updatePlayPauseButton(false)
        }
        handler.removeCallbacks(updateProgressRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.videoView.stopPlayback()
    }

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_DURATION_MS = "extra_duration_ms"
        const val EXTRA_RETAKE = "extra_retake"
        const val EXTRA_DELETED = "extra_deleted"
    }
}
