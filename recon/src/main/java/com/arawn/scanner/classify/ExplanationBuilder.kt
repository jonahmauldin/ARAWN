package com.arawn.scanner.classify

/**
 * Turns the winning class's signals into a human-readable, ordered evidence
 * list — the "why" behind a verdict. Deterministic and side-effect free.
 *
 * Order: vendor attribution first (most legible), then the contributing rule
 * reasons by descending weight, then contrast/absence lines that explain what
 * the device is NOT. A [LinkedHashSet] de-duplicates while preserving order.
 */
object ExplanationBuilder {

    fun build(
        input: ClassifierInput,
        top: DeviceClass,
        topSignals: List<ScoreSignal>,
        status: ClassificationStatus,
        runnerUp: DeviceClass?,
    ): List<String> {
        val lines = LinkedHashSet<String>()

        if (input.hasVendor) lines.add("Vendor: ${input.vendor}")

        topSignals.sortedByDescending { it.points }.forEach { lines.add(it.reason) }

        // Contrast lines — the example output's "No Wi-Fi AP characteristics".
        when (input.transport) {
            Transport.BLE ->
                if (top != DeviceClass.UNKNOWN && top != DeviceClass.ROUTER_AP) {
                    lines.add("No Wi-Fi AP characteristics")
                }
            Transport.WIFI ->
                if (input.ssid.isNullOrBlank() && top == DeviceClass.ROUTER_AP) {
                    lines.add("Hidden SSID (no network name broadcast)")
                }
        }

        if (status == ClassificationStatus.AMBIGUOUS && runnerUp != null) {
            lines.add("Ambiguous with ${runnerUp.label}")
        }
        if (status == ClassificationStatus.LOW_CONFIDENCE) {
            lines.add("Weak evidence — best guess only")
        }

        return lines.toList()
    }
}
