package com.arawn.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arawn.core.database.CoordinatePair
import com.arawn.core.database.GeoDao
import com.arawn.core.database.RouteWithPoints
import com.arawn.core.database.WaypointEntity
import com.arawn.core.database.WirelessDao
import com.arawn.scanner.tilePacks.TilePackPanel
import com.arawn.scanner.tilePacks.loadActivePack
import kotlinx.coroutines.flow.combine
import java.io.File
import java.util.Locale

private val Amber      = Color(0xFFE0B341)
private val TermGreen  = Color(0xFF35D07F)
private val PanelBlack = Color(0xFF0A0A0A)

private enum class OpsView { MAP, PACKS }

/**
 * Operations Center home screen.
 *
 * Shows an osmdroid map with all session GPS tracks, planned-route polylines,
 * waypoint markers, and a live GPS fix marker. A MAP/PACKS tab row switches
 * between the map view and the offline tile-pack manager.
 */
@Composable
fun OpsScreen(
    wirelessDao: WirelessDao,
    geoDao: GeoDao,
    livePosition: CoordinatePair?,
) {
    val context   = LocalContext.current
    var opsView   by remember { mutableStateOf(OpsView.MAP) }

    val tracks    = remember { mutableStateListOf<SessionTrack>() }
    val waypoints = remember { mutableStateListOf<WaypointEntity>() }
    val routes    = remember { mutableStateListOf<RouteWithPoints>() }

    // Active tile pack (null = online MAPNIK). Stored in SharedPreferences so it
    // survives navigation away from OPS; updating it triggers MapView recreation.
    var activePackFile by remember { mutableStateOf<File?>(loadActivePack(context)) }

    // Collect all sessions (with missionId) and their GPS tracks together.
    LaunchedEffect(Unit) {
        wirelessDao.observeSessions().collect { sessions ->
            val loaded = sessions.mapNotNull { session ->
                val coords = wirelessDao.getSessionCoordinates(session.sessionId)
                if (coords.isEmpty()) null
                else SessionTrack(coords, session.missionId)
            }
            tracks.clear()
            tracks.addAll(loaded)
        }
    }

    // Collect all waypoints.
    LaunchedEffect(Unit) {
        geoDao.observeAllWaypoints().collect { list ->
            waypoints.clear()
            waypoints.addAll(list)
        }
    }

    // Collect all routes with their points.
    LaunchedEffect(Unit) {
        geoDao.observeAllRoutesWithPoints().collect { list ->
            routes.clear()
            routes.addAll(list)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // ── Header ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text       = "ARAWN // OPERATIONS CENTER",
                color      = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize   = 15.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text       = if (livePosition != null) "● LIVE" else "○ IDLE",
                color      = if (livePosition != null) TermGreen else Color.Gray,
                fontFamily = FontFamily.Monospace,
                fontSize   = 13.sp,
            )
        }

        // ── MAP / PACKS tab row ───────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            OpsView.entries.forEach { view ->
                val selected = opsView == view
                Text(
                    text       = "[ ${view.name} ]",
                    color      = if (selected) Amber else Color(0xFF333333),
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 12.sp,
                    modifier   = Modifier
                        .clickable { opsView = view }
                        .padding(end = 12.dp),
                )
            }
            if (activePackFile != null) {
                Spacer(Modifier.weight(1f))
                Text(
                    text       = "⊟ offline",
                    color      = TermGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 11.sp,
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1A1A1A)))

        // ── Content area ──────────────────────────────────────────────────
        when (opsView) {
            OpsView.MAP -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(PanelBlack)
                        .clipToBounds(),
                ) {
                    OpsMapPanel(
                        tracks        = tracks,
                        livePosition  = livePosition,
                        waypoints     = waypoints,
                        routes        = routes,
                        activePackFile = activePackFile,
                        modifier      = Modifier.fillMaxSize(),
                    )

                    if (tracks.isEmpty() && livePosition == null) {
                        Text(
                            text     = "// no session tracks yet\n// start a RECON scan to populate the map",
                            color    = Color(0xFF3A3A3A),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        )
                    }
                }

                // ── Status footer ─────────────────────────────────────────
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text       = "tracks: ${tracks.size}" +
                            if (waypoints.isNotEmpty()) "  ·  wpts: ${waypoints.size}" else "",
                        color      = Color.Gray,
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 11.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    if (livePosition != null) {
                        Text(
                            text       = "%.5f, %.5f".format(
                                Locale.US,
                                livePosition.latitude,
                                livePosition.longitude,
                            ),
                            color      = Color(0xFF555555),
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 11.sp,
                        )
                    }
                }
            }

            OpsView.PACKS -> {
                TilePackPanel(
                    activePack    = activePackFile,
                    onPackChanged = { file ->
                        activePackFile = file
                        // Return to map view so the new tile source is visible.
                        opsView = OpsView.MAP
                    },
                )
            }
        }
    }
}
