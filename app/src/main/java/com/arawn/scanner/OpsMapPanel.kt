package com.arawn.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.arawn.core.database.CoordinatePair
import com.arawn.core.database.RouteWithPoints
import com.arawn.core.database.WaypointEntity
import com.arawn.core.database.WaypointType
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * A session GPS track paired with its mission context so the Ops map can
 * colour-code tracks by mission.
 */
data class SessionTrack(
    val coordinates: List<CoordinatePair>,
    val missionId: Long? = null,
)

/**
 * Operations Center map panel.
 *
 * Renders:
 *  - Session GPS tracks as colour-coded polylines (green = latest untagged,
 *    mission-tagged tracks use a deterministic hue derived from missionId).
 *  - All waypoints as small type-coloured diamond markers.
 *  - All planned routes as thin cyan polylines.
 *  - Live position as an amber dot.
 *
 * Tile source: online OSM MAPNIK by default. When [activePackFile] is non-null
 * and points to a valid .mbtiles/.sqlite archive, the map is re-created with
 * osmdroid's OfflineTileProvider so no network tiles are fetched.
 *
 * Max zoom is 21 — tiles are scaled from zoom 19 above that, which looks
 * pixelated but is still useful for precise location pinpointing.
 */
@Composable
fun OpsMapPanel(
    tracks: List<SessionTrack>,
    livePosition: CoordinatePair?,
    waypoints: List<WaypointEntity> = emptyList(),
    routes: List<RouteWithPoints> = emptyList(),
    activePackFile: File? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    remember {
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = context.packageName
        }
    }

    // Re-create MapView when the active tile pack changes.
    val mapView = remember(activePackFile?.path) {
        buildMapView(context, activePackFile)
    }

    val liveMarker = remember(activePackFile?.path) {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = buildLiveIcon(context)
            title = null
            setInfoWindow(null)
            isEnabled = false
        }
    }

    // Keys used to throttle overlay rebuilds — reset on each MapView recreation.
    val trackRenderKey    = remember(activePackFile?.path) { intArrayOf(-1) }
    val waypointRenderKey = remember(activePackFile?.path) { intArrayOf(-1) }
    val routeRenderKey    = remember(activePackFile?.path) { intArrayOf(-1) }
    val framed            = remember(activePackFile?.path) { booleanArrayOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    // Keyed to pack path so the old MapView is detached before the new one is set up.
    DisposableEffect(lifecycleOwner, activePackFile?.path) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE  -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    AndroidView(
        factory  = { mapView },
        modifier = modifier.clipToBounds(),
        update   = { mv ->

            // ── Waypoints ──────────────────────────────────────────────────
            val waypointKey = waypoints.size
            if (waypointRenderKey[0] != waypointKey) {
                waypointRenderKey[0] = waypointKey
                mv.overlays.removeAll { it is Marker && it !== liveMarker }
                waypoints.forEach { wp ->
                    val m = Marker(mv).apply {
                        position = GeoPoint(wp.latitude, wp.longitude)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = buildWaypointIcon(context, wp.type)
                        title = wp.name
                        setInfoWindow(null)
                    }
                    mv.overlays.add(m)
                }
            }

            // ── Planned routes ─────────────────────────────────────────────
            val routeKey = routes.sumOf { it.points.size }
            if (routeRenderKey[0] != routeKey) {
                routeRenderKey[0] = routeKey
                mv.overlays.removeAll { it is Polyline && (it.outlinePaint.color == ROUTE_COLOR) }
                routes.forEach { rw ->
                    if (rw.points.size < 2) return@forEach
                    val poly = Polyline(mv)
                    poly.outlinePaint.color       = ROUTE_COLOR
                    poly.outlinePaint.strokeWidth = 3f
                    poly.outlinePaint.strokeCap   = Paint.Cap.ROUND
                    poly.outlinePaint.alpha       = 180
                    poly.setPoints(rw.points.sortedBy { it.seq }
                        .map { GeoPoint(it.latitude, it.longitude) })
                    mv.overlays.add(0, poly)
                }
            }

            // ── Session tracks ─────────────────────────────────────────────
            val totalPoints = tracks.sumOf { it.coordinates.size }
            if (trackRenderKey[0] != totalPoints) {
                trackRenderKey[0] = totalPoints
                mv.overlays.removeAll {
                    it is Polyline && it.outlinePaint.color != ROUTE_COLOR
                }
                tracks.forEachIndexed { i, track ->
                    if (track.coordinates.size < 2) return@forEachIndexed
                    val poly = Polyline(mv)
                    poly.outlinePaint.color      = sessionColor(i, track.missionId)
                    poly.outlinePaint.strokeWidth = 6f
                    poly.outlinePaint.strokeCap   = Paint.Cap.ROUND
                    poly.outlinePaint.strokeJoin  = Paint.Join.ROUND
                    poly.setPoints(track.coordinates.map { GeoPoint(it.latitude, it.longitude) })
                    mv.overlays.add(0, poly)
                }

                if (!framed[0] && totalPoints > 0) {
                    val allGeo = tracks.flatMap { it.coordinates }
                        .map { GeoPoint(it.latitude, it.longitude) }
                    val bb = BoundingBox.fromGeoPoints(allGeo)
                    mv.post { mv.zoomToBoundingBox(bb.increaseByScale(1.15f), false) }
                    framed[0] = true
                }
            }

            // ── Live position ──────────────────────────────────────────────
            if (livePosition != null) {
                liveMarker.position  = GeoPoint(livePosition.latitude, livePosition.longitude)
                liveMarker.isEnabled = true
                if (!mv.overlays.contains(liveMarker)) mv.overlays.add(liveMarker)
                if (!framed[0]) {
                    mv.controller.animateTo(GeoPoint(livePosition.latitude, livePosition.longitude))
                    mv.controller.setZoom(16.0)
                    framed[0] = true
                }
            } else {
                liveMarker.isEnabled = false
            }

            mv.invalidate()
        },
    )
}

