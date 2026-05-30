package com.arawn.scanner.classify

import com.arawn.scanner.oui.OuiLookupManager

/**
 * Phase 2 — device classification value types.
 *
 * Pure Kotlin, no Android dependencies, so the whole classifier is unit-testable
 * on a plain JVM and cheap enough to run on the scan pipeline's IO thread.
 */

/** The closed set of device categories ARAWN can assign. */
enum class DeviceClass(val label: String) {
    ROUTER_AP("Router/AP"),
    MESH_NODE("Mesh Node"),
    SMARTPHONE("Smartphone"),
    LAPTOP_DESKTOP("Laptop/Desktop"),
    SMART_TV("Smart TV"),
    STREAMING_DEVICE("Streaming Device"),
    SMART_SPEAKER("Smart Speaker"),
    SMART_BULB("Smart Bulb"),
    IOT_DEVICE("IoT Device"),
    SECURITY_CAMERA("Security Camera"),
    PRINTER("Printer"),
    WEARABLE("Wearable"),
    VEHICLE_SYSTEM("Vehicle System"),
    BLE_BEACON("BLE Beacon"),
    UNKNOWN("Unknown"),
}

/** Outcome quality, kept separate from [DeviceClass] so "weak" is explicit. */
enum class ClassificationStatus {
    /** Clear winner with enough evidence. */
    CONFIDENT,
    /** A best guess is reported, but evidence is thin. */
    LOW_CONFIDENCE,
    /** Two classes are within the ambiguity margin; both are surfaced. */
    AMBIGUOUS,
    /** No class cleared the minimum evidence bar. */
    UNKNOWN,
}

/** Which radio produced the observation. */
enum class Transport { WIFI, BLE }

/**
 * The normalized feature bundle a rule sees. One shape for both radios; fields
 * irrelevant to a transport are simply null/empty. [serviceUuids] and
 * [manufacturerIds] are populated only once BLE advertisement capture is
 * extended (Phase 2 step 4 / Phase 5) — rules that read them stay dormant until
 * then, which is intentional and harmless.
 */
data class ClassifierInput(
    val transport: Transport,
    val mac: String,
    val vendor: String?,
    val macScope: OuiLookupManager.MacKind,
    val rssiDbm: Int,
    // Wi-Fi
    val ssid: String? = null,
    val frequencyMhz: Int? = null,
    val capabilities: String? = null,
    // BLE
    val name: String? = null,
    val txPower: Int? = null,
    val serviceUuids: List<String> = emptyList(),
    val manufacturerIds: List<Int> = emptyList(),
) {
    /** True only for a genuinely attributed OUI vendor (not unknown/randomized). */
    val hasVendor: Boolean
        get() = !vendor.isNullOrBlank() &&
            vendor != OuiLookupManager.UNKNOWN_VENDOR &&
            vendor != OuiLookupManager.RANDOMIZED_VENDOR

    /** Vendor reduced to lowercase alphanumerics so manuf short-names match keys. */
    val vendorNorm: String by lazy(LazyThreadSafetyMode.NONE) {
        vendor?.lowercase()?.filter { it.isLetterOrDigit() } ?: ""
    }

    val nameLower: String by lazy(LazyThreadSafetyMode.NONE) { name?.lowercase() ?: "" }

    /** True if the normalized vendor contains any of the pre-normalized [keys]. */
    fun vendorMatches(keys: Set<String>): Boolean =
        vendorNorm.isNotEmpty() && keys.any { vendorNorm.contains(it) }

    /** True if the lowercased name contains any of [keys]. */
    fun nameContains(keys: Set<String>): Boolean =
        nameLower.isNotEmpty() && keys.any { nameLower.contains(it) }

    /** True if a (lowercased) service UUID contains [fragment] (16-bit or full). */
    fun hasServiceUuid(fragment: String): Boolean =
        serviceUuids.any { it.lowercase().contains(fragment) }
}

/** A single rule's contribution toward one class, with provenance + a reason. */
data class ScoreSignal(
    val target: DeviceClass,
    val points: Int,
    val reason: String,
    val ruleId: String,
)

/** The classifier's verdict: top pick, confidence, status, evidence, full math. */
data class ClassificationResult(
    val top: DeviceClass,
    val confidence: Int,            // 0..100
    val status: ClassificationStatus,
    val runnerUp: DeviceClass?,     // non-null only when AMBIGUOUS
    val evidence: List<String>,
    val breakdown: Map<DeviceClass, Int>,
) {
    /** Compact, stable serialization of the full per-class score map for storage. */
    fun breakdownString(): String = breakdown.entries
        .sortedByDescending { it.value }
        .joinToString(";") { "${it.key.name}:${it.value}" }

    companion object {
        val UNKNOWN = ClassificationResult(
            top = DeviceClass.UNKNOWN,
            confidence = 0,
            status = ClassificationStatus.UNKNOWN,
            runnerUp = null,
            evidence = emptyList(),
            breakdown = emptyMap(),
        )
    }
}
