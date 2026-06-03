package com.arawn.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.arawn.core.database.entities.AreaOverlayEntity
import com.arawn.core.database.entities.DocumentEntity
import com.arawn.core.database.entities.MediaAssetEntity
import com.arawn.core.database.entities.MissionEntity
import com.arawn.core.database.entities.MissionGeo
import com.arawn.core.database.entities.MissionItemEntity
import com.arawn.core.database.entities.MissionWithItems
import com.arawn.core.database.entities.NoteEntity
import com.arawn.core.database.entities.OwnerType
import com.arawn.core.database.entities.ReportEntity
import com.arawn.core.database.entities.ReportRouteEntity
import com.arawn.core.database.entities.ReportSessionEntity
import com.arawn.core.database.entities.RouteEntity
import com.arawn.core.database.entities.RoutePointEntity
import com.arawn.core.database.entities.RouteWithPoints
import com.arawn.core.database.entities.VaultEntryEntity
import com.arawn.core.database.entities.WaypointEntity
import kotlinx.coroutines.flow.Flow

/**
 * ARAWN platform DAO structure (DRAFT, review-only). See docs/PLATFORM_ARCHITECTURE.md §5.
 *
 * Design choices to confirm in review:
 *  - One DAO per aggregate (not one giant DAO) — mirrors module boundaries, keeps each
 *    interface small. The existing WirelessDao (recon) stays as-is alongside these.
 *  - Reads return Flow for anything a screen observes; one-shot suspend for export/report.
 *  - Writes are suspend; multi-entity operations are @Transaction.
 *  - Polymorphic Note/MediaAsset have NO DB-level FK, so deleting a parent must also
 *    delete its notes/media — done in repository transactions, NOT cascade. The
 *    cascade-by-owner helpers below exist for exactly that.
 */

// ---------------------------------------------------------------------------
//  MISSIONS  (+ items)
// ---------------------------------------------------------------------------

@Dao
interface MissionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMission(mission: MissionEntity): Long

    @Update
    suspend fun updateMission(mission: MissionEntity)

    /** CASCADE removes mission_items; SET_NULL detaches waypoints/routes/areas/reports.
     *  Polymorphic notes/media for this mission are purged separately by the repository. */
    @Delete
    suspend fun deleteMission(mission: MissionEntity)

    @Query("SELECT * FROM missions WHERE archived = 0 ORDER BY updatedMs DESC")
    fun observeActiveMissions(): Flow<List<MissionEntity>>

    @Query("SELECT * FROM missions WHERE missionId = :id")
    fun observeMission(id: Long): Flow<MissionEntity?>

    @Transaction
    @Query("SELECT * FROM missions WHERE missionId = :id")
    fun observeMissionWithItems(id: Long): Flow<MissionWithItems?>

    @Transaction
    @Query("SELECT * FROM missions WHERE missionId = :id")
    suspend fun getMissionGeo(id: Long): MissionGeo?

    // ---- items ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(item: MissionItemEntity): Long

    @Update
    suspend fun updateItem(item: MissionItemEntity)

    @Delete
    suspend fun deleteItem(item: MissionItemEntity)

    @Query("SELECT * FROM mission_items WHERE missionId = :missionId ORDER BY orderIndex ASC")
    fun observeItems(missionId: Long): Flow<List<MissionItemEntity>>
}

// ---------------------------------------------------------------------------
//  GEO  (waypoints, routes + points, areas) — shared by Ops map + Missions + Recon
// ---------------------------------------------------------------------------

@Dao
interface GeoDao {

    // ---- waypoints ----
    @Insert suspend fun insertWaypoint(w: WaypointEntity): Long
    @Update suspend fun updateWaypoint(w: WaypointEntity)
    @Delete suspend fun deleteWaypoint(w: WaypointEntity)

    @Query("SELECT * FROM waypoints WHERE missionId = :missionId")
    fun observeWaypointsForMission(missionId: Long): Flow<List<WaypointEntity>>

