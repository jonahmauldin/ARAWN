package com.arawn.core.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.TypeConverter

/**
 * ARAWN Field Operations Platform — UNIFIED DATABASE SPINE.
 *
 * New platform entities that sit alongside the existing, untouched recon hierarchy:
 *   SessionEntity (1) ──< LogEntryEntity (1) ──< WifiApEntity / BleDeviceEntity
 *
 * The only change to the recon subtree is one additive nullable column:
 *   SessionEntity.missionId: Long?   (soft FK — no DB-level constraint due to SQLite
 *   ALTER TABLE limitations; integrity enforced in repository layer)
 *
 * Conventions (match existing ScanEntities.kt):
 *  - data class @Entity, snake_case tableName, autoGenerate Long PK.
 *  - epoch-millis Longs named *Ms.
 *  - real FKs with explicit onDelete; index every FK column.
 *  - enums persisted as their .name String via SpineConverters (registered on ArawnDatabase).
 *
 * Polymorphism: Note and MediaAsset attach to several parents via (ownerType, ownerId).
 * There is intentionally NO ForeignKey on those two tables — referential integrity is
 * enforced in the repository layer (cascade-on-parent-delete in code/transactions).
 */

// ===========================================================================
//  ENUMS  (persisted as String via SpineConverters at the bottom of this file)
// ===========================================================================

enum class MissionStatus { PLANNED, ACTIVE, COMPLETE, ARCHIVED }

enum class MissionItemType { OBJECTIVE, CHECKLIST }

enum class WaypointType { GENERIC, PARKING, ENTRY, OBSERVATION, EXIT, HAZARD, CACHE, POI }

/** A planned route is drawn by the user; a recorded route is a captured GPS track. */
enum class RouteType { PLANNED, RECORDED }

/** What a polymorphic Note / MediaAsset is attached to. */
enum class OwnerType { MISSION, WAYPOINT, ROUTE, AREA, RECON_SESSION }

enum class MediaKind { PHOTO, AUDIO, VIDEO, FILE }

enum class ReportFormat { HTML, PDF }

enum class VaultEntryKind { REPORT, MEDIA, DOCUMENT, NOTE_EXPORT, OTHER }

// ===========================================================================
//  VAULT  (single source of truth for all encrypted blobs — defined first,
//          because MediaAsset, Document, and Report FK into it)
// ===========================================================================

/**
 * The SINGLE source of truth for every encrypted blob.
 * MediaAsset / Document / Report reference a row here via vaultEntryId rather than
 * tracking their own file path, hash, or key. This gives one place to manage
 * encryption and one place to crypto-shred: destroy [keyId]'s key material, delete
 * the ciphertext at [encryptedBlobUri], delete this row, and FK CASCADE removes the
 * typed row that referenced it.
 */
@Entity(
    tableName = "vault_entries",
    indices = [Index("kind"), Index("sha256")],
)
data class VaultEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val vaultEntryId: Long = 0,
    val kind: VaultEntryKind,
    val displayName: String,
    /** Ciphertext location in app-internal storage. */
    val encryptedBlobUri: String,
    /** Per-file key reference for targeted crypto-shred. */
    val keyId: String,
    val sizeBytes: Long,
    /** Plaintext SHA-256 (computed before encryption) for dedupe/integrity. */
    val sha256: String,
    val tags: String? = null,
    val addedMs: Long,
)

// ===========================================================================
//  MISSION  (top of the planning hierarchy)
// ===========================================================================

@Entity(
    tableName = "missions",
    indices = [Index("status"), Index("archived")],
)
data class MissionEntity(
    @PrimaryKey(autoGenerate = true)
    val missionId: Long = 0,
    val name: String,
    val description: String? = null,
    val status: MissionStatus = MissionStatus.PLANNED,
    /** ARGB int for map/list color coding. */
    val color: Int? = null,
    val createdMs: Long,
    val updatedMs: Long,
    /** Soft-archive flag (kept for reports/history; hidden from active list). */
    val archived: Boolean = false,
)

/**
 * Objectives + checklist items for a mission. One table, discriminated by [type],
 * because they share ordering/done/notes and render in the same timeline.
 */
