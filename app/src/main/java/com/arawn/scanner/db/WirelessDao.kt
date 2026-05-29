package com.arawn.scanner.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Thread-safe data access for the wireless master log.
 *
 * All write methods are `suspend` (Room dispatches them off the main thread on
 * its own executor). The parent→child bundle insert runs inside a single SQLite
 * transaction via [insertScanWindow], so a scan window is persisted atomically:
 * either the log entry and all its signals land, or none do.
 *
 * Declared as an abstract class (not an interface) so [insertScanWindow] can
 * carry a real body while still being wrapped by Room's @Transaction.
 */
@Dao
abstract class WirelessDao {

    // ---- Sessions -----------------------------------------------------

    @Insert
    abstract suspend fun insertSession(session: SessionEntity): Long

    @Query(
        "UPDATE sessions SET endTime = :endTime, pointsCollected = :points, " +
            "totalDistanceM = :distanceM WHERE sessionId = :sessionId"
    )
    abstract suspend fun finalizeSession(
        sessionId: Long,
        endTime: Long,
        points: Int,
        distanceM: Double,
    )

    @Query("SELECT * FROM sessions ORDER BY startTime DESC")
    abstract fun observeSessions(): Flow<List<SessionEntity>>

    /** Most recent session (running or finalized); null if none exist yet. */
    @Query("SELECT * FROM sessions ORDER BY startTime DESC LIMIT 1")
    abstract suspend fun getMostRecentSession(): SessionEntity?

    // ---- Child inserts (used by the transaction below) ----------------

    @Insert
    abstract suspend fun insertLogEntry(entry: LogEntryEntity): Long

    @Insert
    abstract suspend fun insertWifi(aps: List<WifiApEntity>)

    @Insert
    abstract suspend fun insertBle(devices: List<BleDeviceEntity>)

    /**
     * Atomically persist one scan window: the GPS [entry] plus its [wifi] and
     * [ble] children. The auto-generated entry id is propagated to the children
     * before they are written, so callers pass children with `entryId = 0`.
     *
     * @return the generated entryId.
     */
    @Transaction
    open suspend fun insertScanWindow(
        entry: LogEntryEntity,
        wifi: List<WifiApEntity>,
        ble: List<BleDeviceEntity>,
    ): Long {
        val entryId = insertLogEntry(entry)
        if (wifi.isNotEmpty()) insertWifi(wifi.map { it.copy(entryId = entryId) })
        if (ble.isNotEmpty()) insertBle(ble.map { it.copy(entryId = entryId) })
        return entryId
    }

    // ---- Reads (nested views for future UI / export phases) -----------

    @Transaction
    @Query("SELECT * FROM log_entries WHERE sessionId = :sessionId ORDER BY timestampMs")
    abstract fun observeEntriesWithSignals(sessionId: Long): Flow<List<LogEntryWithSignals>>

    /**
     * One-shot snapshot of every scan window (with its Wi-Fi + BLE children) for
     * a session, ordered chronologically. Used by the CSV exporter; the @Transaction
     * guarantees the parent/child reads are internally consistent.
     */
    @Transaction
    @Query("SELECT * FROM log_entries WHERE sessionId = :sessionId ORDER BY timestampMs")
    abstract suspend fun getEntriesWithSignals(sessionId: Long): List<LogEntryWithSignals>

    @Transaction
    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId")
    abstract suspend fun getSessionWithEntries(sessionId: Long): SessionWithEntries?

    @Query("SELECT COUNT(*) FROM log_entries WHERE sessionId = :sessionId")
    abstract suspend fun countEntries(sessionId: Long): Int

    /**
     * Lightweight GPS-only projection for the offline map (Phase 6): just
     * lat/lon/timestamp per scan window, chronologically ordered. Projects three
     * columns instead of the full entity + its Wi-Fi/BLE subtree, so drawing a
     * track stays cheap even for long sessions.
     */
    @Query(
        "SELECT latitude, longitude, timestampMs FROM log_entries " +
            "WHERE sessionId = :sessionId ORDER BY timestampMs"
    )
    abstract suspend fun getSessionCoordinates(sessionId: Long): List<CoordinatePair>

    /** Most recently started session id, or null if no sessions exist yet. */
    @Query("SELECT sessionId FROM sessions ORDER BY startTime DESC LIMIT 1")
    abstract suspend fun getLatestSessionId(): Long?
}
