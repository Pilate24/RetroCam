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
import com.google.android.material.button.MaterialButton
import com.retrocam.app.R
import com.retrocam.app.trip.Trip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService

class TripsAdapter(
    private var trips: List<Trip>,
    private var activeTripId: String?,
    private val resolver: ContentResolver,
    private val executor: ExecutorService,
    private val onTripClick: (Trip) -> Unit,
    private val onSetActiveClick: (Trip) -> Unit
) : RecyclerView.Adapter<TripsAdapter.TripViewHolder>() {

    private val thumbnailCache = object : LruCache<String, Bitmap>(CACHE_SIZE) {}
    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    class TripViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val coverImage: ImageView = view.findViewById(R.id.tripCoverImage)
        val activeBadge: TextView = view.findViewById(R.id.tripActiveBadge)
        val mediaCountBadge: TextView = view.findViewById(R.id.tripMediaCountBadge)
        val nameText: TextView = view.findViewById(R.id.tripName)
        val destinationText: TextView = view.findViewById(R.id.tripDestination)
        val datesText: TextView = view.findViewById(R.id.tripDates)
        val setActiveButton: MaterialButton = view.findViewById(R.id.tripSetActiveButton)
    }

    fun updateData(newTrips: List<Trip>, newActiveId: String?) {
        this.trips = newTrips
        this.activeTripId = newActiveId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TripViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_trip, parent, false)
        return TripViewHolder(view)
    }

    override fun getItemCount(): Int = trips.size

    override fun onBindViewHolder(holder: TripViewHolder, position: Int) {
        val trip = trips[position]
        val isActive = trip.id == activeTripId

        holder.nameText.text = trip.name
        holder.destinationText.text = trip.destination

        // Format dates
        val startStr = dateFormat.format(Date(trip.startDate))
        holder.datesText.text = if (trip.endDate != null && trip.endDate > trip.startDate) {
            val endStr = dateFormat.format(Date(trip.endDate))
            "$startStr – $endStr"
        } else {
            startStr
        }

        // Media count
        holder.mediaCountBadge.text = when (trip.mediaCount) {
            0 -> "No items"
            1 -> "1 item"
            else -> "${trip.mediaCount} items"
        }

        // Active state
        holder.activeBadge.isVisible = isActive
        if (isActive) {
            holder.setActiveButton.text = "Active"
            holder.setActiveButton.setTextColor(holder.itemView.context.getColor(R.color.amber))
        } else {
            holder.setActiveButton.text = "Set Active"
            holder.setActiveButton.setTextColor(holder.itemView.context.getColor(R.color.readout))
        }

        holder.itemView.setOnClickListener { onTripClick(trip) }
        holder.setActiveButton.setOnClickListener { onSetActiveClick(trip) }

        // Cover image loading
        val coverUriStr = trip.coverUri
        holder.coverImage.tag = coverUriStr

        if (coverUriStr.isNullOrEmpty()) {
            holder.coverImage.setImageResource(R.drawable.camera_body)
            holder.coverImage.alpha = 0.3f
        } else {
            holder.coverImage.alpha = 1.0f
            val cached = thumbnailCache.get(coverUriStr)
            if (cached != null) {
                holder.coverImage.setImageBitmap(cached)
            } else {
                holder.coverImage.setImageBitmap(null)
                executor.execute {
                    val bitmap = try {
                        val uri = Uri.parse(coverUriStr)
                        resolver.loadThumbnail(uri, Size(THUMB_PX, THUMB_PX), null)
                    } catch (e: Exception) {
                        null
                    }
                    if (bitmap != null) {
                        thumbnailCache.put(coverUriStr, bitmap)
                        holder.coverImage.post {
                            if (holder.coverImage.tag == coverUriStr) {
                                holder.coverImage.setImageBitmap(bitmap)
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val THUMB_PX = 480
        private const val CACHE_SIZE = 50
    }
}
