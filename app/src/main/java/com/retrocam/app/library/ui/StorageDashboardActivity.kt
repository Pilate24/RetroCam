package com.retrocam.app.library.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.retrocam.app.R
import com.retrocam.app.databinding.ActivityStorageDashboardBinding
import com.retrocam.app.gallery.ViewerActivity
import com.retrocam.app.library.model.MediaItem
import com.retrocam.app.library.model.StorageStats
import com.retrocam.app.library.repository.MediaLibraryRepository
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class StorageDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorageDashboardBinding
    private lateinit var repository: MediaLibraryRepository
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val thumbnailExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val thumbCache = object : LruCache<String, Bitmap>(40) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStorageDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = MediaLibraryRepository.getInstance(this)

        binding.backButton.setOnClickListener { finish() }

        binding.btnReviewDuplicates.setOnClickListener {
            startActivity(Intent(this, DuplicateReviewActivity::class.java))
        }

        binding.btnClearCache.setOnClickListener {
            thumbCache.evictAll()
            try {
                cacheDir.deleteRecursively()
            } catch (e: Exception) {
                // ignore
            }
            Toast.makeText(this, "Thumbnail cache cleared", Toast.LENGTH_SHORT).show()
        }

        binding.recyclerLargestVideos.layoutManager = LinearLayoutManager(this)
        binding.recyclerLargestPhotos.layoutManager = LinearLayoutManager(this)
    }

    override fun onResume() {
        super.onResume()
        loadStats()
    }

    private fun loadStats() {
        backgroundExecutor.execute {
            val stats = repository.computeStorageStats()
            runOnUiThread {
                bindStats(stats)
            }
        }
    }

    private fun bindStats(stats: StorageStats) {
        val totalMb = stats.totalSizeBytes / (1024.0 * 1024.0)
        val totalGb = totalMb / 1024.0
        binding.storageTotalSizeText.text = if (totalGb >= 1.0) "%.2f GB".format(totalGb) else "%.1f MB".format(totalMb)
        binding.storageTotalCountText.text = "${stats.totalCount} media items indexed"

        val photoMb = stats.photoSizeBytes / (1024.0 * 1024.0)
        binding.storagePhotoSizeText.text = "%.1f MB".format(photoMb)
        binding.storagePhotoCountText.text = "${stats.photoCount} photos"

        val videoMb = stats.videoSizeBytes / (1024.0 * 1024.0)
        binding.storageVideoSizeText.text = "%.1f MB".format(videoMb)
        binding.storageVideoCountText.text = "${stats.videoCount} videos"

        binding.storageEditedCountText.text = "${stats.editedCount}"

        val dupMb = stats.potentialReclaimableBytes / (1024.0 * 1024.0)
        binding.storageDuplicateSummaryText.text = "${stats.potentialDuplicateGroupsCount} duplicate groups · %.1f MB reclaimable".format(dupMb)

        binding.recyclerLargestVideos.adapter = StorageFileListAdapter(stats.largestVideos)
        binding.recyclerLargestPhotos.adapter = StorageFileListAdapter(stats.largestPhotos)
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdownNow()
        thumbnailExecutor.shutdownNow()
    }

    inner class StorageFileListAdapter(
        private val files: List<MediaItem>
    ) : RecyclerView.Adapter<StorageFileListAdapter.FileHolder>() {

        inner class FileHolder(view: View) : RecyclerView.ViewHolder(view) {
            val thumb: ImageView = view.findViewById(R.id.storageFileThumb)
            val name: TextView = view.findViewById(R.id.storageFileName)
            val details: TextView = view.findViewById(R.id.storageFileDetails)
            val size: TextView = view.findViewById(R.id.storageFileSize)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_storage_file, parent, false)
            return FileHolder(view)
        }

        override fun getItemCount(): Int = files.size

        override fun onBindViewHolder(holder: FileHolder, position: Int) {
            val item = files[position]
            holder.name.text = item.displayName
            holder.size.text = item.fileSizeFormatted

            holder.details.text = if (item.isVideo) {
                "${item.durationFormatted} · ${item.resolutionFormatted}"
            } else {
                "${item.resolutionFormatted} · ${item.mimeType.substringAfter('/')}"
            }

            holder.itemView.setOnClickListener {
                startActivity(
                    Intent(this@StorageDashboardActivity, ViewerActivity::class.java).apply {
                        putExtra(ViewerActivity.EXTRA_MEDIA_ID, item.id)
                        putExtra(ViewerActivity.EXTRA_URI, item.uri)
                        putExtra(ViewerActivity.EXTRA_NAME, item.displayName)
                        putExtra(ViewerActivity.EXTRA_IS_VIDEO, item.isVideo)
                        putExtra(ViewerActivity.EXTRA_DURATION_MS, item.durationMs)
                    }
                )
            }

            // Thumbnail loading
            holder.thumb.tag = item.id
            val cached = thumbCache.get(item.id)
            if (cached != null) {
                holder.thumb.setImageBitmap(cached)
                return
            }

            holder.thumb.setImageBitmap(null)
            val uri = item.uri ?: return
            thumbnailExecutor.execute {
                val thumbnail = try {
                    contentResolver.loadThumbnail(uri, Size(120, 120), null)
                } catch (e: Exception) {
                    null
                } ?: return@execute

                thumbCache.put(item.id, thumbnail)
                holder.thumb.post {
                    if (holder.thumb.tag == item.id) {
                        holder.thumb.setImageBitmap(thumbnail)
                    }
                }
            }
        }
    }
}
