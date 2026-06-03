package com.arawn.scanner.report

data class ReportSession(
    val sessionId: Long,
    val startMs: Long,
    val endMs: Long?,
    val pointsCollected: Int,
) {
    val durationMs: Long get() = (endMs ?: System.currentTimeMillis()) - startMs
}

data class WifiEntry(
    val ssid: String,
    val bssid: String,
    val bestRssi: Int,
    val frequencyMhz: Int,
    val channel: Int,
    val band: String,
    val capabilities: String,
    val securityType: String,
    val vendor: String,
    val deviceClass: String,
    val classConfidence: Int,
    val classStatus: String,
    val lat: Double,
    val lon: Double,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val seenCount: Int,
)

data class BleEntry(
    val name: String,
    val mac: String,
    val bestRssi: Int,
    val vendor: String,
    val deviceClass: String,
    val classConfidence: Int,
    val classStatus: String,
    val lat: Double,
    val lon: Double,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val seenCount: Int,
)

/** GPS fix for the map track. [sessionId] is used to colour-code multi-session tracks. */
data class TrackPoint(val lat: Double, val lon: Double, val tsMs: Long, val sessionId: Long = 0)

data class ReportMeta(
    val appVersion: String,
    val deviceModel: String,
    val androidVersion: String,
    val exportMs: Long,
    val sessionIds: List<Long>,
    val totalRecords: Int,
) {
    /** Primary session id — backward-compat accessor for single-session callers. */
    val sessionId: Long get() = sessionIds.first()
}

data class ReportData(
    val sessions: List<ReportSession>,
    val wifi: List<WifiEntry>,
    val ble: List<BleEntry>,
    val track: List<TrackPoint>,
    val meta: ReportMeta,
) {
    /** Primary session — backward-compat accessor used by [HtmlReportRenderer]. */
    val session: ReportSession get() = sessions.first()
    val isMultiSession: Boolean get() = sessions.size > 1
}
