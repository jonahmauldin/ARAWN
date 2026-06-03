package com.arawn.scanner

import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arawn.core.database.CoordinatePair
import com.arawn.core.database.WirelessDao
import java.util.Locale

private val Amber       = Color(0xFFE0B341)
private val TermGreen   = Color(0xFF35D07F)
private val PanelBlack  = Color(0xFF0A0A0A)
private val Ink         = Color(0xFFE6E6E6)

/**
 * Operations Center home screen (Phase B).
 *
 * Shows an osmdroid map with:
 *  - All previous session GPS tracks rendered as colored polylines
 *    (most recent session = terminal green, older sessions cycle through a palette).
 *  - Live scan position as an amber marker whenever a scan is running.
 *
 * Track data is loaded reactively from [wirelessDao.observeSessions] — the map
 * refreshes automatically when a new session is added or finalized. Loading all
 * track coordinates is N+1 queries (one per session) but the session count is
 * small in practice and all runs happen on IO dispatcher via Room.
 *
 * Future phases will overlay waypoints (Phase C), area polygons (Phase C),
 * and mission-filtered views (Phase C/D).
 */
@Composable
fun OpsScreen(
    wirelessDao: WirelessDao,
    livePosition: CoordinatePair?,
) {
    val tracks = remember { mutableStateListOf<List<CoordinatePair>>() }

    // Observe all sessions; for each, load its GPS track. The Flow stays live so
    // adding a new session (start tracking → OPS tab) triggers a map refresh.
    LaunchedEffect(Unit) {
        wirelessDao.observeSessions().collect { sessions ->
            val loaded = sessions.map { session ->
                wirelessDao.getSessionCoordinates(session.sessionId)
            }.filter { it.isNotEmpty() }
            tracks.clear()
            tracks.addAll(loaded)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "ARAWN // OPERATIONS CENTER",
                color = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (livePosition != null) "● LIVE" else "○ IDLE",
                color = if (livePosition != null) TermGreen else Color.Gray,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }

        // Map — fills the remaining space.
        // clipToBounds is a second line of defence (belt-and-suspenders with the clip
        // already on AndroidView inside OpsMapPanel) so the MapView cannot overdraw
        // the header Row above or the footer Row below regardless of recomposition order.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(PanelBlack)
                .clipToBounds(),
        ) {
            OpsMapPanel(
                tracks = tracks,
                livePosition = livePosition,
                modifier = Modifier.fillMaxSize(),
            )

            // Overlay hint shown only when both tracks and live position are absent
            if (tracks.isEmpty() && livePosition == null) {
                Text(
                    text = "// no session tracks yet\n// start a RECON scan to populate the map",
                    color = Color(0xFF3A3A3A),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                )
            }
        }

        // Status footer
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "tracks: ${tracks.size}",
                color = Color.Gray,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            Spacer(Modifier.weight(1f))
            if (livePosition != null) {
                Text(
                    text = "%.5f, %.5f".format(Locale.US, livePosition.latitude, livePosition.longitude),
                    color = Color(0xFF555555),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