// =============================================================================
//  Helpers
// =============================================================================

private fun buildMapView(context: Context, packFile: File?): MapView {
    val mv = MapView(context)
    if (packFile != null && packFile.exists()) {
        runCatching {
            val provider = org.osmdroid.tileprovider.modules.OfflineTileProvider(
                org.osmdroid.tileprovider.util.SimpleRegisterReceiver(context),
                arrayOf(packFile),
            )
            mv.tileProvider = provider
            mv.setUseDataConnection(false)
        }.onFailure {
            // Corrupt or unsupported archive — fall back to online.
            mv.setUseDataConnection(true)
            mv.setTileSource(TileSourceFactory.MAPNIK)
        }
    } else {
        mv.setUseDataConnection(true)
        mv.setTileSource(TileSourceFactory.MAPNIK)
    }
    mv.setMultiTouchControls(true)
    mv.setMinZoomLevel(5.0)
    mv.setMaxZoomLevel(21.0)
    mv.controller.setZoom(13.0)
    return mv
}

/**
 * Session track color.
 * Mission-tagged tracks get a deterministic hue from the mission ID (golden-angle
 * spread so adjacent IDs land far apart on the color wheel).
 * Un-tagged tracks cycle through [TRACK_COLORS] with the most-recent session
 * (index 0) getting terminal green.
 */
private fun sessionColor(index: Int, missionId: Long?): Int {
    if (missionId != null) {
        val hue = (missionId * 137.508f) % 360f
        return AndroidColor.HSVToColor(floatArrayOf(hue, 0.70f, 0.88f))
    }
    return TRACK_COLORS[index % TRACK_COLORS.size]
}

/** Colors for un-tagged session tracks, newest session = index 0 (terminal green). */
private val TRACK_COLORS = listOf(
    0xFF35D07F.toInt(), // terminal green  (most recent)
    0xFFE0B341.toInt(), // amber
    0xFF4FA3D8.toInt(), // steel blue
    0xFFCC5A5A.toInt(), // red
    0xFF9B59B6.toInt(), // purple
    0xFF1ABC9C.toInt(), // teal
)

/** Thin cyan used for planned route polylines — distinct from session tracks. */
private const val ROUTE_COLOR = 0xFF00CFCF.toInt()

/** WaypointType → ARGB marker fill color. */
private fun waypointColor(type: WaypointType): Int = when (type) {
    WaypointType.GENERIC     -> 0xFFE6E6E6.toInt() // white
    WaypointType.PARKING     -> 0xFF4FA3D8.toInt() // steel blue
    WaypointType.ENTRY       -> 0xFF35D07F.toInt() // green
    WaypointType.OBSERVATION -> 0xFFE0B341.toInt() // amber
    WaypointType.EXIT        -> 0xFF888888.toInt() // gray
    WaypointType.HAZARD      -> 0xFFCC3B3B.toInt() // red
    WaypointType.CACHE       -> 0xFF9B59B6.toInt() // purple
    WaypointType.POI         -> 0xFF1ABC9C.toInt() // teal
}

/** Small diamond marker for a waypoint, colour-coded by type. */
private fun buildWaypointIcon(context: Context, type: WaypointType): Drawable {
    val dp = context.resources.displayMetrics.density
    val size = (dp * 12).toInt().coerceAtLeast(8)
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val r = size / 2f
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = waypointColor(type)
        style = Paint.Style.FILL
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#0A0A0A")
        style = Paint.Style.STROKE
        strokeWidth = dp * 1f
    }
    // Draw a diamond (rotated square) shape.
    val path = android.graphics.Path().apply {
        moveTo(r, 0f)
        lineTo(size.toFloat(), r)
        lineTo(r, size.toFloat())
        lineTo(0f, r)
        close()
    }
    canvas.drawPath(path, fill)
    canvas.drawPath(path, stroke)
    return BitmapDrawable(context.resources, bitmap)
}

/** Small filled amber circle — marks the current live GPS position. */
private fun buildLiveIcon(context: Context): Drawable {
    val dp = context.resources.displayMetrics.density
    val sizePx = (dp * 14).toInt().coerceAtLeast(10)
    val bitmap = createBitmap(sizePx, sizePx)
    val canvas = Canvas(bitmap)
    val r = sizePx / 2f
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#0A0A0A")
        style = Paint.Style.STROKE
        strokeWidth = dp * 1.5f
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#E0B341") // amber
        style = Paint.Style.FILL
    }
    canvas.drawCircle(r, r, r - ring.strokeWidth, fill)
    canvas.drawCircle(r, r, r - ring.strokeWidth, ring)
    return BitmapDrawable(context.resources, bitmap)
}
