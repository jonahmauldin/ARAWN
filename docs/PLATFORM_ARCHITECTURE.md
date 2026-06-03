# ARAWN — Field Operations Platform: Master Architecture

> **Status:** **LOCKED (simplified)** pending final Phase-A go/no-go. Simplification review applied 2026-06-01.
> **Last updated:** 2026-06-01
> **Scope:** Evolution of ARAWN from a passive Wi-Fi/BLE recon logger into a complete, offline-first Field Operations Platform for Android.

---

## 0. Purpose of This Document

ARAWN today is a single-purpose, **field-validated** reconnaissance logger (native Kotlin + Jetpack Compose, Room, osmdroid, offline OUI + heuristic device classification, WiGLE/enriched CSV + interactive HTML reporting). This document defines the target architecture for evolving it into a full Field Operations Platform **without rewriting the working recon core**.

The evolution is strictly **additive**: Recon becomes one first-class feature among several peers, all sitting on a shared data spine and a shared map. The explicit anti-goal is rewriting validated code.

This is the single source of architectural truth. **It has been through a deliberate simplification pass** (§13) to strip enterprise/scale scaffolding that doesn't serve a single-operator field tool. Future development follows it.

---

## 1. Project Principles (Non-Negotiable)

1. **Personal field operations platform, not a SaaS product.** One operator. No accounts, multi-tenancy, telemetry, or growth engineering.
2. **Offline-first operation is mandatory.** Every core capability works with no cellular and no internet. Online is opt-in convenience, never a dependency.
3. **No paid APIs, subscriptions, cloud dependencies, usage quotas, or trial-based services.** Buildable and maintainable forever at zero recurring cost.
4. **Local ownership of data.** All data, maps, keys, logs, media stay on-device. No mandatory cloud sync. Export is user-initiated and local.
5. **Recon stays first-class and foundational** — preserved and evolved, never demoted or removed.
6. **Simplicity and maintainability over enterprise-scale architecture.** The pragmatic solo-maintainer choice wins over theoretical purity. *(This principle drove the §13 simplification.)*
7. **Existing working recon functionality is preserved and evolved, not rewritten.**
8. **Passive, receive-only RF capture:** never inject packets, capture payloads, or perform handshakes. Stock Android APIs only, no root/monitor mode. (Wiretap Act compliance.)
9. **Open-source / permissively-licensed dependencies preferred. No vendor lock-in** (no Google Maps Platform, Firebase, AWS, proprietary mapping).

---

## 2. Overall Architecture

### 2.1 Pattern: Pragmatic Modular Monolith

A **3-module Gradle project**, single APK. Boundaries that matter are enforced by the module split; everything else is organized by **package**, not by Gradle module, to avoid build-config overhead a solo dev doesn't benefit from.

```
:app      ← UI shell + all platform features (as packages), nav host, DI container, theme
:core     ← shared infrastructure (as packages): database, crypto, map, location, common, ui
:recon    ← the field-validated scanner, kept ISOLATED + independently buildable
```

**Why `:recon` stays its own module:** it is the one validated, on-device-proven unit you want to refactor in isolation and never accidentally break while building new features. Everything else lives as packages under `:app` / `:core`.

**Package layout inside the modules:**

```
:core
  com.arawn.core.database   (entities, dao, migrations, db build + SQLCipher)
  com.arawn.core.crypto     (KeyManager, FileCipher, VaultKeyProvider)
  com.arawn.core.location   (fused GPS/NETWORK location flow)
  com.arawn.core.map        (osmdroid wrapper, TilePackManager, OverlayFactory)
  com.arawn.core.ui         (theme, operator components, shared map composables)
  com.arawn.core.common     (Result, dispatchers, MediaStoreWriter, GeoExport, logger)

:app
  com.arawn.app                 (Application, AppContainer, MainActivity, navigation)
  com.arawn.feature.operations  (map home dashboard)
  com.arawn.feature.missions    (planner: missions, waypoints, routes, areas, items)
  com.arawn.feature.reports     (report assembly + HTML/PDF render)
  com.arawn.feature.vault       (encrypted store UI + logic)
  com.arawn.feature.knowledge   (document library + PDF viewer)
  com.arawn.feature.terminal    (Termux bridge — DEFERRED, see §11)

:recon
  com.arawn.scanner.*           (existing scanner, oui, classify, recon ui, recon export)
```

