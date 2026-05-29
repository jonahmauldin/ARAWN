package com.arawn.scanner.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.arawn.scanner.db.ArawnDatabase
import com.arawn.scanner.db.LogEntryEntity
import com.arawn.scanner.db.LogEntryWithSignals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WiGLE-compatible CSV exporter (Phase 4, Module D).
 *
 * Reads one tracking session out of [ArawnDatabase] and writes a single CSV that
 * follows the WiGLE WigleWifi-1.6 layout, then drops it into the device's public
 * Downloads collection via [MediaStore] — scoped-storage compliant on Android
 * 10–15 with NO storage permission and NO root.
 *
 * 100% local / free: stock `android.*` + `java.*` only. No network, no SDKs, no
 * tokens. Nothing leaves the device — the user moves the file manually.
 *
 * Rows stream straight to the output stream as they are formatted, so peak memory
 * is bounded by the relational read, not by the size of the CSV text.
 */
class WigleCsvExporter(context: Context) {

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
    suspend fun exportMostRecentSession(): Result = withContext(Dispatchers.IO) {
        val session = dao.getMostRecentSession() ?: return@withContext Result.NoSession
        export(session.sessionId)
    }

    /** Export a specific session by id. */
    suspend fun export(sessionId: Long): Result = withContext(Dispatchers.IO) {
        val entries = dao.getEntriesWithSignals(sessionId)
        if (entries.isEmpty()) return@withContext Result.NoData

        val displayName = "ARAWN_session_${sessionId}_${FILE_STAMP.format(Date())}.csv"
        val resolver = appContext.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            // Land in /Downloads/ARAWN/ on the primary external volume.
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/ARAWN",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: return@withContext Result.Failure("MediaStore rejected the new file")

        try {
            var rows = 0
            resolver.openOutputStream(uri)?.use { os ->
                os.bufferedWriter().use { w ->
                    w.append(META_HEADER).append('\n')
                    w.append(COLUMN_HEADER).append('\n')
                    for (entry in entries) {
                        rows += writeEntryRows(w, entry)
                    }
                }
            } ?: throw IllegalStateException("openOutputStream returned null")

            // Publish: clear IS_PENDING so the file becomes visible to other apps.
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            Result.Success(displayName, uri, rows)
        } catch (e: Exception) {
            // Roll back the half-written pending entry so we never leak a stub.
            runCatching { resolver.delete(uri, null, null) }
            Result.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Emit one CSV row per Wi-Fi and BLE observation in a scan window. Windows
     * without a real GPS fix are skipped entirely so we never write the
     * dangerous (0,0) null-island default that pollutes WiGLE maps.
     *
     * @return number of data rows written for this entry.
     */
    private fun writeEntryRows(w: Writer, entry: LogEntryWithSignals): Int {
        val fix = entry.entry
        if (!hasValidFix(fix)) return 0

        val firstSeen = TIME_FMT.format(Date(fix.timestampMs))
        val lat = fmt(fix.latitude, 8)
        val lon = fmt(fix.longitude, 8)
        val alt = fmt(fix.altitudeM, 1)
        val acc = if (fix.accuracyM >= 0f) fmt(fix.accuracyM.toDouble(), 1) else ""

        var rows = 0

        for (ap in entry.wifi) {
            w.append(
                row(
                    mac = ap.bssid.uppercase(Locale.US),
                    ssid = ap.ssid,
                    authMode = ap.capabilities,
                    firstSeen = firstSeen,
                    channel = freqToChannel(ap.frequencyMhz)?.toString() ?: "",
                    rssi = ap.rssiDbm,
                    lat = lat, lon = lon, alt = alt, acc = acc,
                    type = "WIFI",
                )
            ).append('\n')
            rows++
        }

        for (dev in entry.ble) {
            w.append(
                row(
                    mac = dev.macAddress.uppercase(Locale.US),
                    ssid = dev.name ?: "",
                    authMode = "", // BLE advertisements carry no Wi-Fi auth flags
                    firstSeen = firstSeen,
                    channel = "", // not applicable to BLE
                    rssi = dev.rssiDbm,
                    lat = lat, lon = lon, alt = alt, acc = acc,
                    type = "BLE",
                )
            ).append('\n')
            rows++
        }

        return rows
    }

    /** Assemble one RFC-4180-escaped CSV record in WiGLE column order. */
    private fun row(
        mac: String,
        ssid: String,
        authMode: String,
        firstSeen: String,
        channel: String,
        rssi: Int,
        lat: String,
        lon: String,
        alt: String,
        acc: String,
        type: String,
    ): String = buildString {
        append(csv(mac)); append(',')
        append(csv(ssid)); append(',')
        append(csv(authMode)); append(',')
        append(csv(firstSeen)); append(',')
        append(csv(channel)); append(',')
        append(rssi); append(',')
        append(lat); append(',')
        append(lon); append(',')
        append(alt); append(',')
        append(acc); append(',')
        append(type)
    }

    /** A window counts as fixed if it isn't sitting on null-island (0,0). */
    private fun hasValidFix(e: LogEntryEntity): Boolean =
        !(e.latitude == 0.0 && e.longitude == 0.0) &&
            !e.latitude.isNaN() && !e.longitude.isNaN()

    /** Locale-stable decimal formatting (always '.', never a comma separator). */
    private fun fmt(value: Double, decimals: Int): String =
        "%.${decimals}f".format(Locale.US, value)

    /**
     * Quote a field per RFC 4180 only when it contains a comma, quote, or
     * newline; internal quotes are doubled. Keeps clean fields unquoted so the
     * output matches what the WiGLE importer emits.
     */
    private fun csv(field: String): String {
        if (field.isEmpty()) return field
        val needsQuote = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuote) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }

    /**
     * IEEE 802.11 channel from center frequency (MHz). Returns null for
     * frequencies outside the known 2.4 / 5 / 6 GHz plans.
     */
    private fun freqToChannel(mhz: Int): Int? = when (mhz) {
        2484 -> 14
        in 2412..2472 -> (mhz - 2407) / 5
        in 5160..5885 -> (mhz - 5000) / 5
        in 5955..7115 -> (mhz - 5950) / 5 // 6 GHz (Wi-Fi 6E)
        else -> null
    }

    private companion object {
        const val META_HEADER =
            "WigleWifi-1.6,appRelease=1.0,model=Galaxy S23 Ultra,device=ARAWN"
        const val COLUMN_HEADER =
            "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,Latitude,Longitude,Altitude,Accuracy,Type"

        // WiGLE FirstSeen is local wall-clock time in this exact pattern.
        val TIME_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
