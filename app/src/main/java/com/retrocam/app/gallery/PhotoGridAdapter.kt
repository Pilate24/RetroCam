package com.retrocam.app.gallery

import android.content.ContentResolver
import android.graphics.Bitmap
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.retrocam.app.R
import java.util.Locale
import java.util.concurrent.ExecutorService

/**
 * Grid of thumbnails for both photos and videos. Decoding happens on [executor] and results are cached.
 */
class PhotoGridAdapter(
    private val photos: List<Photo>,
    private val resolver: ContentResolver,
    private val executor: ExecutorService,
    private val onClick: (Photo) -> Unit
) : RecyclerView.Adapter<PhotoGridAdapter.Holder>() {

    private val cache = object : LruCache<Long, Bitmap>(CACHE_ENTRIES) {}

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.photoThumb)
        val videoBadge: TextView = view.findViewById(R.id.videoBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_photo, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = photos.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val photo = photos[position]
        holder.itemView.setOnClickListener { onClick(photo) }

        if (photo.isVideo) {
            holder.videoBadge.isVisible = true
            val totalSec = (photo.durationMs / 1000).coerceAtLeast(0)
            holder.videoBadge.text = String.format(Locale.US, "▶ %02d:%02d", totalSec / 60, totalSec % 60)
        } else {
            holder.videoBadge.isVisible = false
        }

        // Views are recycled, so tag the target and drop results that arrive after a rebind.
        holder.image.tag = photo.id

        val cached = cache.get(photo.id)
        if (cached != null) {
            holder.image.setImageBitmap(cached)
            return
        }

        holder.image.setImageBitmap(null)
        executor.execute {
            val thumbnail = try {
                resolver.loadThumbnail(photo.uri, Size(THUMB_PX, THUMB_PX), null)
            } catch (e: Exception) {
                null
            } ?: return@execute

            cache.put(photo.id, thumbnail)
            holder.image.post {
                if (holder.image.tag == photo.id) holder.image.setImageBitmap(thumbnail)
            }
        }
    }

    private companion object {
        const val THUMB_PX = 360
        const val CACHE_ENTRIES = 120
    }
}