    /** Global (non-mission) markers for the Ops map base layer. */
    @Query("SELECT * FROM waypoints WHERE missionId IS NULL")
    fun observeGlobalWaypoints(): Flow<List<WaypointEntity>>

    // ---- routes ----
    @Insert suspend fun insertRoute(r: RouteEntity): Long
    @Update suspend fun updateRoute(r: RouteEntity)
    @Delete suspend fun deleteRoute(r: RouteEntity) // CASCADE drops its route_points

    @Insert suspend fun insertRoutePoint(p: RoutePointEntity): Long
    @Insert suspend fun insertRoutePoints(points: List<RoutePointEntity>)

    /** Append one point to a recorded track (live GPS capture path). */
    @Transaction
    suspend fun appendTrackPoint(p: RoutePointEntity) = insertRoutePoint(p)

    @Transaction
    @Query("SELECT * FROM routes WHERE routeId = :routeId")
    suspend fun getRouteWithPoints(routeId: Long): RouteWithPoints?

    @Query("SELECT * FROM route_points WHERE routeId = :routeId ORDER BY seq ASC")
    fun observeRoutePoints(routeId: Long): Flow<List<RoutePointEntity>>

    @Query("SELECT * FROM routes WHERE missionId = :missionId")
    fun observeRoutesForMission(missionId: Long): Flow<List<RouteEntity>>

    // ---- areas ----
    @Insert suspend fun insertArea(a: AreaOverlayEntity): Long
    @Update suspend fun updateArea(a: AreaOverlayEntity)
    @Delete suspend fun deleteArea(a: AreaOverlayEntity)

    @Query("SELECT * FROM area_overlays WHERE missionId = :missionId")
    fun observeAreasForMission(missionId: Long): Flow<List<AreaOverlayEntity>>
}

// ---------------------------------------------------------------------------
//  POLYMORPHIC ATTACHMENTS  (notes + media)
// ---------------------------------------------------------------------------

@Dao
interface AttachmentDao {

    // ---- notes ----
    @Insert suspend fun insertNote(n: NoteEntity): Long
    @Update suspend fun updateNote(n: NoteEntity)
    @Delete suspend fun deleteNote(n: NoteEntity)

    @Query("SELECT * FROM notes WHERE ownerType = :type AND ownerId = :id ORDER BY createdMs DESC")
    fun observeNotes(type: OwnerType, id: Long): Flow<List<NoteEntity>>

    /** Repository-driven cascade: purge a deleted parent's notes (no DB FK exists). */
    @Query("DELETE FROM notes WHERE ownerType = :type AND ownerId = :id")
    suspend fun deleteNotesForOwner(type: OwnerType, id: Long)

    // ---- media ----
    @Insert suspend fun insertMedia(m: MediaAssetEntity): Long
    @Delete suspend fun deleteMedia(m: MediaAssetEntity)

    @Query("SELECT * FROM media_assets WHERE ownerType = :type AND ownerId = :id ORDER BY addedMs DESC")
    fun observeMedia(type: OwnerType, id: Long): Flow<List<MediaAssetEntity>>

    /** Geotagged media for placing photo pins on the map (no decryption needed). */
    @Query("SELECT * FROM media_assets WHERE exifLat IS NOT NULL AND exifLon IS NOT NULL")
    fun observeGeotaggedMedia(): Flow<List<MediaAssetEntity>>

    /**
     * Returns rows so the repository can crypto-shred each referenced vault entry
     * (read vaultEntryId/thumbVaultEntryId → VaultRepository.shred) before the parent
     * delete. Deleting the vault entry CASCADE-removes the media_assets row.
     */
    @Query("SELECT * FROM media_assets WHERE ownerType = :type AND ownerId = :id")
    suspend fun getMediaForOwner(type: OwnerType, id: Long): List<MediaAssetEntity>
}

// ---------------------------------------------------------------------------
//  REPORTS / DOCUMENTS / VAULT INDEX
// ---------------------------------------------------------------------------

