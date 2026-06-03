package com.arawn.scanner

import android.content.Context
import com.arawn.core.crypto.DbPassphraseManager
import com.arawn.core.crypto.VaultCrypto
import com.arawn.core.database.ArawnDatabase
import net.sqlcipher.database.SQLCipherUtils
import net.sqlcipher.database.SupportFactory
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
import com.arawn.scanner.vault.VaultRepository

/**
 * Manual dependency container — the single source of truth for app-scoped
 * singletons. Constructed once in [ArawnApplication.onCreate]; every
 * component that needs a dep pulls it from here instead of constructing
 * its own instance.
 *
 * All properties are lazy so initialization is deferred to first use and
 * nothing blocks [Application.onCreate].
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val database: ArawnDatabase by lazy {
        val passphrase = DbPassphraseManager.getOrCreate(appContext)

        // One-time in-place migration: encrypt an existing plaintext DB before
        // Room opens it. SQLCipherUtils opens the file, checkpoints WAL, exports
        // all pages into a temp encrypted file, then renames it over the original.
        val dbFile = appContext.getDatabasePath("arawn.db")
        if (dbFile.exists()) {
            if (SQLCipherUtils.getDatabaseState(appContext, "arawn.db")
                    == SQLCipherUtils.State.UNENCRYPTED) {
                SQLCipherUtils.encryptTo(appContext, dbFile, passphrase)
            }
        }

        ArawnDatabase.get(appContext, SupportFactory(passphrase))
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
}
