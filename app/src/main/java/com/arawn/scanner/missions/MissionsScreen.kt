package com.arawn.scanner.missions

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
import com.arawn.core.database.GeoDao
import com.arawn.core.database.MissionDao
import com.arawn.core.database.MissionEntity
import com.arawn.core.database.MissionItemEntity
import com.arawn.core.database.MissionItemType
import com.arawn.core.database.MissionStatus
import com.arawn.core.database.MissionWithItems
import com.arawn.core.database.SessionEntity
import com.arawn.core.database.WaypointEntity
import com.arawn.core.database.WaypointType
import com.arawn.core.database.WirelessDao
import kotlinx.coroutines.launch
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
    var showNewDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        missionDao.observeActiveMissions().collect { list ->
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
                text = "${missions.size} active",
                color = Color.Gray,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }

        if (missions.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "// no active missions\n// create one below",
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

// =============================================================================
// MISSION DETAIL
// =============================================================================

@Composable
private fun MissionDetailContent(
    missionId: Long,
    missionDao: MissionDao,
    geoDao: GeoDao,
    wirelessDao: WirelessDao,
    onBack: () -> Unit,
) {
    var missionWithItems by remember { mutableStateOf<MissionWithItems?>(null) }
    val waypoints       = remember { mutableStateListOf<WaypointEntity>() }
    val linkedSessions  = remember { mutableStateListOf<SessionEntity>() }
    val allSessions     = remember { mutableStateListOf<SessionEntity>() }

    var showAddItemDialog     by remember { mutableStateOf(false) }
    var showAddWaypointDialog by remember { mutableStateOf(false) }
    var showTagSessionDialog  by remember { mutableStateOf(false) }
    var itemToDelete          by remember { mutableStateOf<MissionItemEntity?>(null) }
    var waypointToDelete      by remember { mutableStateOf<WaypointEntity?>(null) }
    var sessionToUntag        by remember { mutableStateOf<SessionEntity?>(null) }
    var showArchiveConfirm    by remember { mutableStateOf(false) }

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
            )
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF1A1A1A))
        )
        Spacer(Modifier.height(4.dp))

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
            items(missionItems, key = { it.itemId }) { item ->
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
            items(waypoints, key = { it.waypointId }) { wp ->
                WaypointRow(waypoint = wp, onDelete = { waypointToDelete = wp })
            }
            item {
                AddRowButton("+ ADD WAYPOINT") { showAddWaypointDialog = true }
                Spacer(Modifier.height(8.dp))
            }

            // ── LINKED RECON SESSIONS ─────────────────────────────────────
            item {
                SectionHeader("// LINKED RECON SESSIONS  (${linkedSessions.size})")
            }
            items(linkedSessions, key = { it.sessionId }) { session ->
                SessionRow(session = session, onUntag = { sessionToUntag = session })
            }
            item {
                AddRowButton("+ TAG SESSION") { showTagSessionDialog = true }
                Spacer(Modifier.height(12.dp))
            }

            // ── ARCHIVE ───────────────────────────────────────────────────
            item {
                Button(
                    onClick = { showArchiveConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A0A0A)),
                ) {
                    Text(
                        text = "⊗ ARCHIVE MISSION",
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
            message = "Archive \"${mission.name}\"?\nIt will be hidden from the active list.",
            onConfirm = {
                scope.launch {
                    missionDao.updateMission(
                        mission.copy(archived = true, updatedMs = System.currentTimeMillis())
                    )
                    showArchiveConfirm = false
                    onBack()
                }
            },
            onDismiss = { showArchiveConfirm = false },
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
private fun WaypointRow(waypoint: WaypointEntity, onDelete: () -> Unit) {
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
            Text(
                text = "${waypoint.type.displayLabel()}  " +
                    "%.5f, %.5f".format(Locale.US, waypoint.latitude, waypoint.longitude),
                color = Color(0xFF555555),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
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

@Composable
private fun AddWaypointDialog(
    onConfirm: (name: String, type: WaypointType, lat: Double, lon: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val types = WaypointType.entries
    var name      by remember { mutableStateOf("") }
    var typeIndex by remember { mutableStateOf(0) }
    var lat       by remember { mutableStateOf("") }
    var lon       by remember { mutableStateOf("") }

    val type   = types[typeIndex]
    val latVal = lat.toDoubleOrNull()
    val lonVal = lon.toDoubleOrNull()
    val valid  = name.isNotBlank() &&
        latVal != null && latVal >= -90.0 && latVal <= 90.0 &&
        lonVal != null && lonVal >= -180.0 && lonVal <= 180.0

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelBlack,
        title = {
            Text("ADD WAYPOINT", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalTextField(value = name, label = "NAME", onValueChange = { name = it })

                // Type cycler — avoids nested scroll
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "◀",
                        color = Amber,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable {
                            typeIndex = (typeIndex - 1 + types.size) % types.size
                        },
                    )
                    Text(
                        text = "[ ${type.displayLabel()} ]",
                        color = Amber,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                    Text(
                        text = "▶",
                        color = Amber,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable {
                            typeIndex = (typeIndex + 1) % types.size
                        },
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TerminalTextField(
                        value = lat,
                        label = "LAT",
                        onValueChange = { lat = it },
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                    )
                    TerminalTextField(
                        value = lon,
                        label = "LON",
                        onValueChange = { lon = it },
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onConfirm(name.trim(), type, latVal!!, lonVal!!) },
                enabled = valid,
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
                Text("CONFIRM", fontFamily = FontFamily.Monospace, color = DimRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", fontFamily = FontFamily.Monospace, color = Color.Gray)
            }
        },
    )
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
