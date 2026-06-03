package com.arawn.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.arawn.core.crypto.DbPassphraseManager
import net.sqlcipher.database.SQLCipherUtils
import net.sqlcipher.database.SupportFactory

/**
 * Single local SQLite store for ARAWN. 100% on-device — no network, no cloud.
 *
 * Thread-safe lazy singleton: [get] double-checks under a lock so only one
 * instance is ever built, and it is anchored to the application context to
 * avoid leaking an Activity/Service.
 *
 * The database is encrypted with SQLCipher (AES-256-CBC). A random 32-byte
 * passphrase is generated once and stored in EncryptedSharedPreferences backed
 * by the Android Keystore. On first launch after Phase I, any existing
 * plaintext database is migrated in-place before Room opens it.
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

        fun get(context: Context): ArawnDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }

        private fun build(context: Context): ArawnDatabase {
            val appCtx = context.applicationContext
            val passphrase = DbPassphraseManager.getOrCreate(appCtx)

            // One-time migration: if an unencrypted DB exists (pre-Phase-I install),
            // encrypt it in-place before Room opens it. SQLCipherUtils opens the
            // plaintext file, checkpoints any WAL, exports all pages into a temp
            // encrypted file, then renames it over the original.
            val dbFile = appCtx.getDatabasePath(DB_NAME)
            if (dbFile.exists()) {
                val state = SQLCipherUtils.getDatabaseState(appCtx, DB_NAME)
                if (state == SQLCipherUtils.State.UNENCRYPTED) {
                    SQLCipherUtils.encryptTo(appCtx, dbFile, passphrase)
                }
            }

            return Room.databaseBuilder(
                appCtx,
                ArawnDatabase::class.java,
                DB_NAME,
            )
                .openHelperFactory(SupportFactory(passphrase))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
        }
    }
}
