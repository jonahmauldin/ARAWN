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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.arawn.core.database.CoordinatePair
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Operations Center map panel (Phase B).
 *
 * Renders all previous session tracks as polylines and a live position marker over
 * an osmdroid OpenStreetMap basemap. Designed for the OPS tab home screen — shows
 * the full operational picture at a glance without the per-signal detail of the
 * Recon tab's [com.arawn.scanner.ui.OfflineMapPanel].
 *
 * Track rendering:
 *  - Each session is one [Polyline], colored from [TRACK_COLORS] cycling newest-first.
 *  - Sessions with only one point are skipped (no line to draw).
 *  - Live position gets a distinct amber marker, updated on every GPS fix.
 *
 * Camera:
 *  - Framed once to encompass all track points (+ live position) on first data load.
 *  - Not re-framed while the user is panning/zooming.
 *
 * Performance:
 *  - Track polylines are rebuilt only when the total point count changes.
 *  - The live marker is just moved on every GPS fix — no overlay rebuild.
 */
@Composable
fun OpsMapPanel(
    tracks: List<List<CoordinatePair>>,
    livePosition: CoordinatePair?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    remember {
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = context.packageName
        }
    }

    val mapView = remember {
        MapView(context).apply {
            setUseDataConnection(true)
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setMinZoomLevel(5.0)
            setMaxZoomLevel(18.0)
            controller.setZoom(13.0)
        }
    }

    val liveMarker = remember {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = buildLiveIcon(context)
            title = null
            setInfoWindow(null)
            isEnabled = false
        }
    }

    val trackRenderKey = remember { intArrayOf(-1) }
    val framed = remember { booleanArrayOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
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
        factory = { mapView },
        modifier = modifier,
        update = { mv ->
            val totalPoints = tracks.sumOf { it.size }

            // Rebuild track polylines only when the point count changes.
            if (trackRenderKey[0] != totalPoints) {
                trackRenderKey[0] = totalPoints

                // Remove old polylines, keep the live marker.
                val iter = mv.overlays.iterator()
                while (iter.hasNext()) {
                    if (iter.next() is Polyline) iter.remove()
                }

                // Add tracks newest-first so the most recent renders on top.
                tracks.forEachIndexed { i, track ->
                    if (track.size < 2) return@forEachIndexed
                    val poly = Polyline(mv)
                    poly.outlinePaint.color = TRACK_COLORS[i % TRACK_COLORS.size]
                    poly.outlinePaint.strokeWidth = 6f
                    poly.outlinePaint.strokeCap = Paint.Cap.ROUND
                    poly.outlinePaint.strokeJoin = Paint.Join.ROUND
                    poly.setPoints(track.map { GeoPoint(it.latitude, it.longitude) })
                    // Insert below live marker (index 0 = bottom of stack).
                    mv.overlays.add(0, poly)
                }

                // Frame to show all content exactly once (first non-empty load).
                if (!framed[0] && totalPoints > 0) {
                    val allGeo = tracks.flatten().map { GeoPoint(it.latitude, it.longitude) }
                    val bb = BoundingBox.fromGeoPoints(allGeo)
                    mv.post { mv.zoomToBoundingBox(bb.increaseByScale(1.15f), false) }
                    framed[0] = true
                }
            }

            // Update live position marker on every GPS fix.
            if (livePosition != null) {
                liveMarker.position = GeoPoint(livePosition.latitude, livePosition.longitude)
                liveMarker.isEnabled = true
                if (!mv.overlays.contains(liveMarker)) mv.overlays.add(liveMarker)
                // If there are no tracks yet, at least center on live position.
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

/** Colors for session tracks, newest session = index 0 (terminal green). */
private val TRACK_COLORS = listOf(
    0xFF35D07F.toInt(), // terminal green  (most recent)
    0xFFE0B341.toInt(), // amber
    0xFF4FA3D8.toInt(), // steel blue
    0xFFCC5A5A.toInt(), // red
    0xFF9B59B6.toInt(), // purple
    0xFF1ABC9C.toInt(), // teal
)

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
