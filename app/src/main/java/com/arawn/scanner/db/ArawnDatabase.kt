package com.arawn.scanner.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Single local SQLite store for ARAWN. 100% on-device — no network, no cloud.
 *
 * Thread-safe lazy singleton: [get] double-checks under a lock so only one
 * instance is ever built, and it is anchored to the application context to
 * avoid leaking an Activity/Service.
 */
@Database(
    entities = [
        SessionEntity::class,
        LogEntryEntity::class,
        WifiApEntity::class,
        BleDeviceEntity::class,
    ],
    version = 3, // v3 (Phase 2): + classification columns on wifi_access_points & ble_devices
    exportSchema = false, // flip to true + set room.schemaLocation once migrations begin
)
abstract class ArawnDatabase : RoomDatabase() {

    abstract fun wirelessDao(): WirelessDao

    companion object {
        private const val DB_NAME = "arawn.db"

        @Volatile
        private var INSTANCE: ArawnDatabase? = null

        fun get(context: Context): ArawnDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }

        private fun build(context: Context): ArawnDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ArawnDatabase::class.java,
                DB_NAME,
            )
                // Dev-phase convenience: a schema bump wipes local data instead of
                // crashing. Replace with real Migrations before any public release.
                .fallbackToDestructiveMigration()
                .build()
    }
}
