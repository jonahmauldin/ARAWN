package com.arawn.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteOpenHelperFactory

/**
 * Single local SQLite store for ARAWN. 100% on-device — no network, no cloud.
 *
 * Thread-safe lazy singleton: [get] double-checks under a lock so only one
 * instance is ever built, and it is anchored to the application context to
 * avoid leaking an Activity/Service.
 *
 * The database is encrypted with SQLCipher (AES-256-CBC). The [SupportFactory]
 * is constructed in [AppContainer] (which lives in :app and can depend on the
 * sqlcipher-android AAR directly) and passed in via [get]. This keeps :core
 * free of the SQLCipher compile-time dependency while still encrypting the DB.
 */
@Database(
    entities = [
        // Recon hierarchy (untouched except SessionEntity gained missionId in v4)
        SessionEntity::class,
        LogEntryEntity::class,
        WifiApEntity::class,
        BleDeviceEntity::class,
        // Platform spine (Phase B, DB version 4)
        VaultEntryEntity::class,
        MissionEntity::class,
        MissionItemEntity::class,
        WaypointEntity::class,
        RouteEntity::class,
        RoutePointEntity::class,
        AreaOverlayEntity::class,
        NoteEntity::class,
        MediaAssetEntity::class,
        ReportEntity::class,
        ReportSessionEntity::class,
        ReportRouteEntity::class,
        DocumentEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(SpineConverters::class)
abstract class ArawnDatabase : RoomDatabase() {

    // Recon
    abstract fun wirelessDao(): WirelessDao

    // Platform spine
    abstract fun missionDao(): MissionDao
    abstract fun geoDao(): GeoDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun reportDao(): ReportDao
    abstract fun documentDao(): DocumentDao
    abstract fun vaultDao(): VaultDao

    companion object {
        private const val DB_NAME = "arawn.db"

        @Volatile
        private var INSTANCE: ArawnDatabase? = null

        /**
         * Returns the singleton database, creating it on first call.
         *
         * Pass a [SupportSQLiteOpenHelperFactory] (e.g. SQLCipher's SupportFactory)
         * on the first call to enable whole-database encryption. Subsequent calls
         * return the cached instance regardless of the factory argument.
         */
        fun get(
            context: Context,
            factory: SupportSQLiteOpenHelperFactory? = null,
        ): ArawnDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context, factory).also { INSTANCE = it }
            }

        private fun build(
            context: Context,
            factory: SupportSQLiteOpenHelperFactory?,
        ): ArawnDatabase {
            val builder = Room.databaseBuilder(
                context.applicationContext,
                ArawnDatabase::class.java,
                DB_NAME,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

            if (factory != null) builder.openHelperFactory(factory)

            return builder.build()
        }
    }
}
