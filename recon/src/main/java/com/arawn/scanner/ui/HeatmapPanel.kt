package com.arawn.scanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One heat sample — the maximum signal strength seen at a single GPS fix.
 * Accumulated in memory from live [ScanPacket]s; not persisted to Room.
 */
data class HeatPoint(
    val lat: Double,
    val lon: Double,
    /** Strongest RSSI (dBm) seen at this GPS fix across all Wi-Fi + BLE signals. */
    val maxRssiDbm: Int,
)

/**
 * Live RSSI heatmap overlay (Phase G).
 *
 * Renders [points] as semi-transparent circles projected via an equirectangular
 * transform. Circle colour encodes signal strength:
 *   ≥ −50 dBm → green (strong),  ≥ −70 dBm → amber (moderate),
 *   < −70 dBm → red   (weak/distant).
 *
 * Overlapping circles accumulate opacity naturally, so dense scan areas appear
 * brighter — giving an informal density dimension on top of the RSSI colour.
 */
@Composable
fun HeatmapPanel(
    points: List<HeatPoint>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(Color(0xFF0D0D0D)),
        contentAlignment = Alignment.Center,
    ) {
        if (points.isEmpty()) {
            Text(
                text = "// start scanning to build the heatmap",
                color = Color(0xFF3A3A3A),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(16.dp),
            )
            return@Box
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val W = size.width
            val H = size.height

            val lats = points.map { it.lat }
            val lons = points.map { it.lon }
            val minLat = lats.min();  val maxLat = lats.max()
            val minLon = lons.min();  val maxLon = lons.max()

            // Expand degenerate ranges (single point or tiny cluster).
            val latRange = (maxLat - minLat).coerceAtLeast(0.002)
            val lonRange = (maxLon - minLon).coerceAtLeast(0.002)

            // Pad 12 % around the bounding box so points aren't clipped to the edge.
            val latPad = latRange * 0.12
            val lonPad = lonRange * 0.12
            val padMinLat = minLat - latPad;  val padMaxLat = maxLat + latPad
            val padMinLon = minLon - lonPad;  val padMaxLon = maxLon + lonPad
            val padLatRange = padMaxLat - padMinLat
            val padLonRange = padMaxLon - padMinLon

            fun px(lon: Double): Float = ((lon - padMinLon) / padLonRange * W).toFloat()
            fun py(lat: Double): Float = (H - (lat - padMinLat) / padLatRange * H).toFloat()

            val radius = 22f  // pixels — large enough for overlapping glow

            for (pt in points) {
                val color = when {
                    pt.maxRssiDbm >= -50 -> Color(0xFF35D07F) // green — strong
                    pt.maxRssiDbm >= -70 -> Color(0xFFE0B341) // amber — moderate
                    else                 -> Color(0xFFCC3B3B) // red   — weak
                }
                drawCircle(
                    color  = color,
                    radius = radius,
                    center = Offset(px(pt.lon), py(pt.lat)),
                    alpha  = 0.35f,
                )
            }
        }
    }
}
