package com.arawn.scanner.map

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
import com.arawn.core.database.AreaOverlayEntity
import com.arawn.core.database.CoordinatePair
import com.arawn.core.database.RouteEntity
import com.arawn.core.database.RouteWithPoints
import com.arawn.core.database.WaypointEntity
import com.arawn.core.database.WaypointType
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * Active editing tool for [TacticalMapPanel]. NONE keeps the read/long-press-pin
 * behavior. MARKER reports single taps via [TacticalMapPanel]'s onMapTap (the caller
 * opens a place-marker dialog) but draws no live preview. ROUTE/AREA/MEASURE feed
 * the hoisted drawPoints and render a live preview.
 */
enum class MapTool { NONE, MARKER, ROUTE, AREA, MEASURE }

/**
 * A session GPS track paired with its mission context so the map can colour-code
 * tracks by mission. Pass coordinates already run through [cleanTrack].
 */
data class SessionTrack(
    val coordinates: List<CoordinatePair>,
    val missionId: Long? = null,
)

/**
 * The single drawing-capable osmdroid map used by both the OPS Center and the
 * Mission planner. Renders tracks, waypoints, planned routes, and area/zone
 * polygons as independently toggle-able layers, and supports interactive editing
 * via [tool]:
 *
 *  - [MapTool.NONE]  — pan/zoom; long-press drops a pin ([onMapLongPress]); tapping
 *    a route/area/waypoint fires the matching callback.
 *  - [MapTool.ROUTE] / [MapTool.AREA] — single taps call [onMapTap]; the caller
 *    appends the point to its hoisted [drawPoints] list, which is rendered live
 *    (amber polyline / translucent polygon).
 *  - [MapTool.MEASURE] — taps feed the same [drawPoints]; with two points the panel
 *    draws a ruler line (the caller computes distance/bearing from its own points).
 *
 * Working geometry is hoisted so the toolbar buttons (UNDO / CLEAR / SAVE) live in
 * the caller. Tile source: online OSM by default; [activePackFile] switches to an
 * offline pack without recreating unrelated state.
 */
