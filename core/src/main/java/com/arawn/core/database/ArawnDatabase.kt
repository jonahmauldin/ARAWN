package com.arawn.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Single local SQLite store for ARAWN. 100% on-device — no network, no cloud.
 *
 * Thread-safe lazy singleton: [get] double-checks under a lock so only one
 * instance is ever built, and it is anchored to the application context to
 * avoid leaking an Activity/Service.
 *
 * The database is encrypted with SQLCipher (AES-256). The open-helper factory
 * is constructed in [AppContainer] (which lives in :app and depends on the
 * sqlcipher-android AAR) and applied through the [configure] lambda passed to
 * [get]. This keeps :core free of any SQLCipher / androidx.sqlite factory type
 * reference — Room's KSP processor cannot resolve those AAR types from :core,
 * which is exactly what broke the first E.1 attempt (KSP "MissingType").
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
         * [configure] runs against the Room builder before it is built — :app
         * uses it to install SQLCipher's open-helper factory (whole-DB
         * encryption). Subsequent calls return the cached instance and ignore
         * [configure], so the first caller must be the one that supplies it
         * (see ArawnApplication.onCreate, which forces creation up front).
         */
        fun get(
            context: Context,
            configure: (RoomDatabase.Builder<ArawnDatabase>) -> Unit = {},
        ): ArawnDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context, configure).also { INSTANCE = it }
            }

        private fun build(
            context: Context,
            configure: (RoomDatabase.Builder<ArawnDatabase>) -> Unit,
        ): ArawnDatabase {
            val builder = Room.databaseBuilder(
                context.applicationContext,
                ArawnDatabase::class.java,
                DB_NAME,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

            configure(builder)

            return builder.build()
        }
    }
}
