package com.arawn.scanner.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.arawn.scanner.db.ArawnDatabase
import com.arawn.scanner.db.LogEntryWithSignals
import com.arawn.scanner.oui.OuiLookupManager
import com.arawn.scanner.oui.OuiLookupManager.MacKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ARAWN enriched CSV exporter (Phase 1 — vendor analytics).
 *
 * A SECOND, deliberately non-WiGLE export path. The strict WiGLE-1.6 file
 * ([WigleCsvExporter] / [DataLogBackupExporter]) is a fixed 11-column contract
 * and must stay importable by WiGLE — so this exporter does NOT touch it.
 * Instead it writes a wider, analysis-oriented sheet that adds the offline OUI
 * vendor, the MAC administration scope (so randomized/private addresses are
 * explicit rather than silently "unknown"), and the full radio columns.
 *
 * ### Constraints honored
 *  - 100% on-device: the only sink is local shared storage via [MediaStore].
 *    No network, no SDK, no token. Nothing leaves the device.
 *  - minSdk 30 scoped storage: no runtime storage permission, no root.
 *
 * ### Threading
 *  DB read, formatting, and stream I/O all run on [Dispatchers.IO]; callers may
 *  launch from any scope (e.g. an Activity lifecycleScope) without blocking the
 *  main thread. Rows stream to the output as formatted, so peak memory is bound
 *  by the relational read, not the size of the CSV text.
 *
 * NOTE: shares the MediaStore-publish pattern with the two WiGLE exporters; if a
 * fourth sink ever appears, factor the IS_PENDING insert/publish/rollback dance
 * into one helper rather than copying it a third time.
 */
class EnrichedCsvExporter(context: Context) {

    private val appContext = context.applicationContext
    private val dao = ArawnDatabase.get(appContext).wirelessDao()

    /** Outcome of an export attempt, surfaced to the UI. */
    sealed interface Result {
        data class Success(val displayName: String, val uri: Uri, val rows: Int) : Result
        data object NoSession : Result
        data object NoData : Result
        data class Failure(val message: String) : Result
    }

    /** Export the most recent session (running or finalized). */
    suspend fun exportLatestSession(): Result = withContext(Dispatchers.IO) {
        val sessionId = dao.getLatestSessionId() ?: return@withContext Result.NoSession
        export(sessionId)
    }

    /** Export a specific session by id. Never throws to the caller. */
    suspend fun export(sessionId: Long): Result = withContext(Dispatchers.IO) {
        val entries = dao.getEntriesWithSignals(sessionId)
        if (entries.isEmpty()) return@withContext Result.NoData

        val displayName = "ARAWN_enriched_S${sessionId}_${FILE_STAMP.format(Date())}.csv"
        val resolver = appContext.contentResolver

        val pending = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOCUMENTS}/ARAWN",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, pending)
            ?: return@withContext Result.Failure("MediaStore rejected the new file")

