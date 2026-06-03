package com.arawn.scanner

import android.content.Context
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

    val database: ArawnDatabase by lazy { ArawnDatabase.get(appContext) }

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
}
