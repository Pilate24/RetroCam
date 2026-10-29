package com.retrocam.app.library.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.retrocam.app.R
import com.retrocam.app.databinding.ActivityDuplicateReviewBinding
import com.retrocam.app.databinding.ItemDuplicateGroupBinding
import com.retrocam.app.library.model.DuplicateGroup
import com.retrocam.app.library.model.MediaItem
import com.retrocam.app.library.repository.MediaLibraryRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DuplicateReviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDuplicateReviewBinding
    private lateinit var repository: MediaLibraryRepository
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val thumbnailExecutor: ExecutorService = Executors.newFixedThreadPool(3)

    private val selectedForDeletion = mutableSetOf<String>()
    private var detectedGroups = listOf<DuplicateGroup>()

    private val thumbCache = object : LruCache<String, Bitmap>(80) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDuplicateReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = MediaLibraryRepository.getInstance(this)

        binding.backButton.setOnClickListener { finish() }
        binding.duplicateGroupsRecycler.layoutManager = LinearLayoutManager(this)

        binding.btnDeleteDuplicates.setOnClickListener {
            handleDeleteSelected()
        }

        startDuplicateScan()
    }

    private fun startDuplicateScan() {
        binding.scanProgressBar.isVisible = true
        binding.scanSummaryText.text = "Analyzing media for duplicates..."

        backgroundExecutor.execute {
            val groups = repository.findDuplicates()
            runOnUiThread {
                binding.scanProgressBar.isVisible = false
                detectedGroups = groups
                selectedForDeletion.clear()

                // Default selection: select all duplicates except the first item in each group
                for (group in groups) {
                    for (item in group.items.drop(1)) {
                        selectedForDeletion.add(item.id)
                    }
                }

                updateUI()
            }
        }
    }

    private fun updateUI() {
        if (detectedGroups.isEmpty()) {
            binding.scanStatusBanner.isVisible = false
            binding.cleanupActionBar.isVisible = false
            binding.duplicateGroupsRecycler.isVisible = false
            binding.emptyDuplicateState.isVisible = true
        } else {
            binding.emptyDuplicateState.isVisible = false
            binding.duplicateGroupsRecycler.isVisible = true
            binding.cleanupActionBar.isVisible = true
            binding.scanStatusBanner.isVisible = true

            val totalReclaimable = detectedGroups.sumOf { it.reclaimableBytes }
            val mb = totalReclaimable / (1024.0 * 1024.0)
            binding.scanSummaryText.text = "Found ${detectedGroups.size} duplicate groups · Potential savings: ${"%.1f".format(mb)} MB"

            binding.duplicateGroupsRecycler.adapter = DuplicateGroupsAdapter(detectedGroups)
            updateDeleteButtonText()
        }
    }

    private fun updateDeleteButtonText() {
        var selectedBytes = 0L
        for (group in detectedGroups) {
            for (item in group.items) {
                if (selectedForDeletion.contains(item.id)) {
                    selectedBytes += item.sizeBytes
                }
            }
        }
        val mb = selectedBytes / (1024.0 * 1024.0)
        binding.btnDeleteDuplicates.text = "Delete ${selectedForDeletion.size} Copies (${"%.1f".format(mb)} MB)"
        binding.btnDeleteDuplicates.isEnabled = selectedForDeletion.isNotEmpty()
    }

    private fun handleDeleteSelected() {
        val count = selectedForDeletion.size
        if (count == 0) return

        MaterialAlertDialogBuilder(this)
            .setTitle("Delete $count duplicates?")
            .setMessage("This will permanently delete the selected duplicate photos from your device. One original copy in each group will be preserved.")
            .setPositiveButton("Delete") { _, _ ->
                val allItems = detectedGroups.flatMap { it.items }.associateBy { it.id }
                var deleted = 0
                for (id in selectedForDeletion) {
                    val item = allItems[id] ?: continue
                    val uri = item.uri ?: continue
                    try {
                        contentResolver.delete(uri, null, null)
                        repository.deleteFromIndex(item.id)
                        deleted++
                    } catch (e: Exception) {
                        // ignore or handle security exception
                    }
                }
                Toast.makeText(this, "Deleted $deleted duplicate photos", Toast.LENGTH_SHORT).show()
                startDuplicateScan()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdownNow()
        thumbnailExecutor.shutdownNow()
    }

    inner class DuplicateGroupsAdapter(
        private val groups: List<DuplicateGroup>
    ) : RecyclerView.Adapter<DuplicateGroupsAdapter.GroupHolder>() {

        inner class GroupHolder(val b: ItemDuplicateGroupBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupHolder {
            val b = ItemDuplicateGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return GroupHolder(b)
        }

        override fun getItemCount(): Int = groups.size

        override fun onBindViewHolder(holder: GroupHolder, position: Int) {
            val group = groups[position]
            holder.b.duplicateReasonText.text = group.reason
            val mb = group.reclaimableBytes / (1024.0 * 1024.0)
            holder.b.duplicateReclaimableText.text = "Reclaim: %.1f MB".format(mb)

            holder.b.duplicateItemsRecycler.layoutManager = LinearLayoutManager(
                holder.itemView.context,
                LinearLayoutManager.HORIZONTAL,
                false
            )
            holder.b.duplicateItemsRecycler.adapter = DuplicateChildAdapter(group.items)
        }
    }

    inner class DuplicateChildAdapter(
        private val items: List<MediaItem>
    ) : RecyclerView.Adapter<DuplicateChildAdapter.ChildHolder>() {

        inner class ChildHolder(view: View) : RecyclerView.ViewHolder(view) {
            val thumb: ImageView = view.findViewById(R.id.duplicateThumb)
            val checkBox: CheckBox = view.findViewById(R.id.duplicateCheckBox)
            val badge: TextView = view.findViewById(R.id.duplicateBadge)
            val size: TextView = view.findViewById(R.id.duplicateSize)
            val res: TextView = view.findViewById(R.id.duplicateResolution)
            val date: TextView = view.findViewById(R.id.duplicateDate)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChildHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_duplicate_child, parent, false)
            return ChildHolder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ChildHolder, position: Int) {
            val item = items[position]
            val isFirst = position == 0

            holder.badge.text = if (isFirst) "KEEP (Original)" else "DUPLICATE"
            holder.badge.setTextColor(if (isFirst) getColor(R.color.amber) else 0xCCFFFFFF.toInt())

            holder.size.text = item.fileSizeFormatted
            holder.res.text = item.resolutionFormatted

            val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
            holder.date.text = sdf.format(Date(if (item.dateTaken > 0) item.dateTaken else item.dateAdded))

            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.isChecked = selectedForDeletion.contains(item.id)
            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedForDeletion.add(item.id)
                } else {
                    selectedForDeletion.remove(item.id)
                }
                updateDeleteButtonText()
            }

            // Load thumbnail
            holder.thumb.tag = item.id
            val cached = thumbCache.get(item.id)
            if (cached != null) {
                holder.thumb.setImageBitmap(cached)
                return
            }

            holder.thumb.setImageBitmap(null)
            val uri = item.uri ?: return
            thumbnailExecutor.execute {
                val thumbnail = try {
                    contentResolver.loadThumbnail(uri, Size(240, 240), null)
                } catch (e: Exception) {
                    null
                } ?: return@execute

                thumbCache.put(item.id, thumbnail)
                holder.thumb.post {
                    if (holder.thumb.tag == item.id) {
                        holder.thumb.setImageBitmap(thumbnail)
                    }
                }
            }
        }
    }
}
