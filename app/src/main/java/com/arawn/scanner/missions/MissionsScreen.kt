package com.arawn.scanner.missions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.arawn.core.database.AreaOverlayEntity
import com.arawn.core.database.CoordinatePair
import com.arawn.core.database.GeoDao
import com.arawn.core.database.MissionDao
import com.arawn.core.database.MissionEntity
import com.arawn.core.database.MissionItemEntity
import com.arawn.core.database.MissionItemType
import com.arawn.core.database.MissionStatus
import com.arawn.core.database.MissionWithItems
import com.arawn.core.database.RouteEntity
import com.arawn.core.database.RoutePointEntity
import com.arawn.core.database.RouteType
import com.arawn.core.database.RouteWithPoints
import com.arawn.core.database.SessionEntity
import com.arawn.core.database.WaypointEntity
import com.arawn.core.database.WaypointType
import com.arawn.core.database.WirelessDao
import com.arawn.scanner.hasLocation
import com.arawn.scanner.map.MapTool
import com.arawn.scanner.map.TacticalMapPanel
import com.arawn.scanner.map.bearingDegrees
import com.arawn.scanner.map.haversineMeters
import com.arawn.scanner.map.measureLabel
import com.arawn.scanner.map.polygonToGeoJson
import androidx.compose.ui.draw.clipToBounds
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Amber        = Color(0xFFE0B341)
private val TerminalGreen = Color(0xFF35D07F)
private val PanelBlack   = Color(0xFF0A0A0A)
private val Ink          = Color(0xFFE6E6E6)
private val DimRed       = Color(0xFFCC3B3B)

private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
private fun fmtMs(ms: Long): String = dateFmt.format(Date(ms))

private fun MissionStatus.displayColor() = when (this) {
    MissionStatus.PLANNED  -> Color.Gray
    MissionStatus.ACTIVE   -> TerminalGreen
    MissionStatus.COMPLETE -> Amber
    MissionStatus.ARCHIVED -> Color(0xFF2A2A2A)
}

private fun MissionStatus.displayLabel() = name

private fun WaypointType.displayLabel() = when (this) {
    WaypointType.GENERIC     -> "GENERIC"
    WaypointType.PARKING     -> "PARKING"
    WaypointType.ENTRY       -> "ENTRY"
    WaypointType.OBSERVATION -> "OBSERVATION"
    WaypointType.EXIT        -> "EXIT"
    WaypointType.HAZARD      -> "HAZARD"
    WaypointType.CACHE       -> "CACHE"
    WaypointType.POI         -> "POI"
}

// =============================================================================
// ENTRY POINT
// =============================================================================

@Composable
fun MissionsScreen(
    missionDao: MissionDao,
    geoDao: GeoDao,
    wirelessDao: WirelessDao,
    missionRepository: MissionRepository,
    livePosition: CoordinatePair? = null,
) {
    var selectedMissionId by remember { mutableStateOf<Long?>(null) }

    if (selectedMissionId == null) {
        MissionListContent(
            missionDao = missionDao,
            onSelectMission = { selectedMissionId = it },
        )
    } else {
        MissionDetailContent(
            missionId = selectedMissionId!!,
            missionDao = missionDao,
            geoDao = geoDao,
            wirelessDao = wirelessDao,
            missionRepository = missionRepository,
            livePosition = livePosition,
            onBack = { selectedMissionId = null },
        )
    }
}

// =============================================================================
// MISSION LIST
// =============================================================================

@Composable
private fun MissionListContent(
    missionDao: MissionDao,
    onSelectMission: (Long) -> Unit,
) {
    val missions = remember { mutableStateListOf<MissionEntity>() }
    var showArchived by remember { mutableStateOf(false) }
    var showNewDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Re-subscribes whenever the tab flips: active list vs. archived list.
    LaunchedEffect(showArchived) {
        val flow = if (showArchived) missionDao.observeArchivedMissions()
                   else missionDao.observeActiveMissions()
        flow.collect { list ->
            missions.clear()
            missions.addAll(list)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "ARAWN // MISSION PLANNER",
                color = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${missions.size} ${if (showArchived) "archived" else "active"}",
                color = Color.Gray,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }

        // Active / archived tab row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MissionTab("ACTIVE",   !showArchived) { showArchived = false }
            MissionTab("ARCHIVED",  showArchived) { showArchived = true }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1A1A1A)))

        if (missions.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (showArchived) "// no archived missions"
                           else "// no active missions\n// create one below",
                    color = Color(0xFF3A3A3A),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(missions, key = { it.missionId }) { mission ->
                    MissionCard(mission = mission, onClick = { onSelectMission(mission.missionId) })
                }
            }
        }

        // Creation is only meaningful on the active list.
        if (!showArchived) {
            Button(
                onClick = { showNewDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF161616)),
            ) {
                Text(
                    text = "+ NEW MISSION",
                    fontFamily = FontFamily.Monospace,
                    color = Amber,
                    fontSize = 13.sp,
                )
            }
        }
    }

    if (showNewDialog) {
        NewMissionDialog(
            onConfirm = { name, desc ->
                scope.launch {
                    val id = missionDao.insertMission(
                        MissionEntity(
                            name = name,
                            description = desc.ifBlank { null },
                            createdMs = System.currentTimeMillis(),
                            updatedMs = System.currentTimeMillis(),
                        )
                    )
                    showNewDialog = false
                    onSelectMission(id)
                }
            },
            onDismiss = { showNewDialog = false },
        )
    }
}

