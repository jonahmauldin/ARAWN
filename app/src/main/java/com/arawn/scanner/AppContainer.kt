package com.arawn.scanner

import android.content.Context
import com.arawn.core.crypto.DbPassphraseManager
import com.arawn.core.crypto.VaultCrypto
import com.arawn.core.database.ArawnDatabase
import com.arawn.core.database.AttachmentDao
import com.arawn.core.database.DocumentDao
import com.arawn.core.database.GeoDao
import com.arawn.core.database.MissionDao
import com.arawn.core.database.ReportDao
import com.arawn.core.database.VaultDao
import com.arawn.core.database.WirelessDao
import com.arawn.scanner.export.DataLogBackupExporter
import com.arawn.scanner.export.EnrichedCsvExporter
import com.arawn.scanner.export.HtmlReportExporter
import com.arawn.scanner.knowledge.KnowledgeRepository
import com.arawn.scanner.missions.MissionRepository
import com.arawn.scanner.vault.VaultRepository
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.io.RandomAccessFile

/**
 * Manual dependency container — the single source of truth for app-scoped
 * singletons. Constructed once in [ArawnApplication.onCreate]; every
 * component that needs a dep pulls it from here instead of constructing
 * its own instance.
 *
 * Most properties are lazy. The exception is [database], whose creation
 * [ArawnApplication] forces up front so the SQLCipher-backed instance wins the
 * process-wide singleton before any recon-side caller can build a plaintext one.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val database: ArawnDatabase by lazy {
        // SQLCipher's native library must be loaded before any database call.
        System.loadLibrary("sqlcipher")

        val passphrase = DbPassphraseManager.getOrCreate(appContext)

        // E.1: whole-DB encryption is enabled here. SQLCipher cannot open the
        // legacy plaintext arawn.db, and net.zetetic:sqlcipher-android ships no
        // in-place migration helper (the old net.sqlcipher SQLCipherUtils is gone),
        // so a pre-existing UNENCRYPTED database is wiped once and recreated
        // encrypted. User-approved: no critical on-device data yet. Room's real
        // schema migrations still apply to the encrypted DB on future upgrades.
        val dbFile = appContext.getDatabasePath(DB_NAME)
        if (dbFile.exists() && isPlaintextSqlite(dbFile)) {
            appContext.deleteDatabase(DB_NAME)
        }

        ArawnDatabase.get(appContext) { builder ->
            builder.openHelperFactory(SupportOpenHelperFactory(passphrase))
        }
    }

    // Recon
    val wirelessDao: WirelessDao by lazy { database.wirelessDao() }

    // Platform spine DAOs (Phase B)
    val missionDao: MissionDao by lazy { database.missionDao() }
    val geoDao: GeoDao by lazy { database.geoDao() }
    val attachmentDao: AttachmentDao by lazy { database.attachmentDao() }
    val reportDao: ReportDao by lazy { database.reportDao() }
    val documentDao: DocumentDao by lazy { database.documentDao() }
    val vaultDao: VaultDao by lazy { database.vaultDao() }

    // Exporters
    val dataLogExporter: DataLogBackupExporter by lazy { DataLogBackupExporter(appContext) }
    val enrichedCsvExporter: EnrichedCsvExporter by lazy { EnrichedCsvExporter(appContext) }
    val htmlReportExporter: HtmlReportExporter by lazy { HtmlReportExporter(appContext) }

    // Phase E: Vault encryption
    val vaultCrypto: VaultCrypto by lazy { VaultCrypto(appContext) }
    val vaultRepository: VaultRepository by lazy {
        VaultRepository(appContext, vaultDao, vaultCrypto)
    }

    // Phase H: Knowledge Base
    val knowledgeRepository: KnowledgeRepository by lazy {
        KnowledgeRepository(appContext, documentDao, vaultDao, vaultRepository)
    }

    // Mission Integration: lifecycle delete with cross-source cascade
    val missionRepository: MissionRepository by lazy {
        MissionRepository(missionDao, geoDao, wirelessDao, attachmentDao, vaultDao, vaultRepository)
    }

    private companion object {
        private const val DB_NAME = "arawn.db"

        // First 16 bytes of every plaintext SQLite file: the ASCII text
        // "SQLite format 3" (15 chars) followed by a single NUL byte. Built at
        // runtime (string + 0 byte) so no NUL literal lives in the source.
        private val SQLITE_MAGIC: ByteArray =
            "SQLite format 3".toByteArray(Charsets.US_ASCII) + 0.toByte()

        /**
         * True if [file] begins with the plaintext SQLite header. A SQLCipher-
         * encrypted database starts with a random salt instead, so this cleanly
         * distinguishes a not-yet-encrypted legacy DB from an already-encrypted
         * one and keeps the one-time wipe from firing on every launch.
         */
        private fun isPlaintextSqlite(file: File): Boolean = try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < SQLITE_MAGIC.size.toLong()) {
                    false
                } else {
                    val header = ByteArray(SQLITE_MAGIC.size)
                    raf.readFully(header)
                    header.contentEquals(SQLITE_MAGIC)
                }
            }
        } catch (t: Throwable) {
            false // if we can't read it, don't risk deleting it
        }
    }
}
