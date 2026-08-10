package com.retrocam.app.gallery

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.retrocam.app.databinding.ActivityGalleryBinding
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

    /** Reloaded on resume so a shot taken since the last visit shows up. */
    override fun onResume() {
        super.onResume()
        val photos = PhotoStore.query(contentResolver)

        binding.emptyState.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
        binding.photoGrid.adapter = PhotoGridAdapter(
            photos, contentResolver, thumbnailExecutor
        ) { photo ->
            startActivity(
                Intent(this, ViewerActivity::class.java)
                    .putExtra(ViewerActivity.EXTRA_URI, photo.uri)
                    .putExtra(ViewerActivity.EXTRA_NAME, photo.name)
            )
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