> Extract additional Gradle modules **only if build times ever hurt** — not preemptively.

### 2.2 Layering (Lean)

**Compose screen → ViewModel → DAO, directly.** Room entities *are* the working model.

- **No domain-model layer, no entity↔domain mappers** for the new spine. (The existing recon `Mappers.kt` stays — it bridges the live `ScanPacket` transport type to storage, which is a genuine reason.)
- **A repository exists only where an operation spans sources** — e.g. vault (DAO + file crypto), reports (multiple DAOs + renderer), and cascade-deletes of polymorphic notes/media. Concretely: `VaultRepository`, `ReportRepository`, `MissionRepository`. Nothing else gets a repository.
- **Use cases** are plain functions/classes only where real logic lives (classification, crypto, report assembly) — not a mandated layer.

### 2.3 Dependency Injection: Manual Container

A hand-written **`AppContainer`** constructed in `ArawnApplication`, holding the ~5 singletons (DB, OUI table, crypto/key manager, location source, tile manager) and exposing factories for ViewModels. **No Hilt, no annotation processor for DI.** Zero codegen, trivial to debug, nothing to break on an AGP/Kotlin bump.

### 2.4 Concurrency & Reactive Model

**Kotlin Coroutines + Flow**, `Dispatchers.IO` for DB/file, structured concurrency tied to lifecycle/service scopes.

- The scanner's proven pattern — **SharedFlow to UI + drop-oldest Channel to a single IO DB-writer coroutine** — is the canonical model for long-running producers (recon, GPS track recording).
- UI observes `StateFlow` from ViewModels.
- **No WorkManager.** Heatmaps and report generation run in `viewModelScope` coroutines with a progress indicator. WorkManager is reserved for genuinely *unattended, must-survive-process-death* jobs — none exist yet; add it only when one does.

---

## 3. Technology Stack

| Concern | Choice | License | Rationale |
|---|---|---|---|
| Language | **Kotlin** | Apache-2.0 | Existing. |
| UI | **Jetpack Compose** (Material 3) | Apache-2.0 | Existing; one toolkit. |
| Min/Target SDK | **minSdk 30 / targetSdk 35** | — | Existing; covers S23 Ultra. |
| DI | **Manual `AppContainer`** | n/a | No codegen, no annotation processor. |
| Local DB | **Room** + **SQLCipher** (`net.zetetic:sqlcipher-android`) | Apache-2.0 / BSD | Room exists; SQLCipher = whole-DB at-rest encryption. |
| Map engine | **osmdroid** | Apache-2.0 | Existing; keyless, offline packs. **Never Google Maps (paid).** |
| Offline tiles | **MBTiles / osmdroid SQLite archives** | ODbL data | User-provided packs. |
| File crypto | **Tink `StreamingAead`** (AES-256-GCM-HKDF) | Apache-2.0 | Streams large media; no hand-rolled crypto. |
| Key wrapping | **Android Keystore** (StrongBox) | AOSP | Hardware-backed key wrapping + biometric gate. |
| KDF | **PBKDF2withHmacSHA256** (platform) | AOSP | Zero-dep; key is also Keystore-wrapped. *(Argon2 dropped — was a native dep for marginal gain.)* |
| Biometrics | **androidx.biometric** | Apache-2.0 | Fingerprint + device-credential fallback. |
| PDF viewing | **PdfRenderer** (platform) | AOSP | Zero-dep Knowledge Base viewer. |
| PDF generation | **WebView → `PrintDocumentAdapter`** | AOSP | Reuses the HTML report; one layout source. |
| HTML reports | **Hand-built HTML + inlined JS/CSS** | n/a | Existing; self-contained, no CDN. |
| In-app charts | **Compose `Canvas`** (existing spectrum chart) | Apache-2.0 | No chart library. |
| Background | **Coroutines only** | Apache-2.0 | No WorkManager until an unattended job exists. |
| Serialization | **kotlinx.serialization** | Apache-2.0 | Report model, GeoJSON. |
| Geo formats | **Hand-rolled GPX/KML/GeoJSON writers** | n/a | Tiny, no dep; consistent with existing CSV/WiGLE. |
| Terminal | **Termux `RUN_COMMAND` intent** (deferred) | GPLv3 (separate app) | Bridge, never embed Linux. |
| Build/CI | **Gradle KTS + GitHub Actions** | — | Existing cloud build (no local Android toolchain). |
| Testing | **Targeted JVM unit tests** (JUnit) | Apache-2.0 | Focus on crypto, classification, migrations. No mandated Robolectric/Compose-UI harness. |

