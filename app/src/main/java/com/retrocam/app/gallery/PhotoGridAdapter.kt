package com.retrocam.app.gallery

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
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
import java.util.Locale
import java.util.concurrent.ExecutorService

/**
 * Grid of thumbnails for both photos and videos. Decoding happens on [executor] and results are cached.
 * Supports multi-selection for batch operations (delete, share).
 */
class PhotoGridAdapter(
    private var photos: List<Photo>,
    private val resolver: ContentResolver,
    private val executor: ExecutorService,
    private val onClick: (Photo) -> Unit,
    private val onLongClick: ((Photo) -> Unit)? = null,
    private val onSelectionChanged: ((selectedCount: Int) -> Unit)? = null
) : RecyclerView.Adapter<PhotoGridAdapter.Holder>() {

    private val cache = object : LruCache<Long, Bitmap>(CACHE_ENTRIES) {}
    private val selectedUris = mutableSetOf<Uri>()
    var isSelectionMode: Boolean = false
        private set

    val selectedCount: Int
        get() = selectedUris.size

    val totalCount: Int
        get() = photos.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.photoThumb)
        val videoBadge: TextView = view.findViewById(R.id.videoBadge)
        val editedBadge: TextView = view.findViewById(R.id.editedBadge)
        val favoriteBadge: ImageView = view.findViewById(R.id.favoriteBadge)
        val selectionDimOverlay: View = view.findViewById(R.id.selectionDimOverlay)
        val selectionCheck: ImageView = view.findViewById(R.id.selectionCheck)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_photo, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = photos.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val photo = photos[position]
        val isSelected = photo.uri != null && selectedUris.contains(photo.uri)

        holder.itemView.setOnClickListener {
            if (isSelectionMode) {
                toggleSelection(photo)
            } else {
                onClick(photo)
            }
        }

        holder.itemView.setOnLongClickListener {
            if (!isSelectionMode) {
                if (onLongClick != null) {
                    onLongClick.invoke(photo)
                } else {
                    enterSelectionMode(photo)
                }
                true
            } else {
                false
            }
        }

        // 1. Selection indicator
        if (isSelectionMode) {
            holder.selectionCheck.isVisible = true
            holder.selectionDimOverlay.isVisible = isSelected
            if (isSelected) {
                holder.selectionCheck.setImageResource(R.drawable.ic_check_circle)
                holder.selectionCheck.setColorFilter(Color.parseColor("#E8A33D"))
            } else {
                holder.selectionCheck.setImageResource(R.drawable.ic_check)
                holder.selectionCheck.setColorFilter(Color.parseColor("#66FFFFFF"))
            }
        } else {
            holder.selectionCheck.isVisible = false
            holder.selectionDimOverlay.isVisible = false
        }

        // 2. Video badge
        if (photo.isVideo) {
            holder.videoBadge.isVisible = true
            val totalSec = (photo.durationMs / 1000).coerceAtLeast(0)
            holder.videoBadge.text = String.format(Locale.US, "▶ %02d:%02d", totalSec / 60, totalSec % 60)
        } else {
            holder.videoBadge.isVisible = false
        }

        // 3. Category / Edited badge
        when (photo.folder) {
            GalleryFolder.RAW -> {
                holder.editedBadge.isVisible = true
                holder.editedBadge.text = "RAW"
                holder.editedBadge.setBackgroundColor(Color.parseColor("#2E7D32"))
                holder.editedBadge.setTextColor(Color.WHITE)
            }
            GalleryFolder.LONG_EXP -> {
                holder.editedBadge.isVisible = true
                holder.editedBadge.text = "LONG EXP"
                holder.editedBadge.setBackgroundColor(Color.parseColor("#E8A33D"))
                holder.editedBadge.setTextColor(Color.BLACK)
            }
            GalleryFolder.PHOTOBOOTH -> {
                holder.editedBadge.isVisible = true
                holder.editedBadge.text = "BOOTH"
                holder.editedBadge.setBackgroundColor(Color.parseColor("#E8A33D"))
                holder.editedBadge.setTextColor(Color.BLACK)
            }
            GalleryFolder.REMASTERED -> {
                holder.editedBadge.isVisible = true
                holder.editedBadge.text = "EDITED"
                holder.editedBadge.setBackgroundColor(Color.parseColor("#E8A33D"))
                holder.editedBadge.setTextColor(Color.BLACK)
            }
            else -> {
                holder.editedBadge.isVisible = false
            }
        }

        // 4. Favorite badge
        holder.favoriteBadge.isVisible = photo.isFavorite

        // 5. Thumbnail loading
        holder.image.tag = photo.id
        val cached = cache.get(photo.id)
        if (cached != null) {
            holder.image.setImageBitmap(cached)
            return
        }

        holder.image.setImageBitmap(null)
        val photoUri = photo.uri ?: return
        executor.execute {
            val thumbnail = try {
                resolver.loadThumbnail(photoUri, Size(THUMB_PX, THUMB_PX), null)
            } catch (e: Exception) {
                null
            } ?: return@execute

            cache.put(photo.id, thumbnail)
            holder.image.post {
                if (holder.image.tag == photo.id) holder.image.setImageBitmap(thumbnail)
            }
        }
    }

    fun submitList(newList: List<Photo>) {
        photos = newList
        val validUris = newList.mapNotNull { it.uri }.toSet()
        selectedUris.retainAll(validUris)
        notifyDataSetChanged()
        if (isSelectionMode) {
            onSelectionChanged?.invoke(selectedUris.size)
        }
    }

    fun enterSelectionMode(initialSelected: Photo? = null) {
        isSelectionMode = true
        selectedUris.clear()
        initialSelected?.uri?.let { selectedUris.add(it) }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedUris.size)
    }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedUris.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    fun toggleSelection(photo: Photo) {
        val uri = photo.uri ?: return
        if (selectedUris.contains(uri)) {
            selectedUris.remove(uri)
        } else {
            selectedUris.add(uri)
        }
        val pos = photos.indexOfFirst { it.uri == uri }
        if (pos != -1) {
            notifyItemChanged(pos)
        }
        onSelectionChanged?.invoke(selectedUris.size)
    }

    fun selectAll() {
        selectedUris.clear()
        selectedUris.addAll(photos.mapNotNull { it.uri })
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedUris.size)
    }

    fun deselectAll() {
        selectedUris.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    fun isAllSelected(): Boolean {
        return photos.isNotEmpty() && selectedUris.size == photos.size
    }

    fun getSelectedPhotos(): List<Photo> {
        return photos.filter { it.uri != null && selectedUris.contains(it.uri) }
    }

    fun getSelectedUris(): List<Uri> {
        return selectedUris.toList()
    }

    private companion object {
        const val THUMB_PX = 360
        const val CACHE_ENTRIES = 120
    }
}
