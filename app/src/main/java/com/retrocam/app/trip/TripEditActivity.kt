package com.retrocam.app.trip

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.datepicker.MaterialDatePicker
import com.retrocam.app.R
import com.retrocam.app.databinding.ActivityTripEditBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TripEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTripEditBinding
    private lateinit var tripRepository: TripRepository

    private var editingTripId: String? = null
    private var selectedStartDate: Long = System.currentTimeMillis()
    private var selectedEndDate: Long? = null
    private var selectedCoverUri: String? = null

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    private val pickCoverLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (ignored: Exception) {}
            selectedCoverUri = uri.toString()
            binding.editTripCoverPreview.setImageURI(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTripEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tripRepository = TripRepository.getInstance(this)
        editingTripId = intent.getStringExtra(EXTRA_TRIP_ID)

        setUpViews()
        populateIfEditing()
    }

    private fun setUpViews() {
        binding.editTripBackButton.setOnClickListener { finish() }

        // Start Date Picker
        binding.editTripStartDateInput.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Select Trip Start Date")
                .setSelection(selectedStartDate)
                .build()

            picker.addOnPositiveButtonClickListener { selection ->
                selectedStartDate = selection
                binding.editTripStartDateInput.setText(dateFormat.format(Date(selection)))
                if (selectedEndDate != null && selectedEndDate!! < selectedStartDate) {
                    selectedEndDate = null
                    binding.editTripEndDateInput.setText("")
                }
            }
            picker.show(supportFragmentManager, "start_date_picker")
        }

        // End Date Picker
        binding.editTripEndDateInput.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Select Trip End Date (Optional)")
                .setSelection(selectedEndDate ?: selectedStartDate)
                .build()

            picker.addOnPositiveButtonClickListener { selection ->
                if (selection >= selectedStartDate) {
                    selectedEndDate = selection
                    binding.editTripEndDateInput.setText(dateFormat.format(Date(selection)))
                } else {
                    Toast.makeText(this, "End date cannot precede start date", Toast.LENGTH_SHORT).show()
                }
            }
            picker.show(supportFragmentManager, "end_date_picker")
        }

        // Initial date display
        binding.editTripStartDateInput.setText(dateFormat.format(Date(selectedStartDate)))

        // Cover Photo Selection
        binding.selectCoverButton.setOnClickListener {
            pickCoverLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        // Save Button
        binding.editTripSaveButton.setOnClickListener { saveTrip() }

        // Delete Button
        binding.editTripDeleteButton.setOnClickListener { confirmDeleteTrip() }
    }

    private fun populateIfEditing() {
        val tripId = editingTripId ?: return
        val trip = tripRepository.getTrip(tripId) ?: return

        binding.editTripScreenTitle.text = "Edit Trip"
        binding.editTripNameInput.setText(trip.name)
        binding.editTripDestinationInput.setText(trip.destination)

        selectedStartDate = trip.startDate
        binding.editTripStartDateInput.setText(dateFormat.format(Date(trip.startDate)))

        if (trip.endDate != null) {
            selectedEndDate = trip.endDate
            binding.editTripEndDateInput.setText(dateFormat.format(Date(trip.endDate)))
        }

        selectedCoverUri = trip.coverUri
        if (!trip.coverUri.isNullOrEmpty()) {
            try {
                binding.editTripCoverPreview.setImageURI(Uri.parse(trip.coverUri))
            } catch (e: Exception) {
                binding.editTripCoverPreview.setImageResource(R.drawable.camera_body)
            }
        }

        val isActive = tripRepository.getActiveTripId() == tripId
        binding.editTripSetActiveCheckbox.isChecked = isActive
        binding.editTripDeleteButton.visibility = View.VISIBLE
    }

    private fun saveTrip() {
        val name = binding.editTripNameInput.text?.toString()?.trim().orEmpty()
        val destination = binding.editTripDestinationInput.text?.toString()?.trim().orEmpty()

        if (name.isEmpty()) {
            binding.editTripNameInput.error = "Please enter a trip name"
            binding.editTripNameInput.requestFocus()
            return
        }

        if (destination.isEmpty()) {
            binding.editTripDestinationInput.error = "Please enter a destination"
            binding.editTripDestinationInput.requestFocus()
            return
        }

        val setAsActive = binding.editTripSetActiveCheckbox.isChecked
        val tripId = editingTripId

        if (tripId != null) {
            val existing = tripRepository.getTrip(tripId)
            if (existing != null) {
                val updated = existing.copy(
                    name = name,
                    destination = destination,
                    startDate = selectedStartDate,
                    endDate = selectedEndDate,
                    coverUri = selectedCoverUri ?: existing.coverUri
                )
                tripRepository.updateTrip(updated)
                if (setAsActive) {
                    tripRepository.setActiveTrip(tripId)
                } else if (tripRepository.getActiveTripId() == tripId) {
                    tripRepository.clearActiveTrip()
                }
                Toast.makeText(this, "Trip updated", Toast.LENGTH_SHORT).show()
            }
        } else {
            tripRepository.createTrip(
                name = name,
                destination = destination,
                startDate = selectedStartDate,
                endDate = selectedEndDate,
                coverUri = selectedCoverUri,
                setAsActive = setAsActive
            )
            Toast.makeText(this, "Trip created", Toast.LENGTH_SHORT).show()
        }

        finish()
    }

    private fun confirmDeleteTrip() {
        val tripId = editingTripId ?: return
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

    companion object {
        const val EXTRA_TRIP_ID = "extra_trip_id"
    }
}
