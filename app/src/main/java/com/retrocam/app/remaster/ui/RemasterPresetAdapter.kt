package com.retrocam.app.remaster.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.retrocam.app.R
import com.retrocam.app.databinding.ItemRemasterPresetBinding
import com.retrocam.app.remaster.model.RemasterPreset

class RemasterPresetAdapter(
    private val presets: List<RemasterPreset>,
    private var selectedPreset: RemasterPreset?,
    private val onPresetSelected: (RemasterPreset) -> Unit
) : RecyclerView.Adapter<RemasterPresetAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemRemasterPresetBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRemasterPresetBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val preset = presets[position]
        val isSelected = preset == selectedPreset

        holder.binding.presetTitle.text = preset.title
        holder.binding.presetSubtitle.text = preset.description

        if (isSelected) {
            holder.binding.presetCard.setBackgroundResource(R.drawable.category_pill_selected)
            holder.binding.presetTitle.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.amber))
        } else {
            holder.binding.presetCard.setBackgroundResource(R.drawable.thumb_frame)
            holder.binding.presetTitle.setTextColor(Color.WHITE)
        }

        holder.itemView.setOnClickListener {
            val prev = selectedPreset
            selectedPreset = preset
            notifyDataSetChanged()
            onPresetSelected(preset)
        }
    }

    override fun getItemCount(): Int = presets.size

    fun setSelected(preset: RemasterPreset?) {
        selectedPreset = preset
        notifyDataSetChanged()
    }
}
