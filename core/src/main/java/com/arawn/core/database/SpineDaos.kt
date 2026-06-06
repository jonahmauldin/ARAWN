package com.arawn.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * ARAWN platform DAO structure — one interface per aggregate.
 *
 * Design:
 *  - One DAO per aggregate, mirrors module boundaries.
 *  - Reads return Flow for anything a screen observes; one-shot suspend for export.
 *  - Writes are suspend; multi-entity operations are @Transaction.
 *  - Polymorphic Note/MediaAsset have NO DB-level FK, so deleting a parent must also
 *    delete its notes/media — done in repository transactions, NOT cascade. The
 *    cascade-by-owner helpers exist for exactly that.
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

    /** Soft-archived missions, newest-updated first — drives the ARCHIVED tab. */
    @Query("SELECT * FROM missions WHERE archived = 1 ORDER BY updatedMs DESC")
    fun observeArchivedMissions(): Flow<List<MissionEntity>>

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
//  GEO  (waypoints, routes + points, areas)
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

    /** All waypoints regardless of mission — used by the Ops map overlay. */
    @Query("SELECT * FROM waypoints ORDER BY createdMs DESC")
    fun observeAllWaypoints(): Flow<List<WaypointEntity>>

    /** All routes with their points — used by the Ops map overlay. */
    @Transaction
    @Query("SELECT * FROM routes ORDER BY createdMs DESC")
    fun observeAllRoutesWithPoints(): Flow<List<RouteWithPoints>>

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

    /** Mission routes with their ordered points — used by the Mission planner map. */
    @Transaction
    @Query("SELECT * FROM routes WHERE missionId = :missionId ORDER BY createdMs DESC")
    fun observeRoutesWithPointsForMission(missionId: Long): Flow<List<RouteWithPoints>>

    // ---- areas ----
    @Insert suspend fun insertArea(a: AreaOverlayEntity): Long
    @Update suspend fun updateArea(a: AreaOverlayEntity)
    @Delete suspend fun deleteArea(a: AreaOverlayEntity)

    @Query("SELECT * FROM area_overlays WHERE missionId = :missionId")
    fun observeAreasForMission(missionId: Long): Flow<List<AreaOverlayEntity>>

    /** All zones/areas regardless of mission — used by the Ops map overlay + object manager. */
    @Query("SELECT * FROM area_overlays ORDER BY createdMs DESC")
    fun observeAllAreas(): Flow<List<AreaOverlayEntity>>
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

    // ---- normalized source links ----
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

    /** Drop the index row when its plaintext file is deleted from MediaStore. */
    @Query("DELETE FROM reports WHERE filePath = :filePath")
    suspend fun deleteByFilePath(filePath: String)
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

    // LIKE for v1; consider FTS4/FTS5 if library grows large.
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
}
