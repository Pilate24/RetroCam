package com.retrocam.app.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.retrocam.app.R

/** Category tabs above the filter carousel. Filtering only, no effect on the active preset. */
class CategoryAdapter(
    private val categories: List<String>,
    private val onItemClick: (String) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.Holder>() {

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
            .inflate(R.layout.item_category, parent, false) as TextView
        return Holder(view)
    }

    override fun getItemCount(): Int = categories.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val isSelected = position == selectedPosition
        holder.label.text = categories[position]
        holder.label.setTextColor(if (isSelected) COLOR_SELECTED else COLOR_IDLE)
        holder.label.setBackgroundResource(
            if (isSelected) R.drawable.category_pill_selected else 0
        )
        holder.label.setOnClickListener {
            val current = holder.bindingAdapterPosition
            if (current != RecyclerView.NO_POSITION) {
                selectedPosition = current
                onItemClick(categories[current])
            }
        }
    }

    private companion object {
        const val COLOR_SELECTED = Color.BLACK
        val COLOR_IDLE = Color.argb(190, 255, 255, 255)
    }
}
