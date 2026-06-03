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
 * Single local SQLite store for ARAWN — encrypted with SQLCipher (Phase E).
 *
 * The passphrase is a random 256-bit key stored in EncryptedSharedPreferences,
 * wrapped by an Android Keystore AES-256-GCM key. The Keystore key requires no
 * user auth (so the scan service can access the DB at all times); the device's
 * own hardware-backed key store provides the root of trust.
 *
 * On the first launch after Phase E, an existing unencrypted database is
 * migrated in-place by [SQLCipherUtils.encryptTo]. Subsequent opens go through
 * [SupportFactory] directly — Room never sees a plaintext file again.
 *
 * NOTE: The migration in [build] blocks the calling thread for the duration of
 * the SQLCipher encryption pass. For a typical ARAWN database (< 5 MB) this
 * takes < 200 ms. A future iteration could move this to a startup coroutine.
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
            val ctx = context.applicationContext
            val passphrase = DbPassphraseManager.getOrCreate(ctx)

            // SupportFactory instantiation loads the SQLCipher native library,
            // which must happen before any SQLCipherUtils calls.
            val factory = SupportFactory(passphrase)

            // One-time migration: if an unencrypted database exists from an earlier
            // build, encrypt it in-place before handing control to Room.
            val dbFile = ctx.getDatabasePath(DB_NAME)
            if (dbFile.exists()) {
                runCatching {
                    if (SQLCipherUtils.getDatabaseState(ctx, dbFile) ==
                        SQLCipherUtils.State.UNENCRYPTED
                    ) {
                        SQLCipherUtils.encryptTo(ctx, dbFile, passphrase)
                    }
                }
                // If encryptTo fails (e.g. disk full), the original unencrypted
                // file remains and Room will fail to open it through SupportFactory —
                // a hard crash is preferable to silently proceeding with no data.
            }

            return Room.databaseBuilder(ctx, ArawnDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
        }
    }
}
