package com.retrocam.app.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.retrocam.app.R
import com.retrocam.app.filter.FilterPreset

/** Horizontal strip of looks. The centred one is active; see MainActivity for the snapping. */
class FilterCarouselAdapter(
    presets: List<FilterPreset>,
    private val thumbnails: ThumbnailCache?,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<FilterCarouselAdapter.Holder>() {

    var presets: List<FilterPreset> = presets
        private set

    var selectedPosition: Int = 0
        set(value) {
            if (field == value) return
            val previous = field
            field = value
            notifyItemChanged(previous)
            notifyItemChanged(value)
        }

    fun submit(newPresets: List<FilterPreset>, selected: Int) {
        presets = newPresets
        selectedPosition = selected
        notifyDataSetChanged()
    }

    /** Called when fresh thumbnails become available for the current scene. */
    fun refreshThumbnails() = notifyDataSetChanged()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.filterThumb)
        val label: TextView = view.findViewById(R.id.filterName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_filter, parent, false))

    override fun getItemCount(): Int = presets.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val preset = presets[position]
        val isSelected = position == selectedPosition

        holder.label.text = preset.name
        holder.label.setTextColor(if (isSelected) COLOR_SELECTED else COLOR_IDLE)
        holder.itemView.alpha = if (isSelected) 1.0f else 0.72f
        holder.itemView.scaleX = if (isSelected) 1.0f else 0.94f
        holder.itemView.scaleY = if (isSelected) 1.0f else 0.94f
        holder.itemView.setOnClickListener {
            val current = holder.bindingAdapterPosition
            if (current != RecyclerView.NO_POSITION) onItemClick(current)
        }

        // Views are recycled, so tag the target and drop results that arrive after a rebind.
        holder.thumb.tag = preset.id
        val cached = thumbnails?.cached(preset)
        if (cached != null) {
            holder.thumb.setImageBitmap(cached)
            return
        }
        // setImageBitmap(null) still allocates a BitmapDrawable and warns; this actually clears it.
        holder.thumb.setImageDrawable(null)
        thumbnails?.request(
            preset,
            stillWanted = { holder.thumb.tag == preset.id }
        ) { bitmap ->
            holder.thumb.post {
                if (holder.thumb.tag == preset.id) holder.thumb.setImageBitmap(bitmap)
            }
        }
    }

    private companion object {
        const val COLOR_SELECTED = Color.WHITE
        val COLOR_IDLE = Color.argb(150, 255, 255, 255)
    }
}
