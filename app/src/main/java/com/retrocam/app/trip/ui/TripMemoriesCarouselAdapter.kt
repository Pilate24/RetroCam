package com.retrocam.app.trip.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService

class TripMemoriesCarouselAdapter(
    private var mediaList: List<TripMedia>,
    private val resolver: ContentResolver,
    private val executor: ExecutorService,
    private val onMediaClick: (TripMedia) -> Unit
) : RecyclerView.Adapter<TripMemoriesCarouselAdapter.CarouselViewHolder>() {

    private val cache = object : LruCache<String, Bitmap>(CACHE_SIZE) {}
    private val dateTimeFormat = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.US)

    class CarouselViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.carouselImage)
        val positionBadge: TextView = view.findViewById(R.id.carouselPositionBadge)
        val dateText: TextView = view.findViewById(R.id.carouselDateText)
        val videoPlayContainer: View = view.findViewById(R.id.carouselVideoPlayContainer)
        val videoDurationText: TextView = view.findViewById(R.id.carouselVideoDurationText)
        val mediaTitle: TextView = view.findViewById(R.id.carouselMediaTitle)
        val contributorText: TextView = view.findViewById(R.id.carouselContributorText)
    }

    fun updateMedia(newMedia: List<TripMedia>) {
        this.mediaList = newMedia
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CarouselViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_trip_carousel, parent, false)
        return CarouselViewHolder(view)
    }

    override fun getItemCount(): Int = mediaList.size

    override fun onBindViewHolder(holder: CarouselViewHolder, position: Int) {
        val item = mediaList[position]

        // Position and date
        holder.positionBadge.text = "${position + 1} of $itemCount"
        holder.dateText.text = dateTimeFormat.format(Date(item.dateTaken))
        holder.mediaTitle.text = item.displayName

        if (!item.contributorName.isNullOrEmpty()) {
            holder.contributorText.isVisible = true
            holder.contributorText.text = "Captured by ${item.contributorName}"
        } else {
            holder.contributorText.isVisible = false
        }

        // Video indicators
        if (item.isVideo) {
            holder.videoPlayContainer.isVisible = true
            val totalSec = (item.durationMs / 1000).coerceAtLeast(0)
            holder.videoDurationText.text = String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
        } else {
            holder.videoPlayContainer.isVisible = false
        }

        holder.itemView.setOnClickListener { onMediaClick(item) }

        // Thumbnail / full preview loading
        val uriStr = item.uriString
        holder.image.tag = uriStr

        val cached = cache.get(uriStr)
        if (cached != null) {
            holder.image.setImageBitmap(cached)
            return
        }

        holder.image.setImageBitmap(null)
        executor.execute {
            val bitmap = try {
                val uri = Uri.parse(uriStr)
                if (item.isVideo) {
                    resolver.loadThumbnail(uri, Size(CAROUSEL_PX, CAROUSEL_PX), null)
                } else {
                    // Try decoding with high quality for carousel
                    resolver.openInputStream(uri)?.use { stream ->
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = 2
                        }
                        BitmapFactory.decodeStream(stream, null, options)
                    } ?: resolver.loadThumbnail(uri, Size(CAROUSEL_PX, CAROUSEL_PX), null)
                }
            } catch (e: Exception) {
                null
            }

            if (bitmap != null) {
                cache.put(uriStr, bitmap)
                holder.image.post {
                    if (holder.image.tag == uriStr) {
                        holder.image.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    companion object {
        private const val CAROUSEL_PX = 1080
        private const val CACHE_SIZE = 30
    }
}
