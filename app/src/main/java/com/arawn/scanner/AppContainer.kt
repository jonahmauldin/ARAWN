package com.arawn.scanner

import android.content.Context
import com.arawn.core.database.ArawnDatabase
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
    val wirelessDao: WirelessDao by lazy { database.wirelessDao() }

    val dataLogExporter: DataLogBackupExporter by lazy { DataLogBackupExporter(appContext) }
    val enrichedCsvExporter: EnrichedCsvExporter by lazy { EnrichedCsvExporter(appContext) }
    val htmlReportExporter: HtmlReportExporter by lazy { HtmlReportExporter(appContext) }
}
