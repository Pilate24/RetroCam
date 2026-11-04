package com.retrocam.app.trip

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class TripDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_TRIPS (
                $COL_TRIP_ID TEXT PRIMARY KEY,
                $COL_TRIP_NAME TEXT NOT NULL,
                $COL_TRIP_DESTINATION TEXT NOT NULL,
                $COL_TRIP_START_DATE INTEGER NOT NULL,
                $COL_TRIP_END_DATE INTEGER,
                $COL_TRIP_COVER_URI TEXT,
                $COL_TRIP_MEDIA_COUNT INTEGER DEFAULT 0,
                $COL_TRIP_CREATED_AT INTEGER NOT NULL,
                $COL_TRIP_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_MEDIA (
                $COL_MEDIA_ID TEXT PRIMARY KEY,
                $COL_MEDIA_TRIP_ID TEXT NOT NULL,
                $COL_MEDIA_URI TEXT NOT NULL,
                $COL_MEDIA_DISPLAY_NAME TEXT NOT NULL,
                $COL_MEDIA_IS_VIDEO INTEGER DEFAULT 0,
                $COL_MEDIA_DURATION_MS INTEGER DEFAULT 0,
                $COL_MEDIA_DATE_TAKEN INTEGER NOT NULL,
                $COL_MEDIA_CONTRIBUTOR TEXT,
                FOREIGN KEY ($COL_MEDIA_TRIP_ID) REFERENCES $TABLE_TRIPS($COL_TRIP_ID) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX idx_trip_media_trip ON $TABLE_MEDIA ($COL_MEDIA_TRIP_ID)")
        db.execSQL("CREATE INDEX idx_trip_media_date ON $TABLE_MEDIA ($COL_MEDIA_DATE_TAKEN ASC)")
        db.execSQL("CREATE INDEX idx_trips_created ON $TABLE_TRIPS ($COL_TRIP_CREATED_AT DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    }

    companion object {
        private const val DATABASE_NAME = "retrocam_trips.db"
        private const val DATABASE_VERSION = 2

        const val TABLE_TRIPS = "trips"
        const val COL_TRIP_ID = "id"
        const val COL_TRIP_NAME = "name"
        const val COL_TRIP_DESTINATION = "destination"
        const val COL_TRIP_START_DATE = "start_date"
        const val COL_TRIP_END_DATE = "end_date"
        const val COL_TRIP_COVER_URI = "cover_uri"
        const val COL_TRIP_MEDIA_COUNT = "media_count"
        const val COL_TRIP_CREATED_AT = "created_at"
        const val COL_TRIP_UPDATED_AT = "updated_at"

        const val TABLE_MEDIA = "trip_media"
        const val COL_MEDIA_ID = "id"
        const val COL_MEDIA_TRIP_ID = "trip_id"
        const val COL_MEDIA_URI = "uri"
        const val COL_MEDIA_DISPLAY_NAME = "display_name"
        const val COL_MEDIA_IS_VIDEO = "is_video"
        const val COL_MEDIA_DURATION_MS = "duration_ms"
        const val COL_MEDIA_DATE_TAKEN = "date_taken"
        const val COL_MEDIA_CONTRIBUTOR = "contributor"
    }
}
