package com.retrocam.app.gallery

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import com.retrocam.app.R
import com.retrocam.app.databinding.ActivityGalleryBinding
import com.retrocam.app.trip.TripsActivity
import com.retrocam.app.video.VideoReviewActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var thumbnailExecutor: ExecutorService
    private lateinit var adapter: PhotoGridAdapter

    private var allMedia: List<Photo> = emptyList()
    private var currentFolder: GalleryFolder = GalleryFolder.ALL
    private val folderChipViews = mutableMapOf<GalleryFolder, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        thumbnailExecutor = Executors.newFixedThreadPool(THUMBNAIL_THREADS)

        setUpNavigation()
        setUpRecyclerView()
        setUpFolderChips()
        setUpSelectionActions()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adapter.isSelectionMode) {
                    exitSelectionMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setUpNavigation() {
        binding.backButton.setOnClickListener { finish() }
        binding.tripsButton.setOnClickListener {
            startActivity(Intent(this, TripsActivity::class.java))
        }
    }

    private fun setUpRecyclerView() {
        binding.photoGrid.layoutManager = GridLayoutManager(this, COLUMNS)

        adapter = PhotoGridAdapter(
            photos = emptyList(),
            resolver = contentResolver,
            executor = thumbnailExecutor,
            onClick = { item ->
                val uri = item.uri ?: return@PhotoGridAdapter
                if (item.isVideo) {
                    startActivity(
                        Intent(this, VideoReviewActivity::class.java)
                            .putExtra(VideoReviewActivity.EXTRA_URI, uri)
                            .putExtra(VideoReviewActivity.EXTRA_NAME, item.name)
                            .putExtra(VideoReviewActivity.EXTRA_DURATION_MS, item.durationMs)
                    )
                } else {
                    startActivity(
                        Intent(this, ViewerActivity::class.java)
                            .putExtra(ViewerActivity.EXTRA_URI, uri)
                            .putExtra(ViewerActivity.EXTRA_NAME, item.name)
                    )
                }
            },
            onLongClick = { item ->
                enterSelectionMode(item)
            },
            onSelectionChanged = { count ->
                updateSelectionState(count)
            }
        )
        binding.photoGrid.adapter = adapter
    }

    private fun setUpFolderChips() {
        binding.folderChipsContainer.removeAllViews()
        folderChipViews.clear()

        val inflater = LayoutInflater.from(this)
        for (folder in GalleryFolder.entries) {
            val chipView = inflater.inflate(R.layout.item_gallery_folder_chip, binding.folderChipsContainer, false) as TextView
            chipView.text = getString(folder.titleResId)
            chipView.setOnClickListener {
                selectFolder(folder)
            }
            binding.folderChipsContainer.addView(chipView)
            folderChipViews[folder] = chipView
        }
        updateFolderChipsUi()
    }

    private fun setUpSelectionActions() {
        binding.selectModeButton.setOnClickListener {
            enterSelectionMode()
        }

        binding.cancelSelectionButton.setOnClickListener {
            exitSelectionMode()
        }

        binding.selectAllButton.setOnClickListener {
            if (adapter.isAllSelected()) {
                adapter.deselectAll()
            } else {
                adapter.selectAll()
            }
        }

        binding.batchShareButton.setOnClickListener {
            shareSelectedItems()
        }

        binding.batchDeleteButton.setOnClickListener {
            confirmBatchDelete()
        }
    }

    override fun onResume() {
        super.onResume()
        reloadMedia()
    }

    private fun reloadMedia() {
        allMedia = PhotoStore.query(contentResolver, this)
        updateFolderChipsUi()
        applyCurrentFolderFilter()
    }

    private fun selectFolder(folder: GalleryFolder) {
        if (currentFolder == folder) return
        currentFolder = folder
        updateFolderChipsUi()
        applyCurrentFolderFilter()
    }

    private fun updateFolderChipsUi() {
        val counts = PhotoStore.getFolderCounts(allMedia)

        for ((folder, view) in folderChipViews) {
            val count = counts[folder] ?: 0
            val folderName = getString(folder.titleResId)
            view.text = if (count > 0) "$folderName · $count" else folderName

            if (folder == currentFolder) {
                view.setBackgroundResource(R.drawable.gallery_chip_selected)
                view.setTextColor(Color.BLACK)
            } else {
                view.setBackgroundResource(R.drawable.gallery_chip_unselected)
                view.setTextColor(Color.parseColor("#D9FFFFFF"))
            }
        }
    }

    private fun applyCurrentFolderFilter() {
        val filtered = PhotoStore.filter(allMedia, currentFolder)
        adapter.submitList(filtered)

        binding.mediaCountSubtitle.text = getString(R.string.gallery_items_count, filtered.size)

        if (filtered.isEmpty()) {
            binding.emptyState.isVisible = true
            if (currentFolder == GalleryFolder.ALL) {
                binding.emptyState.text = getString(R.string.gallery_empty)
            } else {
                val folderName = getString(currentFolder.titleResId)
                binding.emptyState.text = getString(R.string.gallery_empty_folder, folderName)
            }
        } else {
            binding.emptyState.isVisible = false
        }
    }

    private fun enterSelectionMode(initialItem: Photo? = null) {
        binding.normalToolbar.isVisible = false
        binding.selectionToolbar.isVisible = true
        adapter.enterSelectionMode(initialItem)
    }

    private fun exitSelectionMode() {
        binding.normalToolbar.isVisible = true
        binding.selectionToolbar.isVisible = false
        adapter.exitSelectionMode()
    }

    private fun updateSelectionState(count: Int) {
        binding.selectionCountText.text = getString(R.string.gallery_selected_count, count)
        binding.selectAllButton.text = if (adapter.isAllSelected()) {
            getString(R.string.gallery_deselect_all)
        } else {
            getString(R.string.gallery_select_all)
        }

        binding.batchShareButton.isEnabled = count > 0
        binding.batchShareButton.alpha = if (count > 0) 1.0f else 0.4f

        binding.batchDeleteButton.isEnabled = count > 0
        binding.batchDeleteButton.alpha = if (count > 0) 1.0f else 0.4f
    }

    private fun shareSelectedItems() {
        val selected = adapter.getSelectedPhotos()
        if (selected.isEmpty()) return

        if (selected.size == 1) {
            val item = selected[0]
            val uri = item.uri ?: return
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_photo)))
        } else {
            val uris = ArrayList(selected.mapNotNull { it.uri })
            val hasVideo = selected.any { it.isVideo }
            val hasPhoto = selected.any { !it.isVideo }
            val mimeType = when {
                hasVideo && hasPhoto -> "*/*"
                hasVideo -> "video/*"
                else -> "image/*"
            }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.gallery_share_selected)))
        }
    }

    private fun confirmBatchDelete() {
        val selected = adapter.getSelectedPhotos()
        if (selected.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.gallery_delete_multi_confirm_title, selected.size))
            .setMessage(getString(R.string.gallery_delete_multi_confirm_msg, selected.size))
            .setPositiveButton(R.string.delete_confirm_action) { _, _ ->
                val uris = selected.mapNotNull { it.uri }
                val deletedCount = PhotoStore.deleteMultiple(contentResolver, uris, this)
                Toast.makeText(this, getString(R.string.gallery_deleted_toast, deletedCount), Toast.LENGTH_SHORT).show()
                exitSelectionMode()
                reloadMedia()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
