package com.arawn.scanner.db

import com.arawn.scanner.ScanPacket

/**
 * Bridges the in-memory transport model ([ScanPacket]) to the relational
 * storage entities. Children are emitted with `entryId = 0`; the DAO transaction
 * stamps them with the real foreign key after the parent insert.
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
        WifiApEntity(
            entryId = 0,
            ssid = w.ssid,
            bssid = w.bssid,
            rssiDbm = w.rssiDbm,
            frequencyMhz = w.frequencyMhz,
            capabilities = w.capabilities,
            vendorName = w.vendorName,
        )
    }

fun ScanPacket.toBleEntities(): List<BleDeviceEntity> =
    ble.map { b ->
        BleDeviceEntity(
            entryId = 0,
            macAddress = b.macAddress,
            name = b.name,
            rssiDbm = b.rssiDbm,
            txPower = b.txPower,
            vendorName = b.vendorName,
        )
    }
