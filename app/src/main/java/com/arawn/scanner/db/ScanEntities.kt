package com.arawn.scanner.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * Relational schema for ARAWN's local master log (Phase 2).
 *
 * Hierarchy:
 *   SessionEntity (1) ──< LogEntryEntity (1) ──< WifiApEntity   (N)
 *                                              └─< BleDeviceEntity (N)
 *
 * Each LogEntryEntity is one GPS fix (a scan window); the child rows are the
 * Wi-Fi / BLE signals observed in that window. ON DELETE CASCADE means deleting
 * a session purges its entire subtree in one statement.
 *
 * These are storage types only — the in-memory [com.arawn.scanner.ScanPacket]
 * remains the transport/UI model. Mappers.kt bridges the two.
 */

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val sessionId: Long = 0,
    val startTime: Long,
    /** Null while the run is in progress; set when the session is finalized. */
    val endTime: Long? = null,
    val pointsCollected: Int = 0,
    val totalDistanceM: Double = 0.0,
)

@Entity(
    tableName = "log_entries",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class LogEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val entryId: Long = 0,
    val sessionId: Long,
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double,
    val accuracyM: Float,
    val speedMps: Float,
)

@Entity(
    tableName = "wifi_access_points",
    foreignKeys = [
        ForeignKey(
            entity = LogEntryEntity::class,
            parentColumns = ["entryId"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("entryId"), Index("bssid")],
)
data class WifiApEntity(
    @PrimaryKey(autoGenerate = true)
    val apId: Long = 0,
    val entryId: Long,
    val ssid: String,
    val bssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val capabilities: String,
    /**
     * IEEE OUI manufacturer resolved offline from the BSSID prefix (Phase 3).
     * Null when the lookup table was not yet warm at capture time; "Unknown
     * Vendor" when the prefix is genuinely absent from the local registry.
     */
    val vendorName: String? = null,
)

@Entity(
    tableName = "ble_devices",
    foreignKeys = [
        ForeignKey(
            entity = LogEntryEntity::class,
            parentColumns = ["entryId"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("entryId"), Index("macAddress")],
)
data class BleDeviceEntity(
    @PrimaryKey(autoGenerate = true)
    val bleId: Long = 0,
    val entryId: Long,
    val macAddress: String,
    val name: String?,
    val rssiDbm: Int,
    val txPower: Int?,
    /**
     * IEEE OUI manufacturer resolved offline from the MAC prefix (Phase 3).
     * Frequently "Randomized (private)" for BLE: most advertisers use a
     * locally-administered (RPA) address, which the resolver flags explicitly
     * rather than attributing to a manufacturer.
     */
    val vendorName: String? = null,
)

// ---------------------------------------------------------------------------
//  Read-side relation POJOs (the "deeply nested" view for queries / export)
// ---------------------------------------------------------------------------

/** One scan window with its full Wi-Fi + BLE child arrays. */
data class LogEntryWithSignals(
    @Embedded val entry: LogEntryEntity,
    @Relation(parentColumn = "entryId", entityColumn = "entryId")
    val wifi: List<WifiApEntity>,
    @Relation(parentColumn = "entryId", entityColumn = "entryId")
    val ble: List<BleDeviceEntity>,
)

/** A session with all of its log entries (each still expandable to signals). */
data class SessionWithEntries(
    @Embedded val session: SessionEntity,
    @Relation(parentColumn = "sessionId", entityColumn = "sessionId")
    val entries: List<LogEntryEntity>,
)

/**
 * Minimal GPS projection for the offline map (Phase 6).
 *
 * A deliberately tiny row — just the three numeric fields the map needs —
 * so rendering a track never pulls the heavy nested Wi-Fi/BLE signal arrays
 * into memory. Column names match [LogEntryEntity] so Room maps it directly.
 */
data class CoordinatePair(
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long,
)
