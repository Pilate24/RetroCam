package com.retrocam.app.gallery

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.retrocam.app.R
import com.retrocam.app.library.model.MediaItem
import com.retrocam.app.library.ui.ZoomableImageView
import java.util.Locale

class MediaPagerAdapter(
    private val context: Context,
    private val onSingleTap: () -> Unit
) : RecyclerView.Adapter<MediaPagerAdapter.MediaViewHolder>() {

    private val mediaList = mutableListOf<MediaItem>()

    fun submitList(list: List<MediaItem>) {
        mediaList.clear()
        mediaList.addAll(list)
        notifyDataSetChanged()
    }

    fun getItem(position: Int): MediaItem? {
        return mediaList.getOrNull(position)
    }

    override fun getItemCount(): Int = mediaList.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_viewer_media, parent, false)
        return MediaViewHolder(view)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(mediaList[position])
    }

    override fun onViewRecycled(holder: MediaViewHolder) {
        super.onViewRecycled(holder)
        holder.recycle()
    }

    inner class MediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val zoomablePhoto: ZoomableImageView = view.findViewById(R.id.zoomablePhoto)
        private val videoContainer: View = view.findViewById(R.id.videoContainer)
        private val videoPlayer: VideoView = view.findViewById(R.id.videoPlayer)
        private val playPauseOverlay: ImageView = view.findViewById(R.id.videoPlayPauseOverlay)
        private val videoControlsBar: View = view.findViewById(R.id.videoControlsBar)
        private val timeCurrent: TextView = view.findViewById(R.id.videoTimeCurrent)
        private val timeTotal: TextView = view.findViewById(R.id.videoTimeTotal)
        private val seekBar: SeekBar = view.findViewById(R.id.videoSeekBar)
        private val btnSpeed: Button = view.findViewById(R.id.btnPlaybackSpeed)

        private val handler = Handler(Looper.getMainLooper())
        private var isTrackingTouch = false
        private var playbackSpeed = 1.0f
        private var mediaPlayer: MediaPlayer? = null

        private val progressRunnable = object : Runnable {
            override fun run() {
                if (videoPlayer.isPlaying && !isTrackingTouch) {
                    val pos = videoPlayer.currentPosition
                    seekBar.progress = pos
                    timeCurrent.text = formatTime(pos.toLong())
                }
                handler.postDelayed(this, 250)
            }
        }

        fun bind(item: MediaItem) {
            itemView.setOnClickListener { onSingleTap() }

            if (item.isVideo) {
                zoomablePhoto.isVisible = false
                videoContainer.isVisible = true
                setupVideo(item)
            } else {
                videoContainer.isVisible = false
                zoomablePhoto.isVisible = true
                zoomablePhoto.setImageURI(item.uri)
            }
        }

        private fun setupVideo(item: MediaItem) {
            videoPlayer.setVideoURI(item.uri)
            videoPlayer.setOnPreparedListener { mp ->
                mediaPlayer = mp
                val duration = if (item.durationMs > 0) item.durationMs.toInt() else mp.duration
                seekBar.max = duration
                timeTotal.text = formatTime(duration.toLong())
                timeCurrent.text = "00:00"
                videoPlayer.seekTo(100) // Show first frame
                updatePlayPauseState(false)
            }

            videoPlayer.setOnCompletionListener {
                updatePlayPauseState(false)
                handler.removeCallbacks(progressRunnable)
            }

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        timeCurrent.text = formatTime(progress.toLong())
                    }
                }
                override fun onStartTrackingTouch(sBar: SeekBar?) {
                    isTrackingTouch = true
                }
                override fun onStopTrackingTouch(sBar: SeekBar?) {
                    isTrackingTouch = false
                    videoPlayer.seekTo(sBar?.progress ?: 0)
                }
            })

            playPauseOverlay.setOnClickListener { togglePlayback() }
            videoPlayer.setOnClickListener { togglePlayback() }

            btnSpeed.setOnClickListener {
                playbackSpeed = when (playbackSpeed) {
                    1.0f -> 1.5f
                    1.5f -> 2.0f
                    2.0f -> 0.5f
                    else -> 1.0f
                }
                btnSpeed.text = "${playbackSpeed}x"
                try {
                    val mp = mediaPlayer
                    if (mp != null) {
                        val params = mp.playbackParams
                        params.speed = playbackSpeed
                        mp.playbackParams = params
                    }
                } catch (e: Exception) {
                    // Ignore if device playbackParams unavailable
                }
            }
        }

        private fun togglePlayback() {
            if (videoPlayer.isPlaying) {
                videoPlayer.pause()
                updatePlayPauseState(false)
                handler.removeCallbacks(progressRunnable)
            } else {
                videoPlayer.start()
                updatePlayPauseState(true)
                handler.post(progressRunnable)
            }
        }

        private fun updatePlayPauseState(isPlaying: Boolean) {
            playPauseOverlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            if (isPlaying) {
                playPauseOverlay.animate().alpha(0f).setDuration(400).withEndAction {
                    playPauseOverlay.isVisible = false
                }.start()
            } else {
                playPauseOverlay.isVisible = true
                playPauseOverlay.animate().alpha(1f).setDuration(150).start()
            }
        }

        private fun formatTime(ms: Long): String {
            val totalSec = (ms / 1000).coerceAtLeast(0)
            val min = totalSec / 60
            val sec = totalSec % 60
            return String.format(Locale.US, "%02d:%02d", min, sec)
        }

        fun recycle() {
            handler.removeCallbacks(progressRunnable)
            if (videoPlayer.isPlaying) {
                videoPlayer.stopPlayback()
            }
            mediaPlayer = null
            zoomablePhoto.setImageBitmap(null)
        }
    }
}
