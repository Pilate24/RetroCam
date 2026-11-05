package com.retrocam.app.gallery

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.retrocam.app.R
import com.retrocam.app.databinding.ActivityViewerBinding
import com.retrocam.app.remaster.ui.RemasterActivity
import java.util.Locale

/** Full-size view of one saved shot, with share, AI Remaster, favorites, details, and delete. */
class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private var photoUri: Uri? = null
    private var photoName: String = ""
    private var areControlsVisible: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uri = intentUri()
        if (uri == null) {
            finish()
            return
        }
        photoUri = uri
        photoName = intent.getStringExtra(EXTRA_NAME).orEmpty()

        binding.photo.setImageURI(uri)
        binding.photoName.text = photoName

        setUpControls(uri)
    }

    private fun setUpControls(uri: Uri) {
        binding.backButton.setOnClickListener { finish() }

        // Tap photo to toggle top and bottom toolbars for immersive full-screen viewing
        binding.photo.setOnClickListener {
            areControlsVisible = !areControlsVisible
            binding.topBar.isVisible = areControlsVisible
            binding.bottomBar.isVisible = areControlsVisible
        }

        // 1. Favorite button
        updateFavoriteIcon(FavoriteStore.isFavorite(this, uri))
        binding.favoriteButton.setOnClickListener {
            val isFav = FavoriteStore.toggleFavorite(this, uri)
            updateFavoriteIcon(isFav)
            Toast.makeText(
                this,
                if (isFav) R.string.favorite_added else R.string.favorite_removed,
                Toast.LENGTH_SHORT
            ).show()
        }

        // 2. Remaster button
        binding.remasterButton.setOnClickListener {
            startActivity(
                Intent(this, RemasterActivity::class.java)
                    .putExtra(RemasterActivity.EXTRA_URI, uri)
                    .putExtra(RemasterActivity.EXTRA_NAME, photoName)
            )
        }

        // 3. Share button
        binding.shareButton.setOnClickListener {
            share(uri)
        }

        // 4. Delete button
        binding.deleteButton.setOnClickListener {
            confirmDelete(uri)
        }

        // 5. Info / Details button
        binding.infoButton.setOnClickListener {
            showPhotoDetails(uri)
        }
    }

    private fun updateFavoriteIcon(isFavorite: Boolean) {
        binding.favoriteButton.setImageResource(
            if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
    }

    private fun confirmDelete(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.gallery_delete_single_title)
            .setMessage(R.string.gallery_delete_single_msg)
            .setPositiveButton(R.string.delete_confirm_action) { _, _ ->
                val deleted = PhotoStore.delete(contentResolver, uri, this)
                if (deleted) {
                    Toast.makeText(this, R.string.gallery_deleted_single_toast, Toast.LENGTH_SHORT).show()
                    val result = Intent().putExtra(EXTRA_DELETED, true)
                    setResult(RESULT_OK, result)
                    finish()
                } else {
                    Toast.makeText(this, R.string.gallery_delete_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showPhotoDetails(uri: Uri) {
        var width = 0
        var height = 0
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, options)
                width = options.outWidth
                height = options.outHeight
            }
        } catch (ignored: Exception) {}

        var sizeStr = "Unknown"
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val bytes = pfd.statSize
                if (bytes > 0) {
                    val mb = bytes / (1024f * 1024f)
                    sizeStr = if (mb >= 1.0f) {
                        String.format(Locale.US, "%.2f MB", mb)
                    } else {
                        String.format(Locale.US, "%.1f KB", bytes / 1024f)
                    }
                }
            }
        } catch (ignored: Exception) {}

        val categoryName = when {
            photoName.contains("RAW", ignoreCase = true) || photoName.endsWith(".dng", ignoreCase = true) -> "RAW Digital Negative"
            photoName.contains("LongExp", ignoreCase = true) -> "Long Exposure"
            photoName.contains("Booth", ignoreCase = true) -> "Photobooth Strip"
            photoName.startsWith("Remastered", ignoreCase = true) -> "Remastered Photo"
            else -> "RetroCam Photo"
        }

        val resolutionStr = if (width > 0 && height > 0) "$width × $height" else "Unknown"
        val message = buildString {
            append("${getString(R.string.photo_info_name)}: $photoName\n")
            append("${getString(R.string.photo_info_category)}: $categoryName\n")
            append("${getString(R.string.photo_info_resolution)}: $resolutionStr\n")
            append("${getString(R.string.photo_info_size)}: $sizeStr\n")
            append("${getString(R.string.photo_info_folder)}: Pictures/RetroCam")
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.photo_details_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun intentUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_URI, Uri::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_URI)
        }

    /**
     * The chooser is the consent step — the user picks the destination app, and the read grant
     * rides along with the URI rather than being handed out ahead of time.
     */
    private fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (photoName.endsWith(".dng", ignoreCase = true)) "image/x-adobe-dng" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_photo)))
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
        const val EXTRA_DELETED = "extra_deleted"
    }
}
