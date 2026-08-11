package com.retrocam.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.retrocam.app.databinding.ActivityCompareBinding
import com.retrocam.app.filter.PresetRepository

/**
 * Shows one frozen frame graded through every camera preset at once, so the differences can be
 * judged side by side rather than by cycling the carousel and trying to remember.
 *
 * The frame is frozen deliberately: comparing looks against a live preview would mean each tile
 * showed a different moment, and the scene change would swamp the difference between renderings.
 */
class CompareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompareBinding
    private var thumbnails: ThumbnailCache? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        val frame = CompareFrame.get()
        val presets = PresetRepository.load(assets)
        // Original first as the reference, then every camera.
        val ordered = presets.filter { it.id == "original" } +
            presets.filter { it.category == "Camera" }

        if (frame == null || ordered.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            return
        }

        val cache = ThumbnailCache(targetWidth = TILE_WIDTH).also { thumbnails = it }
        cache.setSource(frame)

        binding.compareGrid.layoutManager = GridLayoutManager(this, COLUMNS)
        binding.compareGrid.adapter = CompareAdapter(ordered, cache) { preset ->
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PRESET_ID, preset.id))
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbnails?.close()
    }

    companion object {
        const val EXTRA_PRESET_ID = "presetId"
        private const val COLUMNS = 2
        private const val TILE_WIDTH = 320
    }
}
