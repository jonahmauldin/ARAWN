package com.arawn.scanner.db

import com.arawn.scanner.ScanPacket
import com.arawn.scanner.classify.ClassifierInputMapper
import com.arawn.scanner.classify.DeviceClassifier

/**
 * Bridges the in-memory transport model ([ScanPacket]) to the relational
 * storage entities. Children are emitted with `entryId = 0`; the DAO transaction
 * stamps them with the real foreign key after the parent insert.
 *
 * Phase 2: each Wi-Fi/BLE observation is classified here, on the IO writer
 * thread, so the heuristic verdict is persisted alongside the raw signal. The
 * classifier is stateless and shared via [DeviceClassifier.DEFAULT].
 */

fun ScanPacket.toLogEntry(sessionId: Long): LogEntryEntity =
    LogEntryEntity(
        sessionId = sessionId,
        timestampMs = timestampMs,
        latitude = latitude,
        longitude = longitude,
        altitudeM = altitudeM,
        accuracyM = accuracyM,
        speedMps = speedMps,
    )

fun ScanPacket.toWifiEntities(): List<WifiApEntity> =
    wifi.map { w ->
        val c = DeviceClassifier.DEFAULT.classify(ClassifierInputMapper.from(w))
        WifiApEntity(
            entryId = 0,
            ssid = w.ssid,
            bssid = w.bssid,
            rssiDbm = w.rssiDbm,
            frequencyMhz = w.frequencyMhz,
            capabilities = w.capabilities,
            vendorName = w.vendorName,
            deviceClass = c.top.name,
            classConfidence = c.confidence,
            classStatus = c.status.name,
            classBreakdown = c.breakdownString().ifEmpty { null },
        )
    }

fun ScanPacket.toBleEntities(): List<BleDeviceEntity> =
    ble.map { b ->
        val c = DeviceClassifier.DEFAULT.classify(ClassifierInputMapper.from(b))
        BleDeviceEntity(
            entryId = 0,
            macAddress = b.macAddress,
            name = b.name,
            rssiDbm = b.rssiDbm,
            txPower = b.txPower,
            vendorName = b.vendorName,
            deviceClass = c.top.name,
            classConfidence = c.confidence,
            classStatus = c.status.name,
            classBreakdown = c.breakdownString().ifEmpty { null },
        )
    }
