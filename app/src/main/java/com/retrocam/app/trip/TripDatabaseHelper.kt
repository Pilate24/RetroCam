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

        createLibraryTables(db)
    }

    private fun createLibraryTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_MEDIA_INDEX (
                $COL_INDEX_ID TEXT PRIMARY KEY,
                $COL_INDEX_URI TEXT NOT NULL,
                $COL_INDEX_NAME TEXT NOT NULL,
                $COL_INDEX_IS_VIDEO INTEGER DEFAULT 0,
                $COL_INDEX_MIME_TYPE TEXT NOT NULL,
                $COL_INDEX_DATE_TAKEN INTEGER NOT NULL,
                $COL_INDEX_DATE_ADDED INTEGER NOT NULL,
                $COL_INDEX_DURATION_MS INTEGER DEFAULT 0,
                $COL_INDEX_WIDTH INTEGER DEFAULT 0,
                $COL_INDEX_HEIGHT INTEGER DEFAULT 0,
                $COL_INDEX_SIZE_BYTES INTEGER DEFAULT 0,
                $COL_INDEX_IS_FAVORITE INTEGER DEFAULT 0,
                $COL_INDEX_IS_EDITED INTEGER DEFAULT 0,
                $COL_INDEX_ORIGINAL_ID TEXT,
                $COL_INDEX_CAMERA_MAKE TEXT,
                $COL_INDEX_CAMERA_MODEL TEXT,
                $COL_INDEX_FOCAL_LENGTH REAL,
                $COL_INDEX_ISO INTEGER,
                $COL_INDEX_EXPOSURE_TIME REAL,
                $COL_INDEX_APERTURE REAL,
                $COL_INDEX_RELATIVE_PATH TEXT,
                $COL_INDEX_CONTENT_HASH TEXT,
                $COL_INDEX_PERCEPTUAL_HASH INTEGER,
                $COL_INDEX_LAST_INDEXED INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ALBUMS (
                $COL_ALBUM_ID TEXT PRIMARY KEY,
                $COL_ALBUM_NAME TEXT NOT NULL,
                $COL_ALBUM_COVER_URI TEXT,
                $COL_ALBUM_CREATED_AT INTEGER NOT NULL,
                $COL_ALBUM_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ALBUM_MEDIA (
                $COL_AM_ALBUM_ID TEXT NOT NULL,
                $COL_AM_MEDIA_ID TEXT NOT NULL,
                PRIMARY KEY ($COL_AM_ALBUM_ID, $COL_AM_MEDIA_ID),
                FOREIGN KEY ($COL_AM_ALBUM_ID) REFERENCES $TABLE_ALBUMS($COL_ALBUM_ID) ON DELETE CASCADE,
                FOREIGN KEY ($COL_AM_MEDIA_ID) REFERENCES $TABLE_MEDIA_INDEX($COL_INDEX_ID) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_index_date ON $TABLE_MEDIA_INDEX ($COL_INDEX_DATE_TAKEN DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_index_fav ON $TABLE_MEDIA_INDEX ($COL_INDEX_IS_FAVORITE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_index_type ON $TABLE_MEDIA_INDEX ($COL_INDEX_IS_VIDEO)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_index_edited ON $TABLE_MEDIA_INDEX ($COL_INDEX_IS_EDITED)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_index_hash ON $TABLE_MEDIA_INDEX ($COL_INDEX_CONTENT_HASH)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_album_media_album ON $TABLE_ALBUM_MEDIA ($COL_AM_ALBUM_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_album_media_media ON $TABLE_ALBUM_MEDIA ($COL_AM_MEDIA_ID)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createLibraryTables(db)
        }
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

        // Media Library Tables
        const val TABLE_MEDIA_INDEX = "media_index"
        const val COL_INDEX_ID = "media_id"
        const val COL_INDEX_URI = "uri"
        const val COL_INDEX_NAME = "display_name"
        const val COL_INDEX_IS_VIDEO = "is_video"
        const val COL_INDEX_MIME_TYPE = "mime_type"
        const val COL_INDEX_DATE_TAKEN = "date_taken"
        const val COL_INDEX_DATE_ADDED = "date_added"
        const val COL_INDEX_DURATION_MS = "duration_ms"
        const val COL_INDEX_WIDTH = "width"
        const val COL_INDEX_HEIGHT = "height"
        const val COL_INDEX_SIZE_BYTES = "size_bytes"
        const val COL_INDEX_IS_FAVORITE = "is_favorite"
        const val COL_INDEX_IS_EDITED = "is_edited"
        const val COL_INDEX_ORIGINAL_ID = "original_media_id"
        const val COL_INDEX_CAMERA_MAKE = "camera_make"
        const val COL_INDEX_CAMERA_MODEL = "camera_model"
        const val COL_INDEX_FOCAL_LENGTH = "focal_length"
        const val COL_INDEX_ISO = "iso"
        const val COL_INDEX_EXPOSURE_TIME = "exposure_time"
        const val COL_INDEX_APERTURE = "aperture"
        const val COL_INDEX_RELATIVE_PATH = "relative_path"
        const val COL_INDEX_CONTENT_HASH = "content_hash"
        const val COL_INDEX_PERCEPTUAL_HASH = "perceptual_hash"
        const val COL_INDEX_LAST_INDEXED = "last_indexed"

        const val TABLE_ALBUMS = "library_albums"
        const val COL_ALBUM_ID = "id"
        const val COL_ALBUM_NAME = "name"
        const val COL_ALBUM_COVER_URI = "cover_uri"
        const val COL_ALBUM_CREATED_AT = "created_at"
        const val COL_ALBUM_UPDATED_AT = "updated_at"

        const val TABLE_ALBUM_MEDIA = "library_album_media"
        const val COL_AM_ALBUM_ID = "album_id"
        const val COL_AM_MEDIA_ID = "media_id"
    }
}
