# Schema Draft — REVIEW ONLY (not compiled)

These `.kt` files are a **draft of the proposed unified database spine** for the Field
Operations Platform (see `../PLATFORM_ARCHITECTURE.md` §5). They live under `docs/`
**deliberately outside any Gradle source set** so they are *not* part of the build and
do *not* affect the current app.

**Purpose:** review the data model — entities, relationships, DAO structure — before
Phase A implementation begins. Nothing here is wired in yet.

When approved, these move to `:core:database/.../entities` and `.../dao`, the existing
`db/ScanEntities.kt` subtree is preserved (the recon hierarchy is untouched except for
one additive nullable `missionId` column on `SessionEntity`), and real Room `Migration`
objects + `exportSchema = true` replace `fallbackToDestructiveMigration()`.

Files:
- `SpineEntities.kt` — new platform entities, enums, and read-side relation POJOs.
- `SpineDaos.kt` — proposed DAO interfaces (one per aggregate).

**Locked data decisions reflected here (2026-06-01):**
- Report ↔ sessions/routes use **normalized join tables** (`report_sessions`, `report_routes`), not CSV fields.
- **VaultEntry is the single source of truth for every encrypted blob + key.** `MediaAsset`, `Document`, and `Report` reference a vault entry via `vaultEntryId` (FK CASCADE) instead of carrying their own file path / hash / key.
- Document search is **LIKE-based** for v1 (FTS deferred).
- Note/MediaAsset ownership is **polymorphic** (`ownerType` + `ownerId`, no DB FK); integrity + cascade enforced in the repository layer.