@Composable
fun TacticalMapPanel(
    tracks: List<SessionTrack> = emptyList(),
    waypoints: List<WaypointEntity> = emptyList(),
    routes: List<RouteWithPoints> = emptyList(),
    areas: List<AreaOverlayEntity> = emptyList(),
    livePosition: CoordinatePair? = null,
    activePackFile: File? = null,
    showTracks: Boolean = true,
    showWaypoints: Boolean = true,
    showRoutes: Boolean = true,
    showAreas: Boolean = true,
    tool: MapTool = MapTool.NONE,
    drawPoints: List<GeoPoint> = emptyList(),
    onMapTap: ((GeoPoint) -> Unit)? = null,
    onMapLongPress: ((Double, Double) -> Unit)? = null,
    onWaypointTap: ((WaypointEntity) -> Unit)? = null,
    onRouteTap: ((RouteEntity) -> Unit)? = null,
    onAreaTap: ((AreaOverlayEntity) -> Unit)? = null,
    onCenterChanged: ((Double, Double) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    remember {
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = context.packageName
        }
    }

    // Stable refs so the overlay closures always see the latest callback / tool
    // without forcing a MapView rebuild.
    val onTapRef        = remember { mutableStateOf(onMapTap) }
    val onLongPressRef  = remember { mutableStateOf(onMapLongPress) }
    val onWaypointRef   = remember { mutableStateOf(onWaypointTap) }
    val onRouteRef      = remember { mutableStateOf(onRouteTap) }
    val onAreaRef       = remember { mutableStateOf(onAreaTap) }
    val onCenterRef     = remember { mutableStateOf(onCenterChanged) }
    val toolRef         = remember { mutableStateOf(tool) }
    SideEffect {
        onTapRef.value       = onMapTap
        onLongPressRef.value = onMapLongPress
        onWaypointRef.value  = onWaypointTap
        onRouteRef.value     = onRouteTap
        onAreaRef.value      = onAreaTap
        onCenterRef.value    = onCenterChanged
        toolRef.value        = tool
    }

    val packKey = activePackFile?.path
    val mapView = remember(packKey) { buildMapView(context, activePackFile) }

    val liveMarker = remember(packKey) {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = buildLiveIcon(context)
            title = null
            setInfoWindow(null)
            isEnabled = false
        }
    }

    // Per-layer overlay holders so each layer can be rebuilt independently.
    val areaHolder     = remember(packKey) { mutableListOf<Overlay>() }
    val trackHolder    = remember(packKey) { mutableListOf<Overlay>() }
    val routeHolder    = remember(packKey) { mutableListOf<Overlay>() }
    val waypointHolder = remember(packKey) { mutableListOf<Overlay>() }
    val previewHolder  = remember(packKey) { mutableListOf<Overlay>() }

    val areaKey     = remember(packKey) { intArrayOf(Int.MIN_VALUE) }
    val trackKey    = remember(packKey) { intArrayOf(Int.MIN_VALUE) }
    val routeKey    = remember(packKey) { intArrayOf(Int.MIN_VALUE) }
    val waypointKey = remember(packKey) { intArrayOf(Int.MIN_VALUE) }
    val framed      = remember(packKey) { booleanArrayOf(false) }
    val wired       = remember(packKey) { booleanArrayOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, packKey) {
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

            // ── One-time wiring: tap/long-press events + center listener ──────
            if (!wired[0]) {
                val receiver = object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                        return if (toolRef.value != MapTool.NONE) {
                            onTapRef.value?.invoke(p); true
                        } else false
                    }
                    override fun longPressHelper(p: GeoPoint): Boolean {
                        val cb = onLongPressRef.value ?: return false
                        cb(p.latitude, p.longitude); return true
                    }
                }
                // Added first → checked LAST in osmdroid's top-down tap dispatch, so it
                // is the fallback once routes/areas/markers have had their chance.
                mv.overlays.add(MapEventsOverlay(receiver))
                if (onCenterRef.value != null) {
                    mv.addMapListener(object : MapListener {
                        override fun onScroll(event: ScrollEvent?): Boolean {
                            val c = mv.mapCenter
                            onCenterRef.value?.invoke(c.latitude, c.longitude); return false
                        }
                        override fun onZoom(event: ZoomEvent?): Boolean {
                            val c = mv.mapCenter
                            onCenterRef.value?.invoke(c.latitude, c.longitude); return false
                        }
                    })
                }
                wired[0] = true
            }

            // ── Areas (bottom layer) ─────────────────────────────────────────
            val ak = if (showAreas) areas.hashCode() else -1
            if (areaKey[0] != ak) {
                areaKey[0] = ak
                mv.overlays.removeAll(areaHolder); areaHolder.clear()
                if (showAreas) {
                    areas.forEach { area ->
                        val pts = geoJsonToPolygon(area.geoJson)
                        if (pts.size < 3) return@forEach
                        val poly = Polygon(mv).apply {
                            setPoints(pts)
                            fillPaint.color    = (area.fillColor ?: AREA_FILL_DEFAULT)
                            outlinePaint.color = (area.strokeColor ?: AREA_STROKE_DEFAULT)
                            outlinePaint.strokeWidth = 3f
                            title = area.name
                            setOnClickListener { _, _, _ ->
                                if (toolRef.value != MapTool.NONE) false
                                else { onAreaRef.value?.invoke(area); true }
                            }
                        }
                        mv.overlays.add(poly); areaHolder.add(poly)
                    }
                }
            }

            // ── Tracks ───────────────────────────────────────────────────────
            val tk = if (showTracks) tracks.sumOf { it.coordinates.size } else -1
            if (trackKey[0] != tk) {
                trackKey[0] = tk
                mv.overlays.removeAll(trackHolder); trackHolder.clear()
                if (showTracks) {
                    tracks.forEachIndexed { i, track ->
                        if (track.coordinates.size < 2) return@forEachIndexed
                        val poly = Polyline(mv).apply {
                            outlinePaint.color       = sessionColor(i, track.missionId)
                            outlinePaint.strokeWidth = 6f
                            outlinePaint.strokeCap   = Paint.Cap.ROUND
                            outlinePaint.strokeJoin  = Paint.Join.ROUND
                            setPoints(track.coordinates.map { GeoPoint(it.latitude, it.longitude) })
                        }
                        mv.overlays.add(poly); trackHolder.add(poly)
                    }
                }
            }

            // ── Planned routes ───────────────────────────────────────────────
            val rk = if (showRoutes) routes.hashCode() else -1
            if (routeKey[0] != rk) {
                routeKey[0] = rk
                mv.overlays.removeAll(routeHolder); routeHolder.clear()
                if (showRoutes) {
                    routes.forEach { rw ->
                        if (rw.points.size < 2) return@forEach
                        val poly = Polyline(mv).apply {
                            outlinePaint.color       = (rw.route.color ?: ROUTE_COLOR)
                            outlinePaint.strokeWidth = 5f
                            outlinePaint.strokeCap   = Paint.Cap.ROUND
                            outlinePaint.alpha       = 200
                            setPoints(rw.points.sortedBy { it.seq }
                                .map { GeoPoint(it.latitude, it.longitude) })
                            title = rw.route.name
                            setOnClickListener { _, _, _ ->
                                if (toolRef.value != MapTool.NONE) false
                                else { onRouteRef.value?.invoke(rw.route); true }
                            }
                        }
                        mv.overlays.add(poly); routeHolder.add(poly)
                    }
                }
            }

            // ── Waypoint markers ─────────────────────────────────────────────
            val wk = if (showWaypoints) waypoints.hashCode() else -1
            if (waypointKey[0] != wk) {
                waypointKey[0] = wk
                mv.overlays.removeAll(waypointHolder); waypointHolder.clear()
                if (showWaypoints) {
                    waypoints.forEach { wp ->
                        if (wp.latitude == 0.0 && wp.longitude == 0.0) return@forEach
                        val m = Marker(mv).apply {
                            position = GeoPoint(wp.latitude, wp.longitude)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = buildWaypointIcon(context, wp.type)
                            title = wp.name
                            setInfoWindow(null)
                            setOnMarkerClickListener { _, _ ->
                                onWaypointRef.value?.invoke(wp); true
                            }
                        }
                        mv.overlays.add(m); waypointHolder.add(m)
                    }
                }
            }

            // ── Live preview for the active draw/measure tool ─────────────────
            // Cheap (a few overlays) and always correct, so rebuilt every pass.
            mv.overlays.removeAll(previewHolder); previewHolder.clear()
            if ((tool == MapTool.ROUTE || tool == MapTool.AREA || tool == MapTool.MEASURE) &&
                drawPoints.isNotEmpty()
            ) {
                val pts = drawPoints.toList()
                when (tool) {
                    MapTool.AREA -> {
                        if (pts.size >= 3) {
                            val poly = Polygon(mv).apply {
                                setPoints(pts)
                                fillPaint.color    = PREVIEW_FILL
                                outlinePaint.color = PREVIEW_COLOR
                                outlinePaint.strokeWidth = 4f
                            }
                            mv.overlays.add(poly); previewHolder.add(poly)
                        } else if (pts.size == 2) {
                            previewHolder.addLine(mv, pts, PREVIEW_COLOR)
                        }
                    }
                    MapTool.ROUTE, MapTool.MEASURE -> {
                        if (pts.size >= 2) {
                            val color = if (tool == MapTool.MEASURE) MEASURE_COLOR else PREVIEW_COLOR
                            previewHolder.addLine(mv, pts, color)
                        }
                    }
                    MapTool.NONE, MapTool.MARKER -> Unit
                }
                // Vertex dots so each tapped point is visible.
                pts.forEach { gp ->
                    val dot = Marker(mv).apply {
                        position = gp
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = buildVertexIcon(context)
                        setInfoWindow(null)
                        isDraggable = false
                    }
                    mv.overlays.add(dot); previewHolder.add(dot)
                }
            }

            // ── Live position marker (kept on top) ───────────────────────────
            if (livePosition != null) {
                liveMarker.position  = GeoPoint(livePosition.latitude, livePosition.longitude)
                liveMarker.isEnabled = true
                mv.overlays.remove(liveMarker)
                mv.overlays.add(liveMarker)
            } else {
                liveMarker.isEnabled = false
            }

            // ── First-time framing ───────────────────────────────────────────
            if (!framed[0]) {
                val allGeo = buildList {
                    tracks.forEach { t -> t.coordinates.forEach { add(GeoPoint(it.latitude, it.longitude)) } }
                    waypoints.forEach { if (it.latitude != 0.0 || it.longitude != 0.0) add(GeoPoint(it.latitude, it.longitude)) }
                    routes.forEach { r -> r.points.forEach { add(GeoPoint(it.latitude, it.longitude)) } }
                    areas.forEach { a -> addAll(geoJsonToPolygon(a.geoJson)) }
                }
                when {
                    allGeo.size >= 2 -> {
                        val bb = BoundingBox.fromGeoPoints(allGeo)
                        mv.post { mv.zoomToBoundingBox(bb.increaseByScale(1.25f), false) }
                        framed[0] = true
                    }
                    allGeo.size == 1 -> {
                        mv.controller.setCenter(allGeo.first())
                        mv.controller.setZoom(16.0)
                        framed[0] = true
                    }
                    livePosition != null -> {
                        mv.controller.animateTo(GeoPoint(livePosition.latitude, livePosition.longitude))
                        mv.controller.setZoom(16.0)
                        framed[0] = true
                    }
                }
            }

            mv.invalidate()
        },
    )
}

