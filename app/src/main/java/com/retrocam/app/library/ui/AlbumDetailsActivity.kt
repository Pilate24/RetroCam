package com.retrocam.app.library.ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.retrocam.app.R
import com.retrocam.app.databinding.ActivityAlbumDetailsBinding
import com.retrocam.app.gallery.SmartLibraryAdapter
import com.retrocam.app.gallery.ViewerActivity
import com.retrocam.app.library.model.DateGroupingHelper
import com.retrocam.app.library.model.MediaItem
import com.retrocam.app.library.repository.LibraryTab
import com.retrocam.app.library.repository.MediaLibraryRepository
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AlbumDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlbumDetailsBinding
    private lateinit var repository: MediaLibraryRepository
    private lateinit var adapter: SmartLibraryAdapter
    private val thumbnailExecutor: ExecutorService = Executors.newFixedThreadPool(3)

    private var albumId: String = ""
    private var albumName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlbumDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        albumId = intent.getStringExtra(EXTRA_ALBUM_ID).orEmpty()
        albumName = intent.getStringExtra(EXTRA_ALBUM_NAME).orEmpty()

        if (albumId.isEmpty()) {
            finish()
            return
        }

        repository = MediaLibraryRepository.getInstance(this)

        binding.albumTitleText.text = albumName
        binding.backButton.setOnClickListener { finish() }

        setupMenu()
        setupGrid()
    }

    private fun setupGrid() {
        val gridLayoutManager = GridLayoutManager(this, 3)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.getItemViewType(position) == SmartLibraryAdapter.TYPE_HEADER) {
                    3
                } else {
                    1
                }
            }
        }
        binding.albumMediaGrid.layoutManager = gridLayoutManager

        adapter = SmartLibraryAdapter(
            resolver = contentResolver,
            executor = thumbnailExecutor,
            onItemClick = { item ->
                startActivity(
                    Intent(this, ViewerActivity::class.java).apply {
                        putExtra(ViewerActivity.EXTRA_MEDIA_ID, item.id)
                        putExtra(ViewerActivity.EXTRA_URI, item.uri)
                        putExtra(ViewerActivity.EXTRA_NAME, item.displayName)
                        putExtra(ViewerActivity.EXTRA_IS_VIDEO, item.isVideo)
                        putExtra(ViewerActivity.EXTRA_DURATION_MS, item.durationMs)
                    }
                )
            },
            onItemLongClick = { item ->
                showRemoveItemDialog(item)
                true
            }
        )
        binding.albumMediaGrid.adapter = adapter
    }

    private fun setupMenu() {
        binding.btnAlbumMenu.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 1, 0, "Rename Album")
            popup.menu.add(0, 2, 1, "Delete Album")
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> showRenameDialog()
                    2 -> showDeleteAlbumDialog()
                }
                true
            }
            popup.show()
        }
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            setText(albumName)
            setSelection(albumName.length)
            setPadding(40, 30, 40, 30)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Rename Album")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != albumName) {
                    repository.renameAlbum(albumId, newName)
                    albumName = newName
                    binding.albumTitleText.text = newName
                    Toast.makeText(this, "Renamed to '$newName'", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteAlbumDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Album '$albumName'?")
            .setMessage("This will delete the album collection. The original photos and videos will remain safe on your device.")
            .setPositiveButton("Delete Album") { _, _ ->
                repository.deleteAlbum(albumId)
                Toast.makeText(this, "Album deleted", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRemoveItemDialog(item: MediaItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove from Album?")
            .setMessage("Remove '${item.displayName}' from this album? The original photo will not be deleted from device storage.")
            .setPositiveButton("Remove") { _, _ ->
                repository.removeMediaFromAlbum(albumId, listOf(item.id))
                Toast.makeText(this, "Removed from album", Toast.LENGTH_SHORT).show()
                loadAlbumContent()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        loadAlbumContent()
    }

    private fun loadAlbumContent() {
        val items = repository.queryMedia(albumId = albumId)
        binding.albumCountText.text = "${items.size} items"
        binding.emptyAlbumText.isVisible = items.isEmpty()

        val grouped = DateGroupingHelper.groupMediaItems(items)
        adapter.submitItems(grouped)
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbnailExecutor.shutdownNow()
    }

    companion object {
        const val EXTRA_ALBUM_ID = "extra_album_id"
        const val EXTRA_ALBUM_NAME = "extra_album_name"
    }
}
