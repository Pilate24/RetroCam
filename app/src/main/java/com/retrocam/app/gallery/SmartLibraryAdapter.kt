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
import com.retrocam.app.library.model.LibraryListItem
import com.retrocam.app.library.model.MediaItem
import java.util.concurrent.ExecutorService

class SmartLibraryAdapter(
    private val resolver: ContentResolver,
    private val executor: ExecutorService,
    private val onItemClick: (MediaItem) -> Unit,
    private val onItemLongClick: (MediaItem) -> Boolean
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<LibraryListItem>()
    private val cache = object : LruCache<String, Bitmap>(CACHE_ENTRIES) {}

    var isSelectionMode: Boolean = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    val selectedMediaIds = mutableSetOf<String>()

    fun submitItems(newItems: List<LibraryListItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun toggleSelection(mediaId: String) {
        if (selectedMediaIds.contains(mediaId)) {
            selectedMediaIds.remove(mediaId)
        } else {
            selectedMediaIds.add(mediaId)
        }
        notifyDataSetChanged()
    }

    fun selectAll(mediaList: List<MediaItem>) {
        selectedMediaIds.clear()
        selectedMediaIds.addAll(mediaList.map { it.id })
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedMediaIds.clear()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is LibraryListItem.Header -> TYPE_HEADER
            is LibraryListItem.Media -> TYPE_MEDIA
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val view = inflater.inflate(R.layout.item_date_header, parent, false)
            HeaderHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_photo, parent, false)
            MediaHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is LibraryListItem.Header -> {
                (holder as HeaderHolder).bind(item)
            }
            is LibraryListItem.Media -> {
                (holder as MediaHolder).bind(item.item)
            }
        }
    }

    inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val titleView: TextView = view.findViewById(R.id.sectionTitle)
        private val countView: TextView = view.findViewById(R.id.sectionCount)

        fun bind(header: LibraryListItem.Header) {
            titleView.text = header.title
            countView.text = if (header.count > 0) "(${header.count})" else ""
        }
    }

    inner class MediaHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val image: ImageView = view.findViewById(R.id.photoThumb)
        private val videoBadge: TextView = view.findViewById(R.id.videoBadge)
        private val favoriteBadge: ImageView = view.findViewById(R.id.favoriteBadge)
        private val editedBadge: TextView = view.findViewById(R.id.editedBadge)
        private val selectionDim: View = view.findViewById(R.id.selectionDimOverlay)
        private val selectionCheck: ImageView = view.findViewById(R.id.selectionCheck)

        fun bind(media: MediaItem) {
            itemView.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(media.id)
                } else {
                    onItemClick(media)
                }
            }

            itemView.setOnLongClickListener {
                onItemLongClick(media)
            }

            // Video duration badge
            if (media.isVideo) {
                videoBadge.isVisible = true
                videoBadge.text = "▶ ${media.durationFormatted}"
            } else {
                videoBadge.isVisible = false
            }

            // Favorite badge
            favoriteBadge.isVisible = media.isFavorite

            // Edited badge
            editedBadge.isVisible = media.isEdited

            // Selection state
            val isSelected = selectedMediaIds.contains(media.id)
            if (isSelectionMode) {
                selectionDim.isVisible = isSelected
                selectionCheck.isVisible = true
                selectionCheck.setImageResource(if (isSelected) R.drawable.ic_check else R.drawable.ic_check_circle)
                selectionCheck.imageTintList = android.content.res.ColorStateList.valueOf(
                    if (isSelected) itemView.context.getColor(R.color.amber) else 0x55FFFFFF
                )
            } else {
                selectionDim.isVisible = false
                selectionCheck.isVisible = false
            }

            // Thumbnail caching & async decoding
            image.tag = media.id
            val cached = cache.get(media.id)
            if (cached != null) {
                image.setImageBitmap(cached)
                return
            }

            image.setImageBitmap(null)
            val uri = media.uri ?: return
            executor.execute {
                val thumbnail = try {
                    resolver.loadThumbnail(uri, Size(THUMB_PX, THUMB_PX), null)
                } catch (e: Exception) {
                    null
                } ?: return@execute

                cache.put(media.id, thumbnail)
                image.post {
                    if (image.tag == media.id) {
                        image.setImageBitmap(thumbnail)
                    }
                }
            }
        }
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_MEDIA = 1
        private const val THUMB_PX = 360
        private const val CACHE_ENTRIES = 180
    }
}
