package com.arawn.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migration objects for ArawnDatabase.
 *
 * Each migration's SQL must exactly match what Room would generate for the
 * entity diff between those two versions. SQLite only supports ADD COLUMN
 * for ALTER TABLE, so new columns must be nullable or have a default — all
 * columns added here are nullable, which satisfies that constraint.
 */

/** v1 → v2: offline OUI vendor name added to both signal tables. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE wifi_access_points ADD COLUMN vendorName TEXT")
        db.execSQL("ALTER TABLE ble_devices ADD COLUMN vendorName TEXT")
    }
}

/**
 * v2 → v3: heuristic device classification columns added to both signal
 * tables (deviceClass, classConfidence, classStatus, classBreakdown).
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE wifi_access_points ADD COLUMN deviceClass TEXT")
        db.execSQL("ALTER TABLE wifi_access_points ADD COLUMN classConfidence INTEGER")
        db.execSQL("ALTER TABLE wifi_access_points ADD COLUMN classStatus TEXT")
        db.execSQL("ALTER TABLE wifi_access_points ADD COLUMN classBreakdown TEXT")
        db.execSQL("ALTER TABLE ble_devices ADD COLUMN deviceClass TEXT")
        db.execSQL("ALTER TABLE ble_devices ADD COLUMN classConfidence INTEGER")
        db.execSQL("ALTER TABLE ble_devices ADD COLUMN classStatus TEXT")
        db.execSQL("ALTER TABLE ble_devices ADD COLUMN classBreakdown TEXT")
    }
}

/**
 * v3 → v4: full platform spine expansion.
 *
 * Creates 13 new tables for the Field Operations Platform (vault, missions, geo
 * primitives, polymorphic attachments, reports, documents). Also adds missionId to
 * sessions (soft FK — no REFERENCES clause possible via ALTER TABLE in SQLite; the
 * relationship is enforced in the repository layer) and creates an index on it.
 *
 * Table creation order respects FK dependencies (parents before children).
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {

        // vault_entries — no FK deps; must be first so MediaAsset/Report/Document can ref it
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `vault_entries` (
                `vaultEntryId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `kind` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `encryptedBlobUri` TEXT NOT NULL,
                `keyId` TEXT NOT NULL,
                `sizeBytes` INTEGER NOT NULL,
                `sha256` TEXT NOT NULL,
                `tags` TEXT,
                `addedMs` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_entries_kind` ON `vault_entries` (`kind`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_entries_sha256` ON `vault_entries` (`sha256`)")

        // missions
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `missions` (
                `missionId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `description` TEXT,
                `status` TEXT NOT NULL,
                `color` INTEGER,
                `createdMs` INTEGER NOT NULL,
                `updatedMs` INTEGER NOT NULL,
                `archived` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_missions_status` ON `missions` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_missions_archived` ON `missions` (`archived`)")

        // mission_items → missions CASCADE
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `mission_items` (
                `itemId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `missionId` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `done` INTEGER NOT NULL,
                `orderIndex` INTEGER NOT NULL,
                `notes` TEXT,
                `createdMs` INTEGER NOT NULL,
                FOREIGN KEY(`missionId`) REFERENCES `missions`(`missionId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mission_items_missionId` ON `mission_items` (`missionId`)")

        // waypoints → missions SET NULL
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `waypoints` (
                `waypointId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `missionId` INTEGER,
                `name` TEXT NOT NULL,
                `latitude` REAL NOT NULL,
                `longitude` REAL NOT NULL,
                `altitudeM` REAL,
                `type` TEXT NOT NULL,
                `icon` TEXT,
                `notes` TEXT,
                `createdMs` INTEGER NOT NULL,
                FOREIGN KEY(`missionId`) REFERENCES `missions`(`missionId`) ON UPDATE NO ACTION ON DELETE SET NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_waypoints_missionId` ON `waypoints` (`missionId`)")

        // routes → missions SET NULL
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `routes` (
                `routeId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `missionId` INTEGER,
                `name` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `color` INTEGER,
                `createdMs` INTEGER NOT NULL,
                FOREIGN KEY(`missionId`) REFERENCES `missions`(`missionId`) ON UPDATE NO ACTION ON DELETE SET NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_routes_missionId` ON `routes` (`missionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_routes_type` ON `routes` (`type`)")

        // route_points → routes CASCADE
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `route_points` (
                `pointId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `routeId` INTEGER NOT NULL,
                `seq` INTEGER NOT NULL,
                `latitude` REAL NOT NULL,
                `longitude` REAL NOT NULL,
                `altitudeM` REAL,
                `timestampMs` INTEGER,
                FOREIGN KEY(`routeId`) REFERENCES `routes`(`routeId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_route_points_routeId` ON `route_points` (`routeId`)")

        // area_overlays → missions SET NULL
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `area_overlays` (
                `areaId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `missionId` INTEGER,
                `name` TEXT NOT NULL,
                `geoJson` TEXT NOT NULL,
                `fillColor` INTEGER,
                `strokeColor` INTEGER,
                `createdMs` INTEGER NOT NULL,
                FOREIGN KEY(`missionId`) REFERENCES `missions`(`missionId`) ON UPDATE NO ACTION ON DELETE SET NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_area_overlays_missionId` ON `area_overlays` (`missionId`)")

        // notes — no FK by design (polymorphic)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `notes` (
                `noteId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `ownerType` TEXT NOT NULL,
                `ownerId` INTEGER NOT NULL,
                `body` TEXT NOT NULL,
                `createdMs` INTEGER NOT NULL,
                `updatedMs` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_ownerType_ownerId` ON `notes` (`ownerType`, `ownerId`)")

        // media_assets → vault_entries CASCADE
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `media_assets` (
                `mediaId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `ownerType` TEXT NOT NULL,
                `ownerId` INTEGER NOT NULL,
                `kind` TEXT NOT NULL,
                `vaultEntryId` INTEGER NOT NULL,
                `thumbVaultEntryId` INTEGER,
                `exifLat` REAL,
                `exifLon` REAL,
                `capturedMs` INTEGER,
                `addedMs` INTEGER NOT NULL,
                FOREIGN KEY(`vaultEntryId`) REFERENCES `vault_entries`(`vaultEntryId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_assets_ownerType_ownerId` ON `media_assets` (`ownerType`, `ownerId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_assets_vaultEntryId` ON `media_assets` (`vaultEntryId`)")

        // reports → missions SET NULL, vault_entries CASCADE
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `reports` (
                `reportId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `missionId` INTEGER,
                `title` TEXT NOT NULL,
                `generatedMs` INTEGER NOT NULL,
                `format` TEXT NOT NULL,
                `vaultEntryId` INTEGER NOT NULL,
                FOREIGN KEY(`missionId`) REFERENCES `missions`(`missionId`) ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(`vaultEntryId`) REFERENCES `vault_entries`(`vaultEntryId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reports_missionId` ON `reports` (`missionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reports_vaultEntryId` ON `reports` (`vaultEntryId`)")

        // report_sessions → reports CASCADE
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `report_sessions` (
                `reportId` INTEGER NOT NULL,
                `sessionId` INTEGER NOT NULL,
                PRIMARY KEY(`reportId`, `sessionId`),
                FOREIGN KEY(`reportId`) REFERENCES `reports`(`reportId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_report_sessions_reportId` ON `report_sessions` (`reportId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_report_sessions_sessionId` ON `report_sessions` (`sessionId`)")

        // report_routes → reports CASCADE, routes CASCADE
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `report_routes` (
                `reportId` INTEGER NOT NULL,
                `routeId` INTEGER NOT NULL,
                PRIMARY KEY(`reportId`, `routeId`),
                FOREIGN KEY(`reportId`) REFERENCES `reports`(`reportId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`routeId`) REFERENCES `routes`(`routeId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_report_routes_reportId` ON `report_routes` (`reportId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_report_routes_routeId` ON `report_routes` (`routeId`)")

        // documents → vault_entries CASCADE
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `documents` (
                `documentId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `category` TEXT,
                `tags` TEXT,
                `vaultEntryId` INTEGER NOT NULL,
                `pageCount` INTEGER,
                `addedMs` INTEGER NOT NULL,
                FOREIGN KEY(`vaultEntryId`) REFERENCES `vault_entries`(`vaultEntryId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_documents_category` ON `documents` (`category`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_documents_vaultEntryId` ON `documents` (`vaultEntryId`)")

        // sessions: add soft missionId column + index
        db.execSQL("ALTER TABLE sessions ADD COLUMN missionId INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_missionId` ON `sessions` (`missionId`)")
    }
}
