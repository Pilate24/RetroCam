package com.retrocam.app.gallery

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.retrocam.app.MainActivity
import com.retrocam.app.R
import com.retrocam.app.databinding.ActivityGalleryBinding
import com.retrocam.app.databinding.DialogFilterSortBinding
import com.retrocam.app.library.model.DateGroupingHelper
import com.retrocam.app.library.model.MediaItem
import com.retrocam.app.library.repository.LibraryTab
import com.retrocam.app.library.repository.MediaLibraryRepository
import com.retrocam.app.library.repository.SortOrder
import com.retrocam.app.library.ui.AlbumDetailsActivity
import com.retrocam.app.library.ui.DuplicateReviewActivity
import com.retrocam.app.library.ui.StorageDashboardActivity
import com.retrocam.app.remaster.ui.RemasterActivity
import com.retrocam.app.trip.TripsActivity
import com.retrocam.app.video.VideoReviewActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var thumbnailExecutor: ExecutorService
    private lateinit var repository: MediaLibraryRepository

    private lateinit var mediaAdapter: SmartLibraryAdapter
    private lateinit var albumAdapter: AlbumGridAdapter
    private lateinit var gridLayoutManager: GridLayoutManager

    private var activeTab: LibraryTab = LibraryTab.ALL
    private var currentSortOrder: SortOrder = SortOrder.DATE_DESC
    private var gridColumns: Int = DEFAULT_COLUMNS
    private var currentSearchQuery: String = ""

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    private var currentDisplayedMedia = listOf<MediaItem>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        checkPermissionsAndSync(showBannerOnly = false)
        if (granted) {
            Toast.makeText(this, "Media permission granted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        thumbnailExecutor = Executors.newFixedThreadPool(THUMBNAIL_THREADS)
        repository = MediaLibraryRepository.getInstance(this)

        setupNavigation()
        setupGrid()
        setupCategoryTabs()
        setupSearch()
        setupSelectionActions()
        setupMoreMenu()
        setupObservers()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    mediaAdapter.isSelectionMode -> {
                        exitSelectionMode()
                    }
                    binding.searchBarView.isVisible -> {
                        closeSearch()
                    }
                    else -> {
                        isEnabled = false
                        finish()
                    }
                }
            }
        })
    }

    private fun setupNavigation() {
        binding.backButton.setOnClickListener { finish() }
        binding.btnSearch.setOnClickListener { openSearch() }
        binding.btnCloseSearch.setOnClickListener { closeSearch() }
        binding.btnSelectMode.setOnClickListener { enterSelectionMode() }
        binding.btnCloseSelection.setOnClickListener { exitSelectionMode() }
        binding.btnFilterSort.setOnClickListener { showSortFilterDialog() }

        binding.btnEmptyCapture.setOnClickListener {
            // If empty, return to camera
            finish()
        }

        binding.btnGrantPermission.setOnClickListener {
            requestMediaPermissions()
        }
    }

    private fun setupGrid() {
        gridLayoutManager = GridLayoutManager(this, gridColumns)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (activeTab == LibraryTab.ALBUMS) {
                    1
                } else {
                    if (mediaAdapter.getItemViewType(position) == SmartLibraryAdapter.TYPE_HEADER) {
                        gridColumns
                    } else {
                        1
                    }
                }
            }
        }
        binding.photoGrid.layoutManager = gridLayoutManager

        mediaAdapter = SmartLibraryAdapter(
            resolver = contentResolver,
            executor = thumbnailExecutor,
            onItemClick = { item ->
                if (mediaAdapter.isSelectionMode) {
                    updateSelectionCount()
                } else {
                    openMediaViewer(item)
                }
            },
            onItemLongClick = { item ->
                if (!mediaAdapter.isSelectionMode) {
                    enterSelectionMode()
                    mediaAdapter.toggleSelection(item.id)
                    updateSelectionCount()
                }
                true
            }
        )

        albumAdapter = AlbumGridAdapter(
            resolver = contentResolver,
            executor = thumbnailExecutor,
            onAlbumClick = { album ->
                startActivity(
                    Intent(this, AlbumDetailsActivity::class.java)
                        .putExtra(AlbumDetailsActivity.EXTRA_ALBUM_ID, album.id)
                        .putExtra(AlbumDetailsActivity.EXTRA_ALBUM_NAME, album.name)
                )
            }
        )

        binding.photoGrid.adapter = mediaAdapter
    }

    private fun setupCategoryTabs() {
        val tabs = listOf(
            binding.tabAll to LibraryTab.ALL,
            binding.tabPhotos to LibraryTab.PHOTOS,
            binding.tabVideos to LibraryTab.VIDEOS,
            binding.tabFavorites to LibraryTab.FAVORITES,
            binding.tabAlbums to LibraryTab.ALBUMS,
            binding.tabEdited to LibraryTab.EDITED
        )

        for ((view, tab) in tabs) {
            view.setOnClickListener {
                if (activeTab != tab) {
                    activeTab = tab
                    updateTabStyles(tabs, activeTab)
                    reloadContent()
                }
            }
        }
    }

    private fun updateTabStyles(tabs: List<Pair<TextView, LibraryTab>>, selectedTab: LibraryTab) {
        for ((view, tab) in tabs) {
            if (tab == selectedTab) {
                view.setBackgroundResource(R.drawable.category_pill_selected)
                view.setTextColor(getColor(R.color.amber))
                view.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                view.setBackgroundResource(R.drawable.readout_bg)
                view.setTextColor(0xCCFFFFFF.toInt())
                view.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
    }

    private fun setupSearch() {
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.btnClearSearch.isVisible = !s.isNullOrEmpty()
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                searchRunnable = Runnable {
                    currentSearchQuery = s?.toString()?.trim() ?: ""
                    reloadContent()
                }
                searchHandler.postDelayed(searchRunnable!!, 300)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearSearch.setOnClickListener {
            binding.searchEditText.setText("")
        }
    }

    private fun openSearch() {
        binding.normalHeaderView.visibility = View.GONE
        binding.searchBarView.visibility = View.VISIBLE
        binding.searchEditText.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.searchEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        binding.searchEditText.setText("")
        currentSearchQuery = ""
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
        binding.searchBarView.visibility = View.GONE
        binding.normalHeaderView.visibility = View.VISIBLE
        reloadContent()
    }

    private fun enterSelectionMode() {
        mediaAdapter.isSelectionMode = true
        binding.normalHeaderView.visibility = View.GONE
        binding.selectionHeaderView.visibility = View.VISIBLE
        binding.selectionActionBar.visibility = View.VISIBLE
        updateSelectionCount()
    }

    private fun exitSelectionMode() {
        mediaAdapter.clearSelection()
        mediaAdapter.isSelectionMode = false
        binding.selectionHeaderView.visibility = View.GONE
        binding.selectionActionBar.visibility = View.GONE
        binding.normalHeaderView.visibility = View.VISIBLE
    }

    private fun updateSelectionCount() {
        val count = mediaAdapter.selectedMediaIds.size
        binding.selectionCountText.text = "$count selected"
    }

    private fun setupSelectionActions() {
        binding.btnSelectAll.setOnClickListener {
            if (mediaAdapter.selectedMediaIds.size == currentDisplayedMedia.size) {
                mediaAdapter.clearSelection()
            } else {
                mediaAdapter.selectAll(currentDisplayedMedia)
            }
            updateSelectionCount()
        }

        binding.btnShareSelected.setOnClickListener {
            val selected = currentDisplayedMedia.filter { mediaAdapter.selectedMediaIds.contains(it.id) }
            if (selected.isEmpty()) {
                Toast.makeText(this, "No items selected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            shareSelectedMedia(selected)
        }

        binding.btnFavoriteSelected.setOnClickListener {
            val selectedIds = mediaAdapter.selectedMediaIds.toList()
            if (selectedIds.isEmpty()) return@setOnClickListener
            for (id in selectedIds) {
                repository.toggleFavorite(id)
            }
            Toast.makeText(this, "Updated favorites", Toast.LENGTH_SHORT).show()
            exitSelectionMode()
            reloadContent()
        }

        binding.btnAddToAlbumSelected.setOnClickListener {
            val selectedIds = mediaAdapter.selectedMediaIds.toList()
            if (selectedIds.isEmpty()) return@setOnClickListener
            showAddToAlbumDialog(selectedIds)
        }

        binding.btnDeleteSelected.setOnClickListener {
            val selected = currentDisplayedMedia.filter { mediaAdapter.selectedMediaIds.contains(it.id) }
            if (selected.isEmpty()) return@setOnClickListener
            showDeleteConfirmDialog(selected)
        }
    }

    private fun shareSelectedMedia(items: List<MediaItem>) {
        if (items.size == 1) {
            val item = items.first()
            val uri = item.uri ?: return
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share media"))
        } else {
            val uris = ArrayList(items.mapNotNull { it.uri })
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share selected media"))
        }
    }

    private fun showAddToAlbumDialog(mediaIds: List<String>) {
        val albums = repository.queryAllAlbums()
        val options = mutableListOf("＋ Create New Album")
        options.addAll(albums.map { it.name })

        MaterialAlertDialogBuilder(this)
            .setTitle("Add to Album")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    showCreateAlbumDialog(mediaIds)
                } else {
                    val album = albums[which - 1]
                    repository.addMediaToAlbum(album.id, mediaIds)
                    Toast.makeText(this, "Added to ${album.name}", Toast.LENGTH_SHORT).show()
                    exitSelectionMode()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCreateAlbumDialog(mediaIds: List<String>) {
        val input = EditText(this).apply {
            hint = "Album name"
            setPadding(40, 30, 40, 30)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("New Album")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val album = repository.createAlbum(name)
                    repository.addMediaToAlbum(album.id, mediaIds)
                    Toast.makeText(this, "Created album '$name'", Toast.LENGTH_SHORT).show()
                    exitSelectionMode()
                    reloadContent()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteConfirmDialog(items: List<MediaItem>) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${items.size} items?")
            .setMessage("This will remove these media items from device storage. This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                var deletedCount = 0
                for (item in items) {
                    val uri = item.uri ?: continue
                    try {
                        val deleted = contentResolver.delete(uri, null, null)
                        if (deleted > 0) {
                            repository.deleteFromIndex(item.id)
                            deletedCount++
                        }
                    } catch (e: Exception) {
                        // MediaStore delete failed or requires user permission
                    }
                }
                Toast.makeText(this, "Deleted $deletedCount items", Toast.LENGTH_SHORT).show()
                exitSelectionMode()
                reloadContent()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupMoreMenu() {
        binding.btnMoreMenu.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.apply {
                add(0, 1, 0, "Storage & Cleanup")
                add(0, 2, 1, "Duplicate & Similar Photos")
                add(0, 3, 2, "AI Remaster Editor")
                add(0, 4, 3, "Trips & Events")
                add(0, 5, 4, "Refresh Library")
            }
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> startActivity(Intent(this, StorageDashboardActivity::class.java))
                    2 -> startActivity(Intent(this, DuplicateReviewActivity::class.java))
                    3 -> startActivity(Intent(this, RemasterActivity::class.java))
                    4 -> startActivity(Intent(this, TripsActivity::class.java))
                    5 -> {
                        checkPermissionsAndSync(showBannerOnly = false)
                        Toast.makeText(this, "Resyncing library...", Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
            popup.show()
        }
    }

    private fun showSortFilterDialog() {
        val dialogBinding = DialogFilterSortBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        // Init Sort Radio
        when (currentSortOrder) {
            SortOrder.DATE_DESC -> dialogBinding.radioDateDesc.isChecked = true
            SortOrder.DATE_ASC -> dialogBinding.radioDateAsc.isChecked = true
            SortOrder.SIZE_DESC -> dialogBinding.radioSizeDesc.isChecked = true
            SortOrder.NAME_ASC -> dialogBinding.radioNameAsc.isChecked = true
        }

        // Init Grid columns buttons
        fun updateGridColsUI(cols: Int) {
            dialogBinding.btnGrid2Cols.setTextColor(if (cols == 2) getColor(R.color.amber) else 0xFFFFFFFF.toInt())
            dialogBinding.btnGrid3Cols.setTextColor(if (cols == 3) getColor(R.color.amber) else 0xFFFFFFFF.toInt())
            dialogBinding.btnGrid4Cols.setTextColor(if (cols == 4) getColor(R.color.amber) else 0xFFFFFFFF.toInt())
        }
        var tempCols = gridColumns
        updateGridColsUI(tempCols)

        dialogBinding.btnGrid2Cols.setOnClickListener { tempCols = 2; updateGridColsUI(tempCols) }
        dialogBinding.btnGrid3Cols.setOnClickListener { tempCols = 3; updateGridColsUI(tempCols) }
        dialogBinding.btnGrid4Cols.setOnClickListener { tempCols = 4; updateGridColsUI(tempCols) }

        dialogBinding.btnApplyFilterSort.setOnClickListener {
            currentSortOrder = when (dialogBinding.radioGroupSort.checkedRadioButtonId) {
                R.id.radioDateAsc -> SortOrder.DATE_ASC
                R.id.radioSizeDesc -> SortOrder.SIZE_DESC
                R.id.radioNameAsc -> SortOrder.NAME_ASC
                else -> SortOrder.DATE_DESC
            }

            if (tempCols != gridColumns) {
                gridColumns = tempCols
                gridLayoutManager.spanCount = gridColumns
                binding.photoGrid.layoutManager = gridLayoutManager
            }

            dialog.dismiss()
            reloadContent()
        }

        dialog.show()
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            repository.isIndexingFlow.collectLatest { isIndexing ->
                binding.indexingProgressBar.isVisible = isIndexing
            }
        }
    }

    private fun reloadContent() {
        if (activeTab == LibraryTab.ALBUMS) {
            binding.photoGrid.adapter = albumAdapter
            val albums = repository.queryAllAlbums()
            albumAdapter.submitAlbums(albums)
            binding.emptyState.isVisible = albums.isEmpty()
            binding.emptyStateText.text = "No albums created yet.\nTap selection mode on any photo to create an album."
            currentDisplayedMedia = emptyList()
        } else {
            binding.photoGrid.adapter = mediaAdapter
            val items = repository.queryMedia(
                tab = activeTab,
                searchQuery = currentSearchQuery,
                sortOrder = currentSortOrder
            )
            currentDisplayedMedia = items

            val groupedItems = DateGroupingHelper.groupMediaItems(items)
            mediaAdapter.submitItems(groupedItems)

            binding.emptyState.isVisible = items.isEmpty()
            binding.emptyStateText.text = if (currentSearchQuery.isNotBlank()) {
                "No media found matching '$currentSearchQuery'"
            } else {
                getString(R.string.gallery_empty)
            }
        }
    }

    private fun openMediaViewer(item: MediaItem) {
        val intent = Intent(this, ViewerActivity::class.java).apply {
            putExtra(ViewerActivity.EXTRA_MEDIA_ID, item.id)
            putExtra(ViewerActivity.EXTRA_URI, item.uri)
            putExtra(ViewerActivity.EXTRA_NAME, item.displayName)
            putExtra(ViewerActivity.EXTRA_IS_VIDEO, item.isVideo)
            putExtra(ViewerActivity.EXTRA_DURATION_MS, item.durationMs)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndSync(showBannerOnly = true)
        reloadContent()
    }

    private fun checkPermissionsAndSync(showBannerOnly: Boolean) {
        val hasImagePerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_MEDIA_IMAGES
        ) == PackageManager.PERMISSION_GRANTED
        val hasVideoPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_MEDIA_VIDEO
        ) == PackageManager.PERMISSION_GRANTED

        val hasBroad = hasImagePerm && hasVideoPerm
        binding.permissionBanner.isVisible = !hasBroad

        repository.syncWithMediaStore(hasBroadStoragePermission = hasBroad) {
            runOnUiThread {
                reloadContent()
            }
        }
    }

    private fun requestMediaPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbnailExecutor.shutdownNow()
        searchHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        const val DEFAULT_COLUMNS = 3
        const val THUMBNAIL_THREADS = 4
    }
}