**Rejected:** Google Maps Platform, Firebase, Mapbox, analytics SDKs, Hilt, WorkManager (for now), Argon2 (for now), Vico, Robolectric-as-mandate.

---

## 4. Module / Package Boundaries

| Unit | Owns | Depends on |
|---|---|---|
| `:recon` (module) | Scanner service, OUI, classification, recon spectrum/map UI, recon export | `:core` |
| `:core` (module) | DB + entities + DAOs + migrations, crypto/keys, fused location, osmdroid wrapper + tile manager, shared UI/theme, common helpers | — |
| `:app` (module) | Navigation host, `AppContainer`, theme, and all feature packages (operations/missions/reports/vault/knowledge/terminal) | `:core`, `:recon` |

Rule: `:recon` and `:core` never depend on `:app`. Feature *packages* inside `:app` don't import each other's internals — cross-feature needs go through `:core` DAOs and shared models.

---

## 5. Database Design (Unified Spine)

### 5.1 DB Choice

**Room over SQLCipher**, single encrypted file (`arawn.db`). One DB, not one-per-feature — a Mission references a Recon session, a Report pulls from both, the Vault indexes media across all. SQLCipher encrypts the whole file at rest with a key derived from the vault credential (§7). High-frequency scan writes stay fast via the existing batched `@Transaction insertScanWindow`.

### 5.2 Current Schema (preserved as-is)

```
SessionEntity (1) ──< LogEntryEntity (1) ──< WifiApEntity / BleDeviceEntity
```
Untouched except **one additive column**: `SessionEntity.missionId: Long?` (nullable FK → `missions`, `ON DELETE SET NULL`).

### 5.3 New Platform Spine (additive)

```
Mission
  ├─< MissionItem        (objectives + checklist, discriminated by type)
  ├─< Waypoint           (mission-scoped OR global; nullable missionId)
  ├─< Route ─< RoutePoint (planned drawn OR recorded GPS track)
  ├─< AreaOverlay        (GeoJSON polygon/zone)
  ├─< Note      (polymorphic: ownerType + ownerId, NO db FK)
  ├─< MediaAsset(polymorphic: ownerType + ownerId, NO db FK; bytes in vault)
  └─< ReconSession (existing, now missionId-tagged)

Report ─< report_sessions / report_routes   (NORMALIZED join tables — chosen for integrity)
Document                                      (Knowledge Base; bytes in vault)
VaultEntry                                    (SINGLE owner of every encrypted blob + its key)
```

### 5.4 Locked Data Decisions

- **Report ↔ sessions/routes: normalized join tables** (`report_sessions`, `report_routes`) — chosen for referential integrity and future query flexibility over CSV/JSON columns.
- **VaultEntry kept AND made the single source of truth for ciphertext.** Every encrypted blob (media, document, stored report) and its per-file `keyId` lives in `vault_entries`. Typed entities (`MediaAsset`, `Document`, `Report`) reference `vaultEntryId` instead of carrying their own `fileUri`/`sha256`/`keyId`. One place to manage encryption, one place to crypto-shred — *simpler*, not just centralized.
- **Document search: `LIKE` for v1.** FTS4/5 deferred until the library is large enough to justify it.
- **Polymorphic Note/MediaAsset: accepted**, integrity enforced in the repository layer (no DB FK). `(ownerType, ownerId)` indexed; parent-delete cascades run in repository transactions.

### 5.5 Migrations (hard line)