@Dao
interface ReportDao {
    @Insert suspend fun insertReport(r: ReportEntity): Long
    @Delete suspend fun deleteReport(r: ReportEntity)

    @Query("SELECT * FROM reports ORDER BY generatedMs DESC")
    fun observeReports(): Flow<List<ReportEntity>>

    @Query("SELECT * FROM reports WHERE missionId = :missionId ORDER BY generatedMs DESC")
    fun observeReportsForMission(missionId: Long): Flow<List<ReportEntity>>

    // ---- normalized source links (DECISION: join tables, not CSV) ----
    @Insert suspend fun linkSessions(rows: List<ReportSessionEntity>)
    @Insert suspend fun linkRoutes(rows: List<ReportRouteEntity>)

    @Query("SELECT sessionId FROM report_sessions WHERE reportId = :reportId")
    suspend fun getSessionIdsForReport(reportId: Long): List<Long>

    @Query("SELECT routeId FROM report_routes WHERE reportId = :reportId")
    suspend fun getRouteIdsForReport(reportId: Long): List<Long>

    /** Persist a report + its source links atomically (ReportRepository entry point). */
    @Transaction
    suspend fun insertReportWithSources(
        report: ReportEntity,
        sessionIds: List<Long>,
        routeIds: List<Long>,
    ): Long {
        val id = insertReport(report)
        if (sessionIds.isNotEmpty()) linkSessions(sessionIds.map { ReportSessionEntity(id, it) })
        if (routeIds.isNotEmpty()) linkRoutes(routeIds.map { ReportRouteEntity(id, it) })
        return id
    }
}

@Dao
interface DocumentDao {
    @Insert suspend fun insertDocument(d: DocumentEntity): Long
    @Update suspend fun updateDocument(d: DocumentEntity)
    @Delete suspend fun deleteDocument(d: DocumentEntity)

    @Query("SELECT * FROM documents ORDER BY addedMs DESC")
    fun observeDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE category = :category ORDER BY title ASC")
    fun observeByCategory(category: String): Flow<List<DocumentEntity>>

    // REVIEW: tag/title search — LIKE for v1; consider an FTS4/FTS5 virtual table if
    // the library grows large enough that LIKE scans get slow.
    @Query("SELECT * FROM documents WHERE title LIKE '%' || :q || '%' OR tags LIKE '%' || :q || '%'")
    fun search(q: String): Flow<List<DocumentEntity>>
}

@Dao
interface VaultDao {
    @Insert suspend fun insertEntry(e: VaultEntryEntity): Long
    @Delete suspend fun deleteEntry(e: VaultEntryEntity)

    @Query("SELECT * FROM vault_entries ORDER BY addedMs DESC")
    fun observeEntries(): Flow<List<VaultEntryEntity>>

    @Query("SELECT * FROM vault_entries WHERE vaultEntryId = :id")
    suspend fun getEntry(id: Long): VaultEntryEntity?

    /**
     * Crypto-shred flow lives in VaultRepository: look up the entry, destroy its keyId's
     * key material + delete the ciphertext file, then delete this row. FK CASCADE on
     * media_assets/documents/reports removes whatever referenced it.
     * (getAllKeyIds() would only be needed by the DEFERRED panic-wipe spike — omitted for v1.)
     */
}

/*
 * REVIEW — recon integration (in the EXISTING WirelessDao, not duplicated here):
 *   - SessionEntity gains nullable missionId; add:
 *       @Query("UPDATE sessions SET missionId = :missionId WHERE sessionId = :sessionId")
 *       suspend fun tagSessionToMission(sessionId: Long, missionId: Long?)
 *       @Query("SELECT * FROM sessions WHERE missionId = :missionId ORDER BY startTime DESC")
 *       fun observeSessionsForMission(missionId: Long): Flow<List<SessionEntity>>
 *   - getSessionCoordinates(sessionId) already exists (Phase 6) and feeds the shared
 *     map overlay; a recorded route can be materialized from it on demand.
 */
