package com.retrocam.app.trip

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import com.retrocam.app.MainActivity
import com.retrocam.app.R
import com.retrocam.app.databinding.ActivityTripDetailsBinding
import com.retrocam.app.gallery.ViewerActivity
import com.retrocam.app.trip.ui.TripMediaGridAdapter
import com.retrocam.app.trip.ui.TripMemoriesCarouselAdapter
import com.retrocam.app.video.VideoReviewActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TripDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTripDetailsBinding
    private lateinit var tripRepository: TripRepository
    private lateinit var executor: ExecutorService

    private var tripId: String = ""
    private var isCarouselMode = true

    private lateinit var carouselAdapter: TripMemoriesCarouselAdapter
    private lateinit var gridAdapter: TripMediaGridAdapter

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            importSelectedMedia(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTripDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tripId = intent.getStringExtra(EXTRA_TRIP_ID).orEmpty()
        if (tripId.isEmpty()) {
            Toast.makeText(this, "Trip not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tripRepository = TripRepository.getInstance(this)
        executor = Executors.newFixedThreadPool(4)

        setUpViews()
        loadTripData()
    }

    private fun setUpViews() {
        binding.detailsBackButton.setOnClickListener { finish() }

        // Setup Carousel Adapter
        carouselAdapter = TripMemoriesCarouselAdapter(
            mediaList = emptyList(),
            resolver = contentResolver,
            executor = executor,
            onMediaClick = { media -> openMedia(media) }
        )
        binding.carouselViewPager.adapter = carouselAdapter

        // Setup Grid Adapter
        gridAdapter = TripMediaGridAdapter(
            mediaList = emptyList(),
            resolver = contentResolver,
            executor = executor,
            onMediaClick = { media -> openMedia(media) }
        )
        binding.mediaGridRecycler.apply {
            layoutManager = GridLayoutManager(this@TripDetailsActivity, 3)
            adapter = gridAdapter
        }

        // View Mode Switchers
        binding.viewModeCarouselButton.setOnClickListener { setViewMode(carousel = true) }
        binding.viewModeGridButton.setOnClickListener { setViewMode(carousel = false) }

        // Add Media Action
        binding.addMediaButton.setOnClickListener {
            pickMediaLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            )
        }

        // Empty state buttons
        binding.emptyOpenCameraButton.setOnClickListener { openCameraWithActiveTrip() }
        binding.emptyAddMediaButton.setOnClickListener {
            pickMediaLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            )
        }

        // Overflow Menu
        binding.detailsOverflowButton.setOnClickListener { view -> showOverflowMenu(view) }
    }

    private fun setViewMode(carousel: Boolean) {
        isCarouselMode = carousel
        binding.carouselViewPager.isVisible = carousel
        binding.mediaGridRecycler.isVisible = !carousel

        binding.viewModeCarouselButton.setColorFilter(
            getColor(if (carousel) R.color.amber else R.color.readout_dim)
        )
        binding.viewModeGridButton.setColorFilter(
            getColor(if (!carousel) R.color.amber else R.color.readout_dim)
        )
    }

    private fun loadTripData() {
        val trip = tripRepository.getTrip(tripId)
        if (trip == null) {
            Toast.makeText(this, "Trip not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.detailsTripTitle.text = trip.name
        binding.detailsTripSubtitle.text = trip.destination

        val startStr = dateFormat.format(Date(trip.startDate))
        binding.detailsDatesText.text = if (trip.endDate != null && trip.endDate > trip.startDate) {
            val endStr = dateFormat.format(Date(trip.endDate))
            "$startStr – $endStr"
        } else {
            startStr
        }

        val isActive = tripRepository.getActiveTripId() == trip.id
        binding.detailsActiveBadge.isVisible = isActive

        // Load media items
        val mediaList = tripRepository.getTripMedia(tripId)
        if (mediaList.isEmpty()) {
            binding.emptyMediaView.isVisible = true
            binding.carouselViewPager.isVisible = false
            binding.mediaGridRecycler.isVisible = false
        } else {
            binding.emptyMediaView.isVisible = false
            setViewMode(isCarouselMode)
            carouselAdapter.updateMedia(mediaList)
            gridAdapter.updateMedia(mediaList)
        }
    }

    private fun importSelectedMedia(uris: List<Uri>) {
        var importedCount = 0
        for (uri in uris) {
            try {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (ignored: Exception) {}

                val type = contentResolver.getType(uri).orEmpty()
                val isVideo = type.startsWith("video")

                var displayName = "Imported_${System.currentTimeMillis()}"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrEmpty()) displayName = name
                    }
                }

                tripRepository.addMediaToTrip(
                    tripId = tripId,
                    uri = uri,
                    displayName = displayName,
                    isVideo = isVideo,
                    dateTaken = System.currentTimeMillis()
                )
                importedCount++
            } catch (e: Exception) {
                // Skip failed import
            }
        }

        Toast.makeText(this, "Imported $importedCount items to trip", Toast.LENGTH_SHORT).show()
        loadTripData()
    }

    private fun openMedia(media: TripMedia) {
        if (media.isVideo) {
            val intent = Intent(this, VideoReviewActivity::class.java).apply {
                putExtra(VideoReviewActivity.EXTRA_URI, media.uri)
                putExtra(VideoReviewActivity.EXTRA_NAME, media.displayName)
                putExtra(VideoReviewActivity.EXTRA_DURATION_MS, media.durationMs)
            }
            startActivity(intent)
        } else {
            val intent = Intent(this, ViewerActivity::class.java).apply {
                putExtra(ViewerActivity.EXTRA_URI, media.uri)
                putExtra(ViewerActivity.EXTRA_NAME, media.displayName)
            }
            startActivity(intent)
        }
    }

    private fun openCameraWithActiveTrip() {
        tripRepository.setActiveTrip(tripId)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val isActive = tripRepository.getActiveTripId() == tripId

        popup.menu.add(0, 1, 1, if (isActive) "Clear Active Trip" else "Set as Active Trip")
        popup.menu.add(0, 2, 2, "Edit Trip Details")
        popup.menu.add(0, 3, 3, "Open Camera")
        popup.menu.add(0, 4, 4, "Delete Trip")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    if (isActive) {
                        tripRepository.clearActiveTrip()
                        Toast.makeText(this, "Cleared active trip", Toast.LENGTH_SHORT).show()
                    } else {
                        tripRepository.setActiveTrip(tripId)
                        Toast.makeText(this, "Set as active camera trip", Toast.LENGTH_SHORT).show()
                    }
                    loadTripData()
                    true
                }
                2 -> {
                    startActivity(
                        Intent(this, TripEditActivity::class.java)
                            .putExtra(TripEditActivity.EXTRA_TRIP_ID, tripId)
                    )
                    true
                }
                3 -> {
                    openCameraWithActiveTrip()
                    true
                }
                4 -> {
                    confirmDeleteTrip()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun confirmDeleteTrip() {
        AlertDialog.Builder(this)
            .setTitle("Delete Trip?")
            .setMessage("Are you sure you want to delete this trip? Captured photos and videos will remain preserved in your device gallery.")
            .setPositiveButton("Delete") { _, _ ->
                tripRepository.deleteTrip(tripId)
                Toast.makeText(this, "Trip deleted", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        loadTripData()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    companion object {
        const val EXTRA_TRIP_ID = "extra_trip_id"
    }
}