Drop `fallbackToDestructiveMigration()`. Write real `Migration` objects, set `exportSchema = true`, commit schema JSON to git. **Mandatory pairing:** add a stable release signing key as a GitHub Actions secret so updates install without uninstall (today's random debug keystore forces uninstall = DB wipe on every update — incompatible with real migrations).

---

## 6. Project Folder Structure (Target)

```
ARAWN/
├── settings.gradle.kts            (include :app, :core, :recon)
├── build.gradle.kts               (root)
├── gradle/libs.versions.toml      (version catalog)
├── .github/workflows/build-apk.yml
│
├── core/   src/main/kotlin/com/arawn/core/{database,crypto,location,map,ui,common}/
├── recon/  src/main/kotlin/com/arawn/scanner/...   (migrated from today's app/)
└── app/    src/main/kotlin/com/arawn/
            ├── ArawnApplication.kt        (AppContainer construction)
            ├── MainActivity.kt            (single-activity nav host)
            ├── navigation/
            └── feature/{operations,missions,reports,vault,knowledge,terminal}/
```

Migration from today's flat `app/src/main/java/com/arawn/scanner/`: the `scanner` + `db` + `oui` + `classify` + `export` + recon `ui` code splits — persistence/crypto/map/location to `:core`, the rest to `:recon`.

---

## 7. Security Architecture

### 7.1 Key Hierarchy

```
PIN/passphrase ──PBKDF2withHmacSHA256(high iterations)──> KEK
Android Keystore (StrongBox) ── wraps ──> (KEK gates access)
KEK ── unwraps ──> DEK (random 256-bit), never derived directly from the PIN
DEK ──> SQLCipher DB key  +  per-file subkeys (HKDF) for Tink StreamingAead
```

- DEK is random and wrapped, so the user can **change the PIN without re-encrypting data** (re-wrap the DEK).
- Biometric unlock gates Keystore (`setUserAuthenticationRequired`); PIN is the fallback so biometric loss ≠ data loss.

### 7.2 Encryption

- **Database:** SQLCipher, whole-DB at rest.
- **Files (vault media, documents, reports):** **Tink `StreamingAead`** (AES-256-GCM-HKDF); per-file subkey from the DEK. Ciphertext in app-internal `filesDir`; only the encrypted index (`vault_entries`) lives in the DB. **Never** plaintext in MediaStore.

### 7.3 Vault Scope (v1) — DEFINITIVE

The v1 vault is **limited to exactly** the following. Nothing else ships in v1.

- **SQLCipher-encrypted database** (whole-DB at rest).
- **Tink-encrypted files** (`StreamingAead`, per-file subkey).
- **Android Keystore key wrapping** (StrongBox, hardware-backed).
- **Biometric + PIN authentication** (PIN is the fallback / KEK source).
- **Auto-lock** on background/timeout (zero the in-memory DEK, close SQLCipher).
- **Crypto-shred deletion** (destroy the per-file key → ciphertext unrecoverable; the correct primitive for flash storage where overwrite is unreliable).

### 7.4 Explicitly EXCLUDED from v1

- **Duress PIN, decoy vault, and panic-wipe are NOT in the v1 architecture.** There is no decoy DEK, no second-credential code path, and no destructive panic gesture. **Decision (final):** deferred entirely.
- **Reasoning:** they introduce irreversible failure modes (data-loss risk outweighs benefit), add significant cryptographic + UX complexity, deliver limited real-world security given acknowledged forensic limitations (file sizes/timestamps/free-space can betray a hidden volume), and inflate scope + testing burden.
- **Revisit only if a concrete operational need emerges** — and only then behind an explicit "data cannot be recovered" acknowledgment. The v1 design carries no scaffolding for these features.

### 7.5 Threat Model (Honest Limits)

- **Defends well:** lost/stolen device (locked/off), casual inspection, app-data extraction without the PIN.
- **Out of scope:** compromised OS / attacker-rooted device / hardware implants; a running unlocked app holds keys in RAM (mitigated by aggressive auto-lock).

---

## 8. Offline Mapping Strategy

- **Engine: osmdroid (existing).** `OfflineMapPanel` is the basis.
- **Primary tiles: user-provided offline packs** — **MBTiles** / osmdroid SQLite archives. `TilePackManager` (`:core` map package) imports via SAF, indexes name/bounds/zoom into the DB, lets the user pick the active pack per area.
- **Obtaining packs (free, documented in-app):** **Mobile Atlas Creator (MOBAC)** exporting **OpenTopoMap / OpenStreetMap raster** (ODbL — attribution baked into the map UI).
- **Topo:** OpenTopoMap raster packs give offline contours/hillshade. Vector tiles (MapLibre) are a **future spike only**, not v1 — one map engine.
- **Online tiles (existing opt-in concession):** an **explicit, default-off** toggle — "Allow online tiles when no offline pack present (leaks viewport/IP to OSM; never scan data)."
- **GPS & tracks:** reuse `:core` location (fused GPS/NETWORK + last-known kickstart). Track recording writes `RoutePoint` rows; map renders polylines. Recon green-dots and route polylines share `OverlayFactory`.

---

## 9. Recon Module (Preserve + Integrate)

**Already built and field-validated — preserve, don't redesign.**

- **Preserved unchanged:** `WirelessScannerService` (foreground, `location|connectedDevice`, receive-only, GPS-heartbeat → `ScanPacket` SharedFlow + drop-oldest Channel → IO writer); the passive constraint; offline OUI engine (~57k manuf entries, tiered MA-L/M/S, randomized-MAC classification); heuristic classification engine; spectrum chart; recon osmdroid map.
- **Integrated:** `missionId` tagging of sessions; on-demand RSSI **heatmap** overlay (computed in a coroutine, cached — no WorkManager); unified **GPX/KML/GeoJSON** export via `:core` `GeoExport`; randomized-MAC filter toggle.
- **Refactor risk (managed):** moving `com.arawn.scanner.*` into the `:recon` module + `:core` is a package/module move touching validated code. It is its **own isolated, CI-gated commit**, separate from any behavior change, so a regression is bisectable. First and riskiest Phase-A step.

---

## 10. Reporting Engine

- **Primary: self-contained interactive HTML** (existing `HtmlReportRenderer`, generalized mission-wide). `ReportRepository` gathers mission + sessions + routes + waypoints + media + notes → `ReportModel` (kotlinx.serialization) → renderer emits one `.html` with **all CSS/JS/images inlined** (base64), **zero CDN**. Contents: summary, timeline, embedded static map image (osmdroid → bitmap → base64), tracks, recon tables + Device Types chart, geotagged photo gallery, checklist status.
- **Secondary: PDF** via **WebView `PrintDocumentAdapter`** (reuses the HTML — one layout source). Native `PdfDocument` rejected (second renderer to maintain).
- **Output:** written to the **Vault** by default (a `vault_entries` row), with an explicit "Export decrypted copy to `Documents/ARAWN/`" action. Extract the 3×-duplicated MediaStore `IS_PENDING` write into `:core` `MediaStoreWriter`. Runs in a `viewModelScope` coroutine with progress — no WorkManager.

---

## 11. Roadmap & Phased Development Plan

Each phase ends in a **CI-built, on-device-testable APK**. "Done" = compiles in GitHub Actions + validated on the S23 Ultra.

> **Current state (done):** recon module complete through its internal Phase 2. Foundation for everything below.

**Phase A — Platform Foundation** *(highest priority)*
- Split into `:app` / `:core` / `:recon`; version catalog.
- Manual `AppContainer` DI.
- Single-activity Compose Navigation shell, bottom nav (Ops / Missions / Recon / Vault / More).
- **Remove destructive migrations**; real migrations + `exportSchema`; **stable CI signing key**.
- *Recon module-extraction is its own isolated, CI-gated commit (§9).*

**Phase B — Unified DB & Operations Center**
- Spine schema (§5.3) incl. report join tables + VaultEntry-owns-ciphertext.
- Ops map home: layer toggles, long-press waypoint drop, marker detail sheet, GPS track recording → `Route`. Wire recon coordinates into the shared map model.

**Phase C — Mission Planner**
- Mission CRUD, folders, status; objectives + checklist + timeline; draw routes/areas; attach notes/photos; tag recon sessions.

**Phase D — Reporting Engine**
- Generalize HTML report mission-wide; `ReportRepository`; static map snapshot; photo gallery; PDF via WebView; extract `MediaStoreWriter`; unify GPX/KML/GeoJSON in `:core`.

**Phase E — Secure Vault (core only)**
- Key hierarchy (§7): PBKDF2 KEK → wrapped DEK → SQLCipher + Tink files. Biometric + PIN, auto-lock, crypto-shred. VaultEntry as the encrypted-asset index. Migrate report/media bytes into the encrypted store.

**Phase F — Offline Mapping Hardening**
- `TilePackManager` SAF import + per-area selection; in-app MOBAC/OpenTopoMap docs + attribution; online-tiles toggle (default off).

**Phase G — Recon Heatmap & Polish**
- On-demand RSSI heatmap (coroutine + cache); randomized-MAC filter toggle.

**Phase H — Local Knowledge Base**
- Document import into vault; `PdfRenderer` viewer; categories/tags/`LIKE` search.

**Deferred / build-if-wanted (not committed phases):**
- **Terminal / Termux bridge** — lowest capability-per-complexity; build only if actually wanted.
- **Duress/decoy vault + panic-wipe** — **excluded from v1 entirely** (§7.4); revisit only on a concrete operational need.
- **MapLibre vector tiles** — future spike (§8).

**Cross-cutting:** targeted JVM unit tests (crypto, classification, migrations); keep the standing bug-scan rule on every file touched; stable signing key mandatory once migrations land in Phase A.

---

## 12. Key Trade-offs Summary

| Decision | Chosen | Why |
|---|---|---|
| Modules | 3 (`:app`/`:core`/`:recon`) | Isolate validated recon; avoid 15-module build tax |
| DI | Manual container | No codegen/annotation processor to break |
| Layering | ViewModel→DAO direct; repo only cross-source | Entities are the model; skip CRUD ceremony |
| Background | Coroutines only | WorkManager unneeded until unattended jobs exist |
| KDF | PBKDF2 (platform) | Drop Argon2 native dep; key also Keystore-wrapped |
| DB encryption | Whole-DB SQLCipher | Simpler; leaks nothing at rest |
| Secure delete | Crypto-shred | Flash wear-leveling defeats overwrite |
| Vault ciphertext | Single owner: VaultEntry | One place for files+keys+crypto-shred |
| Report links | Normalized join tables | Integrity + query flexibility (user's call) |
| Doc search | LIKE (v1) | FTS only if corpus grows |
| Map engine | osmdroid raster | One engine, already working |
| Report | HTML-first, PDF via WebView | One layout source |
| Duress vault | Excluded from v1 (final) | Irreversible data-loss risk + crypto/UX complexity + weak forensic benefit |
| Terminal | Deferred / build-if-wanted | Lowest capability-per-complexity |
| Migrations | Real migrations | Field data is irreplaceable |

---

## 13. Simplification Pass (record)

This architecture was deliberately trimmed before locking, to honor Principle #6. Removed vs. the first draft: 15 modules → 3; Hilt → manual DI; domain layer + entity↔domain mappers → none (entities are the model); per-entity repositories → repositories only for cross-source ops; WorkManager → coroutines; Argon2 → platform PBKDF2; Vico → existing Compose Canvas; Robolectric/Compose-UI-test mandate → targeted JVM units; duress/decoy vault + panic-wipe → **excluded from v1 (final)**; Terminal → deferred. **Net: ~half the moving parts removed, 100% of field capability preserved.**

---

## 14. Approval Gate

Locked for review:
- [x] Platform architecture (§2–§4) — simplified
- [x] Database schema (§5 + `docs/schema-draft/`) — locked data decisions applied
- [x] Module boundaries (§4) — 3 modules
- [x] Development roadmap (§11) — simplified

**Non-negotiable before Phase A touches data:** (1) remove `fallbackToDestructiveMigration` + add real migrations; (2) add a stable CI signing key; (3) perform the recon module-extraction as an isolated, CI-gated commit.