        try {
            var rows = 0
            resolver.openOutputStream(uri)?.use { os ->
                os.bufferedWriter().use { w ->
                    w.append(COLUMN_HEADER).append('\n')
                    for (entry in entries) rows += writeEntryRows(w, entry)
                }
            } ?: throw IllegalStateException("openOutputStream returned null")

            val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, publish, null, null)
            Result.Success(displayName, uri, rows)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) } // never leak a half-written stub
            Result.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Emit one row per Wi-Fi and BLE observation in a scan window. */
    private fun writeEntryRows(w: Writer, entry: LogEntryWithSignals): Int {
        val fix = entry.entry
        val firstSeen = TIME_FMT.format(Date(fix.timestampMs))
        val lat = coord(fix.latitude)
        val lon = coord(fix.longitude)
        val alt = metric(fix.altitudeM)
        val acc = if (fix.accuracyM >= 0f) metric(fix.accuracyM.toDouble()) else ""
        val spd = metric(fix.speedMps.toDouble())

        var rows = 0

        for (ap in entry.wifi) {
            w.append(
                row(
                    type = "WIFI",
                    mac = ap.bssid.uppercase(Locale.US),
                    name = ap.ssid,
                    vendor = vendorOf(ap.vendorName, ap.bssid),
                    scope = scopeLabel(ap.bssid),
                    rssi = ap.rssiDbm,
                    freqMhz = ap.frequencyMhz.toString(),
                    channel = freqToChannel(ap.frequencyMhz)?.toString() ?: "",
                    band = bandOf(ap.frequencyMhz),
                    security = ap.capabilities,
                    txPower = "",
                    firstSeen = firstSeen, lat = lat, lon = lon, alt = alt, acc = acc, spd = spd,
                )
            ).append('\n')
            rows++
        }

        for (dev in entry.ble) {
            w.append(
                row(
                    type = "BLE",
                    mac = dev.macAddress.uppercase(Locale.US),
                    name = dev.name ?: "",
                    vendor = vendorOf(dev.vendorName, dev.macAddress),
                    scope = scopeLabel(dev.macAddress),
                    rssi = dev.rssiDbm,
                    freqMhz = "",          // BLE channel/freq not surfaced by the scan API
                    channel = "",
                    band = "BLE",
                    security = "",         // BLE advertisements carry no Wi-Fi auth flags
                    txPower = dev.txPower?.toString() ?: "",
                    firstSeen = firstSeen, lat = lat, lon = lon, alt = alt, acc = acc, spd = spd,
                )
            ).append('\n')
            rows++
        }

        return rows
    }

    /**
     * Prefer the vendor stamped at capture time; if it was null (table not yet
     * warm during that scan window) resolve it now from the live table so an
     * export is never blank just because of warm-up timing.
     */
    private fun vendorOf(stamped: String?, mac: String): String =
        if (!stamped.isNullOrBlank()) stamped else OuiLookupManager.lookup(mac)

    /** Human label for the MAC administration scope. */
    private fun scopeLabel(mac: String): String = when (OuiLookupManager.classify(mac)) {
        MacKind.GLOBAL -> "Global"
        MacKind.RANDOMIZED -> "Randomized"
        MacKind.MULTICAST -> "Multicast"
        MacKind.MALFORMED -> "Malformed"
    }

    private fun bandOf(mhz: Int): String = when (mhz) {
        in 2401..2499 -> "2.4GHz"
        in 5150..5895 -> "5GHz"
        in 5925..7125 -> "6GHz"
        else -> ""
    }

    private fun row(
        type: String, mac: String, name: String, vendor: String, scope: String,
        rssi: Int, freqMhz: String, channel: String, band: String, security: String,
        txPower: String, firstSeen: String, lat: String, lon: String, alt: String,
        acc: String, spd: String,
    ): String = buildString {
        append(type); append(',')
        append(csv(mac)); append(',')
        append(csv(name)); append(',')
        append(csv(vendor)); append(',')
        append(scope); append(',')
        append(rssi); append(',')
        append(freqMhz); append(',')
        append(channel); append(',')
        append(band); append(',')
        append(csv(security)); append(',')
        append(txPower); append(',')
        append(csv(firstSeen)); append(',')
        append(lat); append(',')
        append(lon); append(',')
        append(alt); append(',')
        append(acc); append(',')
        append(spd)
    }

    private fun coord(value: Double): String =
        if (value.isFinite()) "%.6f".format(Locale.US, value) else ""

    private fun metric(value: Double): String =
        if (value.isFinite()) "%.1f".format(Locale.US, value) else ""

    /** RFC-4180 quoting: wrap + double internal quotes only when needed. */
    private fun csv(field: String): String {
        if (field.isEmpty()) return field
        val needsQuote = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuote) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }

    private fun freqToChannel(mhz: Int): Int? = when (mhz) {
        2484 -> 14
        in 2412..2472 -> (mhz - 2407) / 5
        in 5160..5885 -> (mhz - 5000) / 5
        in 5955..7115 -> (mhz - 5950) / 5
        else -> null
    }

    private companion object {
        const val COLUMN_HEADER =
            "Type,MAC,Name,Vendor,MacScope,RSSI,FreqMHz,Channel,Band,Security,TxPower," +
                "FirstSeen,Latitude,Longitude,Altitude,Accuracy,SpeedMps"

        val TIME_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
