package com.arawn.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
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
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
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
 * Full-featured Ops Center map panel.
 *
 * Renders session GPS tracks, waypoints, and planned routes as distinct overlay
 * layers. Each layer can be toggled on/off independently.
 *
 * Interaction:
 *  - Long press on an empty map area → [onMapLongPress] with the lat/lon of the
 *    press point. The caller shows a "Place Pin" dialog and inserts a waypoint.
 *  - Tap on a waypoint marker → [onWaypointTap]. The caller shows an edit sheet.
 *
 * Tile source: online OSM MAPNIK by default; [activePackFile] switches to an
 * osmdroid OfflineTileProvider without recreating the composable.
 */
@Composable
fun OpsMapPanel(
    tracks: List<SessionTrack>,
    livePosition: CoordinatePair?,
    waypoints: List<WaypointEntity> = emptyList(),
    routes: List<RouteWithPoints> = emptyList(),
    activePackFile: File? = null,
    showTracks: Boolean = true,
    showWaypoints: Boolean = true,
    showRoutes: Boolean = true,
    onMapLongPress: ((Double, Double) -> Unit)? = null,
    onWaypointTap: ((WaypointEntity) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    remember {
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = context.packageName
        }
    }

    // Stable refs so closures inside AndroidView always call the latest callback
    // without triggering a MapView recreation.
    val onLongPressRef   = remember { mutableStateOf(onMapLongPress) }
    val onWaypointTapRef = remember { mutableStateOf(onWaypointTap) }
    SideEffect {
        onLongPressRef.value   = onMapLongPress
        onWaypointTapRef.value = onWaypointTap
    }

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

    val trackRenderKey    = remember(activePackFile?.path) { intArrayOf(-1) }
    val waypointRenderKey = remember(activePackFile?.path) { intArrayOf(-1) }
    val routeRenderKey    = remember(activePackFile?.path) { intArrayOf(-1) }
    val framed            = remember(activePackFile?.path) { booleanArrayOf(false) }
    val eventsAdded       = remember(activePackFile?.path) { booleanArrayOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
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

            // ── Map-events overlay (long press → pin) ─────────────────────
            if (!eventsAdded[0]) {
                val receiver = object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint) = false
                    override fun longPressHelper(p: GeoPoint): Boolean {
                        onLongPressRef.value?.invoke(p.latitude, p.longitude)
                        return true
                    }
                }
                mv.overlays.add(MapEventsOverlay(receiver))
                eventsAdded[0] = true
            }

            // ── Planned routes ─────────────────────────────────────────────
            val routeKey = if (showRoutes) routes.sumOf { it.points.size } else -999
            if (routeRenderKey[0] != routeKey) {
                routeRenderKey[0] = routeKey
                mv.overlays.removeAll { it is Polyline && it.outlinePaint.color == ROUTE_COLOR }
                if (showRoutes) {
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
            }

            // ── Waypoint markers ───────────────────────────────────────────
            val waypointKey = if (showWaypoints) waypoints.size else -999
            if (waypointRenderKey[0] != waypointKey) {
                waypointRenderKey[0] = waypointKey
                mv.overlays.removeAll { it is Marker && it !== liveMarker }
                if (showWaypoints) {
                    waypoints.forEach { wp ->
                        // Skip placeholder waypoints at origin
                        if (wp.latitude == 0.0 && wp.longitude == 0.0) return@forEach
                        val m = Marker(mv).apply {
                            position = GeoPoint(wp.latitude, wp.longitude)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = buildWaypointIcon(context, wp.type)
                            title = wp.name
                            setInfoWindow(null)
                            setOnMarkerClickListener { _, _ ->
                                onWaypointTapRef.value?.invoke(wp)
                                true
                            }
                        }
                        mv.overlays.add(m)
                    }
                }
            }

            // ── Session tracks ─────────────────────────────────────────────
            val totalPoints = if (showTracks) tracks.sumOf { it.coordinates.size } else -999
            if (trackRenderKey[0] != totalPoints) {
                trackRenderKey[0] = totalPoints
                mv.overlays.removeAll {
                    it is Polyline && it.outlinePaint.color != ROUTE_COLOR
                }
                if (showTracks) {
                    tracks.forEachIndexed { i, track ->
                        if (track.coordinates.size < 2) return@forEachIndexed
                        val poly = Polyline(mv)
                        poly.outlinePaint.color       = sessionColor(i, track.missionId)
                        poly.outlinePaint.strokeWidth = 6f
                        poly.outlinePaint.strokeCap   = Paint.Cap.ROUND
                        poly.outlinePaint.strokeJoin  = Paint.Join.ROUND
                        poly.setPoints(
                            track.coordinates.map { GeoPoint(it.latitude, it.longitude) }
                        )
                        mv.overlays.add(0, poly)
                    }
                }

                if (!framed[0] && totalPoints > 0) {
                    val allGeo = tracks.flatMap { it.coordinates }
                        .map { GeoPoint(it.latitude, it.longitude) }
                    val bb = BoundingBox.fromGeoPoints(allGeo)
                    mv.post { mv.zoomToBoundingBox(bb.increaseByScale(1.15f), false) }
                    framed[0] = true
                }
            }

            // ── Live position marker ───────────────────────────────────────
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
//  Shared helpers (internal so WaypointMiniMap can reuse them)
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

internal fun waypointColor(type: WaypointType): Int = when (type) {
    WaypointType.GENERIC     -> 0xFFE6E6E6.toInt()
    WaypointType.PARKING     -> 0xFF4FA3D8.toInt()
    WaypointType.ENTRY       -> 0xFF35D07F.toInt()
    WaypointType.OBSERVATION -> 0xFFE0B341.toInt()
    WaypointType.EXIT        -> 0xFF888888.toInt()
    WaypointType.HAZARD      -> 0xFFCC3B3B.toInt()
    WaypointType.CACHE       -> 0xFF9B59B6.toInt()
    WaypointType.POI         -> 0xFF1ABC9C.toInt()
}

/** Diamond-shaped marker icon, colour-coded by waypoint type. */
internal fun buildWaypointIcon(context: Context, type: WaypointType): Drawable {
    val dp   = context.resources.displayMetrics.density
    val size = (dp * 14).toInt().coerceAtLeast(10)
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val r = size / 2f
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = waypointColor(type); style = Paint.Style.FILL
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#0A0A0A")
        style = Paint.Style.STROKE
        strokeWidth = dp * 1.2f
    }
    val path = android.graphics.Path().apply {
        moveTo(r, 0f); lineTo(size.toFloat(), r)
        lineTo(r, size.toFloat()); lineTo(0f, r); close()
    }
    canvas.drawPath(path, fill)
    canvas.drawPath(path, stroke)
    return BitmapDrawable(context.resources, bitmap)
}

