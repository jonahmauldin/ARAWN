package com.arawn.scanner

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arawn.core.database.AreaOverlayEntity
import com.arawn.core.database.CoordinatePair
import com.arawn.core.database.GeoDao
import com.arawn.core.database.MissionDao
import com.arawn.core.database.MissionEntity
import com.arawn.core.database.MissionStatus
import com.arawn.core.database.RouteEntity
import com.arawn.core.database.RoutePointEntity
import com.arawn.core.database.RouteType
import com.arawn.core.database.RouteWithPoints
import com.arawn.core.database.WaypointEntity
import com.arawn.core.database.WaypointType
import com.arawn.core.database.WirelessDao
import com.arawn.scanner.map.MapTool
import com.arawn.scanner.map.SessionTrack
import com.arawn.scanner.map.TacticalMapPanel
import com.arawn.scanner.map.cleanTrack
import com.arawn.scanner.map.polygonToGeoJson
import com.arawn.scanner.tilePacks.TilePackPanel
import com.arawn.scanner.tilePacks.loadActivePack
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.io.File
import java.util.Locale

private val Amber      = Color(0xFFE0B341)
private val TermGreen  = Color(0xFF35D07F)
private val PanelBlack = Color(0xFF0A0A0A)
private val Ink        = Color(0xFFE6E6E6)
private val DimRed     = Color(0xFFCC3B3B)

private val fieldColors
    @Composable get() = OutlinedTextFieldDefaults.colors(
        focusedTextColor      = Ink,
        unfocusedTextColor    = Ink,
        focusedBorderColor    = Amber,
        unfocusedBorderColor  = Color(0xFF333333),
        cursorColor           = Amber,
        focusedLabelColor     = Amber,
        unfocusedLabelColor   = Color(0xFF666666),
        focusedContainerColor   = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
    )

private val monoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

private enum class OpsView { MAP, OBJECTS, PLAN, PACKS }

/**
 * Operations Center — map view, mission planner, and tile-pack manager.
 *
 * ### MAP view
 * Shows all session GPS tracks, waypoint pins, planned routes, and zone overlays
 * on a live osmdroid map. Layer toggles control what's visible. Long-pressing the
 * map drops a new global waypoint at the press point — the "Place Pin" dialog
 * pre-fills the coordinates so the operator only needs to name it and pick a
 * type. Tapping an existing waypoint/route/zone opens an edit/delete sheet. The
 * ✎ ROUTE and ▭ ZONE tools draw new geometry directly on the map.
 *
 * ### OBJECTS view
 * A single management list of every map object (waypoints, routes, zones) across
 * all missions and the global layer. Each row shows what mission (if any) it is
 * linked to and opens the same edit/rename/delete sheet as tapping it on the map —
 * so a route or zone can always be deleted without hunting for its thin line.
 *
 * ### PLAN view
 * Lists every global waypoint (those not yet assigned to a mission) with
 * checkboxes. Select the pins that belong to a new mission, tap
 * "CREATE MISSION", give it a name, and the mission appears in the MISSIONS
 * tab with all selected waypoints already linked. Natural workflow:
 *   1. Scout in RECON → GPS tracks build up.
 *   2. OPS → long-press to drop pins at key locations.
 *   3. OPS PLAN → select pins → name the mission → CREATE.
 *   4. MISSIONS → new mission with all waypoints pre-placed.
 *
 * ### PACKS view
 * Offline tile-pack manager (see [TilePackPanel]).
 */
