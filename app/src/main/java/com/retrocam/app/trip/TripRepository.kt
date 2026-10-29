package com.retrocam.app.trip

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class TripRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val dbHelper = TripDatabaseHelper(appContext)
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _tripsFlow = MutableStateFlow<List<Trip>>(emptyList())
    val tripsFlow: StateFlow<List<Trip>> = _tripsFlow.asStateFlow()

    private val _activeTripFlow = MutableStateFlow<Trip?>(null)
    val activeTripFlow: StateFlow<Trip?> = _activeTripFlow.asStateFlow()

    init {
        refreshTrips()
    }

    /**
     * Refreshes both the trips list flow and the active trip flow.
     */
    fun refreshTrips() {
        val trips = queryAllTrips()
        _tripsFlow.value = trips

        val activeId = prefs.getString(KEY_ACTIVE_TRIP_ID, null)
        val activeTrip = if (activeId != null) {
            trips.find { it.id == activeId }
        } else null

        if (activeId != null && activeTrip == null) {
            // The saved active trip was deleted or invalid; clear it
            prefs.edit().remove(KEY_ACTIVE_TRIP_ID).apply()
        }
        _activeTripFlow.value = activeTrip
    }

    private fun queryAllTrips(): List<Trip> {
        val list = mutableListOf<Trip>()
        val db = dbHelper.readableDatabase
        val query = """
            SELECT t.${TripDatabaseHelper.COL_TRIP_ID},
                   t.${TripDatabaseHelper.COL_TRIP_NAME},
                   t.${TripDatabaseHelper.COL_TRIP_DESTINATION},
                   t.${TripDatabaseHelper.COL_TRIP_START_DATE},
                   t.${TripDatabaseHelper.COL_TRIP_END_DATE},
                   t.${TripDatabaseHelper.COL_TRIP_COVER_URI},
                   COUNT(m.${TripDatabaseHelper.COL_MEDIA_ID}) AS actual_media_count,
                   t.${TripDatabaseHelper.COL_TRIP_CREATED_AT},
                   t.${TripDatabaseHelper.COL_TRIP_UPDATED_AT}
            FROM ${TripDatabaseHelper.TABLE_TRIPS} t
            LEFT JOIN ${TripDatabaseHelper.TABLE_MEDIA} m 
                   ON t.${TripDatabaseHelper.COL_TRIP_ID} = m.${TripDatabaseHelper.COL_MEDIA_TRIP_ID}
            GROUP BY t.${TripDatabaseHelper.COL_TRIP_ID}
            ORDER BY t.${TripDatabaseHelper.COL_TRIP_CREATED_AT} DESC
        """.trimIndent()

        try {
            db.rawQuery(query, null).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursorToTrip(cursor))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying trips", e)
        }
        return list
    }

    fun getAllTrips(): List<Trip> = queryAllTrips()

    fun getTrip(tripId: String): Trip? {
        val db = dbHelper.readableDatabase
        val query = """
            SELECT t.${TripDatabaseHelper.COL_TRIP_ID},
                   t.${TripDatabaseHelper.COL_TRIP_NAME},
                   t.${TripDatabaseHelper.COL_TRIP_DESTINATION},
                   t.${TripDatabaseHelper.COL_TRIP_START_DATE},
                   t.${TripDatabaseHelper.COL_TRIP_END_DATE},
                   t.${TripDatabaseHelper.COL_TRIP_COVER_URI},
                   COUNT(m.${TripDatabaseHelper.COL_MEDIA_ID}) AS actual_media_count,
                   t.${TripDatabaseHelper.COL_TRIP_CREATED_AT},
                   t.${TripDatabaseHelper.COL_TRIP_UPDATED_AT}
            FROM ${TripDatabaseHelper.TABLE_TRIPS} t
            LEFT JOIN ${TripDatabaseHelper.TABLE_MEDIA} m 
                   ON t.${TripDatabaseHelper.COL_TRIP_ID} = m.${TripDatabaseHelper.COL_MEDIA_TRIP_ID}
            WHERE t.${TripDatabaseHelper.COL_TRIP_ID} = ?
            GROUP BY t.${TripDatabaseHelper.COL_TRIP_ID}
        """.trimIndent()

        try {
            db.rawQuery(query, arrayOf(tripId)).use { cursor ->
                if (cursor.moveToNext()) {
                    return cursorToTrip(cursor)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying trip $tripId", e)
        }
        return null
    }

    fun createTrip(
        name: String,
        destination: String,
        startDate: Long,
        endDate: Long? = null,
        coverUri: String? = null,
        setAsActive: Boolean = true
    ): Trip {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(TripDatabaseHelper.COL_TRIP_ID, id)
            put(TripDatabaseHelper.COL_TRIP_NAME, name)
            put(TripDatabaseHelper.COL_TRIP_DESTINATION, destination)
            put(TripDatabaseHelper.COL_TRIP_START_DATE, startDate)
            put(TripDatabaseHelper.COL_TRIP_END_DATE, endDate)
            put(TripDatabaseHelper.COL_TRIP_COVER_URI, coverUri)
            put(TripDatabaseHelper.COL_TRIP_MEDIA_COUNT, 0)
            put(TripDatabaseHelper.COL_TRIP_CREATED_AT, now)
            put(TripDatabaseHelper.COL_TRIP_UPDATED_AT, now)
        }

        val db = dbHelper.writableDatabase
        db.insertWithOnConflict(
            TripDatabaseHelper.TABLE_TRIPS,
            null,
            values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )

        val trip = Trip(
            id = id,
            name = name,
            destination = destination,
            startDate = startDate,
            endDate = endDate,
            coverUri = coverUri,
            mediaCount = 0,
            createdAt = now,
            updatedAt = now
        )

        if (setAsActive) {
            setActiveTrip(id)
        } else {
            refreshTrips()
        }

        return trip
    }

    fun updateTrip(trip: Trip): Boolean {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(TripDatabaseHelper.COL_TRIP_NAME, trip.name)
            put(TripDatabaseHelper.COL_TRIP_DESTINATION, trip.destination)
            put(TripDatabaseHelper.COL_TRIP_START_DATE, trip.startDate)
            put(TripDatabaseHelper.COL_TRIP_END_DATE, trip.endDate)
            put(TripDatabaseHelper.COL_TRIP_COVER_URI, trip.coverUri)
            put(TripDatabaseHelper.COL_TRIP_UPDATED_AT, now)
        }

        val db = dbHelper.writableDatabase
        val rows = db.update(
            TripDatabaseHelper.TABLE_TRIPS,
            values,
            "${TripDatabaseHelper.COL_TRIP_ID} = ?",
            arrayOf(trip.id)
        )
        refreshTrips()
        return rows > 0
    }

    fun deleteTrip(tripId: String): Boolean {
        val db = dbHelper.writableDatabase
        val rows = db.delete(
            TripDatabaseHelper.TABLE_TRIPS,
            "${TripDatabaseHelper.COL_TRIP_ID} = ?",
            arrayOf(tripId)
        )
        if (getActiveTripId() == tripId) {
            clearActiveTrip()
        } else {
            refreshTrips()
        }
        return rows > 0
    }

    fun getActiveTripId(): String? = prefs.getString(KEY_ACTIVE_TRIP_ID, null)

    fun getActiveTrip(): Trip? {
        val id = getActiveTripId() ?: return null
        return getTrip(id)
    }

    fun setActiveTrip(tripId: String?) {
        if (tripId == null) {
            prefs.edit().remove(KEY_ACTIVE_TRIP_ID).apply()
        } else {
            prefs.edit().putString(KEY_ACTIVE_TRIP_ID, tripId).apply()
        }
        refreshTrips()
    }

    fun clearActiveTrip() {
        setActiveTrip(null)
    }

    /**
     * Fetches all media for a trip, sorted chronologically (oldest to newest) for the carousel.
     */
    fun getTripMedia(tripId: String): List<TripMedia> {
        val list = mutableListOf<TripMedia>()
        val db = dbHelper.readableDatabase
        val columns = arrayOf(
            TripDatabaseHelper.COL_MEDIA_ID,
            TripDatabaseHelper.COL_MEDIA_TRIP_ID,
            TripDatabaseHelper.COL_MEDIA_URI,
            TripDatabaseHelper.COL_MEDIA_DISPLAY_NAME,
            TripDatabaseHelper.COL_MEDIA_IS_VIDEO,
            TripDatabaseHelper.COL_MEDIA_DURATION_MS,
            TripDatabaseHelper.COL_MEDIA_DATE_TAKEN,
            TripDatabaseHelper.COL_MEDIA_CONTRIBUTOR
        )

        try {
            db.query(
                TripDatabaseHelper.TABLE_MEDIA,
                columns,
                "${TripDatabaseHelper.COL_MEDIA_TRIP_ID} = ?",
                arrayOf(tripId),
                null,
                null,
                "${TripDatabaseHelper.COL_MEDIA_DATE_TAKEN} ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursorToTripMedia(cursor))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying trip media for $tripId", e)
        }
        return list
    }

    /**
     * Associates a newly captured or selected media item with a trip.
     * Updates the trip's media count and sets the cover photo if currently unset.
     */
    fun addMediaToTrip(
        tripId: String,
        uri: Uri,
        displayName: String,
        isVideo: Boolean,
        dateTaken: Long = System.currentTimeMillis(),
        durationMs: Long = 0L,
        contributorName: String? = null
    ): TripMedia {
        val mediaId = UUID.randomUUID().toString()
        val db = dbHelper.writableDatabase

        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put(TripDatabaseHelper.COL_MEDIA_ID, mediaId)
                put(TripDatabaseHelper.COL_MEDIA_TRIP_ID, tripId)
                put(TripDatabaseHelper.COL_MEDIA_URI, uri.toString())
                put(TripDatabaseHelper.COL_MEDIA_DISPLAY_NAME, displayName)
                put(TripDatabaseHelper.COL_MEDIA_IS_VIDEO, if (isVideo) 1 else 0)
                put(TripDatabaseHelper.COL_MEDIA_DURATION_MS, durationMs)
                put(TripDatabaseHelper.COL_MEDIA_DATE_TAKEN, dateTaken)
                put(TripDatabaseHelper.COL_MEDIA_CONTRIBUTOR, contributorName)
            }
            db.insert(TripDatabaseHelper.TABLE_MEDIA, null, values)

            // If trip has no cover, automatically use this media
            val trip = getTrip(tripId)
            if (trip != null && trip.coverUri.isNullOrEmpty()) {
                val updateCover = ContentValues().apply {
                    put(TripDatabaseHelper.COL_TRIP_COVER_URI, uri.toString())
                    put(TripDatabaseHelper.COL_TRIP_UPDATED_AT, System.currentTimeMillis())
                }
                db.update(
                    TripDatabaseHelper.TABLE_TRIPS,
                    updateCover,
                    "${TripDatabaseHelper.COL_TRIP_ID} = ?",
                    arrayOf(tripId)
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        refreshTrips()

        return TripMedia(
            id = mediaId,
            tripId = tripId,
            uriString = uri.toString(),
            displayName = displayName,
            isVideo = isVideo,
            durationMs = durationMs,
            dateTaken = dateTaken,
            contributorName = contributorName
        )
    }

    /**
     * Automatically associates newly captured media with the active trip (if one is selected).
     */
    fun addMediaToActiveTrip(
        uri: Uri,
        displayName: String,
        isVideo: Boolean,
        dateTaken: Long = System.currentTimeMillis(),
        durationMs: Long = 0L,
        contributorName: String? = null
    ): TripMedia? {
        val activeId = getActiveTripId() ?: return null
        return addMediaToTrip(
            tripId = activeId,
            uri = uri,
            displayName = displayName,
            isVideo = isVideo,
            dateTaken = dateTaken,
            durationMs = durationMs,
            contributorName = contributorName
        )
    }

    fun removeMediaFromTrip(mediaId: String, tripId: String): Boolean {
        val db = dbHelper.writableDatabase
        val rows = db.delete(
            TripDatabaseHelper.TABLE_MEDIA,
            "${TripDatabaseHelper.COL_MEDIA_ID} = ? AND ${TripDatabaseHelper.COL_MEDIA_TRIP_ID} = ?",
            arrayOf(mediaId, tripId)
        )
        refreshTrips()
        return rows > 0
    }

    fun setTripCover(tripId: String, coverUri: String): Boolean {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(TripDatabaseHelper.COL_TRIP_COVER_URI, coverUri)
            put(TripDatabaseHelper.COL_TRIP_UPDATED_AT, System.currentTimeMillis())
        }
        val rows = db.update(
            TripDatabaseHelper.TABLE_TRIPS,
            values,
            "${TripDatabaseHelper.COL_TRIP_ID} = ?",
            arrayOf(tripId)
        )
        refreshTrips()
        return rows > 0
    }

    private fun cursorToTrip(cursor: Cursor): Trip {
        val id = cursor.getString(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_TRIP_ID))
        val name = cursor.getString(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_TRIP_NAME))
        val destination = cursor.getString(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_TRIP_DESTINATION))
        val startDate = cursor.getLong(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_TRIP_START_DATE))
        val endDate = if (cursor.isNull(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_TRIP_END_DATE))) {
            null
        } else {
            cursor.getLong(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_TRIP_END_DATE))
        }
        val coverUri = cursor.getString(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_TRIP_COVER_URI))
        val countCol = cursor.getColumnIndex("actual_media_count")
        val mediaCount = if (countCol != -1) {
            cursor.getInt(countCol)
        } else {
            cursor.getInt(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_TRIP_MEDIA_COUNT))
        }
        val createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_TRIP_CREATED_AT))
        val updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_TRIP_UPDATED_AT))

        return Trip(
            id = id,
            name = name,
            destination = destination,
            startDate = startDate,
            endDate = endDate,
            coverUri = coverUri,
            mediaCount = mediaCount,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun cursorToTripMedia(cursor: Cursor): TripMedia {
        val id = cursor.getString(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_MEDIA_ID))
        val tripId = cursor.getString(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_MEDIA_TRIP_ID))
        val uri = cursor.getString(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_MEDIA_URI))
        val name = cursor.getString(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_MEDIA_DISPLAY_NAME))
        val isVideo = cursor.getInt(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_MEDIA_IS_VIDEO)) == 1
        val duration = cursor.getLong(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_MEDIA_DURATION_MS))
        val dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_MEDIA_DATE_TAKEN))
        val contributor = cursor.getString(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COL_MEDIA_CONTRIBUTOR))

        return TripMedia(
            id = id,
            tripId = tripId,
            uriString = uri,
            displayName = name,
            isVideo = isVideo,
            durationMs = duration,
            dateTaken = dateTaken,
            contributorName = contributor
        )
    }

    companion object {
        private const val TAG = "TripRepository"
        private const val PREFS_NAME = "retrocam_trip_prefs"
        private const val KEY_ACTIVE_TRIP_ID = "active_trip_id"

        @Volatile
        private var instance: TripRepository? = null

        fun getInstance(context: Context): TripRepository {
            return instance ?: synchronized(this) {
                instance ?: TripRepository(context).also { instance = it }
            }
        }
    }
}
