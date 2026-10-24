package com.retrocam.app.gallery

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.retrocam.app.databinding.ActivityGalleryBinding
import com.retrocam.app.video.VideoReviewActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var thumbnailExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        thumbnailExecutor = Executors.newFixedThreadPool(THUMBNAIL_THREADS)
        binding.backButton.setOnClickListener { finish() }
        binding.photoGrid.layoutManager = GridLayoutManager(this, COLUMNS)
    }

    /** Reloaded on resume so a shot or video taken since the last visit shows up. */
    override fun onResume() {
        super.onResume()
        val mediaItems = PhotoStore.query(contentResolver)

        binding.emptyState.visibility = if (mediaItems.isEmpty()) View.VISIBLE else View.GONE
        binding.photoGrid.adapter = PhotoGridAdapter(
            mediaItems, contentResolver, thumbnailExecutor
        ) { item ->
            if (item.isVideo) {
                startActivity(
                    Intent(this, VideoReviewActivity::class.java)
                        .putExtra(VideoReviewActivity.EXTRA_URI, item.uri)
                        .putExtra(VideoReviewActivity.EXTRA_NAME, item.name)
                        .putExtra(VideoReviewActivity.EXTRA_DURATION_MS, item.durationMs)
                )
            } else {
                startActivity(
                    Intent(this, ViewerActivity::class.java)
                        .putExtra(ViewerActivity.EXTRA_URI, item.uri)
                        .putExtra(ViewerActivity.EXTRA_NAME, item.name)
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbnailExecutor.shutdownNow()
    }

    private companion object {
        const val COLUMNS = 3
        const val THUMBNAIL_THREADS = 3
    }
}