@Entity(
    tableName = "mission_items",
    foreignKeys = [
        ForeignKey(
            entity = MissionEntity::class,
            parentColumns = ["missionId"],
            childColumns = ["missionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("missionId")],
)
data class MissionItemEntity(
    @PrimaryKey(autoGenerate = true)
    val itemId: Long = 0,
    val missionId: Long,
    val type: MissionItemType,
    val title: String,
    val done: Boolean = false,
    /** Manual sort position within the mission. */
    val orderIndex: Int = 0,
    val notes: String? = null,
    val createdMs: Long,
)

// ===========================================================================
//  GEO PRIMITIVES  (waypoints, routes, areas) — mission-scoped OR global
// ===========================================================================

@Entity(
    tableName = "waypoints",
    foreignKeys = [
        ForeignKey(
            entity = MissionEntity::class,
            parentColumns = ["missionId"],
            childColumns = ["missionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("missionId")],
)
data class WaypointEntity(
    @PrimaryKey(autoGenerate = true)
    val waypointId: Long = 0,
    /** Null = global/persistent marker not tied to a mission. */
    val missionId: Long? = null,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double? = null,
    val type: WaypointType = WaypointType.GENERIC,
    /** Optional icon key for the map renderer. */
    val icon: String? = null,
    val notes: String? = null,
    val createdMs: Long,
)

@Entity(
    tableName = "routes",
    foreignKeys = [
        ForeignKey(
            entity = MissionEntity::class,
            parentColumns = ["missionId"],
            childColumns = ["missionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("missionId"), Index("type")],
)
data class RouteEntity(
    @PrimaryKey(autoGenerate = true)
    val routeId: Long = 0,
    val missionId: Long? = null,
    val name: String,
    /** PLANNED (drawn) or RECORDED (captured GPS track). Same table, same renderer. */
    val type: RouteType,
    val color: Int? = null,
    val createdMs: Long,
)

@Entity(
    tableName = "route_points",
    foreignKeys = [
        ForeignKey(
            entity = RouteEntity::class,
            parentColumns = ["routeId"],
            childColumns = ["routeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("routeId")],
)
data class RoutePointEntity(
    @PrimaryKey(autoGenerate = true)
    val pointId: Long = 0,
    val routeId: Long,
    /** Order along the route (0-based). */
    val seq: Int,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double? = null,
    /** Null for PLANNED points; set for RECORDED track points. */
    val timestampMs: Long? = null,
)

/** Polygon/zone overlay, geometry stored as GeoJSON text. */
@Entity(
    tableName = "area_overlays",
    foreignKeys = [
        ForeignKey(
            entity = MissionEntity::class,
            parentColumns = ["missionId"],
            childColumns = ["missionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("missionId")],
)
data class AreaOverlayEntity(
    @PrimaryKey(autoGenerate = true)
    val areaId: Long = 0,
    val missionId: Long? = null,
    val name: String,
    /** GeoJSON Polygon/MultiPolygon string. */
    val geoJson: String,
    val fillColor: Int? = null,
    val strokeColor: Int? = null,
    val createdMs: Long,
)

// ===========================================================================
//  POLYMORPHIC ATTACHMENTS  (Note, MediaAsset) — NO FK by design
// ===========================================================================

@Entity(
    tableName = "notes",
    indices = [Index(value = ["ownerType", "ownerId"])],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true)
    val noteId: Long = 0,
    val ownerType: OwnerType,
    val ownerId: Long,
    val body: String,
    val createdMs: Long,
    val updatedMs: Long,
)

/**
 * A media file attached to any owner. The ciphertext + per-file key are owned by a
 * [VaultEntryEntity] (the single source of truth for all encrypted blobs); this row
 * references it via [vaultEntryId] and never carries its own file path / key / hash.
 * EXIF geotags ARE copied into columns so the map/report can place a photo WITHOUT
 * decrypting it.
 */
@Entity(
    tableName = "media_assets",
    foreignKeys = [
        ForeignKey(
            entity = VaultEntryEntity::class,
            parentColumns = ["vaultEntryId"],
            childColumns = ["vaultEntryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["ownerType", "ownerId"]), Index("vaultEntryId")],
)
data class MediaAssetEntity(
    @PrimaryKey(autoGenerate = true)
    val mediaId: Long = 0,
    val ownerType: OwnerType,
    val ownerId: Long,
    val kind: MediaKind,
    /** The encrypted blob (ciphertext + keyId + size + sha256) in vault_entries. */
    val vaultEntryId: Long,
    /** Optional separate vault entry for an encrypted thumbnail. */
    val thumbVaultEntryId: Long? = null,
    val exifLat: Double? = null,
    val exifLon: Double? = null,
    val capturedMs: Long? = null,
    val addedMs: Long,
)

// ===========================================================================
//  REPORTS / KNOWLEDGE BASE
// ===========================================================================

@Entity(
    tableName = "reports",
    foreignKeys = [
        ForeignKey(
            entity = MissionEntity::class,
            parentColumns = ["missionId"],
            childColumns = ["missionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = VaultEntryEntity::class,
            parentColumns = ["vaultEntryId"],
            childColumns = ["vaultEntryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("missionId"), Index("vaultEntryId")],
)
data class ReportEntity(
    @PrimaryKey(autoGenerate = true)
    val reportId: Long = 0,
    val missionId: Long? = null,
    val title: String,
    val generatedMs: Long,
    val format: ReportFormat,
    /** The generated file's encrypted blob in vault_entries. */
    val vaultEntryId: Long,
)

/** Which recon sessions a report drew from (normalized join table). */
@Entity(
    tableName = "report_sessions",
    primaryKeys = ["reportId", "sessionId"],
    foreignKeys = [
        ForeignKey(
            entity = ReportEntity::class,
            parentColumns = ["reportId"],
            childColumns = ["reportId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("reportId"), Index("sessionId")],
)
data class ReportSessionEntity(
    val reportId: Long,
    val sessionId: Long,
)

/** Which routes a report drew from (normalized join table). */
@Entity(
    tableName = "report_routes",
    primaryKeys = ["reportId", "routeId"],
    foreignKeys = [
        ForeignKey(
            entity = ReportEntity::class,
            parentColumns = ["reportId"],
            childColumns = ["reportId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RouteEntity::class,
            parentColumns = ["routeId"],
            childColumns = ["routeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("reportId"), Index("routeId")],
)
data class ReportRouteEntity(
    val reportId: Long,
    val routeId: Long,
)

/**
 * Local Knowledge Base entry. The bytes live in a [VaultEntryEntity]; this row holds
 * only library metadata. Search is LIKE-based for v1 (FTS deferred).
 */
@Entity(
    tableName = "documents",
    foreignKeys = [
        ForeignKey(
            entity = VaultEntryEntity::class,
            parentColumns = ["vaultEntryId"],
            childColumns = ["vaultEntryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("category"), Index("vaultEntryId")],
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true)
    val documentId: Long = 0,
    val title: String,
    /** Free-form category (e.g. "Manual", "SOP", "Field Guide"). */
    val category: String? = null,
    /** Comma-separated tags (LIKE-searched for v1). */
    val tags: String? = null,
    /** The encrypted file's blob in vault_entries. */
    val vaultEntryId: Long,
    val pageCount: Int? = null,
    val addedMs: Long,
)

// ===========================================================================
//  READ-SIDE RELATION POJOs
// ===========================================================================

/** A mission with its objectives/checklist. */
data class MissionWithItems(
    @Embedded val mission: MissionEntity,
    @Relation(parentColumn = "missionId", entityColumn = "missionId")
    val items: List<MissionItemEntity>,
)

/** A mission with all of its geo primitives — payload the Ops map needs. */
data class MissionGeo(
    @Embedded val mission: MissionEntity,
    @Relation(parentColumn = "missionId", entityColumn = "missionId")
    val waypoints: List<WaypointEntity>,
    @Relation(parentColumn = "missionId", entityColumn = "missionId")
    val areas: List<AreaOverlayEntity>,
    @Relation(parentColumn = "missionId", entityColumn = "missionId")
    val routes: List<RouteEntity>,
)

/** A route with its ordered points (DAO query should ORDER BY seq). */
data class RouteWithPoints(
    @Embedded val route: RouteEntity,
    @Relation(parentColumn = "routeId", entityColumn = "routeId")
    val points: List<RoutePointEntity>,
)

// ===========================================================================
//  TYPE CONVERTERS  (register via @TypeConverters(SpineConverters::class) on ArawnDatabase)
// ===========================================================================

/**
 * Enums persist as their .name String. Kept as text so the DB is human-readable and
 * forgiving across additions (append new enum values at the end; never reorder/rename
 * without a migration mapping).
 */
class SpineConverters {

    @TypeConverter fun fromMissionStatus(v: MissionStatus): String = v.name
    @TypeConverter fun toMissionStatus(v: String): MissionStatus = MissionStatus.valueOf(v)

    @TypeConverter fun fromMissionItemType(v: MissionItemType): String = v.name
    @TypeConverter fun toMissionItemType(v: String): MissionItemType = MissionItemType.valueOf(v)

    @TypeConverter fun fromWaypointType(v: WaypointType): String = v.name
    @TypeConverter fun toWaypointType(v: String): WaypointType = WaypointType.valueOf(v)

    @TypeConverter fun fromRouteType(v: RouteType): String = v.name
    @TypeConverter fun toRouteType(v: String): RouteType = RouteType.valueOf(v)

    @TypeConverter fun fromOwnerType(v: OwnerType): String = v.name
    @TypeConverter fun toOwnerType(v: String): OwnerType = OwnerType.valueOf(v)

    @TypeConverter fun fromMediaKind(v: MediaKind): String = v.name
    @TypeConverter fun toMediaKind(v: String): MediaKind = MediaKind.valueOf(v)

    @TypeConverter fun fromReportFormat(v: ReportFormat): String = v.name
    @TypeConverter fun toReportFormat(v: String): ReportFormat = ReportFormat.valueOf(v)

    @TypeConverter fun fromVaultEntryKind(v: VaultEntryKind): String = v.name
    @TypeConverter fun toVaultEntryKind(v: String): VaultEntryKind = VaultEntryKind.valueOf(v)
}
