package com.arawn.scanner.export

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.arawn.scanner.db.ArawnDatabase
import com.arawn.scanner.db.LogEntryWithSignals
import com.arawn.scanner.db.WirelessDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 4 — asynchronous local log exporter.
 *
 * Reads a finished tracking session out of [ArawnDatabase] and serializes its
 * Wi-Fi + BLE observations into a WiGLE-compatible CSV, then writes the file to
 * the device's public Documents directory via the [MediaStore] API.
 *
 * ### Constraints honored
 *  - 100% on-device: the only sink is local shared storage. No network, no
 *    cloud upload, no third-party / paid library — just the platform DB and
 *    `MediaStore`.
 *  - minSdk 30 (Android 11): scoped storage is always in effect, so writing via
 *    `MediaStore` needs NO runtime storage permission and NO root. The app owns
 *    the file it creates under Documents/ARAWN/.
 *
 * ### Threading
 *  All DB reads, string building, and stream I/O run on [Dispatchers.IO]; the
 *  caller can launch [exportLatestSession] / [exportSession] from any scope
 *  (e.g. an Activity's lifecycleScope) without blocking the main thread.
 */
class DataLogBackupExporter(context: Context) {

    // Anchor to the application context so we never leak an Activity/Service.
    private val appContext = context.applicationContext
    private val dao: WirelessDao = ArawnDatabase.get(appContext).wirelessDao()

    /** Outcome of an export attempt, surfaced to the UI as a single message. */
    data class ExportResult(
        val success: Boolean,
        val message: String,
        val rowsWritten: Int = 0,
        val relativePath: String? = null,
    )

    /** Convenience: export the most recent session, or report if none exists. */
    suspend fun exportLatestSession(): ExportResult = withContext(Dispatchers.IO) {
        val sessionId = dao.getLatestSessionId()
            ?: return@withContext ExportResult(false, "No sessions recorded yet — run a survey first.")
        exportSession(sessionId)
    }

    /**
     * Export one session by id. Returns a populated [ExportResult] on success or
     * a descriptive failure result; never throws to the caller.
     */
    suspend fun exportSession(sessionId: Long): ExportResult = withContext(Dispatchers.IO) {
        runCatching {
            val entries = dao.getEntriesWithSignals(sessionId)
            if (entries.isEmpty()) {
                return@runCatching ExportResult(
                    false, "Session $sessionId has no logged data to export.",
                )
            }

            val csv = buildCsv(entries)
            val rows = csv.dataRowCount
            val fileName = "ARAWN_S${sessionId}_${FILE_STAMP.format(Date())}.csv"
            val path = writeToDocuments(fileName, csv.text)
                ?: return@runCatching ExportResult(
                    false, "Storage write was rejected by MediaStore.",
                )

            ExportResult(
                success = true,
                message = "Exported $rows rows → $path",
                rowsWritten = rows,
                relativePath = path,
            )
        }.getOrElse { e ->
            ExportResult(false, "Export failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---- CSV assembly -------------------------------------------------

    private class Csv(val text: String, val dataRowCount: Int)

    private fun buildCsv(entries: List<LogEntryWithSignals>): Csv {
        val sb = StringBuilder(entries.size * 256)
        var rows = 0

        // Row 1 — WiGLE pre-header / app configuration banner.
        sb.append(WIGLE_PREHEADER).append('\n')
        // Row 2 — column headers.
        sb.append(COLUMN_HEADER).append('\n')

        // Row 3+ — one line per signal, stamped with its scan window's GPS fix.
        for (e in entries) {
            val firstSeen = TIME_FMT.format(Date(e.entry.timestampMs))
            val lat = coord(e.entry.latitude)
            val lon = coord(e.entry.longitude)
            val alt = metric(e.entry.altitudeM)
            // accuracyM is -1f when the fix carried no accuracy: emit blank.
            val acc = if (e.entry.accuracyM >= 0f) metric(e.entry.accuracyM.toDouble()) else ""

            for (w in e.wifi) {
                sb.append(field(w.bssid)).append(',')
                    .append(field(w.ssid)).append(',')
                    .append(field(w.capabilities)).append(',')
                    .append(field(firstSeen)).append(',')
                    .append(frequencyToChannel(w.frequencyMhz)).append(',')
                    .append(w.rssiDbm).append(',')
                    .append(lat).append(',')
                    .append(lon).append(',')
                    .append(alt).append(',')
                    .append(acc).append(',')
                    .append("WIFI").append('\n')
                rows++
            }

            for (b in e.ble) {
                // BLE advertisers have no Wi-Fi auth/channel; leave those blank.
                sb.append(field(b.macAddress)).append(',')
                    .append(field(b.name ?: "")).append(',')
                    .append("").append(',')          // AuthMode (n/a for BLE)
                    .append(field(firstSeen)).append(',')
                    .append("").append(',')          // Channel (n/a for BLE)
                    .append(b.rssiDbm).append(',')
                    .append(lat).append(',')
                    .append(lon).append(',')
                    .append(alt).append(',')
                    .append(acc).append(',')
                    .append("BLE").append('\n')
                rows++
            }
        }
        return Csv(sb.toString(), rows)
    }

    // ---- Formatting helpers -------------------------------------------

    /** Lat/Lon to 6 decimal places (~0.1 m); guards against NaN/Inf. */
    private fun coord(value: Double): String =
        if (value.isFinite()) "%.6f".format(Locale.US, value) else ""

    /** Altitude / accuracy to 1 decimal place; guards against NaN/Inf. */
    private fun metric(value: Double): String =
        if (value.isFinite()) "%.1f".format(Locale.US, value) else ""

    /**
     * RFC-4180 CSV field escaping: wrap in double quotes and double any embedded
     * quotes when the value contains a comma, quote, or newline. SSIDs and
     * capability strings routinely contain commas, so this is mandatory.
     */
    private fun field(raw: String): String {
        if (raw.isEmpty()) return ""
        val needsQuoting = raw.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return raw
        return "\"" + raw.replace("\"", "\"\"") + "\""
    }

    /** Wi-Fi center frequency (MHz) → channel number; 0 if outside known bands. */
    private fun frequencyToChannel(freqMhz: Int): Int = when (freqMhz) {
        2484 -> 14
        in 2412..2472 -> (freqMhz - 2407) / 5      // 2.4 GHz: ch 1–13
        in 5160..5895 -> (freqMhz - 5000) / 5      // 5 GHz
        in 5955..7115 -> (freqMhz - 5950) / 5      // 6 GHz (Wi-Fi 6E)
        else -> 0
    }

    // ---- MediaStore sink ----------------------------------------------

    /**
     * Write [content] to `Documents/ARAWN/<fileName>` via MediaStore and return
     * the human-readable relative path, or null if the insert was rejected.
     *
     * Uses IS_PENDING so the file is invisible to other apps until the bytes are
     * fully flushed — a clean, atomic publish on API 29+.
     */
    private fun writeToDocuments(fileName: String, content: String): String? {
        val resolver = appContext.contentResolver
        val relativeDir = "${Environment.DIRECTORY_DOCUMENTS}/$SUBDIR"

        val pending = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, pending) ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            } ?: run {
                resolver.delete(uri, null, null) // roll back the empty placeholder
                return null
            }
            val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, publish, null, null)
            "$relativeDir/$fileName"
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) } // don't leave a half-written ghost
            throw e
        }
    }

    private companion object {
        const val SUBDIR = "ARAWN"

        const val WIGLE_PREHEADER =
            "WigleWifi-1.6,appRelease=1.0,model=Galaxy S23 Ultra,device=ARAWN"
        const val COLUMN_HEADER =
            "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,Latitude,Longitude,Altitude,Accuracy,Type"

        // "yyyy"/"dd" (year/day-of-month) — NOT "YYYY"/"DD" (week-year/day-of-year).
        val TIME_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
