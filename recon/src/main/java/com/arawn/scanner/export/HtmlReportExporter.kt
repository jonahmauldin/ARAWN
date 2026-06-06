package com.arawn.scanner.export

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.arawn.core.database.ArawnDatabase
import com.arawn.core.database.ReportEntity
import com.arawn.core.database.ReportFormat
import com.arawn.scanner.report.HtmlReportRenderer
import com.arawn.scanner.report.ReportDataCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates a self-contained HTML report and writes it to Documents/ARAWN/.
 * All CSS, JS, and chart logic is embedded inline — no server or internet required.
 * The map section uses a Canvas renderer (no Leaflet CDN dependency).
 */
class HtmlReportExporter(context: Context) {

    private val appContext = context.applicationContext
    private val dao = ArawnDatabase.get(appContext).wirelessDao()
    private val reportDao = ArawnDatabase.get(appContext).reportDao()

    data class ExportResult(
        val success: Boolean,
        val message: String,
        val relativePath: String? = null,
    )

    suspend fun exportLatestSession(): ExportResult = withContext(Dispatchers.IO) {
        val id = dao.getLatestSessionId()
            ?: return@withContext ExportResult(false, "No sessions yet — run a survey first.")
        exportSessions(listOf(id))
    }

    suspend fun exportSession(sessionId: Long): ExportResult = exportSessions(listOf(sessionId))

    /**
     * Generate one combined report from one or more sessions.
     * Wi-Fi/BLE rows are merged per-BSSID; tracks are concatenated and colour-coded.
     *
     * [title] is an optional operator label (e.g. "Walmart scan"). When present it is
     * embedded in the file's display name as `Walmart scan (ARAWN S3 <stamp>).html`
     * so reports are identifiable in the History list; the History tab parses it back
     * out. Legacy untitled reports keep the `ARAWN_Report_S#_<stamp>.html` name.
     */
    suspend fun exportSessions(
        sessionIds: List<Long>,
        title: String? = null,
    ): ExportResult = withContext(Dispatchers.IO) {
        runCatching {
            val data = ReportDataCollector(dao, appContext).collectMultiple(sessionIds, title)
                ?: return@runCatching ExportResult(false, "Selected sessions have no data.")
            val html = HtmlReportRenderer.render(data)
            val stamp = STAMP.format(Date())
            val sessLabel = if (sessionIds.size == 1) "S${sessionIds.first()}"
                            else "Multi${sessionIds.size}"
            val safeTitle = sanitizeTitle(title)
            val name = if (safeTitle != null)
                "$safeTitle (ARAWN $sessLabel $stamp).html"
            else
                "ARAWN_Report_${sessLabel}_$stamp.html"
            val path = write(name, html)
                ?: return@runCatching ExportResult(false, "MediaStore rejected the write.")

            // Phase 4: index the report so it shows up in the HISTORY tab from
            // the DB (mission association + future filters), in addition to the
            // existing MediaStore scan. Best-effort: a DB failure must NOT mask
            // a successful file write — the operator's report on disk is the
            // source of truth.
            runCatching {
                reportDao.insertReportWithSources(
                    report = ReportEntity(
                        missionId   = null,
                        title       = safeTitle ?: "Report $sessLabel",
                        generatedMs = System.currentTimeMillis(),
                        format      = ReportFormat.HTML,
                        filePath    = path,
                    ),
                    sessionIds = sessionIds,
                    routeIds   = emptyList(),
                )
            }

            ExportResult(true, "Report saved → $path", path)
        }.getOrElse { e ->
            ExportResult(false, "Report error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Make an operator title safe for a MediaStore display name: drop path-illegal
     * characters and parentheses (the History parser uses ` (ARAWN ` as the title
     * delimiter), collapse whitespace, and cap the length. Returns null when nothing
     * usable remains, so the caller falls back to the untitled naming scheme.
     */
    private fun sanitizeTitle(raw: String?): String? {
        val cleaned = raw
            ?.replace(Regex("""[/\\:*?"<>|()\r\n\t]"""), " ")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.take(60)
            ?.trim()
        return cleaned?.takeIf { it.isNotBlank() }
    }

    private fun write(name: String, content: String): String? {
        val resolver = appContext.contentResolver
        val dir = "${Environment.DIRECTORY_DOCUMENTS}/ARAWN"
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/html")
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv,
        ) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                ?: run { resolver.delete(uri, null, null); return null }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            "$dir/$name"
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    companion object {
        private val STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
