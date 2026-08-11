package com.retrocam.app.ui

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.annotation.RequiresApi
import com.retrocam.app.filter.FilterPreset
import com.retrocam.app.filter.OverlayCompositor
import com.retrocam.app.filter.ShaderRenderer
import java.util.Date
import java.util.concurrent.Executors

/**
 * Renders the carousel's preview thumbnails by running each preset over a downscaled camera frame.
 *
 * Deliberately reuses the same [ShaderRenderer] and [OverlayCompositor] as the live preview and the
 * capture, so a thumbnail is the real filter rather than a separate approximation that could drift.
 * One small renderer is shared across every preset — allocating seventy would mean seventy
 * ImageReaders and their buffers.
 *
 * Work happens on one background thread and results are cached, so scrolling the carousel never
 * touches the camera thread.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class ThumbnailCache(private val targetWidth: Int = 132) {

    private val executor = Executors.newSingleThreadExecutor()
    private val cache = LruCache<String, Bitmap>(CACHE_ENTRIES)

    private var renderer: ShaderRenderer? = null
    private var source: Bitmap? = null

    @Volatile
    private var closed = false

    /** Bumped whenever the source frame changes, so in-flight renders for the old frame are dropped. */
    @Volatile
    private var generation = 0

    /**
     * Supplies the frame thumbnails are rendered from. Called rarely — the thumbnails only need to
     * look like the scene, not track it — because each call invalidates the whole cache.
     */
    fun setSource(frame: Bitmap) {
        if (closed) return
        val scaled = Bitmap.createScaledBitmap(
            frame,
            targetWidth,
            (targetWidth * frame.height / frame.width.toFloat()).toInt().coerceAtLeast(1),
            true
        )
        executor.execute {
            if (closed) {
                scaled.recycle()
                return@execute
            }
            val previous = source
            source = scaled
            previous?.recycle()

            val existing = renderer
            if (existing == null || existing.width != scaled.width || existing.height != scaled.height) {
                existing?.close()
                renderer = ShaderRenderer(scaled.width, scaled.height)
            }
            generation++
            cache.evictAll()
        }
    }

    fun cached(preset: FilterPreset): Bitmap? = cache.get(preset.id)

    /**
     * Renders [preset] if it isn't cached yet. [onReady] fires on the background thread; callers
     * are responsible for hopping to the UI thread. [stillWanted] is checked immediately before
     * delivery so a recycled row doesn't get someone else's thumbnail.
     */
    fun request(preset: FilterPreset, stillWanted: () -> Boolean, onReady: (Bitmap) -> Unit) {
        if (closed) return
        val requestedAt = generation
        executor.execute {
            if (closed || requestedAt != generation) return@execute
            cache.get(preset.id)?.let { onReady(it); return@execute }

            val frame = source ?: return@execute
            val shader = renderer ?: return@execute
            val thumbnail = try {
                // Seed 0: thumbnails must not shimmer as the carousel rebinds.
                val filtered = shader.render(frame, 0f, preset)
                val composed = OverlayCompositor.compose(filtered, preset, FIXED_DATE)
                if (composed !== filtered) filtered.recycle()
                composed
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not render thumbnail for '${preset.id}'", e)
                return@execute
            }

            cache.put(preset.id, thumbnail)
            if (stillWanted()) onReady(thumbnail)
        }
    }

    fun close() {
        closed = true
        executor.execute {
            renderer?.close()
            renderer = null
            source?.recycle()
            source = null
            cache.evictAll()
        }
        executor.shutdown()
    }

    private companion object {
        const val TAG = "RetroCam"
        const val CACHE_ENTRIES = 96
        val FIXED_DATE = Date(0)
    }
}
