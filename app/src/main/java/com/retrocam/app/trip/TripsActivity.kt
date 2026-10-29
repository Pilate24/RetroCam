package com.retrocam.app.trip

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.retrocam.app.databinding.ActivityTripsBinding
import com.retrocam.app.trip.ui.TripsAdapter
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TripsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTripsBinding
    private lateinit var tripRepository: TripRepository
    private lateinit var tripsAdapter: TripsAdapter
    private lateinit var thumbnailExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTripsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tripRepository = TripRepository.getInstance(this)
        thumbnailExecutor = Executors.newFixedThreadPool(4)

        setUpViews()
        observeData()
    }

    private fun setUpViews() {
        binding.tripsBackButton.setOnClickListener { finish() }

        tripsAdapter = TripsAdapter(
            trips = emptyList(),
            activeTripId = null,
            resolver = contentResolver,
            executor = thumbnailExecutor,
            onTripClick = { trip ->
                val intent = Intent(this, TripDetailsActivity::class.java).apply {
                    putExtra(TripDetailsActivity.EXTRA_TRIP_ID, trip.id)
                }
                startActivity(intent)
            },
            onSetActiveClick = { trip ->
                val currentActive = tripRepository.getActiveTripId()
                if (currentActive == trip.id) {
                    tripRepository.clearActiveTrip()
                } else {
                    tripRepository.setActiveTrip(trip.id)
                }
            }
        )

        binding.tripsRecycler.apply {
            layoutManager = LinearLayoutManager(this@TripsActivity)
            adapter = tripsAdapter
        }

        binding.createTripFab.setOnClickListener {
            startActivity(Intent(this, TripEditActivity::class.java))
        }

        binding.emptyCreateTripButton.setOnClickListener {
            startActivity(Intent(this, TripEditActivity::class.java))
        }

        binding.activeTripBannerClear.setOnClickListener {
            tripRepository.clearActiveTrip()
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    tripRepository.tripsFlow.collect { trips ->
                        updateTripsUI(trips, tripRepository.getActiveTripId())
                    }
                }
                launch {
                    tripRepository.activeTripFlow.collect { activeTrip ->
                        updateActiveTripBanner(activeTrip)
                        tripsAdapter.updateData(tripRepository.getAllTrips(), activeTrip?.id)
                    }
                }
            }
        }
    }

    private fun updateTripsUI(trips: List<Trip>, activeTripId: String?) {
        if (trips.isEmpty()) {
            binding.emptyTripsView.visibility = View.VISIBLE
            binding.tripsRecycler.visibility = View.GONE
        } else {
            binding.emptyTripsView.visibility = View.GONE
            binding.tripsRecycler.visibility = View.VISIBLE
            tripsAdapter.updateData(trips, activeTripId)
        }
    }

    private fun updateActiveTripBanner(activeTrip: Trip?) {
        if (activeTrip != null) {
            binding.activeTripBanner.visibility = View.VISIBLE
            binding.activeTripBannerName.text = "${activeTrip.name} (${activeTrip.destination})"
        } else {
            binding.activeTripBanner.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        tripRepository.refreshTrips()
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbnailExecutor.shutdownNow()
    }
}
