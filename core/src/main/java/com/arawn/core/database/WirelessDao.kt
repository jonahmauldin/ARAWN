package com.arawn.core.database

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

    /** Single session entity, or null if not found. */
    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId")
    abstract suspend fun getSession(sessionId: Long): SessionEntity?

    /**
     * Per-BSSID Wi-Fi aggregate for one session — SQLite GROUP BY means one row
     * per unique BSSID regardless of how many GPS windows observed it.
     *
     * Use this instead of [getEntriesWithSignals] when building reports: for a
     * 40-minute scan the full entity graph can be hundreds of thousands of objects
     * and exceed the ART heap limit; this query returns at most a few hundred rows.
     *
     * Column aliases (bestRssi, lat, lon, firstMs, lastMs, seenCount) map
     * directly to [WifiAggregate] property names.
     */
    @Query("""
        SELECT
            w.bssid,
            w.ssid,
            MAX(w.rssiDbm)          AS bestRssi,
            w.frequencyMhz,
            w.capabilities,
            w.vendorName,
            w.deviceClass,
            MAX(w.classConfidence)  AS classConfidence,
            w.classStatus,
            e.latitude              AS lat,
            e.longitude             AS lon,
            MIN(e.timestampMs)      AS firstMs,
            MAX(e.timestampMs)      AS lastMs,
            COUNT(*)                AS seenCount
        FROM wifi_access_points w
        INNER JOIN log_entries e ON w.entryId = e.entryId
        WHERE e.sessionId = :sessionId
        GROUP BY w.bssid
    """)
    abstract suspend fun getWifiAggregates(sessionId: Long): List<WifiAggregate>

    /**
     * Per-MAC BLE aggregate for one session. Same memory rationale as
     * [getWifiAggregates]. [BleAggregate.name] uses MAX() so a non-null device
     * name is preferred over null when both appear across observations.
     */
    @Query("""
        SELECT
            b.macAddress,
            MAX(b.name)             AS name,
            MAX(b.rssiDbm)          AS bestRssi,
            b.vendorName,
            b.deviceClass,
            MAX(b.classConfidence)  AS classConfidence,
            b.classStatus,
            e.latitude              AS lat,
            e.longitude             AS lon,
            MIN(e.timestampMs)      AS firstMs,
            MAX(e.timestampMs)      AS lastMs,
            COUNT(*)                AS seenCount
        FROM ble_devices b
        INNER JOIN log_entries e ON b.entryId = e.entryId
        WHERE e.sessionId = :sessionId
        GROUP BY b.macAddress
    """)
    abstract suspend fun getBleAggregates(sessionId: Long): List<BleAggregate>

    // ---- Mission linking (Phase B) -------------------------------------------

    /** Attach or detach a session from a mission (pass null to clear). */
    @Query("UPDATE sessions SET missionId = :missionId WHERE sessionId = :sessionId")
    abstract suspend fun tagSessionToMission(sessionId: Long, missionId: Long?)

    /** Live list of sessions belonging to a mission, newest first. */
    @Query("SELECT * FROM sessions WHERE missionId = :missionId ORDER BY startTime DESC")
    abstract fun observeSessionsForMission(missionId: Long): Flow<List<SessionEntity>>

    /**
     * Detach every session linked to [missionId]. Used when a mission is deleted:
     * SessionEntity.missionId is a soft reference (no DB FK to SET_NULL it), so the
     * link must be cleared explicitly or sessions would point at a ghost mission.
     */
    @Query("UPDATE sessions SET missionId = NULL WHERE missionId = :missionId")
    abstract suspend fun clearMissionFromSessions(missionId: Long)
}
