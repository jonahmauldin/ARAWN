package com.arawn.scanner.ui

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
import com.arawn.scanner.db.CoordinatePair
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * GPS track viewer for ARAWN (Phase 6).
 *
 * Renders the GPS path of one session over an osmdroid [MapView] using online
 * OpenStreetMap (MAPNIK) base tiles. Only tile-image requests use the network —
 * the scan database (Wi-Fi/BLE/GPS logs) is never transmitted; this composable
 * only ever reads coordinates locally to draw markers. To return to a fully
 * offline basemap, set [MapView.setUseDataConnection]`(false)` and drop a
 * .sqlite/.mbtiles/.zip tile archive into osmdroid's cache dir.
 *
 * It consumes the lightweight [CoordinatePair] projection (lat/lon/timestamp
 * only) — never the heavy Wi-Fi/BLE subtree — so panning stays smooth.
 */
@Composable
fun OfflineMapPanel(
    coordinates: List<CoordinatePair>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // One-time osmdroid configuration, before the first MapView is built.
    remember {
        Configuration.getInstance().apply {
            // Plain app-private prefs (no androidx.preference dependency); this
            // also pins the tile cache under the app's own storage.
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            // OSM's tile usage policy requires an identifying User-Agent; the app
            // package name is the standard, compliant value.
            userAgentValue = context.packageName
        }
    }

    // A single tiny dot drawable shared by every marker — no per-point bitmap
    // allocation, no text label, so large tracks stay light.
    val dotIcon = remember { buildDotIcon(context) }

    val mapView = remember {
        MapView(context).apply {
            setUseDataConnection(true) // online tiles: download the OSM basemap
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setMinZoomLevel(5.0)
            setMaxZoomLevel(18.0)
            controller.setZoom(15.0)
        }
    }

    // Track whether we've already framed the path, and how many points are drawn,
    // so recompositions don't rebuild overlays or yank the camera while the user
    // is panning.
    val renderState = remember { intArrayOf(-1) }
    val centered = remember { booleanArrayOf(false) }

    // Forward Compose lifecycle to the MapView and detach it on disposal.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
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
            // Rebuild overlays only when the point count actually changed.
            if (renderState[0] == coordinates.size) return@AndroidView
            renderState[0] = coordinates.size

            mv.overlays.clear()
            coordinates.forEach { c ->
                val marker = Marker(mv).apply {
                    position = GeoPoint(c.latitude, c.longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = dotIcon
                    title = null
                    // No info window → no popup, no text subtitle to lay out.
                    setInfoWindow(null)
                }
                mv.overlays.add(marker)
            }

            // Frame the track on the most recent fix exactly once.
            if (!centered[0] && coordinates.isNotEmpty()) {
                val last = coordinates.last()
                mv.controller.setCenter(GeoPoint(last.latitude, last.longitude))
                centered[0] = true
            }
            mv.invalidate()
        },
    )
}

/**
 * Builds a small filled circle as the shared marker icon — low-profile, no text,
 * drawn once and reused for every point.
 */
private fun buildDotIcon(context: Context): Drawable {
    val sizePx = (context.resources.displayMetrics.density * 8).toInt().coerceAtLeast(6)
    val bitmap = createBitmap(sizePx, sizePx)
    val canvas = Canvas(bitmap)
    val radius = sizePx / 2f
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#35D07F") // terminal green, matches theme
        style = Paint.Style.FILL
    }
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#0A0A0A")
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 1f
    }
    canvas.drawCircle(radius, radius, radius - ring.strokeWidth, fill)
    canvas.drawCircle(radius, radius, radius - ring.strokeWidth, ring)
    return BitmapDrawable(context.resources, bitmap)
}
