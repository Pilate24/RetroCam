package com.retrocam.app.booth

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.retrocam.app.R
import com.retrocam.app.databinding.ActivityPhotoboothBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class PhotoboothActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoboothBinding
    private val executor = Executors.newSingleThreadExecutor()

    private var poseBitmaps = mutableListOf<Bitmap>()
    private var currentStripBitmap: Bitmap? = null
    private var currentTheme = FilmStripGenerator.StripTheme.CLASSIC_WHITE
    private var presetName = "Retro"
    private var lastSavedUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoboothBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val paths = intent.getStringArrayListExtra(EXTRA_POSE_PATHS)
        presetName = intent.getStringExtra(EXTRA_PRESET_NAME) ?: "Retro"

        if (paths.isNullOrEmpty()) {
            Toast.makeText(this, "No photobooth images found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupButtons()
        setupThemeSelectors()
        loadPosesAndRender(paths)
    }

    private fun setupButtons() {
        binding.backButton.setOnClickListener { finish() }
        binding.retakeButton.setOnClickListener { finish() }

        binding.saveButton.setOnClickListener {
            saveStripToGallery(showToast = true)
        }

        binding.shareButton.setOnClickListener {
            shareStrip()
        }
    }

    private fun setupThemeSelectors() {
        val themeViews = listOf(
            binding.themeWhite to FilmStripGenerator.StripTheme.CLASSIC_WHITE,
            binding.themeBlack to FilmStripGenerator.StripTheme.RETRO_BLACK,
            binding.themeCream to FilmStripGenerator.StripTheme.CREAM_PAPER,
            binding.themePink to FilmStripGenerator.StripTheme.Y2K_PINK,
            binding.themeLavender to FilmStripGenerator.StripTheme.LAVENDER
        )

        for ((view, theme) in themeViews) {
            view.setOnClickListener {
                if (currentTheme != theme) {
                    currentTheme = theme
                    updateThemePillsUI(themeViews, theme)
                    renderStrip()
                }
            }
        }
    }

    private fun updateThemePillsUI(
        pills: List<Pair<TextView, FilmStripGenerator.StripTheme>>,
        selectedTheme: FilmStripGenerator.StripTheme
    ) {
        for ((view, theme) in pills) {
            if (theme == selectedTheme) {
                view.setBackgroundResource(R.drawable.mode_pill_selected)
                view.setTextColor(Color.BLACK)
                view.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                view.setBackgroundResource(R.drawable.readout_bg)
                view.setTextColor(Color.WHITE)
                view.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
    }

    private fun loadPosesAndRender(paths: List<String>) {
        binding.loadingIndicator.visibility = View.VISIBLE
        executor.execute {
            val loaded = mutableListOf<Bitmap>()
            for (p in paths) {
                val f = File(p)
                if (f.exists()) {
                    val bmp = BitmapFactory.decodeFile(p)
                    if (bmp != null) loaded.add(bmp)
                }
            }

            poseBitmaps = loaded
            renderStripInternal()
        }
    }

    private fun renderStrip() {
        if (poseBitmaps.isEmpty()) return
        binding.loadingIndicator.visibility = View.VISIBLE
        executor.execute {
            renderStripInternal()
        }
    }

    private fun renderStripInternal() {
        val options = FilmStripGenerator.StripOptions(
            theme = currentTheme,
            showDate = true,
            showBarcode = true,
            presetName = presetName
        )

        val strip = FilmStripGenerator.generate(poseBitmaps, options)
        currentStripBitmap = strip

        runOnUiThread {
            binding.loadingIndicator.visibility = View.GONE
            binding.stripPreview.setImageBitmap(strip)
            // Reset saved URI when theme is modified
            lastSavedUri = null
        }
    }

    private fun saveStripToGallery(showToast: Boolean = true): Uri? {
        val strip = currentStripBitmap ?: return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "RetroCam_Booth_$stamp"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RetroCam")
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { out ->
                strip.compress(Bitmap.CompressFormat.JPEG, 96, out)
            }
            lastSavedUri = uri
            if (showToast) {
                Toast.makeText(this, getString(R.string.strip_saved_toast), Toast.LENGTH_SHORT).show()
            }
        }
        return uri
    }

    private fun shareStrip() {
        val uri = lastSavedUri ?: saveStripToGallery(showToast = false) ?: return
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_strip)))
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
        for (b in poseBitmaps) {
            b.recycle()
        }
        poseBitmaps.clear()
        currentStripBitmap?.recycle()
    }

    companion object {
        const val EXTRA_POSE_PATHS = "extra_pose_paths"
        const val EXTRA_PRESET_NAME = "extra_preset_name"
    }
}
