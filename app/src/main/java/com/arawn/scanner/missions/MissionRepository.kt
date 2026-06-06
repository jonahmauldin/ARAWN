package com.arawn.scanner.missions

import com.arawn.core.database.AttachmentDao
import com.arawn.core.database.GeoDao
import com.arawn.core.database.MissionDao
import com.arawn.core.database.MissionEntity
import com.arawn.core.database.OwnerType
import com.arawn.core.database.VaultDao
import com.arawn.core.database.WirelessDao
import com.arawn.scanner.vault.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin cross-source repository for mission deletion.
 *
 * Hard-deleting a mission spans several tables that Room's foreign keys do NOT
 * fully cover, so the orchestration lives here rather than in a single DAO:
 *
 *  - `mission_items`         — CASCADE-deleted by the `missions` FK (automatic).
 *  - waypoints/routes/areas  — `missionId` FK is `SET_NULL`, so by default they
 *                              survive as GLOBAL objects (the "detach" path).
 *  - `reports`               — `missionId` FK is `SET_NULL`; reports are always
 *                              preserved (valuable output), just unlinked.
 *  - `sessions`              — `missionId` is a SOFT reference (no DB FK); must be
 *                              cleared explicitly or they point at a ghost mission.
 *  - notes / media_assets    — polymorphic `(ownerType, ownerId)`, NO DB FK; must
 *                              be purged in code. Media additionally owns encrypted
 *                              vault blobs that need a crypto-shred.
 *
 * Two delete modes are offered to the operator ("ask each time"):
 *  - [deleteDetachObjects]   keeps the mission's waypoints/routes/zones as global.
 *  - [deleteWithObjects]     also deletes those geo objects (and their attachments).
 *
 * Recon sessions and reports are NEVER destroyed by either path — only detached.
 */
class MissionRepository(
    private val missionDao: MissionDao,
    private val geoDao: GeoDao,
    private val wirelessDao: WirelessDao,
    private val attachmentDao: AttachmentDao,
    private val vaultDao: VaultDao,
    private val vaultRepository: VaultRepository,
) {

    /**
     * Delete the mission but keep its waypoints/routes/zones — they become global
     * objects (the `SET_NULL` FK handles the detach as the row is removed). Linked
     * sessions and reports are detached too; the mission's own notes/media are purged.
     */
    suspend fun deleteDetachObjects(mission: MissionEntity) = withContext(Dispatchers.IO) {
        wirelessDao.clearMissionFromSessions(mission.missionId)
        purgeAttachments(OwnerType.MISSION, mission.missionId)
        // CASCADE removes mission_items; SET_NULL detaches waypoints/routes/areas/reports.
        missionDao.deleteMission(mission)
    }

    /**
     * Delete the mission AND every geo object it owns (waypoints, routes + their
     * points, zones), purging each object's attachments first. Sessions and reports
     * are still only detached, never deleted.
     */
    suspend fun deleteWithObjects(mission: MissionEntity) = withContext(Dispatchers.IO) {
        // Snapshot the geo children while they are still linked to the mission.
        val geo = missionDao.getMissionGeo(mission.missionId)
        geo?.waypoints?.forEach { wp ->
            purgeAttachments(OwnerType.WAYPOINT, wp.waypointId)
            geoDao.deleteWaypoint(wp)
        }
        geo?.routes?.forEach { route ->
            purgeAttachments(OwnerType.ROUTE, route.routeId)
            geoDao.deleteRoute(route) // CASCADE drops route_points
        }
        geo?.areas?.forEach { area ->
            purgeAttachments(OwnerType.AREA, area.areaId)
            geoDao.deleteArea(area)
        }

        wirelessDao.clearMissionFromSessions(mission.missionId)
        purgeAttachments(OwnerType.MISSION, mission.missionId)
        // Geo is already gone, so the SET_NULL FK has nothing left to detach.
        missionDao.deleteMission(mission)
    }

    /**
     * Remove the polymorphic notes + media attached to one owner. Media blobs are
     * crypto-shredded through the vault (delete ciphertext + key + row); shredding a
     * media row's primary vault entry CASCADE-removes the media_assets row itself.
     * No-op for owners with no attachments (the dormant common case today).
     */
    private suspend fun purgeAttachments(type: OwnerType, ownerId: Long) {
        attachmentDao.getMediaForOwner(type, ownerId).forEach { media ->
            // Thumbnail blob has no FK back to media_assets, so shred it separately.
            media.thumbVaultEntryId?.let { thumbId ->
                vaultDao.getEntry(thumbId)?.let { vaultRepository.shred(it) }
            }
            vaultDao.getEntry(media.vaultEntryId)?.let { vaultRepository.shred(it) }
        }
        attachmentDao.deleteNotesForOwner(type, ownerId)
    }
}
