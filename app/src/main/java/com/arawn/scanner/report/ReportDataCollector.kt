package com.arawn.scanner.report

import android.content.Context
import android.os.Build
import com.arawn.scanner.db.WirelessDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReportDataCollector(private val dao: WirelessDao, private val context: Context) {

    suspend fun collect(sessionId: Long): ReportData? = withContext(Dispatchers.IO) {
        val sessionContainer = dao.getSessionWithEntries(sessionId) ?: return@withContext null
        val entries = dao.getEntriesWithSignals(sessionId)
        if (entries.isEmpty()) return@withContext null

        // Aggregate Wi-Fi: one row per BSSID, keeping the observation with the
        // strongest RSSI. First/last timestamps and sighting count are tracked
        // across all observations so the report reflects the full session.
        data class WifiAgg(
            var ssid: String, var bssid: String,
            var bestRssi: Int, var frequencyMhz: Int,
            var capabilities: String, var vendor: String,
            var lat: Double, var lon: Double,
            var firstMs: Long, var lastMs: Long, var count: Int,
        )
        data class BleAgg(
            var name: String?, var mac: String,
            var bestRssi: Int, var vendor: String,
            var lat: Double, var lon: Double,
            var firstMs: Long, var lastMs: Long, var count: Int,
        )

        val wifiAggs = LinkedHashMap<String, WifiAgg>()
        val bleAggs  = LinkedHashMap<String, BleAgg>()
        val track    = ArrayList<TrackPoint>(entries.size)

        for (e in entries) {
            val ts  = e.entry.timestampMs
            val lat = e.entry.latitude
            val lon = e.entry.longitude
            track.add(TrackPoint(lat, lon, ts))

            for (w in e.wifi) {
                val agg = wifiAggs[w.bssid]
                if (agg == null) {
                    wifiAggs[w.bssid] = WifiAgg(
                        w.ssid, w.bssid, w.rssiDbm, w.frequencyMhz,
                        w.capabilities, w.vendorName ?: "Unknown",
                        lat, lon, ts, ts, 1,
                    )
                } else {
                    if (w.rssiDbm > agg.bestRssi) {
                        agg.bestRssi = w.rssiDbm; agg.lat = lat; agg.lon = lon
                        if (!w.vendorName.isNullOrBlank() && w.vendorName != "Unknown Vendor")
                            agg.vendor = w.vendorName
                    }
                    if (ts < agg.firstMs) agg.firstMs = ts
                    if (ts > agg.lastMs)  agg.lastMs  = ts
                    agg.count++
                }
            }

            for (b in e.ble) {
                val agg = bleAggs[b.macAddress]
                if (agg == null) {
                    bleAggs[b.macAddress] = BleAgg(
                        b.name, b.macAddress, b.rssiDbm,
                        b.vendorName ?: "Unknown",
                        lat, lon, ts, ts, 1,
                    )
                } else {
                    if (b.rssiDbm > agg.bestRssi) { agg.bestRssi = b.rssiDbm; agg.lat = lat; agg.lon = lon }
                    if (b.name != null && agg.name == null) agg.name = b.name
                    if (ts < agg.firstMs) agg.firstMs = ts
                    if (ts > agg.lastMs)  agg.lastMs  = ts
                    agg.count++
                }
            }
        }

        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")

        val session = sessionContainer.session
        ReportData(
            session = ReportSession(session.sessionId, session.startTime, session.endTime, session.pointsCollected),
            wifi = wifiAggs.values.map { a ->
                WifiEntry(
                    ssid = a.ssid, bssid = a.bssid,
                    bestRssi = a.bestRssi, frequencyMhz = a.frequencyMhz,
                    channel = freqToChannel(a.frequencyMhz),
                    band = freqToBand(a.frequencyMhz),
                    capabilities = a.capabilities,
                    securityType = parseSecurity(a.capabilities),
                    vendor = a.vendor,
                    lat = a.lat, lon = a.lon,
                    firstSeenMs = a.firstMs, lastSeenMs = a.lastMs, seenCount = a.count,
                )
            }.sortedByDescending { it.bestRssi },
            ble = bleAggs.values.map { a ->
                BleEntry(
                    name = a.name ?: "(unnamed)", mac = a.mac,
                    bestRssi = a.bestRssi, vendor = a.vendor,
                    lat = a.lat, lon = a.lon,
                    firstSeenMs = a.firstMs, lastSeenMs = a.lastMs, seenCount = a.count,
                )
            }.sortedByDescending { it.bestRssi },
            track = track,
            meta = ReportMeta(
                appVersion = appVersion,
                deviceModel = Build.MODEL,
                androidVersion = Build.VERSION.RELEASE,
                exportMs = System.currentTimeMillis(),
                sessionId = session.sessionId,
                totalRecords = entries.sumOf { it.wifi.size + it.ble.size },
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

    private fun parseSecurity(cap: String) = when {
        "WPA3" in cap -> "WPA3"
        "WPA2" in cap -> "WPA2"
        "WPA"  in cap -> "WPA"
        "WEP"  in cap -> "WEP"
        "OWE"  in cap -> "OWE"
        else          -> "Open"
    }
}
