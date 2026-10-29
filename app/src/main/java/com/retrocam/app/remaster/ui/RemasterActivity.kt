package com.retrocam.app.remaster.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.slider.Slider
import com.retrocam.app.R
import com.retrocam.app.databinding.ActivityRemasterBinding
import com.retrocam.app.databinding.ItemAdjustmentSliderBinding
import com.retrocam.app.databinding.ItemAdjustmentSwitchBinding
import com.retrocam.app.remaster.analyzer.ImageRemasterAnalyzer
import com.retrocam.app.remaster.model.RemasterAdjustments
import com.retrocam.app.remaster.model.RemasterHistory
import com.retrocam.app.remaster.model.RemasterPreset
import com.retrocam.app.remaster.processor.RemasterPipeline
import com.retrocam.app.remaster.processor.SuperResolutionProcessor
import com.retrocam.app.trip.TripRepository
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class RemasterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRemasterBinding
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var sourceUri: Uri? = null
    private var sourceName: String? = null

    private var fullResOriginal: Bitmap? = null
    private var previewOriginal: Bitmap? = null
    private var previewRemastered: Bitmap? = null

    private var pipeline: RemasterPipeline? = null
    private var analysisReport: ImageRemasterAnalyzer.AnalysisReport? = null

    private val history = RemasterHistory()
    private var currentAdjustments = RemasterAdjustments.DEFAULT
    private var activePreset: RemasterPreset? = null

    private lateinit var presetAdapter: RemasterPresetAdapter
    private var activeCategory = Category.AUTO

    private lateinit var tripRepository: TripRepository

    private enum class Category {
        AUTO, PRESETS, LIGHT, COLOR, DETAIL, PORTRAIT, AI_DETAIL
    }

    private val photoPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            sourceUri = uri
            loadPhotoAndInitialize(uri)
        } else if (previewOriginal == null) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemasterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tripRepository = TripRepository.getInstance(this)

        setupToolbar()
        setupCategoryTabs()
        setupPresetsRecyclerView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                confirmExitIfNeeded()
            }
        })

        val uri = intentUri()
        sourceName = intent.getStringExtra(EXTRA_NAME)
        if (uri != null) {
            sourceUri = uri
            loadPhotoAndInitialize(uri)
        } else {
            // Launch photo picker if no URI passed
            photoPickerLauncher.launch("image/*")
        }
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { confirmExitIfNeeded() }

        binding.btnUndo.setOnClickListener {
            val prev = history.undo()
            if (prev != null) {
                applyAdjustments(prev, recordHistory = false)
            }
        }

        binding.btnRedo.setOnClickListener {
            val next = history.redo()
            if (next != null) {
                applyAdjustments(next, recordHistory = false)
            }
        }

        binding.btnReset.setOnClickListener {
            if (!currentAdjustments.isDefault()) {
                activePreset = null
                presetAdapter.setSelected(null)
                applyAdjustments(RemasterAdjustments.DEFAULT, recordHistory = true)
                Toast.makeText(this, "Reset to original settings", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnExport.setOnClickListener {
            showExportDialog()
        }

        binding.btnOneTapAuto.setOnClickListener {
            executeAutoRemaster()
        }
    }

    private fun setupCategoryTabs() {
        binding.tabAuto.setOnClickListener { selectCategory(Category.AUTO) }
        binding.tabPresets.setOnClickListener { selectCategory(Category.PRESETS) }
        binding.tabLight.setOnClickListener { selectCategory(Category.LIGHT) }
        binding.tabColor.setOnClickListener { selectCategory(Category.COLOR) }
        binding.tabDetail.setOnClickListener { selectCategory(Category.DETAIL) }
        binding.tabPortrait.setOnClickListener { selectCategory(Category.PORTRAIT) }
        binding.tabAiDetail.setOnClickListener { selectCategory(Category.AI_DETAIL) }
    }

    private fun setupPresetsRecyclerView() {
        presetAdapter = RemasterPresetAdapter(
            presets = RemasterPreset.values().toList(),
            selectedPreset = activePreset
        ) { selected ->
            activePreset = selected
            val presetAdjustments = selected.createAdjustments()
            applyAdjustments(presetAdjustments, recordHistory = true)
        }
        binding.presetsRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.presetsRecyclerView.adapter = presetAdapter
    }

    private fun selectCategory(cat: Category) {
        activeCategory = cat
        val tabs = listOf(
            binding.tabAuto,
            binding.tabPresets,
            binding.tabLight,
            binding.tabColor,
            binding.tabDetail,
            binding.tabPortrait,
            binding.tabAiDetail
        )

        val activeTab = when (cat) {
            Category.AUTO -> binding.tabAuto
            Category.PRESETS -> binding.tabPresets
            Category.LIGHT -> binding.tabLight
            Category.COLOR -> binding.tabColor
            Category.DETAIL -> binding.tabDetail
            Category.PORTRAIT -> binding.tabPortrait
            Category.AI_DETAIL -> binding.tabAiDetail
        }

        for (tab in tabs) {
            if (tab == activeTab) {
                tab.setBackgroundResource(R.drawable.category_pill_selected)
                tab.setTextColor(ContextCompat.getColor(this, R.color.amber))
            } else {
                tab.setBackgroundResource(R.drawable.readout_bg)
                tab.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            }
        }

        when (cat) {
            Category.AUTO -> {
                binding.panelAuto.visibility = View.VISIBLE
                binding.presetsRecyclerView.visibility = View.GONE
                binding.panelSliders.visibility = View.GONE
            }
            Category.PRESETS -> {
                binding.panelAuto.visibility = View.GONE
                binding.presetsRecyclerView.visibility = View.VISIBLE
                binding.panelSliders.visibility = View.GONE
            }
            Category.LIGHT, Category.COLOR, Category.DETAIL, Category.PORTRAIT, Category.AI_DETAIL -> {
                binding.panelAuto.visibility = View.GONE
                binding.presetsRecyclerView.visibility = View.GONE
                binding.panelSliders.visibility = View.VISIBLE
                populateSlidersForCategory(cat)
            }
        }
    }

    private fun populateSlidersForCategory(cat: Category) {
        val container = binding.slidersDynamicLayout
        container.removeAllViews()

        when (cat) {
            Category.LIGHT -> {
                addSlider(container, "Brightness", currentAdjustments.brightness, -100, 100) { v ->
                    currentAdjustments = currentAdjustments.copy(brightness = v / 100f)
                }
                addSlider(container, "Exposure", currentAdjustments.exposure, -100, 100) { v ->
                    currentAdjustments = currentAdjustments.copy(exposure = v / 100f)
                }
                addSlider(container, "Contrast", currentAdjustments.contrast, -100, 100) { v ->
                    currentAdjustments = currentAdjustments.copy(contrast = v / 100f)
                }
                addSlider(container, "Highlights", currentAdjustments.highlights, -100, 100) { v ->
                    currentAdjustments = currentAdjustments.copy(highlights = v / 100f)
                }
                addSlider(container, "Shadows", currentAdjustments.shadows, -100, 100) { v ->
                    currentAdjustments = currentAdjustments.copy(shadows = v / 100f)
                }
            }
            Category.COLOR -> {
                addSlider(container, "Saturation", currentAdjustments.saturation, -100, 100) { v ->
                    currentAdjustments = currentAdjustments.copy(saturation = v / 100f)
                }
                addSlider(container, "Vibrance", currentAdjustments.vibrance, -100, 100) { v ->
                    currentAdjustments = currentAdjustments.copy(vibrance = v / 100f)
                }
                addSlider(container, "Temperature", currentAdjustments.temperature, -100, 100) { v ->
                    currentAdjustments = currentAdjustments.copy(temperature = v / 100f)
                }
                addSlider(container, "Tint", currentAdjustments.tint, -100, 100) { v ->
                    currentAdjustments = currentAdjustments.copy(tint = v / 100f)
                }
            }
            Category.DETAIL -> {
                addSlider(container, "Sharpness", currentAdjustments.sharpness, 0, 100) { v ->
                    currentAdjustments = currentAdjustments.copy(sharpness = v / 100f)
                }
                addSlider(container, "Clarity", currentAdjustments.clarity, -100, 100) { v ->
                    currentAdjustments = currentAdjustments.copy(clarity = v / 100f)
                }
                addSlider(container, "Noise Reduction", currentAdjustments.noiseReduction, 0, 100) { v ->
                    currentAdjustments = currentAdjustments.copy(noiseReduction = v / 100f)
                }
                addSlider(container, "Detail Recovery", currentAdjustments.detailRecovery, 0, 100) { v ->
                    currentAdjustments = currentAdjustments.copy(detailRecovery = v / 100f)
                }
                addSlider(container, "Vignette", currentAdjustments.vignette, -100, 100) { v ->
                    currentAdjustments = currentAdjustments.copy(vignette = v / 100f)
                }
            }
            Category.PORTRAIT -> {
                addSlider(container, "Skin Smoothing", currentAdjustments.portraitSkinSmoothing, 0, 100) { v ->
                    currentAdjustments = currentAdjustments.copy(portraitSkinSmoothing = v / 100f)
                }
                addSlider(container, "Face Illumination", currentAdjustments.portraitFaceIllumination, 0, 100) { v ->
                    currentAdjustments = currentAdjustments.copy(portraitFaceIllumination = v / 100f)
                }
                addSlider(container, "Eye Enhancement", currentAdjustments.portraitEyeEnhancement, 0, 100) { v ->
                    currentAdjustments = currentAdjustments.copy(portraitEyeEnhancement = v / 100f)
                }
                addSlider(container, "Background Blur", currentAdjustments.portraitBackgroundBlur, 0, 100) { v ->
                    currentAdjustments = currentAdjustments.copy(portraitBackgroundBlur = v / 100f)
                }
                addSwitch(container, "Red-Eye Correction", "Automatically remove flash reflections in eyes", currentAdjustments.portraitRedEyeCorrection) { isChecked ->
                    currentAdjustments = currentAdjustments.copy(portraitRedEyeCorrection = isChecked)
                    applyAdjustments(currentAdjustments, recordHistory = true)
                }
            }
            Category.AI_DETAIL -> {
                addSlider(container, "AI Detail Recovery", currentAdjustments.aiDetailRecovery, 0, 100) { v ->
                    currentAdjustments = currentAdjustments.copy(aiDetailRecovery = v / 100f)
                }
                addSwitch(container, "Compression Artifact Removal", "De-block and smooth low-quality JPEG compression noise", currentAdjustments.removeArtifacts) { isChecked ->
                    currentAdjustments = currentAdjustments.copy(removeArtifacts = isChecked)
                    applyAdjustments(currentAdjustments, recordHistory = true)
                }
                addSwitch(container, "AI 2× Super Resolution on Export", "Synthesize high-frequency edges for double resolution export", currentAdjustments.aiUpscale) { isChecked ->
                    currentAdjustments = currentAdjustments.copy(aiUpscale = isChecked)
                    applyAdjustments(currentAdjustments, recordHistory = true)
                }

                val infoCard = TextView(this).apply {
                    text = "ℹ️ AI Detail Recovery synthesizes natural high-frequency edge textures without fabricating synthetic identity changes. 100% on-device processing."
                    setTextColor(0xAAFFFFFF.toInt())
                    textSize = 11f
                    setPadding(12, 12, 12, 12)
                }
                container.addView(infoCard)
            }
            else -> {}
        }
    }

    private fun addSlider(
        parent: View,
        label: String,
        initialValue: Float,
        minVal: Int,
        maxVal: Int,
        onUpdate: (Float) -> Unit
    ) {
        val sliderBinding = ItemAdjustmentSliderBinding.inflate(layoutInflater, binding.slidersDynamicLayout, true)
        val intVal = (initialValue * 100f).roundToInt().coerceIn(minVal, maxVal)

        sliderBinding.sliderLabel.text = label
        sliderBinding.sliderValue.text = if (intVal > 0 && minVal < 0) "+$intVal" else "$intVal"

        sliderBinding.sliderWidget.valueFrom = minVal.toFloat()
        sliderBinding.sliderWidget.valueTo = maxVal.toFloat()
        sliderBinding.sliderWidget.value = intVal.toFloat()

        sliderBinding.sliderWidget.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val v = value.toInt()
                sliderBinding.sliderValue.text = if (v > 0 && minVal < 0) "+$v" else "$v"
                onUpdate(value)
                renderPreview()
            }
        }

        sliderBinding.sliderWidget.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                history.push(currentAdjustments)
                updateUndoRedoButtons()
            }
        })
    }

    private fun addSwitch(
        parent: View,
        title: String,
        subtitle: String,
        initialValue: Boolean,
        onToggle: (Boolean) -> Unit
    ) {
        val switchBinding = ItemAdjustmentSwitchBinding.inflate(layoutInflater, binding.slidersDynamicLayout, true)
        switchBinding.switchTitle.text = title
        switchBinding.switchSubtitle.text = subtitle
        switchBinding.switchWidget.isChecked = initialValue

        switchBinding.switchWidget.setOnCheckedChangeListener { _, isChecked ->
            onToggle(isChecked)
        }
    }

    private fun loadPhotoAndInitialize(uri: Uri) {
        showLoading("Loading photo...")
        backgroundExecutor.execute {
            try {
                val (fullBitmap, previewBitmap) = decodeBitmaps(uri)
                if (fullBitmap == null || previewBitmap == null) {
                    runOnUiThread {
                        hideLoading()
                        Toast.makeText(this, "Failed to load image format", Toast.LENGTH_LONG).show()
                        finish()
                    }
                    return@execute
                }

                fullResOriginal = fullBitmap
                previewOriginal = previewBitmap

                pipeline?.close()
                pipeline = RemasterPipeline(previewBitmap.width, previewBitmap.height)

                // Perform deep photo analysis
                val report = ImageRemasterAnalyzer.analyze(previewBitmap)
                analysisReport = report

                runOnUiThread {
                    binding.comparisonView.setBitmaps(previewBitmap, previewBitmap)
                    binding.analysisInsightsBadge.text = report.summaryBadge
                    binding.autoAnalysisSummary.text = "Diagnosis: ${report.summaryBadge}\nTap below to apply tailored enhancements"
                    hideLoading()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading image", e)
                runOnUiThread {
                    hideLoading()
                    Toast.makeText(this, "Error loading image: ${e.message}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun decodeBitmaps(uri: Uri): Pair<Bitmap?, Bitmap?> {
        // Read bounds and EXIF orientation
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }

        val origW = options.outWidth
        val origH = options.outHeight
        if (origW <= 0 || origH <= 0) return Pair(null, null)

        val rotationDegrees = getExifRotation(uri)

        // 1. Decode Full-Res Bitmap (bounded to safe memory ceiling e.g. 4096 max dimension)
        val maxFullDim = 4096
        var sampleSize = 1
        val maxDim = max(origW, origH)
        while (maxDim / (sampleSize * 2) >= maxFullDim) {
            sampleSize *= 2
        }

        val fullOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        var full = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, fullOptions) }
        if (full != null && rotationDegrees != 0) {
            val rotated = rotateBitmap(full, rotationDegrees)
            if (rotated !== full) full.recycle()
            full = rotated
        }

        // 2. Decode Viewport Preview Bitmap (max 1600 px for smooth real-time GPU response)
        val maxPreviewDim = 1600
        var previewSampleSize = 1
        while (maxDim / (previewSampleSize * 2) >= maxPreviewDim) {
            previewSampleSize *= 2
        }

        val previewOptions = BitmapFactory.Options().apply { inSampleSize = previewSampleSize }
        var preview = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, previewOptions) }
        if (preview != null && rotationDegrees != 0) {
            val rotated = rotateBitmap(preview, rotationDegrees)
            if (rotated !== preview) preview.recycle()
            preview = rotated
        }

        return Pair(full, preview)
    }

    private fun getExifRotation(uri: Uri): Int {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun executeAutoRemaster() {
        val report = analysisReport
        if (report == null) {
            Toast.makeText(this, "Analyzing photo...", Toast.LENGTH_SHORT).show()
            return
        }

        showLoading("Applying One-Tap Auto Remaster...")
        backgroundExecutor.execute {
            val autoAdjustments = report.recommendedAdjustments
            currentAdjustments = autoAdjustments
            history.push(autoAdjustments)

            val preview = previewOriginal
            val currentPipeline = pipeline
            if (preview != null && currentPipeline != null) {
                val remastered = currentPipeline.process(preview, autoAdjustments, report.faces)
                runOnUiThread {
                    previewRemastered = remastered
                    binding.comparisonView.updateRemastered(remastered)
                    updateUndoRedoButtons()
                    hideLoading()
                    Toast.makeText(this, "Auto Remaster Applied: ${report.summaryBadge}", Toast.LENGTH_SHORT).show()
                }
            } else {
                runOnUiThread { hideLoading() }
            }
        }
    }

    private fun applyAdjustments(adjustments: RemasterAdjustments, recordHistory: Boolean) {
        currentAdjustments = adjustments
        if (recordHistory) {
            history.push(adjustments)
            updateUndoRedoButtons()
        }
        renderPreview()
        if (activeCategory != Category.AUTO && activeCategory != Category.PRESETS) {
            populateSlidersForCategory(activeCategory)
        }
    }

    private fun renderPreview() {
        val preview = previewOriginal ?: return
        val currentPipeline = pipeline ?: return
        val faces = analysisReport?.faces ?: emptyList()

        backgroundExecutor.execute {
            try {
                val remastered = currentPipeline.process(preview, currentAdjustments, faces)
                runOnUiThread {
                    previewRemastered = remastered
                    binding.comparisonView.updateRemastered(remastered)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error rendering preview", e)
            }
        }
    }

    private fun updateUndoRedoButtons() {
        binding.btnUndo.isEnabled = history.canUndo
        binding.btnUndo.setColorFilter(if (history.canUndo) android.graphics.Color.WHITE else 0x55FFFFFF)

        binding.btnRedo.isEnabled = history.canRedo
        binding.btnRedo.setColorFilter(if (history.canRedo) android.graphics.Color.WHITE else 0x55FFFFFF)
    }

    private fun showExportDialog() {
        val full = fullResOriginal ?: return
        val dialog = RemasterExportDialog(
            context = this,
            originalWidth = full.width,
            originalHeight = full.height,
            onSaveCopy = { isUpscale, format, quality ->
                exportImage(isUpscale, format, quality, replaceOriginal = false, share = false)
            },
            onReplaceOriginal = { isUpscale, format, quality ->
                exportImage(isUpscale, format, quality, replaceOriginal = true, share = false)
            },
            onShare = { isUpscale, format, quality ->
                exportImage(isUpscale, format, quality, replaceOriginal = false, share = true)
            }
        )
        dialog.show()
    }

    private fun exportImage(
        isUpscale: Boolean,
        format: Bitmap.CompressFormat,
        quality: Int,
        replaceOriginal: Boolean,
        share: Boolean
    ) {
        val full = fullResOriginal ?: return
        showLoading(if (isUpscale) "AI 2× Super Resolution upscaling..." else "Rendering remastered photo...")

        backgroundExecutor.execute {
            try {
                val exportPipeline = RemasterPipeline(full.width, full.height)
                val faces = analysisReport?.faces ?: emptyList()
                var result = exportPipeline.process(full, currentAdjustments, faces)
                exportPipeline.close()

                if (isUpscale) {
                    runOnUiThread { showLoading("Synthesizing fine textures & edges...") }
                    val upscaled = SuperResolutionProcessor.upscale2x(
                        source = result,
                        removeArtifacts = currentAdjustments.removeArtifacts,
                        detailStrength = currentAdjustments.detailRecovery + currentAdjustments.aiDetailRecovery
                    )
                    if (upscaled !== result) result.recycle()
                    result = upscaled
                }

                if (replaceOriginal && sourceUri != null) {
                    // Replace original file with confirmation completed
                    val targetUri = sourceUri!!
                    contentResolver.openOutputStream(targetUri, "wt")?.use { out ->
                        result.compress(format, quality, out)
                    }
                    runOnUiThread {
                        hideLoading()
                        Toast.makeText(this, "Original photo replaced successfully", Toast.LENGTH_SHORT).show()
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                } else {
                    // Save as Copy
                    val savedUri = saveToMediaStore(result, format, quality)
                    runOnUiThread {
                        hideLoading()
                        if (savedUri != null) {
                            if (share) {
                                shareImage(savedUri, format)
                            } else {
                                Toast.makeText(this, "Remastered photo saved to Pictures/RetroCam", Toast.LENGTH_LONG).show()
                                setResult(Activity.RESULT_OK)
                                finish()
                            }
                        } else {
                            Toast.makeText(this, "Failed to save photo", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                runOnUiThread {
                    hideLoading()
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveToMediaStore(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): Uri? {
        val isPng = format == Bitmap.CompressFormat.PNG
        val extension = if (isPng) "png" else "jpg"
        val mimeType = if (isPng) "image/png" else "image/jpeg"
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "Remastered_${stamp}.$extension"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RetroCam")
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(format, quality, out)
        }

        val src = sourceUri
        if (src != null) {
            try {
                com.retrocam.app.library.repository.MediaLibraryRepository.getInstance(this)
                    .recordEditedCopy(src.toString(), uri, displayName)
            } catch (e: Exception) {
                // non-fatal
            }
        }

        // Add to active trip if present
        val activeTrip = tripRepository.getActiveTrip()
        if (activeTrip != null) {
            tripRepository.addMediaToActiveTrip(
                uri = uri,
                displayName = displayName,
                isVideo = false,
                dateTaken = System.currentTimeMillis()
            )
        }

        return uri
    }

    private fun shareImage(uri: Uri, format: Bitmap.CompressFormat) {
        val mimeType = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Remastered Photo"))
    }

    private fun showLoading(status: String) {
        runOnUiThread {
            binding.loadingStatusText.text = status
            binding.loadingOverlay.visibility = View.VISIBLE
        }
    }

    private fun hideLoading() {
        runOnUiThread {
            binding.loadingOverlay.visibility = View.GONE
        }
    }

    private fun confirmExitIfNeeded() {
        if (!currentAdjustments.isDefault()) {
            AlertDialog.Builder(this)
                .setTitle("Discard Changes?")
                .setMessage("You have unsaved remastering enhancements. Are you sure you want to exit?")
                .setPositiveButton("Discard") { _, _ -> finish() }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            finish()
        }
    }

    private fun intentUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_URI)
        }

    override fun onDestroy() {
        super.onDestroy()
        pipeline?.close()
        backgroundExecutor.shutdownNow()
        fullResOriginal?.recycle()
        previewOriginal?.recycle()
        previewRemastered?.recycle()
    }

    companion object {
        private const val TAG = "RemasterActivity"
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
    }
}
