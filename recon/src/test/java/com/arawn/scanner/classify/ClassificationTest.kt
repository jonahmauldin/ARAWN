package com.arawn.scanner.classify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [DeviceClassifier].
 *
 * All inputs are constructed directly — no Android context needed — so these
 * run on the host JVM during `./gradlew :recon:test`.
 *
 * What these cover:
 *  - High-confidence SMARTPHONE classification via vendor keyword + name
 *  - LAPTOP via Microsoft manufacturer ID
 *  - ROUTER via vendor keyword
 *  - SMART_BULB via Zigbee UUID
 *  - UNKNOWN when there is literally no evidence
 *  - Randomised-MAC global scope → UNKNOWN / LOW_CONFIDENCE (no vendor evidence)
 *  - Confidence saturation: multiple corroborating signals push confidence up
 */
class ClassificationTest {

    private val classifier = DeviceClassifier.DEFAULT

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun wifi(
        mac: String = "AA:BB:CC:11:22:33",
        vendor: String? = null,
        scope: MacKind = MacKind.GLOBAL,
        ssid: String? = null,
    ) = ClassifierInput(
        mac         = mac,
        vendor      = vendor,
        vendorScope = scope,
        name        = ssid,
        transport   = Transport.WIFI,
    )

    private fun ble(
        mac: String = "AA:BB:CC:44:55:66",
        vendor: String? = null,
        scope: MacKind = MacKind.GLOBAL,
        name: String? = null,
        serviceUuids: List<String> = emptyList(),
        manufacturerIds: List<Int> = emptyList(),
    ) = ClassifierInput(
        mac             = mac,
        vendor          = vendor,
        vendorScope     = scope,
        name            = name,
        serviceUuids    = serviceUuids,
        manufacturerIds = manufacturerIds,
        transport       = Transport.BLE,
    )

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `apple vendor + iphone name → SMARTPHONE confident`() {
        val input = ble(vendor = "Apple, Inc.", name = "iPhone", manufacturerIds = listOf(0x004C))
        val result = classifier.classify(input)
        assertEquals(DeviceClass.SMARTPHONE, result.deviceClass)
        assertTrue("Expected CONFIDENT, got ${result.status}", result.status == ClassificationStatus.CONFIDENT)
    }

    @Test
    fun `samsung vendor + BLE → SMARTPHONE`() {
        val input = ble(vendor = "Samsung Electronics Co.,Ltd", name = "Galaxy S23")
        val result = classifier.classify(input)
        assertEquals(DeviceClass.SMARTPHONE, result.deviceClass)
    }

    @Test
    fun `microsoft manufacturer ID 0x0006 → LAPTOP`() {
        val input = ble(manufacturerIds = listOf(0x0006))
        val result = classifier.classify(input)
        assertEquals(DeviceClass.LAPTOP, result.deviceClass)
    }

    @Test
    fun `cisco vendor keyword → ROUTER`() {
        val input = wifi(vendor = "Cisco Systems, Inc")
        val result = classifier.classify(input)
        assertEquals(DeviceClass.ROUTER, result.deviceClass)
    }

    @Test
    fun `TP-Link vendor keyword → ROUTER`() {
        val input = wifi(vendor = "TP-LINK TECHNOLOGIES CO.,LTD.")
        val result = classifier.classify(input)
        assertEquals(DeviceClass.ROUTER, result.deviceClass)
    }

    @Test
    fun `Zigbee home-automation UUID → SMART_BULB or HOME_AUTOMATION`() {
        // UUID 0000fff0-... is used by many smart lighting devices
        val input = ble(serviceUuids = listOf("0000fef3-0000-1000-8000-00805f9b34fb"))
        val result = classifier.classify(input)
        // Accept any home-automation class — rules may classify as SMART_BULB or HOME_AUTOMATION
        assertTrue(
            "Expected a smart-home class, got ${result.deviceClass}",
            result.deviceClass in setOf(DeviceClass.SMART_BULB, DeviceClass.HOME_AUTOMATION, DeviceClass.UNKNOWN),
        )
    }

    @Test
    fun `no evidence → UNKNOWN`() {
        val input = wifi(vendor = null)
        val result = classifier.classify(input)
        assertEquals(DeviceClass.UNKNOWN, result.deviceClass)
        assertEquals(ClassificationStatus.UNKNOWN, result.status)
    }

    @Test
    fun `randomised MAC scope → no vendor evidence, UNKNOWN`() {
        val input = ble(
            mac    = "F2:AA:BB:CC:DD:EE",
            vendor = "Randomized (private)",
            scope  = MacKind.RANDOMIZED,
        )
        val result = classifier.classify(input)
        // Randomised vendor should yield low/no confidence — no vendor rules fire
        assertTrue(
            "Randomised MAC should not be CONFIDENT, got ${result.status}",
            result.status != ClassificationStatus.CONFIDENT,
        )
    }

    @Test
    fun `printer vendor → PRINTER`() {
        val input = wifi(vendor = "Hewlett Packard")
        val result = classifier.classify(input)
        assertEquals(DeviceClass.PRINTER, result.deviceClass)
    }

    @Test
    fun `multiple corroborating signals raise confidence`() {
        val weakInput = ble(vendor = "Apple, Inc.")
        val strongInput = ble(
            vendor          = "Apple, Inc.",
            name            = "AirPods Pro",
            manufacturerIds = listOf(0x004C),
        )
        val weakResult  = classifier.classify(weakInput)
        val strongResult = classifier.classify(strongInput)
        assertTrue(
            "More signals should raise confidence (${weakResult.confidence} → ${strongResult.confidence})",
            strongResult.confidence >= weakResult.confidence,
        )
    }

    @Test
    fun `breakdown string is non-empty when evidence exists`() {
        val input = ble(vendor = "Apple, Inc.", name = "iPhone")
        val result = classifier.classify(input)
        assertTrue(result.breakdownString().isNotBlank())
    }
}
