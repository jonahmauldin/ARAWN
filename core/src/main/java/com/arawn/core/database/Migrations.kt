package com.arawn.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migration objects for ArawnDatabase.
 *
 * Each migration's SQL must exactly match what Room would generate for the
 * entity diff between those two versions. SQLite only supports ADD COLUMN
 * for ALTER TABLE, so new columns must be nullable or have a default — all
 * columns added here are nullable, which satisfies that constraint.
 */

/** v1 → v2: offline OUI vendor name added to both signal tables. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE wifi_access_points ADD COLUMN vendorName TEXT")
        db.execSQL("ALTER TABLE ble_devices ADD COLUMN vendorName TEXT")
    }
}

/**
 * v2 → v3: heuristic device classification columns added to both signal
 * tables (deviceClass, classConfidence, classStatus, classBreakdown).
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE wifi_access_points ADD COLUMN deviceClass TEXT")
        db.execSQL("ALTER TABLE wifi_access_points ADD COLUMN classConfidence INTEGER")
        db.execSQL("ALTER TABLE wifi_access_points ADD COLUMN classStatus TEXT")
        db.execSQL("ALTER TABLE wifi_access_points ADD COLUMN classBreakdown TEXT")
        db.execSQL("ALTER TABLE ble_devices ADD COLUMN deviceClass TEXT")
        db.execSQL("ALTER TABLE ble_devices ADD COLUMN classConfidence INTEGER")
        db.execSQL("ALTER TABLE ble_devices ADD COLUMN classStatus TEXT")
        db.execSQL("ALTER TABLE ble_devices ADD COLUMN classBreakdown TEXT")
    }
}