@Composable
private fun MissionCard(mission: MissionEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBlack, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = mission.name,
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = fmtMs(mission.createdMs),
                color = Color(0xFF555555),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = mission.status.displayLabel(),
            color = mission.status.displayColor(),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun MissionTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = "[ $label ]",
        color = if (selected) Amber else Color(0xFF333333),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

// =============================================================================
// MISSION DETAIL
// =============================================================================

@Composable
private fun MissionDetailContent(
    missionId: Long,
    missionDao: MissionDao,
    geoDao: GeoDao,
    wirelessDao: WirelessDao,
    missionRepository: MissionRepository,
    livePosition: CoordinatePair?,
    onBack: () -> Unit,
) {
    var missionWithItems by remember { mutableStateOf<MissionWithItems?>(null) }
    val waypoints       = remember { mutableStateListOf<WaypointEntity>() }
    val routes          = remember { mutableStateListOf<RouteWithPoints>() }
    val areas           = remember { mutableStateListOf<AreaOverlayEntity>() }
    val linkedSessions  = remember { mutableStateListOf<SessionEntity>() }
    val allSessions     = remember { mutableStateListOf<SessionEntity>() }

    // Global (unassigned) geo objects — candidates for the LINK EXISTING picker.
    val globalWaypoints = remember { mutableStateListOf<WaypointEntity>() }
    val globalRoutes    = remember { mutableStateListOf<RouteWithPoints>() }
    val globalAreas     = remember { mutableStateListOf<AreaOverlayEntity>() }

    var showAddItemDialog     by remember { mutableStateOf(false) }
    var showAddWaypointDialog by remember { mutableStateOf(false) }
    var showTagSessionDialog  by remember { mutableStateOf(false) }
    var showLinkObjectsDialog by remember { mutableStateOf(false) }
    var itemToDelete          by remember { mutableStateOf<MissionItemEntity?>(null) }
    var waypointToDelete      by remember { mutableStateOf<WaypointEntity?>(null) }
    var sessionToUntag        by remember { mutableStateOf<SessionEntity?>(null) }
    var showArchiveConfirm    by remember { mutableStateOf(false) }
    var showRestoreConfirm    by remember { mutableStateOf(false) }
    var showEditDialog        by remember { mutableStateOf(false) }
    var showDeleteDialog      by remember { mutableStateOf(false) }

    // ── Planner map state ──────────────────────────────────────────────────
    var mapTool          by remember { mutableStateOf(MapTool.NONE) }
    val drawPoints       = remember { mutableStateListOf<GeoPoint>() }
    var liveCenter       by remember { mutableStateOf<GeoPoint?>(null) }
    var pendingMarker    by remember { mutableStateOf<GeoPoint?>(null) }
    var showSaveRouteDialog by remember { mutableStateOf(false) }
    var showSaveAreaDialog  by remember { mutableStateOf(false) }
    var editingRoute     by remember { mutableStateOf<RouteEntity?>(null) }
    var editingArea      by remember { mutableStateOf<AreaOverlayEntity?>(null) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(missionId) {
        missionDao.observeMissionWithItems(missionId).collect { missionWithItems = it }
    }
    LaunchedEffect(missionId) {
        geoDao.observeWaypointsForMission(missionId).collect { list ->
            waypoints.clear()
            waypoints.addAll(list)
        }
    }
    LaunchedEffect(missionId) {
        geoDao.observeRoutesWithPointsForMission(missionId).collect { list ->
            routes.clear()
            routes.addAll(list)
        }
    }
    LaunchedEffect(missionId) {
        geoDao.observeAreasForMission(missionId).collect { list ->
            areas.clear()
            areas.addAll(list)
        }
    }
    LaunchedEffect(missionId) {
        wirelessDao.observeSessionsForMission(missionId).collect { list ->
            linkedSessions.clear()
            linkedSessions.addAll(list)
        }
    }
    LaunchedEffect(Unit) {
        wirelessDao.observeSessions().collect { list ->
            allSessions.clear()
            allSessions.addAll(list)
        }
    }
    // Global geo pools for the LINK EXISTING picker. Waypoints have a dedicated
    // global query; routes/zones reuse the Ops "observe all" feeds, filtered to
    // the unassigned ones in-memory (no extra DAO method, no schema change).
    LaunchedEffect(Unit) {
        geoDao.observeGlobalWaypoints().collect { list ->
            globalWaypoints.clear()
            globalWaypoints.addAll(list)
        }
    }
    LaunchedEffect(Unit) {
        geoDao.observeAllRoutesWithPoints().collect { list ->
            globalRoutes.clear()
            globalRoutes.addAll(list.filter { it.route.missionId == null })
        }
    }
    LaunchedEffect(Unit) {
        geoDao.observeAllAreas().collect { list ->
            globalAreas.clear()
            globalAreas.addAll(list.filter { it.missionId == null })
        }
    }

    val mission = missionWithItems?.mission
    val missionItems = missionWithItems?.items ?: emptyList()
    val availableSessions = allSessions.filter { it.missionId == null }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Back / title row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "← MISSIONS",
                color = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onBack),
            )
            Text(
                text = "  /  ${mission?.name ?: "…"}",
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            if (mission != null) {
                if (mission.archived) {
                    Text(
                        text = "⊗ ARCHIVED",
                        color = Color(0xFF555555),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = "✎ EDIT",
                    color = Amber,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { showEditDialog = true },
                )
            }
        }

        if (mission == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "// loading…",
                    color = Color(0xFF3A3A3A),
                    fontFamily = FontFamily.Monospace,
                )
            }
            return@Column
        }

        // Status chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(MissionStatus.PLANNED, MissionStatus.ACTIVE, MissionStatus.COMPLETE).forEach { s ->
                val selected = mission.status == s
                Text(
                    text = "[ ${s.displayLabel()} ]",
                    color = if (selected) s.displayColor() else Color(0xFF333333),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable {
                        scope.launch {
                            missionDao.updateMission(
                                mission.copy(status = s, updatedMs = System.currentTimeMillis())
                            )
                        }
                    },
                )
            }
        }

        if (!mission.description.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = mission.description ?: "",
                color = Color(0xFF888888),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1A1A1A)))

        // ── Mission planner map (ATAK-lite) ───────────────────────────────
        val mappableWaypoints = waypoints.filter { it.hasLocation() }

        // Tool selector. Tapping the active tool toggles it back off.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolChip("◆ MARKER", mapTool == MapTool.MARKER) {
                drawPoints.clear(); mapTool = if (mapTool == MapTool.MARKER) MapTool.NONE else MapTool.MARKER
            }
            ToolChip("✎ ROUTE", mapTool == MapTool.ROUTE) {
                drawPoints.clear(); mapTool = if (mapTool == MapTool.ROUTE) MapTool.NONE else MapTool.ROUTE
            }
            ToolChip("▭ AREA", mapTool == MapTool.AREA) {
                drawPoints.clear(); mapTool = if (mapTool == MapTool.AREA) MapTool.NONE else MapTool.AREA
            }
            ToolChip("⟂ RULER", mapTool == MapTool.MEASURE) {
                drawPoints.clear(); mapTool = if (mapTool == MapTool.MEASURE) MapTool.NONE else MapTool.MEASURE
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth().height(320.dp).clipToBounds(),
        ) {
            TacticalMapPanel(
                // Fresh List instances per emission so the AndroidView update block
                // re-runs and newly drawn routes/zones appear without a layer toggle
                // (mappableWaypoints is already a fresh .filter list). See OpsScreen.
                waypoints       = mappableWaypoints,
                routes          = routes.toList(),
                areas           = areas.toList(),
                livePosition    = livePosition,
                tool            = mapTool,
                drawPoints      = drawPoints,
                onMapTap        = { gp ->
                    when (mapTool) {
                        MapTool.MARKER  -> pendingMarker = gp
                        MapTool.ROUTE,
                        MapTool.AREA    -> drawPoints.add(gp)
                        MapTool.MEASURE -> {
                            if (drawPoints.size >= 2) drawPoints.clear()
                            drawPoints.add(gp)
                        }
                        MapTool.NONE -> Unit
                    }
                },
                onWaypointTap   = { wp -> waypointToDelete = wp },
                onRouteTap      = { route -> editingRoute = route },
                onAreaTap       = { area -> editingArea = area },
                onCenterChanged = { lat, lon -> liveCenter = GeoPoint(lat, lon) },
                modifier        = Modifier.fillMaxSize(),
            )

            // Center crosshair — the reference point for the live readout.
            Text(
                text = "+",
                color = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp,
                modifier = Modifier.align(Alignment.Center),
            )

            // Live coordinate / measure readout.
            val readout: String? = when {
                mapTool == MapTool.MEASURE && drawPoints.size >= 2 -> {
                    val a = drawPoints.first(); val b = drawPoints.last()
                    measureLabel(
                        haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude),
                        bearingDegrees(a.latitude, a.longitude, b.latitude, b.longitude),
                    )
                }
                liveCenter != null ->
                    "⊹ %.5f, %.5f".format(Locale.US, liveCenter!!.latitude, liveCenter!!.longitude)
                else -> null
            }
            if (readout != null) {
                Text(
                    text = readout,
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }

            // Contextual draw actions while a geometry tool is active.
            if (mapTool == MapTool.ROUTE || mapTool == MapTool.AREA) {
                val minPts = if (mapTool == MapTool.AREA) 3 else 2
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0xCC000000))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${drawPoints.size} pts",
                        color = Amber,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    DrawAction("UNDO", drawPoints.isNotEmpty()) {
                        if (drawPoints.isNotEmpty()) drawPoints.removeAt(drawPoints.lastIndex)
                    }
                    DrawAction("SAVE", drawPoints.size >= minPts, TerminalGreen) {
                        if (mapTool == MapTool.ROUTE) showSaveRouteDialog = true
                        else showSaveAreaDialog = true
                    }
                    DrawAction("CANCEL", true, DimRed) {
                        drawPoints.clear(); mapTool = MapTool.NONE
                    }
                }
            } else if (mapTool == MapTool.MARKER) {
                Text(
                    text = "tap map to drop an asset marker",
                    color = Color(0xFFBBBBBB),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1A1A1A)))

        // Scrollable body
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {

            // ── OBJECTIVES & TASKS ────────────────────────────────────────
            item {
                SectionHeader("// OBJECTIVES & TASKS  (${missionItems.size})")
            }
            // Keys are namespaced per section: itemId / waypointId / sessionId are
            // independent autoincrement sequences that overlap (all start at 1), and
            // LazyColumn requires keys unique across the WHOLE list — colliding raw
            // Long keys throw "Key N was already used" and crash the screen.
            items(missionItems, key = { "item-${it.itemId}" }) { item ->
                ItemRow(
                    item = item,
                    onToggle = {
                        scope.launch {
                            missionDao.updateItem(item.copy(done = !item.done))
                        }
                    },
                    onDelete = { itemToDelete = item },
                )
            }
            item {
                AddRowButton("+ ADD ITEM") { showAddItemDialog = true }
                Spacer(Modifier.height(8.dp))
            }

            // ── WAYPOINTS ─────────────────────────────────────────────────
            item {
                SectionHeader("// WAYPOINTS  (${waypoints.size})")
            }
            items(waypoints, key = { "wpt-${it.waypointId}" }) { wp ->
                WaypointRow(
                    waypoint = wp,
                    onDetach = { scope.launch { geoDao.updateWaypoint(wp.copy(missionId = null)) } },
                    onDelete = { waypointToDelete = wp },
                )
            }
            item {
                AddRowButton("+ ADD WAYPOINT") { showAddWaypointDialog = true }
                Spacer(Modifier.height(8.dp))
            }

            // ── ROUTES ────────────────────────────────────────────────────
            item {
                SectionHeader("// ROUTES  (${routes.size})")
            }
            items(routes, key = { "rte-${it.route.routeId}" }) { rw ->
                RouteRow(
                    route = rw,
                    onDetach = { scope.launch { geoDao.updateRoute(rw.route.copy(missionId = null)) } },
                    onClick = { editingRoute = rw.route },
                )
            }
            item {
                HintRow("draw routes with the ✎ ROUTE tool on the map")
                Spacer(Modifier.height(8.dp))
            }

            // ── AREAS / ZONES ─────────────────────────────────────────────
            item {
                SectionHeader("// AREAS / ZONES  (${areas.size})")
            }
            items(areas, key = { "area-${it.areaId}" }) { area ->
                AreaRow(
                    area = area,
                    onDetach = { scope.launch { geoDao.updateArea(area.copy(missionId = null)) } },
                    onClick = { editingArea = area },
                )
            }
            item {
                HintRow("highlight zones with the ▭ AREA tool on the map")
                Spacer(Modifier.height(8.dp))
            }

            // ── LINK EXISTING OBJECTS ─────────────────────────────────────
            // Pull global (unassigned) pins/routes/zones into this mission.
            item {
                AddRowButton("+ LINK EXISTING OBJECT") { showLinkObjectsDialog = true }
                Spacer(Modifier.height(12.dp))
            }

            // ── LINKED RECON SESSIONS ─────────────────────────────────────
            item {
                SectionHeader("// LINKED RECON SESSIONS  (${linkedSessions.size})")
            }
            items(linkedSessions, key = { "sess-${it.sessionId}" }) { session ->
                SessionRow(session = session, onUntag = { sessionToUntag = session })
            }
            item {
                AddRowButton("+ TAG SESSION") { showTagSessionDialog = true }
                Spacer(Modifier.height(12.dp))
            }

            // ── LIFECYCLE ─────────────────────────────────────────────────
            item {
                if (mission.archived) {
                    Button(
                        onClick = { showRestoreConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A1505)),
                    ) {
                        Text(
                            text = "↺ RESTORE MISSION",
                            fontFamily = FontFamily.Monospace,
                            color = TerminalGreen,
                            fontSize = 12.sp,
                        )
                    }
                } else {
                    Button(
                        onClick = { showArchiveConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF15110A)),
                    ) {
                        Text(
                            text = "⊗ ARCHIVE MISSION",
                            fontFamily = FontFamily.Monospace,
                            color = Amber,
                            fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A0A0A)),
                ) {
                    Text(
                        text = "⌫ DELETE MISSION",
                        fontFamily = FontFamily.Monospace,
                        color = DimRed,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    if (showAddItemDialog) {
        AddItemDialog(
            onConfirm = { title, type ->
                scope.launch {
                    missionDao.upsertItem(
                        MissionItemEntity(
                            missionId  = missionId,
                            type       = type,
                            title      = title,
                            orderIndex = missionItems.size,
                            createdMs  = System.currentTimeMillis(),
                        )
                    )
                    showAddItemDialog = false
                }
            },
            onDismiss = { showAddItemDialog = false },
        )
    }

    if (showAddWaypointDialog) {
        AddWaypointDialog(
            onConfirm = { name, type, lat, lon ->
                scope.launch {
                    geoDao.insertWaypoint(
                        WaypointEntity(
                            missionId = missionId,
                            name      = name,
                            latitude  = lat,
                            longitude = lon,
                            type      = type,
                            createdMs = System.currentTimeMillis(),
                        )
                    )
                    showAddWaypointDialog = false
                }
            },
            onDismiss = { showAddWaypointDialog = false },
        )
    }

    if (showTagSessionDialog) {
        TagSessionDialog(
            sessions = availableSessions,
            onTag = { session ->
                scope.launch {
                    wirelessDao.tagSessionToMission(session.sessionId, missionId)
                    showTagSessionDialog = false
                }
            },
            onDismiss = { showTagSessionDialog = false },
        )
    }

    itemToDelete?.let { item ->
        ConfirmDialog(
            message = "Delete item \"${item.title}\"?",
            onConfirm = {
                scope.launch {
                    missionDao.deleteItem(item)
                    itemToDelete = null
                }
            },
            onDismiss = { itemToDelete = null },
        )
    }

    waypointToDelete?.let { wp ->
        ConfirmDialog(
            message = "Delete waypoint \"${wp.name}\"?",
            onConfirm = {
                scope.launch {
                    geoDao.deleteWaypoint(wp)
                    waypointToDelete = null
                }
            },
            onDismiss = { waypointToDelete = null },
        )
    }

    sessionToUntag?.let { session ->
        ConfirmDialog(
            message = "Untag session #${session.sessionId} from this mission?",
            onConfirm = {
                scope.launch {
                    wirelessDao.tagSessionToMission(session.sessionId, null)
                    sessionToUntag = null
                }
            },
            onDismiss = { sessionToUntag = null },
        )
    }

    if (showArchiveConfirm) {
        ConfirmDialog(
            message = "Archive \"${mission?.name ?: ""}\"?\nIt will be hidden from the active list.",
            onConfirm = {
                scope.launch {
                    mission?.let { m ->
                        missionDao.updateMission(
                            m.copy(archived = true, updatedMs = System.currentTimeMillis())
                        )
                    }
                    showArchiveConfirm = false
                    onBack()
                }
            },
            onDismiss = { showArchiveConfirm = false },
        )
    }

    if (showRestoreConfirm && mission != null) {
        ConfirmDialog(
            message = "Restore \"${mission.name}\" to the active list?",
            confirmLabel = "RESTORE",
            confirmColor = TerminalGreen,
            onConfirm = {
                scope.launch {
                    missionDao.updateMission(
                        mission.copy(archived = false, updatedMs = System.currentTimeMillis())
                    )
                    showRestoreConfirm = false
                    onBack()
                }
            },
            onDismiss = { showRestoreConfirm = false },
        )
    }

    if (showEditDialog && mission != null) {
        EditMissionDialog(
            initialName        = mission.name,
            initialDescription = mission.description ?: "",
            onConfirm = { name, desc ->
                scope.launch {
                    missionDao.updateMission(
                        mission.copy(
                            name        = name,
                            description = desc.ifBlank { null },
                            updatedMs   = System.currentTimeMillis(),
                        )
                    )
                    showEditDialog = false
                }
            },
            onDismiss = { showEditDialog = false },
        )
    }

    if (showDeleteDialog && mission != null) {
        DeleteMissionDialog(
            missionName = mission.name,
            onDetachAndDelete = {
                scope.launch {
                    missionRepository.deleteDetachObjects(mission)
                    showDeleteDialog = false
                    onBack()
                }
            },
            onDeleteAll = {
                scope.launch {
                    missionRepository.deleteWithObjects(mission)
                    showDeleteDialog = false
                    onBack()
                }
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    if (showLinkObjectsDialog) {
        LinkObjectsDialog(
            waypoints = globalWaypoints.toList(),
            routes    = globalRoutes.toList(),
            areas     = globalAreas.toList(),
            onLinkWaypoint = { wp -> scope.launch { geoDao.updateWaypoint(wp.copy(missionId = missionId)) } },
            onLinkRoute    = { rt -> scope.launch { geoDao.updateRoute(rt.copy(missionId = missionId)) } },
            onLinkArea     = { ar -> scope.launch { geoDao.updateArea(ar.copy(missionId = missionId)) } },
            onDismiss      = { showLinkObjectsDialog = false },
        )
    }

    // ── Planner dialogs ─────────────────────────────────────────────────────

    pendingMarker?.let { gp ->
        PlaceMarkerDialog(
            lat = gp.latitude,
            lon = gp.longitude,
            onConfirm = { name, type, lat, lon ->
                scope.launch {
                    geoDao.insertWaypoint(
                        WaypointEntity(
                            missionId = missionId,
                            name      = name,
                            latitude  = lat,
                            longitude = lon,
                            type      = type,
                            createdMs = System.currentTimeMillis(),
                        )
                    )
                }
                pendingMarker = null // tool stays MARKER so several can be dropped
            },
            onDismiss = { pendingMarker = null },
        )
    }

    if (showSaveRouteDialog) {
        NamePlannerDialog(
            title      = "SAVE ROUTE",
            countLabel = "${drawPoints.size} points",
            onConfirm  = { name ->
                val pts = drawPoints.toList()
                scope.launch {
                    val routeId = geoDao.insertRoute(
                        RouteEntity(
                            missionId = missionId,
                            name      = name,
                            type      = RouteType.PLANNED,
                            createdMs = System.currentTimeMillis(),
                        )
                    )
                    geoDao.insertRoutePoints(
                        pts.mapIndexed { i, gp ->
                            RoutePointEntity(
                                routeId   = routeId,
                                seq       = i,
                                latitude  = gp.latitude,
                                longitude = gp.longitude,
                            )
                        }
                    )
                }
                drawPoints.clear(); mapTool = MapTool.NONE; showSaveRouteDialog = false
            },
            onDismiss = { showSaveRouteDialog = false },
        )
    }

    if (showSaveAreaDialog) {
        SaveAreaDialog(
            countLabel = "${drawPoints.size} vertices",
            onConfirm  = { name, strokeColor ->
                val pts = drawPoints.toList()
                val fill = (0x33 shl 24) or (strokeColor and 0x00FFFFFF)
                scope.launch {
                    geoDao.insertArea(
                        AreaOverlayEntity(
                            missionId   = missionId,
                            name        = name,
                            geoJson     = polygonToGeoJson(pts),
                            fillColor   = fill,
                            strokeColor = strokeColor,
                            createdMs   = System.currentTimeMillis(),
                        )
                    )
                }
                drawPoints.clear(); mapTool = MapTool.NONE; showSaveAreaDialog = false
            },
            onDismiss = { showSaveAreaDialog = false },
        )
    }

    editingRoute?.let { route ->
        EditGeoDialog(
            title        = "EDIT ROUTE",
            initialName  = route.name,
            subtitle     = "${route.type.name} route",
            onUpdate     = { newName ->
                scope.launch { geoDao.updateRoute(route.copy(name = newName)) }
                editingRoute = null
            },
            onDelete     = {
                scope.launch { geoDao.deleteRoute(route) }
                editingRoute = null
            },
            onDismiss    = { editingRoute = null },
        )
    }

    editingArea?.let { area ->
        EditGeoDialog(
            title        = "EDIT AREA",
            initialName  = area.name,
            subtitle     = "zone overlay",
            onUpdate     = { newName ->
                scope.launch { geoDao.updateArea(area.copy(name = newName)) }
                editingArea = null
            },
            onDelete     = {
                scope.launch { geoDao.deleteArea(area) }
                editingArea = null
            },
            onDismiss    = { editingArea = null },
        )
    }
}

// =============================================================================
// SHARED ROW COMPOSABLES
// =============================================================================

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = Color(0xFF444444),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun AddRowButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color(0xFF3A3A3A),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(Color(0xFF0D0D0D), RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

@Composable
private fun ItemRow(
    item: MissionItemEntity,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBlack, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (item.done) "[✓]" else "[ ]",
            color = if (item.done) TerminalGreen else Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.clickable(onClick = onToggle),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = item.title,
            color = if (item.done) Color(0xFF444444) else Ink,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        // Single-char type tag: O=OBJECTIVE, C=CHECKLIST
        Text(
            text = item.type.name[0].toString(),
            color = Color(0xFF333333),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "[×]",
            color = Color(0xFF3A1A1A),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.clickable(onClick = onDelete),
        )
    }
}

@Composable
private fun WaypointRow(
    waypoint: WaypointEntity,
    onDetach: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBlack, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "◆ ${waypoint.name}",
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            val locationText = if (waypoint.hasLocation())
                "%.5f, %.5f".format(Locale.US, waypoint.latitude, waypoint.longitude)
            else
                "PENDING LOCATION — place pin in OPS"
            Text(
                text = "${waypoint.type.displayLabel()}  $locationText",
                color = if (waypoint.hasLocation()) Color(0xFF555555) else Color(0xFF444444),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
        DetachGlyph(onDetach)
        Spacer(Modifier.width(10.dp))
        Text(
            text = "[×]",
            color = Color(0xFF3A1A1A),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.clickable(onClick = onDelete),
        )
    }
}

/** Compact "pop out to global" affordance — clears the object's missionId so it
 *  leaves the mission and becomes a global object (visible in OPS). */
@Composable
private fun DetachGlyph(onDetach: () -> Unit) {
    Text(
        text = "[↗]",
        color = Color(0xFF6E5A22),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = Modifier.clickable(onClick = onDetach),
    )
}

@Composable
private fun SessionRow(session: SessionEntity, onUntag: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBlack, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "#${session.sessionId}  ${fmtMs(session.startTime)}",
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Text(
                text = "${session.pointsCollected} pts  |  ${"%.0f".format(session.totalDistanceM)} m",
                color = Color(0xFF555555),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
        Text(
            text = "[UNTAG]",
            color = Color(0xFF3A1A1A),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.clickable(onClick = onUntag),
        )
    }
}

// =============================================================================
// DIALOGS
// =============================================================================

@Composable
private fun NewMissionDialog(
    onConfirm: (name: String, description: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelBlack,
        title = {
            Text("NEW MISSION", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalTextField(value = name, label = "NAME (required)", onValueChange = { name = it })
                TerminalTextField(value = desc, label = "DESCRIPTION (optional)", onValueChange = { desc = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), desc.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text("CREATE", fontFamily = FontFamily.Monospace, color = TerminalGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", fontFamily = FontFamily.Monospace, color = Color.Gray)
            }
        },
    )
}

@Composable
private fun AddItemDialog(
    onConfirm: (title: String, type: MissionItemType) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var type  by remember { mutableStateOf(MissionItemType.OBJECTIVE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelBlack,
        title = {
            Text("ADD ITEM", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalTextField(value = title, label = "TITLE", onValueChange = { title = it })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MissionItemType.entries.forEach { t ->
                        Text(
                            text = "[ ${t.name} ]",
                            color = if (type == t) Amber else Color(0xFF333333),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { type = t },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (title.isNotBlank()) onConfirm(title.trim(), type) },
                enabled = title.isNotBlank(),
            ) {
                Text("ADD", fontFamily = FontFamily.Monospace, color = TerminalGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", fontFamily = FontFamily.Monospace, color = Color.Gray)
            }
        },
    )
}

/**
 * Adds a waypoint to a mission with name + type only.
 * Coordinates default to (0, 0) — displayed as "PENDING LOCATION" in the list
 * until the user places the pin precisely via OPS → long-press → DROP PIN.
 */
@Composable
private fun AddWaypointDialog(
    onConfirm: (name: String, type: WaypointType, lat: Double, lon: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val types     = WaypointType.entries
    var name      by remember { mutableStateOf("") }
    var typeIndex by remember { mutableStateOf(0) }
    val type      = types[typeIndex]

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = PanelBlack,
        title = {
            Text("ADD WAYPOINT", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalTextField(value = name, label = "NAME", onValueChange = { name = it })

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "◀", color = Amber, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                        modifier = Modifier.clickable {
                            typeIndex = (typeIndex - 1 + types.size) % types.size
                        },
                    )
                    Text(
                        "[ ${type.displayLabel()} ]",
                        color = Amber, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    )
                    Text(
                        "▶", color = Amber, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                        modifier = Modifier.clickable { typeIndex = (typeIndex + 1) % types.size },
                    )
                }

                Text(
                    text       = "Tip: long-press the OPS map to place pins\nprecisely — then use OPS → PLAN to create\nmissions from them without typing coordinates.",
                    color      = Color(0xFF444444),
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 10.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), type, 0.0, 0.0) },
            ) {
                Text("ADD", fontFamily = FontFamily.Monospace, color = TerminalGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", fontFamily = FontFamily.Monospace, color = Color.Gray)
            }
        },
    )
}

@Composable
private fun TagSessionDialog(
    sessions: List<SessionEntity>,
    onTag: (SessionEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelBlack,
        title = {
            Text("TAG SESSION", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
        },
        text = {
            if (sessions.isEmpty()) {
                Text(
                    text = "// no untagged sessions available",
                    color = Color(0xFF3A3A3A),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    sessions.forEach { session ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onTag(session) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "#${session.sessionId}  ${fmtMs(session.startTime)}",
                                    color = Ink,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                )
                                Text(
                                    text = "${session.pointsCollected} pts",
                                    color = Color(0xFF555555),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color(0xFF1A1A1A))
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", fontFamily = FontFamily.Monospace, color = Color.Gray)
            }
        },
    )
}

@Composable
private fun ConfirmDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "CONFIRM",
    confirmColor: Color = DimRed,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelBlack,
        title = {
            Text("CONFIRM", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
        },
        text = {
            Text(message, color = Ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, fontFamily = FontFamily.Monospace, color = confirmColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", fontFamily = FontFamily.Monospace, color = Color.Gray)
            }
        },
    )
}

/** Edit a mission's name + description (Phase 2 metadata edit). */
@Composable
private fun EditMissionDialog(
    initialName: String,
    initialDescription: String,
    onConfirm: (name: String, description: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var desc by remember { mutableStateOf(initialDescription) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelBlack,
        title = {
            Text("EDIT MISSION", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalTextField(value = name, label = "NAME (required)", onValueChange = { name = it })
                TerminalTextField(value = desc, label = "DESCRIPTION (optional)", onValueChange = { desc = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), desc.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text("SAVE", fontFamily = FontFamily.Monospace, color = TerminalGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", fontFamily = FontFamily.Monospace, color = Color.Gray)
            }
        },
    )
}

/**
 * Hard-delete chooser (Phase 2). Two destructive paths — detach the mission's geo
 * objects to global, or delete them with the mission. Sessions + reports are always
 * preserved (just unlinked), spelled out so the choice is unambiguous.
 */
@Composable
private fun DeleteMissionDialog(
    missionName: String,
    onDetachAndDelete: () -> Unit,
    onDeleteAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelBlack,
        title = {
            Text("DELETE MISSION", fontFamily = FontFamily.Monospace, color = DimRed, fontSize = 14.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Delete \"$missionName\"? This cannot be undone.",
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                Text(
                    text = "Recon sessions and reports are always kept — only unlinked from this mission.",
                    color = Color(0xFF777777),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
                DeleteChoice(
                    title = "⊘ DETACH OBJECTS, DELETE MISSION",
                    subtitle = "Keep waypoints, routes & zones as global objects.",
                    titleColor = Amber,
                    containerColor = Color(0xFF15110A),
                    onClick = onDetachAndDelete,
                )
                DeleteChoice(
                    title = "⌫ DELETE MISSION & ALL OBJECTS",
                    subtitle = "Also permanently delete its waypoints, routes & zones.",
                    titleColor = DimRed,
                    containerColor = Color(0xFF1A0A0A),
                    onClick = onDeleteAll,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", fontFamily = FontFamily.Monospace, color = Color.Gray)
            }
        },
    )
}

@Composable
private fun DeleteChoice(
    title: String,
    subtitle: String,
    titleColor: Color,
    containerColor: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(title, color = titleColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, color = Color(0xFF777777), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

/**
 * Pick an existing GLOBAL (unassigned) waypoint / route / zone to attach to this
 * mission (Phase 3). Stays open so several can be linked in one pass — linked
 * objects drop out of the list as the global pools refresh.
 */
@Composable
private fun LinkObjectsDialog(
    waypoints: List<WaypointEntity>,
    routes: List<RouteWithPoints>,
    areas: List<AreaOverlayEntity>,
    onLinkWaypoint: (WaypointEntity) -> Unit,
    onLinkRoute: (RouteEntity) -> Unit,
    onLinkArea: (AreaOverlayEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val empty = waypoints.isEmpty() && routes.isEmpty() && areas.isEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelBlack,
        title = {
            Text("LINK EXISTING OBJECT", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
        },
        text = {
            if (empty) {
                Text(
                    text = "// no unassigned global objects\n// drop pins or draw routes / zones in OPS first",
                    color = Color(0xFF3A3A3A),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (waypoints.isNotEmpty()) {
                        SectionHeader("// WAYPOINTS")
                        waypoints.forEach { wp ->
                            LinkRow(
                                glyph = "◆",
                                name = wp.name,
                                sublabel = wp.type.displayLabel(),
                                onClick = { onLinkWaypoint(wp) },
                            )
                        }
                    }
                    if (routes.isNotEmpty()) {
                        SectionHeader("// ROUTES")
                        routes.forEach { rw ->
                            LinkRow(
                                glyph = "⟿",
                                name = rw.route.name,
                                sublabel = "${rw.points.size} pts  ·  ${rw.route.type.name}",
                                onClick = { onLinkRoute(rw.route) },
                            )
                        }
                    }
                    if (areas.isNotEmpty()) {
                        SectionHeader("// ZONES")
                        areas.forEach { ar ->
                            LinkRow(
                                glyph = "▭",
                                name = ar.name,
                                sublabel = "zone overlay",
                                onClick = { onLinkArea(ar) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", fontFamily = FontFamily.Monospace, color = Color.Gray)
            }
        },
    )
}

@Composable
private fun LinkRow(glyph: String, name: String, sublabel: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph, color = Amber, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text(sublabel, color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        Text("[ + LINK ]", color = TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

// =============================================================================
// SHARED INPUT
// =============================================================================

@Composable
private fun TerminalTextField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = Amber,
            unfocusedBorderColor = Color(0xFF333333),
            focusedLabelColor    = Amber,
            unfocusedLabelColor  = Color.Gray,
            cursorColor          = Amber,
            focusedTextColor     = Ink,
            unfocusedTextColor   = Ink,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

// =============================================================================
// PLANNER ROWS + DIALOGS
// =============================================================================

@Composable
private fun ToolChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (active) Color.Black else Amber,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(if (active) Amber else Color(0xFF161616), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

@Composable
private fun DrawAction(
    label: String,
    enabled: Boolean,
    color: Color = Amber,
    onClick: () -> Unit,
) {
    Text(
        text = "[ $label ]",
        color = if (enabled) color else Color(0xFF444444),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = if (enabled) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@Composable
private fun HintRow(text: String) {
    Text(
        text = "// $text",
        color = Color(0xFF3A3A3A),
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

@Composable
private fun RouteRow(route: RouteWithPoints, onDetach: () -> Unit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBlack, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "⟿ ${route.route.name}",
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Text(
                text = "${route.points.size} pts  ·  ${route.route.type.name}",
                color = Color(0xFF555555),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
        DetachGlyph(onDetach)
        Spacer(Modifier.width(10.dp))
        Text(
            text = "EDIT",
            color = Color(0xFF555555),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun AreaRow(area: AreaOverlayEntity, onDetach: () -> Unit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBlack, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "▭",
            color = area.strokeColor?.let { Color(it) } ?: Amber,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = area.name,
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Text(
                text = "zone overlay",
                color = Color(0xFF555555),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
        DetachGlyph(onDetach)
        Spacer(Modifier.width(10.dp))
        Text(
            text = "EDIT",
            color = Color(0xFF555555),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
    }
}

/** Drops a mission asset marker at a tapped point (name + type). Coordinates are
 *  passed back through onConfirm as stable copies (see OpsScreen.PlacePinDialog). */
@Composable
private fun PlaceMarkerDialog(
    lat: Double,
    lon: Double,
    onConfirm: (name: String, type: WaypointType, lat: Double, lon: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val types = WaypointType.entries
    var name by remember { mutableStateOf("") }
    var typeIdx by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelBlack,
        title = {
            Text("PLACE MARKER", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "%.6f, %.6f".format(Locale.US, lat, lon),
                    color = Color(0xFF555555),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                TerminalTextField(value = name, label = "Marker name", onValueChange = { name = it })
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("◀", color = Amber, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                        modifier = Modifier.clickable { typeIdx = (typeIdx - 1 + types.size) % types.size })
                    Text("[ ${types[typeIdx].displayLabel()} ]",
                        color = Amber, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text("▶", color = Amber, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                        modifier = Modifier.clickable { typeIdx = (typeIdx + 1) % types.size })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), types[typeIdx], lat, lon) },
            ) {
                Text("DROP", fontFamily = FontFamily.Monospace, color = TerminalGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", fontFamily = FontFamily.Monospace, color = Color.Gray)
            }
        },
    )
}

@Composable
private fun NamePlannerDialog(
    title: String,
    countLabel: String,
    onConfirm: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelBlack,
        title = {
            Text(title, fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(countLabel, color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                TerminalTextField(value = name, label = "Name", onValueChange = { name = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
            ) {
                Text("SAVE", fontFamily = FontFamily.Monospace, color = TerminalGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", fontFamily = FontFamily.Monospace, color = Color.Gray)
            }
        },
    )
}

private val AREA_COLORS = listOf(
    0xFFCC3B3B.toInt(), 0xFFE0B341.toInt(), 0xFF35D07F.toInt(),
    0xFF4FA3D8.toInt(), 0xFF9B59B6.toInt(), 0xFF00CFCF.toInt(),
)

@Composable
private fun SaveAreaDialog(
    countLabel: String,
    onConfirm: (name: String, colorInt: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var colorIdx by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelBlack,
        title = {
            Text("SAVE AREA", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(countLabel, color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                TerminalTextField(value = name, label = "Zone name", onValueChange = { name = it })
                Text("COLOR", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AREA_COLORS.forEachIndexed { i, c ->
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .background(Color(c), RoundedCornerShape(4.dp))
                                .then(
                                    if (i == colorIdx)
                                        Modifier.border(2.dp, Ink, RoundedCornerShape(4.dp))
                                    else Modifier
                                )
                                .clickable { colorIdx = i },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), AREA_COLORS[colorIdx]) },
            ) {
                Text("SAVE", fontFamily = FontFamily.Monospace, color = TerminalGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", fontFamily = FontFamily.Monospace, color = Color.Gray)
            }
        },
    )
}

@Composable
private fun EditGeoDialog(
    title: String,
    initialName: String,
    subtitle: String,
    onUpdate: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelBlack,
        title = {
            Text(title, fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TerminalTextField(value = name, label = "Name", onValueChange = { name = it })
                Text(subtitle, color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                if (confirmDelete) {
                    Text("Permanently delete this?", color = DimRed, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (confirmDelete) {
                    TextButton(onClick = onDelete) {
                        Text("CONFIRM DELETE", fontFamily = FontFamily.Monospace, color = DimRed)
                    }
                } else {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("[×] DELETE", fontFamily = FontFamily.Monospace, color = Color(0xFF3A1A1A))
                    }
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = { if (name.isNotBlank()) onUpdate(name.trim()) },
                    ) {
                        Text("SAVE", fontFamily = FontFamily.Monospace, color = TerminalGreen)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (confirmDelete) confirmDelete = false else onDismiss() }) {
                Text("CANCEL", fontFamily = FontFamily.Monospace, color = Color.Gray)
            }
        },
    )
}
