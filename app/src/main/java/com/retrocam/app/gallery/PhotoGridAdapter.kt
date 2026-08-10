package com.retrocam.app.gallery

import android.content.ContentResolver
import android.graphics.Bitmap
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.retrocam.app.R
import java.util.concurrent.ExecutorService

/**
 * Grid of thumbnails. Decoding happens on [executor] and results are cached, because MediaStore's
 * thumbnail call hits disk and would jank the scroll if run inline.
 */
class PhotoGridAdapter(
    private val photos: List<Photo>,
    private val resolver: ContentResolver,
    private val executor: ExecutorService,
    private val onClick: (Photo) -> Unit
) : RecyclerView.Adapter<PhotoGridAdapter.Holder>() {

    private val cache = object : LruCache<Long, Bitmap>(CACHE_ENTRIES) {}

    class Holder(val image: ImageView) : RecyclerView.ViewHolder(image)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_photo, parent, false) as ImageView
        return Holder(view)
    }

    override fun getItemCount(): Int = photos.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val photo = photos[position]
        holder.image.setOnClickListener { onClick(photo) }

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
