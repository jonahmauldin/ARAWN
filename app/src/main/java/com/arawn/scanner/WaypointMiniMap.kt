package com.arawn.scanner

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.arawn.core.database.WaypointEntity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Read-only mini-map that shows a set of [WaypointEntity] markers framed to
 * encompass all of them. Intended for the Mission detail header — gives the
 * operator an instant geographic overview without opening the full OPS map.
 *
 * Callers should filter out placeholder (0,0) waypoints before passing the list.
 * Multi-touch zoom/pan is enabled so the user can inspect fine details.
 */
@Composable
fun WaypointMiniMap(
    waypoints: List<WaypointEntity>,
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
            setMaxZoomLevel(21.0)
            controller.setZoom(14.0)
        }
    }

    val renderKey = remember { intArrayOf(-1) }
    val framed    = remember { booleanArrayOf(false) }

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
        factory  = { mapView },
        modifier = modifier.clipToBounds(),
        update   = { mv ->
            if (renderKey[0] == waypoints.size) return@AndroidView
            renderKey[0] = waypoints.size

            mv.overlays.clear()
            waypoints.forEach { wp ->
                val marker = Marker(mv).apply {
                    position = GeoPoint(wp.latitude, wp.longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    // Reuse the same diamond icons as OpsMapPanel
                    icon = buildWaypointIcon(context, wp.type)
                    title = wp.name
                    setInfoWindow(null)
                }
                mv.overlays.add(marker)
            }

            if (!framed[0] && waypoints.isNotEmpty()) {
                if (waypoints.size == 1) {
                    mv.controller.setCenter(GeoPoint(waypoints[0].latitude, waypoints[0].longitude))
                    mv.controller.setZoom(15.0)
                } else {
                    val geoPoints = waypoints.map { GeoPoint(it.latitude, it.longitude) }
                    val bb = BoundingBox.fromGeoPoints(geoPoints)
                    mv.post { mv.zoomToBoundingBox(bb.increaseByScale(1.35f), false) }
                }
                framed[0] = true
            }
            mv.invalidate()
        },
    )
}

/** Returns true when a waypoint has been given a real position (not the default 0,0). */
internal fun WaypointEntity.hasLocation() = latitude != 0.0 || longitude != 0.0
