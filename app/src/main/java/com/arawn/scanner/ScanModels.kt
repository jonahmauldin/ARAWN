package com.arawn.scanner

import com.arawn.scanner.oui.OuiLookupManager.UNKNOWN_VENDOR
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Immutable value types for a single scan window.
 *
 * A [ScanPacket] is the atomic unit that streams from the service to the UI:
 * one GPS fix bundled with the wireless environment observed in that window.
 * These map cleanly onto the Phase-4 Room schema (WirelessLogEntry +
 * WifiAccessPoint + BleDeviceLog) but carry no persistence concerns yet.
 */

/** A single Wi-Fi access point seen in a scan window. */
data class WifiObservation(
    val ssid: String,
    val bssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val capabilities: String,
    /** Offline OUI vendor for the BSSID prefix; null until stamped (Phase 3). */
    val vendorName: String? = null,
) {
    /** 2.4 / 5 / 6 GHz band label derived from center frequency. */
    val band: String
        get() = when (frequencyMhz) {
            in 2401..2499 -> "2.4GHz"
            in 5150..5895 -> "5GHz"
            in 5925..7125 -> "6GHz"
            else -> "?"
        }
}

/** A single BLE advertiser seen in a scan window. */
data class BleObservation(
    val macAddress: String,
    val name: String?,
    val rssiDbm: Int,
    val txPower: Int?,
    /** Offline OUI vendor for the MAC prefix; null until stamped (Phase 3). */
    val vendorName: String? = null,
    /**
     * Advertised GATT service UUIDs, lowercased full form (Phase 2). Empty when
     * the advertisement carried none. Feeds the classifier's UUID rules
     * (Eddystone, fitness services, lighting profiles).
     */
    val serviceUuids: List<String> = emptyList(),
    /**
     * Company identifiers from the advertisement's manufacturer-specific data
     * (Phase 2). Passive metadata only — the payload bytes are not decoded here.
     */
    val manufacturerIds: List<Int> = emptyList(),
)

/**
 * One GPS fix + the wireless snapshot captured for that fix.
 *
 * @param timestampMs wall-clock time of the location fix (System.currentTimeMillis).
 */
data class ScanPacket(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double,
    val accuracyM: Float,
    val speedMps: Float,
    val wifi: List<WifiObservation>,
    val ble: List<BleObservation>,
) {
    /** Renders a dense, monospace-friendly summary for the terminal console. */
    fun toConsoleString(): String {
        val ts = TIME_FMT.format(Date(timestampMs))
        val sb = StringBuilder()
        sb.append("┌─[$ts] FIX\n")
        sb.append(
            "│ %.6f, %.6f  alt=%.0fm  ±%.0fm  %.1f m/s\n".format(
                Locale.US, latitude, longitude, altitudeM, accuracyM, speedMps,
            )
        )
        sb.append("│ WIFI x${wifi.size}   BLE x${ble.size}\n")

        wifi.sortedByDescending { it.rssiDbm }.take(MAX_LINES).forEach { w ->
            val ssid = if (w.ssid.isBlank()) "<hidden>" else w.ssid
            sb.append(
                "│  W %-4s %4ddBm %-17s %s\n".format(
                    Locale.US, w.band, w.rssiDbm, w.bssid, ssid + vendorSuffix(w.vendorName),
                )
            )
        }
        if (wifi.size > MAX_LINES) sb.append("│   …+${wifi.size - MAX_LINES} more AP\n")

        ble.sortedByDescending { it.rssiDbm }.take(MAX_LINES).forEach { b ->
            val name = b.name ?: "—"
            sb.append(
                "│  B      %4ddBm %-17s %s\n".format(
                    Locale.US, b.rssiDbm, b.macAddress, name + vendorSuffix(b.vendorName),
                )
            )
        }
        if (ble.size > MAX_LINES) sb.append("│   …+${ble.size - MAX_LINES} more BLE\n")

        sb.append("└────────────────────────────")
        return sb.toString()
    }

    /**
     * " (Cisco Systems)" when a vendor was resolved, "" otherwise. Null (table
     * not yet warm) and the "Unknown Vendor" fallback are both suppressed so the
     * terminal stays dense and only surfaces real attributions.
     */
    private fun vendorSuffix(vendor: String?): String =
        if (vendor.isNullOrBlank() || vendor == UNKNOWN_VENDOR) "" else " ($vendor)"

    private companion object {
        const val MAX_LINES = 8
        val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}
