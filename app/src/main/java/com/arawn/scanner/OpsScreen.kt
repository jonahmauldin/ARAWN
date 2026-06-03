package com.arawn.scanner

import androidx.compose.foundation.background
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
import com.arawn.core.database.CoordinatePair
import com.arawn.core.database.GeoDao
import com.arawn.core.database.MissionDao
import com.arawn.core.database.MissionEntity
import com.arawn.core.database.MissionStatus
import com.arawn.core.database.RouteWithPoints
import com.arawn.core.database.WaypointEntity
import com.arawn.core.database.WaypointType
import com.arawn.core.database.WirelessDao
import com.arawn.scanner.tilePacks.TilePackPanel
import com.arawn.scanner.tilePacks.loadActivePack
import kotlinx.coroutines.launch
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

private enum class OpsView { MAP, PLAN, PACKS }

/**
 * Operations Center — map view, mission planner, and tile-pack manager.
 *
 * ### MAP view
 * Shows all session GPS tracks, waypoint pins, and planned routes on a live
 * osmdroid map. Layer toggles control what's visible. Long-pressing the map
 * drops a new global waypoint at the press point — the "Place Pin" dialog
 * pre-fills the coordinates so the operator only needs to name it and pick a
 * type. Tapping an existing waypoint opens an edit/delete sheet.
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

    LaunchedEffect(Unit) {
        wirelessDao.observeSessions().collect { sessions ->
            val loaded = sessions.mapNotNull { session ->
                val coords = wirelessDao.getSessionCoordinates(session.sessionId)
                if (coords.isEmpty()) null else SessionTrack(coords, session.missionId)
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

    // ── Tile packs ────────────────────────────────────────────────────────────
    var activePackFile by remember { mutableStateOf<File?>(loadActivePack(context)) }

    // ── Layer toggles ─────────────────────────────────────────────────────────
    var showTracks    by remember { mutableStateOf(true) }
    var showWaypoints by remember { mutableStateOf(true) }
    var showRoutes    by remember { mutableStateOf(true) }

    // ── Map interaction state ─────────────────────────────────────────────────
    var pendingPinLat   by remember { mutableStateOf<Double?>(null) }
    var pendingPinLon   by remember { mutableStateOf<Double?>(null) }
    var editingWaypoint by remember { mutableStateOf<WaypointEntity?>(null) }

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
                // Layer toggle row
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LayerChip("TRACKS",    showTracks)    { showTracks    = !showTracks }
                    LayerChip("WPT",       showWaypoints) { showWaypoints = !showWaypoints }
                    LayerChip("ROUTES",    showRoutes)    { showRoutes    = !showRoutes }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text       = "long press = pin",
                        color      = Color(0xFF333333),
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 10.sp,
                    )
                }

                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().background(PanelBlack).clipToBounds(),
                ) {
                    OpsMapPanel(
                        tracks         = tracks,
                        livePosition   = livePosition,
                        waypoints      = waypoints,
                        routes         = routes,
                        activePackFile = activePackFile,
                        showTracks     = showTracks,
                        showWaypoints  = showWaypoints,
                        showRoutes     = showRoutes,
                        onMapLongPress = { lat, lon ->
                            pendingPinLat = lat
                            pendingPinLon = lon
                        },
                        onWaypointTap  = { wp -> editingWaypoint = wp },
                        modifier       = Modifier.fillMaxSize(),
                    )

                    if (tracks.isEmpty() && waypoints.isEmpty() && livePosition == null) {
                        Text(
                            text       = "// long press map to drop a pin\n" +
                                "// start RECON to build GPS tracks",
                            color      = Color(0xFF3A3A3A),
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 12.sp,
                            modifier   = Modifier.align(Alignment.Center).padding(16.dp),
                        )
                    }
                }

                // Status footer
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text       = "tracks: ${tracks.size}  ·  pins: ${waypoints.count { it.missionId == null }}",
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

    // Place-pin dialog (after long press)
    if (pendingPinLat != null && pendingPinLon != null) {
        PlacePinDialog(
            lat      = pendingPinLat!!,
            lon      = pendingPinLon!!,
            onConfirm = { name, type ->
                scope.launch {
                    geoDao.insertWaypoint(
                        WaypointEntity(
                            missionId = null,
                            name      = name,
                            latitude  = pendingPinLat!!,
                            longitude = pendingPinLon!!,
                            type      = type,
                            createdMs = System.currentTimeMillis(),
                        )
                    )
                }
                pendingPinLat = null; pendingPinLon = null
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
}

// =============================================================================
//  Sub-composables
// =============================================================================

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

// =============================================================================
//  Dialogs
// =============================================================================

@Composable
private fun PlacePinDialog(
    lat: Double,
    lon: Double,
    onConfirm: (name: String, type: WaypointType) -> Unit,
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
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), types[typeIdx]) },
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
