package com.retrocam.app.remaster.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.retrocam.app.R
import com.retrocam.app.databinding.DialogRemasterExportBinding
import java.util.Locale

/**
 * Modern Export & Save sheet for AI Photo Remaster.
 *
 * Provides resolution choice (Original vs AI 2x Super Resolution), format (JPEG / PNG),
 * JPEG quality slider, dynamic estimated file size calculation, and safe options:
 * - Save as Copy
 * - Replace Original (with mandatory explicit user confirmation)
 * - Share via system sheet
 */
class RemasterExportDialog(
    context: Context,
    private val originalWidth: Int,
    private val originalHeight: Int,
    private val onSaveCopy: (isUpscale: Boolean, format: Bitmap.CompressFormat, quality: Int) -> Unit,
    private val onReplaceOriginal: (isUpscale: Boolean, format: Bitmap.CompressFormat, quality: Int) -> Unit,
    private val onShare: (isUpscale: Boolean, format: Bitmap.CompressFormat, quality: Int) -> Unit
) : BottomSheetDialog(context) {

    private lateinit var binding: DialogRemasterExportBinding

    private var isUpscale = false
    private var isPng = false
    private var jpegQuality = 95

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogRemasterExportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        updateCalculations()
    }

    private fun setupUI() {
        val upscaledW = originalWidth * 2
        val upscaledH = originalHeight * 2

        binding.radioOriginalRes.text = "Original Resolution (${originalWidth} × ${originalHeight})"
        binding.radioUpscaledRes.text = "AI 2× Super Resolution (${upscaledW} × ${upscaledH})"

        binding.resolutionRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            isUpscale = (checkedId == binding.radioUpscaledRes.id)
            updateCalculations()
        }

        binding.formatToggleGroup.check(binding.btnFormatJpeg.id)
        binding.formatToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isPng = (checkedId == binding.btnFormatPng.id)
                binding.jpegQualityLayout.visibility = if (isPng) View.GONE else View.VISIBLE
                updateCalculations()
            }
        }

        binding.qualitySlider.value = jpegQuality.toFloat()
        binding.qualitySlider.addOnChangeListener { _, value, _ ->
            jpegQuality = value.toInt()
            val qualityLabel = when {
                jpegQuality >= 98 -> "Maximum (100%)"
                jpegQuality >= 93 -> "High ($jpegQuality%)"
                jpegQuality >= 85 -> "Standard ($jpegQuality%)"
                else -> "Compact ($jpegQuality%)"
            }
            binding.qualityPercentText.text = qualityLabel
            updateCalculations()
        }

        binding.btnSaveCopy.setOnClickListener {
            dismiss()
            onSaveCopy(isUpscale, getFormat(), jpegQuality)
        }

        binding.btnShare.setOnClickListener {
            dismiss()
            onShare(isUpscale, getFormat(), jpegQuality)
        }

        binding.btnReplaceOriginal.setOnClickListener {
            showReplaceConfirmation()
        }

        binding.btnCancel.setOnClickListener {
            dismiss()
        }
    }

    private fun getFormat(): Bitmap.CompressFormat =
        if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG

    private fun updateCalculations() {
        val w = if (isUpscale) originalWidth * 2 else originalWidth
        val h = if (isUpscale) originalHeight * 2 else originalHeight
        val totalMegapixels = (w.toLong() * h) / 1_000_000.0

        val estimatedBytes: Double = if (isPng) {
            // PNG estimate: approx 1.6 to 2.4 MB per megapixel
            totalMegapixels * 1.8 * 1024 * 1024
        } else {
            // JPEG estimate scaled by quality factor
            val qFactor = when {
                jpegQuality >= 98 -> 0.42
                jpegQuality >= 95 -> 0.28
                jpegQuality >= 90 -> 0.20
                jpegQuality >= 80 -> 0.14
                else -> 0.10
            }
            totalMegapixels * qFactor * 1024 * 1024
        }

        val sizeFormatted = formatFileSize(estimatedBytes.toLong())
        binding.estimatedSizeText.text = "~$sizeFormatted"
        binding.exportSummaryText.text = "${w} × ${h} • ${if (isPng) "PNG" else "JPEG"} • ~$sizeFormatted"
    }

    private fun formatFileSize(bytes: Long): String {
        return if (bytes < 1024 * 1024) {
            String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    private fun showReplaceConfirmation() {
        AlertDialog.Builder(context)
            .setTitle("Replace Original Photo?")
            .setMessage("This will permanently overwrite the original photo with the remastered version. This action cannot be undone.\n\nAre you sure you want to proceed?")
            .setPositiveButton("Replace") { _, _ ->
                dismiss()
                onReplaceOriginal(isUpscale, getFormat(), jpegQuality)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
