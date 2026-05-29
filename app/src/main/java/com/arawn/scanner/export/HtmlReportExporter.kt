package com.arawn.scanner.export

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.arawn.scanner.db.ArawnDatabase
import com.arawn.scanner.report.HtmlReportRenderer
import com.arawn.scanner.report.ReportDataCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates a self-contained HTML report for one session and writes it to
 * Documents/ARAWN/ via MediaStore. No external libraries, no network calls —
 * all CSS, JavaScript, and chart/table logic is embedded inside the HTML file.
 */
class HtmlReportExporter(context: Context) {

    private val appContext = context.applicationContext
    private val dao = ArawnDatabase.get(appContext).wirelessDao()

    data class ExportResult(
        val success: Boolean,
        val message: String,
        val relativePath: String? = null,
    )

    suspend fun exportLatestSession(): ExportResult = withContext(Dispatchers.IO) {
        val id = dao.getLatestSessionId()
            ?: return@withContext ExportResult(false, "No sessions yet — run a survey first.")
        exportSession(id)
    }

    suspend fun exportSession(sessionId: Long): ExportResult = withContext(Dispatchers.IO) {
        runCatching {
            val data = ReportDataCollector(dao, appContext).collect(sessionId)
                ?: return@runCatching ExportResult(false, "Session $sessionId has no data.")
            val html = HtmlReportRenderer.render(data)
            val name = "ARAWN_Report_S${sessionId}_${STAMP.format(Date())}.html"
            val path = write(name, html)
                ?: return@runCatching ExportResult(false, "MediaStore rejected the write.")
            ExportResult(true, "Report saved → $path", path)
        }.getOrElse { e ->
            ExportResult(false, "Report error: ${e.message ?: e.javaClass.simpleName}")
        }
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
