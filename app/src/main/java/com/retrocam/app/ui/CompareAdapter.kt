package com.retrocam.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.retrocam.app.R
import com.retrocam.app.filter.FilterPreset

/** Grid of the same frame rendered through each camera. Tapping a tile applies that camera. */
class CompareAdapter(
    private val presets: List<FilterPreset>,
    private val thumbnails: ThumbnailCache,
    private val onPick: (FilterPreset) -> Unit
) : RecyclerView.Adapter<CompareAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.compareImage)
        val label: TextView = view.findViewById(R.id.compareName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_compare, parent, false))

    override fun getItemCount(): Int = presets.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val preset = presets[position]
        holder.label.text = preset.name
        holder.itemView.setOnClickListener { onPick(preset) }

        holder.image.tag = preset.id
        val cached = thumbnails.cached(preset)
        if (cached != null) {
            holder.image.setImageBitmap(cached)
            return
        }
        holder.image.setImageDrawable(null)
        thumbnails.request(preset, stillWanted = { holder.image.tag == preset.id }) { bitmap ->
            holder.image.post {
                if (holder.image.tag == preset.id) holder.image.setImageBitmap(bitmap)
            }
        }
    }
}