private fun sessionColor(index: Int, missionId: Long?): Int {
    if (missionId != null) {
        val hue = (missionId * 137.508f) % 360f
        return AndroidColor.HSVToColor(floatArrayOf(hue, 0.70f, 0.88f))
    }
    return TRACK_COLORS[index % TRACK_COLORS.size]
}

private val TRACK_COLORS = listOf(
    0xFF35D07F.toInt(),
    0xFFE0B341.toInt(),
    0xFF4FA3D8.toInt(),
    0xFFCC5A5A.toInt(),
    0xFF9B59B6.toInt(),
    0xFF1ABC9C.toInt(),
)

private const val ROUTE_COLOR = 0xFF00CFCF.toInt()

private fun buildLiveIcon(context: Context): Drawable {
    val dp = context.resources.displayMetrics.density
    val sizePx = (dp * 14).toInt().coerceAtLeast(10)
    val bitmap = createBitmap(sizePx, sizePx)
    val canvas = Canvas(bitmap)
    val r = sizePx / 2f
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#0A0A0A")
        style = Paint.Style.STROKE; strokeWidth = dp * 1.5f
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#E0B341"); style = Paint.Style.FILL
    }
    canvas.drawCircle(r, r, r - ring.strokeWidth, fill)
    canvas.drawCircle(r, r, r - ring.strokeWidth, ring)
    return BitmapDrawable(context.resources, bitmap)
}
