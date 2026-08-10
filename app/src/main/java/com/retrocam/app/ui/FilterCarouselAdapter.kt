package com.retrocam.app.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.retrocam.app.R
import com.retrocam.app.filter.FilterPreset

/** Horizontal strip of look names. The centred one is active; see MainActivity for the snapping. */
class FilterCarouselAdapter(
    private val presets: List<FilterPreset>,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<FilterCarouselAdapter.Holder>() {

    var selectedPosition: Int = 0
        set(value) {
            if (field == value) return
            val previous = field
            field = value
            notifyItemChanged(previous)
            notifyItemChanged(value)
        }

    class Holder(val label: TextView) : RecyclerView.ViewHolder(label)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_filter, parent, false) as TextView
        return Holder(view)
    }

    override fun getItemCount(): Int = presets.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val isSelected = position == selectedPosition
        holder.label.text = presets[position].name
        holder.label.setTextColor(if (isSelected) COLOR_SELECTED else COLOR_IDLE)
        holder.label.scaleX = if (isSelected) 1.0f else 0.9f
        holder.label.scaleY = if (isSelected) 1.0f else 0.9f
        holder.label.setOnClickListener {
            val current = holder.bindingAdapterPosition
            if (current != RecyclerView.NO_POSITION) onItemClick(current)
        }
    }

    private companion object {
        const val COLOR_SELECTED = Color.WHITE
        val COLOR_IDLE = Color.argb(150, 255, 255, 255)
    }
}