@Composable
fun OpsScreen(
    wirelessDao: WirelessDao,
    geoDao: GeoDao,
    missionDao: MissionDao,
    livePosition: CoordinatePair?,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var opsView by remember { mutableStateOf(OpsView.MAP) }

    // ── Data ──────────────────────────────────────────────────────────────────
    val tracks    = remember { mutableStateListOf<SessionTrack>() }
    val waypoints = remember { mutableStateListOf<WaypointEntity>() }
    val routes    = remember { mutableStateListOf<RouteWithPoints>() }
    val areas     = remember { mutableStateListOf<AreaOverlayEntity>() }

    LaunchedEffect(Unit) {
        wirelessDao.observeSessions().collect { sessions ->
            val loaded = sessions.mapNotNull { session ->
                // cleanTrack drops no-fix (0,0) points and GPS "teleport" outliers
                // so the map no longer draws stray lines to spots never visited.
                val coords = cleanTrack(wirelessDao.getSessionCoordinates(session.sessionId))
                if (coords.size < 2) null else SessionTrack(coords, session.missionId)
            }
            tracks.clear(); tracks.addAll(loaded)
        }
    }
    LaunchedEffect(Unit) {
        geoDao.observeAllWaypoints().collect { list ->
            waypoints.clear(); waypoints.addAll(list)
        }
    }
    LaunchedEffect(Unit) {
        geoDao.observeAllRoutesWithPoints().collect { list ->
            routes.clear(); routes.addAll(list)
        }
    }
    LaunchedEffect(Unit) {
        geoDao.observeAllAreas().collect { list ->
            areas.clear(); areas.addAll(list)
        }
    }

    // ── Tile packs ────────────────────────────────────────────────────────────
    var activePackFile by remember { mutableStateOf<File?>(loadActivePack(context)) }

    // ── Layer toggles ─────────────────────────────────────────────────────────
    var showTracks    by remember { mutableStateOf(true) }
    var showWaypoints by remember { mutableStateOf(true) }
    var showRoutes    by remember { mutableStateOf(true) }
    var showAreas     by remember { mutableStateOf(true) }

    // ── Map interaction state ─────────────────────────────────────────────────
    var pendingPinLat   by remember { mutableStateOf<Double?>(null) }
    var pendingPinLon   by remember { mutableStateOf<Double?>(null) }
    var editingWaypoint by remember { mutableStateOf<WaypointEntity?>(null) }

    // ── Route/zone-drawing state ──────────────────────────────────────────────
    var mapTool             by remember { mutableStateOf(MapTool.NONE) }
    val drawPoints          = remember { mutableStateListOf<GeoPoint>() }
    var showSaveRouteDialog by remember { mutableStateOf(false) }
    var showSaveAreaDialog  by remember { mutableStateOf(false) }
    var editingRoute        by remember { mutableStateOf<RouteEntity?>(null) }
    var editingArea         by remember { mutableStateOf<AreaOverlayEntity?>(null) }

    // ── PLAN mode state ───────────────────────────────────────────────────────
    var selectedForMission    by remember { mutableStateOf(emptySet<Long>()) }
    var showCreateMissionDialog by remember { mutableStateOf(false) }

    // ── Layout ────────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text       = "ARAWN // OPERATIONS CENTER",
                color      = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize   = 15.sp,
            )
            Spacer(Modifier.weight(1f))
            if (livePosition != null) {
                Text("● LIVE", color = TermGreen, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
        }

        // View-mode tab row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OpsView.entries.forEach { view ->
                val selected = opsView == view
                Text(
                    text       = "[ ${view.name} ]",
                    color      = if (selected) Amber else Color(0xFF333333),
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 12.sp,
                    modifier   = Modifier.clickable {
                        if (view != OpsView.PLAN) selectedForMission = emptySet()
                        if (view != OpsView.MAP) { mapTool = MapTool.NONE; drawPoints.clear() }
                        opsView = view
                    },
                )
            }
            if (activePackFile != null) {
                Spacer(Modifier.weight(1f))
                Text("⊟ offline", color = TermGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1A1A1A)).padding(top = 4.dp))

        // ── Content ───────────────────────────────────────────────────────────
        when (opsView) {

            // ── MAP ──────────────────────────────────────────────────────────
            OpsView.MAP -> {
                val drawing = mapTool == MapTool.ROUTE || mapTool == MapTool.AREA
                if (drawing) {
                    // Geometry-drawing toolbar (replaces the layer row while drawing).
                    val isArea = mapTool == MapTool.AREA
                    val minPts = if (isArea) 3 else 2
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text       = (if (isArea) "▭ ZONE" else "✎ ROUTE") + " · ${drawPoints.size} pts",
                            color      = Amber,
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 11.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        ToolAction("UNDO", enabled = drawPoints.isNotEmpty()) {
                            if (drawPoints.isNotEmpty()) drawPoints.removeAt(drawPoints.lastIndex)
                        }
                        ToolAction("CLEAR", enabled = drawPoints.isNotEmpty()) { drawPoints.clear() }
                        ToolAction("SAVE", enabled = drawPoints.size >= minPts, color = TermGreen) {
                            if (drawPoints.size >= minPts) {
                                if (isArea) showSaveAreaDialog = true else showSaveRouteDialog = true
                            }
                        }
                        ToolAction("CANCEL", color = DimRed) {
                            drawPoints.clear(); mapTool = MapTool.NONE
                        }
                    }
                } else {
                    // Layer toggle row + draw entry points.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LayerChip("TRACKS", showTracks)    { showTracks    = !showTracks }
                        LayerChip("WPT",    showWaypoints) { showWaypoints = !showWaypoints }
                        LayerChip("ROUTES", showRoutes)    { showRoutes    = !showRoutes }
                        LayerChip("ZONES",  showAreas)     { showAreas     = !showAreas }
                        Spacer(Modifier.weight(1f))
                        ToolAction("✎ ROUTE", color = Amber) {
                            drawPoints.clear(); mapTool = MapTool.ROUTE
                        }
                        ToolAction("▭ ZONE", color = Amber) {
                            drawPoints.clear(); mapTool = MapTool.AREA
                        }
                    }
                }

                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().background(PanelBlack).clipToBounds(),
                ) {
                    // Immutable snapshots: passing fresh List instances on each data
                    // emission changes the parameter identity, which forces the
                    // AndroidView update block to re-run so newly added waypoints /
                    // routes / zones appear immediately (the panel's per-layer hash
                    // gates still prevent redundant overlay rebuilds). Without this,
                    // Compose memoizes the update lambda on the stable SnapshotStateList
                    // reference and the map only refreshes when a layer is toggled.
                    TacticalMapPanel(
                        tracks         = tracks.toList(),
                        livePosition   = livePosition,
                        waypoints      = waypoints.toList(),
                        routes         = routes.toList(),
                        areas          = areas.toList(),
                        activePackFile = activePackFile,
                        showTracks     = showTracks,
                        showWaypoints  = showWaypoints,
                        showRoutes     = showRoutes,
                        showAreas      = showAreas,
                        tool           = mapTool,
                        drawPoints     = drawPoints,
                        onMapTap       = { gp -> drawPoints.add(gp) },
                        onMapLongPress = if (mapTool == MapTool.NONE) {
                            { lat, lon -> pendingPinLat = lat; pendingPinLon = lon }
                        } else null,
                        onWaypointTap  = { wp -> editingWaypoint = wp },
                        onRouteTap     = { route -> editingRoute = route },
                        onAreaTap      = { area -> editingArea = area },
                        modifier       = Modifier.fillMaxSize(),
                    )

                    if (tracks.isEmpty() && waypoints.isEmpty() && routes.isEmpty() &&
                        areas.isEmpty() && livePosition == null
                    ) {
                        Text(
                            text       = "// long press map to drop a pin\n" +
                                "// ✎ ROUTE / ▭ ZONE to plan geometry\n" +
                                "// start RECON to build GPS tracks",
                            color      = Color(0xFF3A3A3A),
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 12.sp,
                            modifier   = Modifier.align(Alignment.Center).padding(16.dp),
                        )
                    }

                    if (drawing) {
                        Text(
                            text       = if (mapTool == MapTool.AREA) "tap map to add zone corners"
                                         else "tap map to add route points",
                            color      = Color(0xFF777777),
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 10.sp,
                            modifier   = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(8.dp),
                        )
                    }
                }

                // Status footer
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text       = "tracks: ${tracks.size}  ·  pins: ${waypoints.size}  ·  routes: ${routes.size}  ·  zones: ${areas.size}",
                        color      = Color.Gray,
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 11.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    if (livePosition != null) {
                        Text(
                            text       = "%.5f, %.5f".format(
                                Locale.US, livePosition.latitude, livePosition.longitude
                            ),
                            color      = Color(0xFF555555),
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 11.sp,
                        )
                    }
                }
            }

            // ── OBJECTS ──────────────────────────────────────────────────────
            OpsView.OBJECTS -> {
                Column(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text       = "ALL MAP OBJECTS",
                            color      = Color(0xFF555555),
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 11.sp,
                            modifier   = Modifier.weight(1f),
                        )
                        Text(
                            text       = "${waypoints.size + routes.size + areas.size} total",
                            color      = Color.Gray,
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 11.sp,
                        )
                    }

                    if (waypoints.isEmpty() && routes.isEmpty() && areas.isEmpty()) {
                        Box(
                            modifier         = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text       = "// no map objects yet\n" +
                                    "// drop pins or draw routes / zones on the MAP",
                                color      = Color(0xFF3A3A3A),
                                fontFamily = FontFamily.Monospace,
                                fontSize   = 12.sp,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier            = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding      = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            if (waypoints.isNotEmpty()) {
                                item("h-wpt") { ObjectSectionHeader("// WAYPOINTS  (${waypoints.size})") }
                                items(waypoints, key = { "wpt-${it.waypointId}" }) { wp ->
                                    MapObjectRow(
                                        glyph    = "◆",
                                        name     = wp.name,
                                        sublabel = "${wp.type.name}  " +
                                            if (wp.hasLocation())
                                                "%.5f, %.5f".format(Locale.US, wp.latitude, wp.longitude)
                                            else "PENDING LOCATION",
                                        linked   = wp.missionId != null,
                                        onClick  = { editingWaypoint = wp },
                                    )
                                }
                            }
                            if (routes.isNotEmpty()) {
                                item("h-rte") { ObjectSectionHeader("// ROUTES  (${routes.size})") }
                                items(routes, key = { "rte-${it.route.routeId}" }) { rw ->
                                    MapObjectRow(
                                        glyph    = "⟿",
                                        name     = rw.route.name,
                                        sublabel = "${rw.points.size} pts  ·  ${rw.route.type.name}",
                                        linked   = rw.route.missionId != null,
                                        onClick  = { editingRoute = rw.route },
                                    )
                                }
                            }
                            if (areas.isNotEmpty()) {
                                item("h-area") { ObjectSectionHeader("// ZONES  (${areas.size})") }
                                items(areas, key = { "area-${it.areaId}" }) { area ->
                                    MapObjectRow(
                                        glyph    = "▭",
                                        name     = area.name,
                                        sublabel = "zone overlay",
                                        linked   = area.missionId != null,
                                        onClick  = { editingArea = area },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── PLAN ─────────────────────────────────────────────────────────
            OpsView.PLAN -> {
                val globalWaypoints = waypoints.filter { it.missionId == null }

                Column(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text       = "SELECT PINS FOR A NEW MISSION",
                            color      = Color(0xFF555555),
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 11.sp,
                            modifier   = Modifier.weight(1f),
                        )
                        if (globalWaypoints.isNotEmpty()) {
                            val allSelected = selectedForMission.size == globalWaypoints.size
                            Text(
                                text       = if (allSelected) "[ DESELECT ALL ]" else "[ SELECT ALL ]",
                                color      = Color(0xFF444444),
                                fontFamily = FontFamily.Monospace,
                                fontSize   = 10.sp,
                                modifier   = Modifier.clickable {
                                    selectedForMission = if (allSelected) emptySet()
                                        else globalWaypoints.map { it.waypointId }.toSet()
                                },
                            )
                        }
                    }

                    if (globalWaypoints.isEmpty()) {
                        Box(
                            modifier         = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text       = "// no unassigned pins yet\n" +
                                    "// go to MAP and long-press to drop pins",
                                color      = Color(0xFF3A3A3A),
                                fontFamily = FontFamily.Monospace,
                                fontSize   = 12.sp,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier        = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding  = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            items(globalWaypoints, key = { it.waypointId }) { wp ->
                                PlanWaypointRow(
                                    wp       = wp,
                                    selected = wp.waypointId in selectedForMission,
                                    onToggle = {
                                        selectedForMission =
                                            if (wp.waypointId in selectedForMission)
                                                selectedForMission - wp.waypointId
                                            else
                                                selectedForMission + wp.waypointId
                                    },
                                )
                            }
                        }
                    }

                    Button(
                        onClick  = { showCreateMissionDialog = true },
                        enabled  = selectedForMission.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF161616)),
                    ) {
                        Text(
                            text       = if (selectedForMission.isEmpty())
                                "SELECT PINS TO CREATE A MISSION"
                            else
                                "⊕ CREATE MISSION (${selectedForMission.size} pins)",
                            fontFamily = FontFamily.Monospace,
                            color      = if (selectedForMission.isEmpty()) Color(0xFF444444) else Amber,
                            fontSize   = 13.sp,
                        )
                    }
                }
            }

            // ── PACKS ────────────────────────────────────────────────────────
            OpsView.PACKS -> {
                TilePackPanel(
                    activePack    = activePackFile,
                    onPackChanged = { file ->
                        activePackFile = file
                        opsView = OpsView.MAP
                    },
                )
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    // Place-pin dialog (after long press).
    // IMPORTANT: onConfirm receives lat/lon as stable parameters — do NOT read
    // pendingPinLat/pendingPinLon inside scope.launch because those state vars
    // are nulled out synchronously after launch() returns, before the coroutine
    // body runs, causing a NPE on the !! operator.
    if (pendingPinLat != null && pendingPinLon != null) {
        PlacePinDialog(
            lat       = pendingPinLat!!,
            lon       = pendingPinLon!!,
            onConfirm = { name, type, pinLat, pinLon ->
                scope.launch {
                    geoDao.insertWaypoint(
                        WaypointEntity(
                            missionId = null,
                            name      = name,
                            latitude  = pinLat,   // stable local copy
                            longitude = pinLon,   // stable local copy
                            type      = type,
                            createdMs = System.currentTimeMillis(),
                        )
                    )
                }
                pendingPinLat = null
                pendingPinLon = null
            },
            onDismiss = { pendingPinLat = null; pendingPinLon = null },
        )
    }

    // Edit-waypoint sheet (after tapping a marker)
    editingWaypoint?.let { wp ->
        EditWaypointDialog(
            waypoint  = wp,
            onUpdate  = { updated ->
                scope.launch { geoDao.updateWaypoint(updated) }
                editingWaypoint = null
            },
            onDelete  = {
                scope.launch { geoDao.deleteWaypoint(wp) }
                editingWaypoint = null
            },
            onDismiss = { editingWaypoint = null },
        )
    }

    // Create mission dialog (from PLAN view)
    if (showCreateMissionDialog) {
        CreateMissionDialog(
            pinCount  = selectedForMission.size,
            onConfirm = { name, desc ->
                scope.launch {
                    val missionId = missionDao.insertMission(
                        MissionEntity(
                            name        = name,
                            description = desc.ifBlank { null },
                            status      = MissionStatus.PLANNED,
                            createdMs   = System.currentTimeMillis(),
                            updatedMs   = System.currentTimeMillis(),
                        )
                    )
                    // Link selected global waypoints to the new mission
                    waypoints.filter { it.waypointId in selectedForMission }.forEach { wp ->
                        geoDao.updateWaypoint(wp.copy(missionId = missionId))
                    }
                    selectedForMission      = emptySet()
                    showCreateMissionDialog = false
                    opsView                 = OpsView.MAP
                }
            },
            onDismiss = { showCreateMissionDialog = false },
        )
    }

    // Save-route dialog (after drawing ≥2 points and tapping SAVE)
    if (showSaveRouteDialog) {
        NameRouteDialog(
            pointCount = drawPoints.size,
            onConfirm  = { name ->
                val pts = drawPoints.toList() // stable copy before state is cleared
                scope.launch {
                    val routeId = geoDao.insertRoute(
                        RouteEntity(
                            missionId = null,
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
                drawPoints.clear()
                mapTool = MapTool.NONE
                showSaveRouteDialog = false
            },
            onDismiss = { showSaveRouteDialog = false },
        )
    }

    // Edit/delete route sheet (after tapping a route polyline or OBJECTS row)
    editingRoute?.let { route ->
        EditRouteDialog(
            route     = route,
            onUpdate  = { updated ->
                scope.launch { geoDao.updateRoute(updated) }
                editingRoute = null
            },
            onDelete  = {
                scope.launch { geoDao.deleteRoute(route) }
                editingRoute = null
            },
            onDismiss = { editingRoute = null },
        )
    }

    // Save-zone dialog (after drawing ≥3 points and tapping SAVE)
    if (showSaveAreaDialog) {
        SaveAreaDialog(
            vertexCount = drawPoints.size,
            onConfirm   = { name, strokeColor ->
                val pts = drawPoints.toList() // stable copy before state is cleared
                val fill = (0x33 shl 24) or (strokeColor and 0x00FFFFFF)
                scope.launch {
                    geoDao.insertArea(
                        AreaOverlayEntity(
                            missionId   = null,
                            name        = name,
                            geoJson     = polygonToGeoJson(pts),
                            fillColor   = fill,
                            strokeColor = strokeColor,
                            createdMs   = System.currentTimeMillis(),
                        )
                    )
                }
                drawPoints.clear()
                mapTool = MapTool.NONE
                showSaveAreaDialog = false
            },
            onDismiss = { showSaveAreaDialog = false },
        )
    }

    // Edit/delete zone sheet (after tapping a zone polygon or OBJECTS row)
    editingArea?.let { area ->
        EditAreaDialog(
            area      = area,
            onUpdate  = { updated ->
                scope.launch { geoDao.updateArea(updated) }
                editingArea = null
            },
            onDelete  = {
                scope.launch { geoDao.deleteArea(area) }
                editingArea = null
            },
            onDismiss = { editingArea = null },
        )
    }
}

// =============================================================================
//  Sub-composables
// =============================================================================

@Composable
private fun ToolAction(
    label: String,
    enabled: Boolean = true,
    color: Color = Amber,
    onClick: () -> Unit,
) {
    Text(
        text       = "[ $label ]",
        color      = if (enabled) color else Color(0xFF333333),
        fontFamily = FontFamily.Monospace,
        fontSize   = 10.sp,
        modifier   = if (enabled) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@Composable
private fun LayerChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text       = "[ $label ]",
        color      = if (active) Amber else Color(0xFF333333),
        fontFamily = FontFamily.Monospace,
        fontSize   = 10.sp,
        modifier   = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun PlanWaypointRow(
    wp: WaypointEntity,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBlack, RoundedCornerShape(5.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text       = if (selected) "[✓]" else "[ ]",
            color      = if (selected) TermGreen else Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontSize   = 12.sp,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(wp.name, color = Ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text(
                text       = "${wp.type.name}  " +
                    if (wp.hasLocation()) "%.5f, %.5f".format(Locale.US, wp.latitude, wp.longitude)
                    else "PENDING LOCATION",
                color      = Color(0xFF555555),
                fontFamily = FontFamily.Monospace,
                fontSize   = 10.sp,
            )
        }
    }
}

@Composable
private fun ObjectSectionHeader(text: String) {
    Text(
        text       = text,
        color      = Color(0xFF444444),
        fontFamily = FontFamily.Monospace,
        fontSize   = 11.sp,
        modifier   = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

/** One management row in the OBJECTS view. Tapping opens the matching edit/delete
 *  sheet (the same one a map tap opens), so any object can be renamed or deleted
 *  without precisely tapping its geometry on the map. */
@Composable
private fun MapObjectRow(
    glyph: String,
    name: String,
    sublabel: String,
    linked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBlack, RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph, color = Amber, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text(sublabel, color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        Text(
            text       = if (linked) "● mission" else "global",
            color      = if (linked) TermGreen else Color(0xFF444444),
            fontFamily = FontFamily.Monospace,
            fontSize   = 10.sp,
        )
        Spacer(Modifier.width(10.dp))
        Text("EDIT", color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

// =============================================================================
//  Dialogs
// =============================================================================

@Composable
private fun PlacePinDialog(
    lat: Double,
    lon: Double,
    // lat/lon are passed BACK through onConfirm so the caller receives stable
    // local copies rather than reading from Compose state after a scope.launch.
    onConfirm: (name: String, type: WaypointType, lat: Double, lon: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val types  = WaypointType.entries
    var name   by remember { mutableStateOf("") }
    var typeIdx by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = PanelBlack,
        title = {
            Text("PLACE PIN", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text       = "%.6f, %.6f".format(Locale.US, lat, lon),
                    color      = Color(0xFF555555),
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 11.sp,
                )
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("Pin name", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = fieldColors,
                    textStyle     = monoStyle,
                )
                // Type cycler
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("◀", color = Amber, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                        modifier = Modifier.clickable { typeIdx = (typeIdx - 1 + types.size) % types.size })
                    Text(
                        "[ ${types[typeIdx].name} ]",
                        color = Amber, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    )
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
                Text("DROP PIN", fontFamily = FontFamily.Monospace, color = TermGreen)
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
private fun EditWaypointDialog(
    waypoint: WaypointEntity,
    onUpdate: (WaypointEntity) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val types   = WaypointType.entries
    var name    by remember(waypoint.waypointId) { mutableStateOf(waypoint.name) }
    var typeIdx by remember(waypoint.waypointId) {
        mutableStateOf(types.indexOfFirst { it == waypoint.type }.coerceAtLeast(0))
    }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = PanelBlack,
        title = {
            Text("EDIT PIN", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("Name", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = fieldColors,
                    textStyle     = monoStyle,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("◀", color = Amber, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                        modifier = Modifier.clickable { typeIdx = (typeIdx - 1 + types.size) % types.size })
                    Text(
                        "[ ${types[typeIdx].name} ]",
                        color = Amber, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    )
                    Text("▶", color = Amber, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                        modifier = Modifier.clickable { typeIdx = (typeIdx + 1) % types.size })
                }
                if (waypoint.hasLocation()) {
                    Text(
                        text       = "%.6f, %.6f".format(Locale.US, waypoint.latitude, waypoint.longitude),
                        color      = Color(0xFF555555),
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 10.sp,
                    )
                }
                if (confirmDelete) {
                    Text(
                        "Permanently delete this pin?",
                        color      = DimRed,
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 11.sp,
                    )
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
                        onClick = {
                            onUpdate(waypoint.copy(name = name.trim(), type = types[typeIdx]))
                        },
                    ) {
                        Text("SAVE", fontFamily = FontFamily.Monospace, color = TermGreen)
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

@Composable
private fun CreateMissionDialog(
    pinCount: Int,
    onConfirm: (name: String, description: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = PanelBlack,
        title = {
            Text("CREATE MISSION", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text       = "$pinCount pin${if (pinCount == 1) "" else "s"} will be linked to this mission.",
                    color      = Color(0xFF555555),
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 11.sp,
                )
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("Mission name", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = fieldColors,
                    textStyle     = monoStyle,
                )
                OutlinedTextField(
                    value         = desc,
                    onValueChange = { desc = it },
                    label         = { Text("Description (optional)", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = fieldColors,
                    textStyle     = monoStyle,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), desc.trim()) },
            ) {
                Text("CREATE", fontFamily = FontFamily.Monospace, color = TermGreen)
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
private fun NameRouteDialog(
    pointCount: Int,
    onConfirm: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = PanelBlack,
        title = {
            Text("SAVE ROUTE", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text       = "$pointCount point${if (pointCount == 1) "" else "s"} in this route.",
                    color      = Color(0xFF555555),
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 11.sp,
                )
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("Route name", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = fieldColors,
                    textStyle     = monoStyle,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
            ) {
                Text("SAVE", fontFamily = FontFamily.Monospace, color = TermGreen)
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
private fun EditRouteDialog(
    route: RouteEntity,
    onUpdate: (RouteEntity) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name          by remember(route.routeId) { mutableStateOf(route.name) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = PanelBlack,
        title = {
            Text("EDIT ROUTE", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("Name", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = fieldColors,
                    textStyle     = monoStyle,
                )
                Text(
                    text       = "${route.type.name} route",
                    color      = Color(0xFF555555),
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 10.sp,
                )
                if (confirmDelete) {
                    Text(
                        "Permanently delete this route?",
                        color      = DimRed,
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 11.sp,
                    )
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
                        onClick = { onUpdate(route.copy(name = name.trim())) },
                    ) {
                        Text("SAVE", fontFamily = FontFamily.Monospace, color = TermGreen)
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

// Zone fill is rendered at ~20% alpha over the chosen stroke color (see save handler).
private val AREA_COLORS = listOf(
    0xFFCC3B3B.toInt(), 0xFFE0B341.toInt(), 0xFF35D07F.toInt(),
    0xFF4FA3D8.toInt(), 0xFF9B59B6.toInt(), 0xFF00CFCF.toInt(),
)

@Composable
private fun SaveAreaDialog(
    vertexCount: Int,
    onConfirm: (name: String, colorInt: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var name     by remember { mutableStateOf("") }
    var colorIdx by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = PanelBlack,
        title = {
            Text("SAVE ZONE", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text       = "$vertexCount vertices in this zone.",
                    color      = Color(0xFF555555),
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 11.sp,
                )
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("Zone name", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = fieldColors,
                    textStyle     = monoStyle,
                )
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
                Text("SAVE", fontFamily = FontFamily.Monospace, color = TermGreen)
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
private fun EditAreaDialog(
    area: AreaOverlayEntity,
    onUpdate: (AreaOverlayEntity) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name          by remember(area.areaId) { mutableStateOf(area.name) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = PanelBlack,
        title = {
            Text("EDIT ZONE", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("Name", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = fieldColors,
                    textStyle     = monoStyle,
                )
                Text(
                    text       = "zone overlay",
                    color      = Color(0xFF555555),
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 10.sp,
                )
                if (confirmDelete) {
                    Text(
                        "Permanently delete this zone?",
                        color      = DimRed,
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 11.sp,
                    )
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
                        onClick = { onUpdate(area.copy(name = name.trim())) },
                    ) {
                        Text("SAVE", fontFamily = FontFamily.Monospace, color = TermGreen)
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
