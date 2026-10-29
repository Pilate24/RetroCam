package com.retrocam.app.gallery

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.retrocam.app.R
import com.retrocam.app.databinding.ActivityViewerBinding
import com.retrocam.app.databinding.SheetMediaMetadataBinding
import com.retrocam.app.library.model.MediaItem
import com.retrocam.app.library.repository.MediaLibraryRepository
import com.retrocam.app.remaster.ui.RemasterActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private lateinit var repository: MediaLibraryRepository
    private lateinit var pagerAdapter: MediaPagerAdapter

    private var mediaList: List<MediaItem> = emptyList()
    private var currentPosition: Int = 0
    private var isBarsVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = MediaLibraryRepository.getInstance(this)

        val initialUri = intentUri()
        val initialMediaId = intent.getStringExtra(EXTRA_MEDIA_ID)

        mediaList = repository.queryMedia()

        // If list is empty or doesn't contain current, create a fallback item from intent
        if (mediaList.isEmpty() && initialUri != null) {
            val name = intent.getStringExtra(EXTRA_NAME) ?: "Media"
            val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
            val dur = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
            mediaList = listOf(
                MediaItem(
                    id = initialMediaId ?: "temp_0",
                    uri = initialUri,
                    displayName = name,
                    isVideo = isVideo,
                    durationMs = dur
                )
            )
        }

        currentPosition = if (initialMediaId != null) {
            mediaList.indexOfFirst { it.id == initialMediaId }.coerceAtLeast(0)
        } else if (initialUri != null) {
            mediaList.indexOfFirst { it.uri == initialUri }.coerceAtLeast(0)
        } else {
            0
        }

        setupViewPager()
        setupButtons()
        updateCurrentItemUI(currentPosition)
    }

    private fun setupViewPager() {
        pagerAdapter = MediaPagerAdapter(this) {
            toggleBarsVisibility()
        }
        pagerAdapter.submitList(mediaList)

        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.setCurrentItem(currentPosition, false)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPosition = position
                updateCurrentItemUI(position)
            }
        })
    }

    private fun setupButtons() {
        binding.backButton.setOnClickListener { finish() }

        binding.favoriteButton.setOnClickListener {
            val item = pagerAdapter.getItem(currentPosition) ?: return@setOnClickListener
            val isFav = repository.toggleFavorite(item.id)
            val updated = item.copy(isFavorite = isFav)
            updateFavoriteButton(isFav)
            Toast.makeText(this, if (isFav) "Added to Favorites" else "Removed from Favorites", Toast.LENGTH_SHORT).show()
        }

        val openRemaster = {
            val item = pagerAdapter.getItem(currentPosition)
            if (item != null && !item.isVideo) {
                val remasterIntent = Intent(this, RemasterActivity::class.java).apply {
                    putExtra(RemasterActivity.EXTRA_URI, item.uri)
                    putExtra(RemasterActivity.EXTRA_NAME, item.displayName)
                }
                startActivity(remasterIntent)
            } else {
                Toast.makeText(this, "Remaster is available for photos", Toast.LENGTH_SHORT).show()
            }
        }
        binding.remasterButton.setOnClickListener { openRemaster() }
        binding.topRemasterButton.setOnClickListener { openRemaster() }

        binding.shareButton.setOnClickListener {
            val item = pagerAdapter.getItem(currentPosition) ?: return@setOnClickListener
            share(item)
        }

        binding.infoButton.setOnClickListener {
            val item = pagerAdapter.getItem(currentPosition) ?: return@setOnClickListener
            showMetadataSheet(item)
        }

        binding.compareButton.setOnClickListener {
            val item = pagerAdapter.getItem(currentPosition) ?: return@setOnClickListener
            val original = repository.getOriginalItem(item)
            if (original != null) {
                showOriginalCompareDialog(original, item)
            } else {
                Toast.makeText(this, "Original photo reference not found", Toast.LENGTH_SHORT).show()
            }
        }

        binding.deleteButton.setOnClickListener {
            val item = pagerAdapter.getItem(currentPosition) ?: return@setOnClickListener
            confirmDelete(item)
        }
    }

    private fun updateCurrentItemUI(position: Int) {
        val item = pagerAdapter.getItem(position) ?: return
        binding.photoName.text = item.displayName
        binding.positionIndicator.text = "${position + 1} / ${mediaList.size}"

        updateFavoriteButton(item.isFavorite)

        // Show AI Remaster button for photos only
        binding.remasterButton.isVisible = !item.isVideo
        binding.topRemasterButton.isVisible = !item.isVideo

        // Show Compare button if this item has an associated original or is edited
        val hasOriginal = item.isEdited || repository.getOriginalItem(item) != null
        binding.compareButton.isVisible = hasOriginal
    }

    private fun updateFavoriteButton(isFavorite: Boolean) {
        binding.favoriteButton.setImageResource(
            if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
    }

    private fun toggleBarsVisibility() {
        isBarsVisible = !isBarsVisible
        binding.topBar.animate().alpha(if (isBarsVisible) 1f else 0f).setDuration(200).start()
        binding.bottomBar.animate().alpha(if (isBarsVisible) 1f else 0f).setDuration(200).start()
        binding.topBar.isVisible = isBarsVisible
        binding.bottomBar.isVisible = isBarsVisible
    }

    private fun showMetadataSheet(item: MediaItem) {
        val sheetBinding = SheetMediaMetadataBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetBinding.root)

        sheetBinding.metaFileName.text = item.displayName

        val dateMs = if (item.dateTaken > 0) item.dateTaken else item.dateAdded
        if (dateMs > 0) {
            val sdf = SimpleDateFormat("MMM d, yyyy · hh:mm a", Locale.getDefault())
            sheetBinding.metaDateTime.text = sdf.format(Date(dateMs))
        } else {
            sheetBinding.metaDateTime.text = "Unknown"
        }

        sheetBinding.metaResolution.text = if (item.resolutionFormatted.isNotEmpty()) item.resolutionFormatted else "Unknown"
        sheetBinding.metaFileSize.text = if (item.fileSizeFormatted.isNotEmpty()) item.fileSizeFormatted else "Unknown"

        if (item.isVideo && item.durationMs > 0) {
            sheetBinding.rowDuration.visibility = View.VISIBLE
            sheetBinding.metaDuration.text = item.durationFormatted
        } else {
            sheetBinding.rowDuration.visibility = View.GONE
        }

        val cameraStr = listOfNotNull(item.cameraMake, item.cameraModel).joinToString(" ")
        if (cameraStr.isNotBlank()) {
            sheetBinding.rowCamera.visibility = View.VISIBLE
            sheetBinding.metaCamera.text = cameraStr
        } else {
            sheetBinding.rowCamera.visibility = View.GONE
        }

        val exifParts = mutableListOf<String>()
        if (item.focalLength != null) exifParts.add("%.1f mm".format(item.focalLength))
        if (item.aperture != null) exifParts.add("f/%.1f".format(item.aperture))
        if (item.exposureTime != null && item.exposureTime > 0) {
            if (item.exposureTime < 1.0f) {
                exifParts.add("1/%d s".format((1.0f / item.exposureTime).toInt()))
            } else {
                exifParts.add("%.1f s".format(item.exposureTime))
            }
        }
        if (item.iso != null) exifParts.add("ISO ${item.iso}")

        if (exifParts.isNotEmpty()) {
            sheetBinding.rowExifParams.visibility = View.VISIBLE
            sheetBinding.metaExifParams.text = exifParts.joinToString(" · ")
        } else {
            sheetBinding.rowExifParams.visibility = View.GONE
        }

        sheetBinding.metaStatus.text = when {
            item.isEdited -> "Edited / Remastered"
            item.isFavorite -> "Favorite Original"
            else -> "Original Media"
        }

        sheetBinding.metaPath.text = item.relativePath?.ifEmpty { "Pictures/RetroCam" } ?: "Pictures/RetroCam"

        dialog.show()
    }

    private fun showOriginalCompareDialog(original: MediaItem, edited: MediaItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Associated Original Found")
            .setMessage("Original: ${original.displayName}\nEdited: ${edited.displayName}\n\nWould you like to open the original photo?")
            .setPositiveButton("View Original") { _, _ ->
                val origIdx = mediaList.indexOfFirst { it.id == original.id }
                if (origIdx >= 0) {
                    binding.viewPager.setCurrentItem(origIdx, true)
                } else {
                    val intent = Intent(this, ViewerActivity::class.java).apply {
                        putExtra(EXTRA_MEDIA_ID, original.id)
                        putExtra(EXTRA_URI, original.uri)
                        putExtra(EXTRA_NAME, original.displayName)
                    }
                    startActivity(intent)
                }
            }
            .setNeutralButton("Open in Remaster") { _, _ ->
                val remasterIntent = Intent(this, RemasterActivity::class.java).apply {
                    putExtra(RemasterActivity.EXTRA_URI, edited.uri)
                    putExtra(RemasterActivity.EXTRA_NAME, edited.displayName)
                }
                startActivity(remasterIntent)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(item: MediaItem) {
        val uri = item.uri ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(R.string.delete_confirm_message)
            .setPositiveButton(R.string.delete_confirm_action) { _, _ ->
                try {
                    contentResolver.delete(uri, null, null)
                    repository.deleteFromIndex(item.id)
                    Toast.makeText(this, "Media deleted", Toast.LENGTH_SHORT).show()
                    finish()
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not delete media", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun share(item: MediaItem) {
        val uri = item.uri ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, if (item.isVideo) getString(R.string.share_video) else getString(R.string.share_photo)))
    }

    @Suppress("DEPRECATION")
    private fun intentUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_URI, Uri::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_URI)
        }

    companion object {
        const val EXTRA_MEDIA_ID = "media_id"
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
        const val EXTRA_IS_VIDEO = "is_video"
        const val EXTRA_DURATION_MS = "duration_ms"
    }
}
