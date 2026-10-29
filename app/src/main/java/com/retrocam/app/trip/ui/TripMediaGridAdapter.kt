package com.retrocam.app.trip.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
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
import com.retrocam.app.trip.TripMedia
import java.util.Locale
import java.util.concurrent.ExecutorService

class TripMediaGridAdapter(
    private var mediaList: List<TripMedia>,
    private val resolver: ContentResolver,
    private val executor: ExecutorService,
    private val onMediaClick: (TripMedia) -> Unit
) : RecyclerView.Adapter<TripMediaGridAdapter.GridViewHolder>() {

    private val cache = object : LruCache<String, Bitmap>(CACHE_SIZE) {}

    class GridViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.gridMediaThumb)
        val videoBadge: TextView = view.findViewById(R.id.gridVideoBadge)
    }

    fun updateMedia(newMedia: List<TripMedia>) {
        this.mediaList = newMedia
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GridViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_trip_grid, parent, false)
        return GridViewHolder(view)
    }

    override fun getItemCount(): Int = mediaList.size

    override fun onBindViewHolder(holder: GridViewHolder, position: Int) {
        val item = mediaList[position]
        holder.itemView.setOnClickListener { onMediaClick(item) }

        if (item.isVideo) {
            holder.videoBadge.isVisible = true
            val totalSec = (item.durationMs / 1000).coerceAtLeast(0)
            holder.videoBadge.text = String.format(Locale.US, "▶ %02d:%02d", totalSec / 60, totalSec % 60)
        } else {
            holder.videoBadge.isVisible = false
        }

        val uriStr = item.uriString
        holder.thumbnail.tag = uriStr

        val cached = cache.get(uriStr)
        if (cached != null) {
            holder.thumbnail.setImageBitmap(cached)
            return
        }

        holder.thumbnail.setImageBitmap(null)
        executor.execute {
            val thumbnail = try {
                val uri = Uri.parse(uriStr)
                resolver.loadThumbnail(uri, Size(GRID_THUMB_PX, GRID_THUMB_PX), null)
            } catch (e: Exception) {
                null
            }

            if (thumbnail != null) {
                cache.put(uriStr, thumbnail)
                holder.thumbnail.post {
                    if (holder.thumbnail.tag == uriStr) {
                        holder.thumbnail.setImageBitmap(thumbnail)
                    }
                }
            }
        }
    }

    companion object {
        private const val GRID_THUMB_PX = 360
        private const val CACHE_SIZE = 100
    }
}
