package com.arawn.scanner.report

import android.content.Context
import android.os.Build
import com.arawn.scanner.classify.DeviceClass
import com.arawn.core.database.WirelessDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReportDataCollector(private val dao: WirelessDao, private val context: Context) {

    /** Collect data for a single session — delegates to [collectMultiple]. */
    suspend fun collect(sessionId: Long, title: String? = null): ReportData? =
        collectMultiple(listOf(sessionId), title)

    /**
     * Collect and merge data from one or more sessions into a single [ReportData].
     * Per-BSSID Wi-Fi and BLE rows are deduplicated across sessions, keeping the
     * observation with the strongest RSSI and the most confident classification.
     * Track points from all sessions are combined and sorted chronologically.
     *
     * Uses SQL GROUP BY aggregate queries ([WirelessDao.getWifiAggregates] /
     * [WirelessDao.getBleAggregates]) instead of loading the full
     * [com.arawn.core.database.LogEntryWithSignals] entity graph. For a long scan
     * the full graph can be hundreds of thousands of objects and exhaust the ART
     * heap; the aggregate queries return at most a few hundred rows.
     *
     * Returns null only when every requested session is missing or empty.
     */
    suspend fun collectMultiple(
        sessionIds: List<Long>,
        title: String? = null,
    ): ReportData? = withContext(Dispatchers.IO) {
        if (sessionIds.isEmpty()) return@withContext null

        // Mutable holders for cross-session merging (one entry per BSSID / MAC).
        data class WifiEntry(
            var ssid: String, var bssid: String,
            var bestRssi: Int, var frequencyMhz: Int,
            var capabilities: String, var vendor: String,
            var deviceClass: String, var classConfidence: Int, var classStatus: String,
            var lat: Double, var lon: Double,
            var firstMs: Long, var lastMs: Long, var count: Int,
        )
        data class BleEntry(
            var name: String?, var mac: String,
            var bestRssi: Int, var vendor: String,
            var deviceClass: String, var classConfidence: Int, var classStatus: String,
            var lat: Double, var lon: Double,
            var firstMs: Long, var lastMs: Long, var count: Int,
        )

        val wifiMap        = LinkedHashMap<String, WifiEntry>()
        val bleMap         = LinkedHashMap<String, BleEntry>()
        val track          = ArrayList<TrackPoint>()
        val validSessions  = ArrayList<ReportSession>()
        var totalRecords   = 0

        for (sessionId in sessionIds) {
            // Lightweight aggregate queries — one row per unique BSSID / MAC.
            val wifiAggs = dao.getWifiAggregates(sessionId)
            val bleAggs  = dao.getBleAggregates(sessionId)
            if (wifiAggs.isEmpty() && bleAggs.isEmpty()) continue

            val session = dao.getSession(sessionId) ?: continue
            validSessions.add(
                ReportSession(session.sessionId, session.startTime, session.endTime, session.pointsCollected)
            )

            // GPS track — coordinate-only projection, already cheap.
            dao.getSessionCoordinates(sessionId).forEach { c ->
                track.add(TrackPoint(c.latitude, c.longitude, c.timestampMs, sessionId))
            }

            totalRecords += wifiAggs.sumOf { it.seenCount } + bleAggs.sumOf { it.seenCount }

            for (w in wifiAggs) {
                val existing = wifiMap[w.bssid]
                if (existing == null) {
                    wifiMap[w.bssid] = WifiEntry(
                        w.ssid, w.bssid, w.bestRssi, w.frequencyMhz,
                        w.capabilities, w.vendorName ?: "Unknown",
                        w.deviceClass ?: "UNKNOWN", w.classConfidence ?: 0, w.classStatus ?: "UNKNOWN",
                        w.lat, w.lon, w.firstMs, w.lastMs, w.seenCount,
                    )
                } else {
                    if (w.bestRssi > existing.bestRssi) {
                        existing.bestRssi = w.bestRssi; existing.lat = w.lat; existing.lon = w.lon
                        val vn = w.vendorName
                        if (!vn.isNullOrBlank() && vn != "Unknown Vendor") existing.vendor = vn
                    }
                    if ((w.classConfidence ?: 0) > existing.classConfidence) {
                        existing.deviceClass     = w.deviceClass    ?: existing.deviceClass
                        existing.classConfidence = w.classConfidence ?: existing.classConfidence
                        existing.classStatus     = w.classStatus    ?: existing.classStatus
                    }
                    if (w.firstMs < existing.firstMs) existing.firstMs = w.firstMs
                    if (w.lastMs  > existing.lastMs)  existing.lastMs  = w.lastMs
                    existing.count += w.seenCount
                }
            }

            for (b in bleAggs) {
                val existing = bleMap[b.macAddress]
                if (existing == null) {
                    bleMap[b.macAddress] = BleEntry(
                        b.name, b.macAddress, b.bestRssi,
                        b.vendorName ?: "Unknown",
                        b.deviceClass ?: "UNKNOWN", b.classConfidence ?: 0, b.classStatus ?: "UNKNOWN",
                        b.lat, b.lon, b.firstMs, b.lastMs, b.seenCount,
                    )
                } else {
                    if (b.bestRssi > existing.bestRssi) { existing.bestRssi = b.bestRssi; existing.lat = b.lat; existing.lon = b.lon }
                    if (b.name != null && existing.name == null) existing.name = b.name
                    if ((b.classConfidence ?: 0) > existing.classConfidence) {
                        existing.deviceClass     = b.deviceClass    ?: existing.deviceClass
                        existing.classConfidence = b.classConfidence ?: existing.classConfidence
                        existing.classStatus     = b.classStatus    ?: existing.classStatus
                    }
                    if (b.firstMs < existing.firstMs) existing.firstMs = b.firstMs
                    if (b.lastMs  > existing.lastMs)  existing.lastMs  = b.lastMs
                    existing.count += b.seenCount
                }
            }
        }

        if (validSessions.isEmpty()) return@withContext null

        // Sort track chronologically across all sessions, then downsample.
        // The canvas map is at most ~800 px wide; 1 000 points is more than enough
        // to draw a faithful path and avoids serialising thousands of JSON objects.
        track.sortBy { it.tsMs }
        val MAX_TRACK = 1_000
        val finalTrack: List<TrackPoint> = if (track.size > MAX_TRACK) {
            val stride = track.size / MAX_TRACK
            ArrayList<TrackPoint>(MAX_TRACK + 1).also { out ->
                track.forEachIndexed { i, pt -> if (i % stride == 0) out.add(pt) }
                if (out.last() != track.last()) out.add(track.last())
            }
        } else track

        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")

        ReportData(
            sessions = validSessions,
            wifi = wifiMap.values.map { a ->
                WifiEntry(
                    ssid = a.ssid, bssid = a.bssid,
                    bestRssi = a.bestRssi, frequencyMhz = a.frequencyMhz,
                    channel = freqToChannel(a.frequencyMhz),
                    band = freqToBand(a.frequencyMhz),
                    capabilities = a.capabilities,
                    securityType = parseSecurity(a.capabilities),
                    vendor = a.vendor,
                    deviceClass = classLabel(a.deviceClass),
                    classConfidence = a.classConfidence,
                    classStatus = a.classStatus,
                    lat = a.lat, lon = a.lon,
                    firstSeenMs = a.firstMs, lastSeenMs = a.lastMs, seenCount = a.count,
                )
            }.sortedByDescending { it.bestRssi },
            ble = bleMap.values.map { a ->
                BleEntry(
                    name = a.name ?: "(unnamed)", mac = a.mac,
                    bestRssi = a.bestRssi, vendor = a.vendor,
                    deviceClass = classLabel(a.deviceClass),
                    classConfidence = a.classConfidence,
                    classStatus = a.classStatus,
                    lat = a.lat, lon = a.lon,
                    firstSeenMs = a.firstMs, lastSeenMs = a.lastMs, seenCount = a.count,
                )
            }.sortedByDescending { it.bestRssi },
            track = finalTrack,
            meta = ReportMeta(
                appVersion = appVersion,
                deviceModel = Build.MODEL,
                androidVersion = Build.VERSION.RELEASE,
                exportMs = System.currentTimeMillis(),
                sessionIds = validSessions.map { it.sessionId },
                totalRecords = totalRecords,
                title = title?.takeIf { it.isNotBlank() }?.trim(),
            ),
        )
    }

    private fun freqToChannel(f: Int) = when (f) {
        2484 -> 14
        in 2412..2472 -> (f - 2407) / 5
        in 5160..5895 -> (f - 5000) / 5
        in 5955..7115 -> (f - 5950) / 5
        else -> 0
    }

    private fun freqToBand(f: Int) = when (f) {
        in 2400..2500 -> "2.4 GHz"
        in 4900..5900 -> "5 GHz"
        in 5900..7200 -> "6 GHz"
        else -> "Other"
    }

    private fun classLabel(name: String): String =
        runCatching { DeviceClass.valueOf(name).label }.getOrDefault("Unknown")

    private fun parseSecurity(cap: String) = when {
        "WPA3" in cap -> "WPA3"
        "WPA2" in cap -> "WPA2"
        "WPA"  in cap -> "WPA"
        "WEP"  in cap -> "WEP"
        "OWE"  in cap -> "OWE"
        else          -> "Open"
    }
}