/** Helper: append a simple polyline through [pts] to the overlay list + holder. */
private fun MutableList<Overlay>.addLine(mv: MapView, pts: List<GeoPoint>, color: Int) {
    val line = Polyline(mv).apply {
        outlinePaint.color       = color
        outlinePaint.strokeWidth = 5f
        outlinePaint.strokeCap   = Paint.Cap.ROUND
        setPoints(pts)
    }
    mv.overlays.add(line); add(line)
}

// =============================================================================
//  Map construction + icons (moved here from the retired OpsMapPanel)
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

/** Small hollow dot marking a tapped vertex while drawing a route/area. */
private fun buildVertexIcon(context: Context): Drawable {
    val dp = context.resources.displayMetrics.density
    val size = (dp * 9).toInt().coerceAtLeast(6)
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val r = size / 2f
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#E0B341"); style = Paint.Style.FILL
    }
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#0A0A0A"); style = Paint.Style.STROKE; strokeWidth = dp
    }
    canvas.drawCircle(r, r, r - ring.strokeWidth, fill)
    canvas.drawCircle(r, r, r - ring.strokeWidth, ring)
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

private const val ROUTE_COLOR         = 0xFF00CFCF.toInt()
private const val MEASURE_COLOR       = 0xFFFFFFFF.toInt()
private const val PREVIEW_COLOR       = 0xFFE0B341.toInt()
private val PREVIEW_FILL              = 0x33E0B341
private val AREA_FILL_DEFAULT         = 0x33CC3B3B
private val AREA_STROKE_DEFAULT       = 0xFFCC3B3B.toInt()

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
