package com.arawn.scanner.map

import com.arawn.core.database.CoordinatePair
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geo helpers shared by the tactical map (track cleanup, the measure ruler,
 * and area/zone GeoJSON (de)serialization). No Compose / Android UI here so the
 * functions stay unit-testable and reusable from any layer.
 */

/** Mean Earth radius in metres. */
private const val EARTH_RADIUS_M = 6_371_000.0

/** Great-circle (haversine) distance between two lat/lon points, in metres. */
fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
}

/** Initial bearing from point 1 → point 2, in degrees 0..360 (0 = true north). */
fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/**
 * Strip GPS artifacts from a session track before it is drawn:
 *  - drops null-island fixes (lat≈0 AND lon≈0) logged when there was no GPS lock;
 *  - drops "teleport" outliers whose implied speed from the previous *kept* point
 *    exceeds [maxSpeedMps] (~55 m/s ≈ 200 km/h — generous for vehicle recon, but
 *    well under the GPS jumps that draw spurious straight lines across the map).
 *
 * This is what removes the stray "yellow lines going to spots I wasn't at" without
 * touching the shared [com.arawn.core.database.WirelessDao.getSessionCoordinates]
 * projection (which is also used by the recon offline map and CSV export). The DAO
 * already orders by timestamp, so a single forward pass suffices; the first valid
 * fix anchors the track.
 */
fun cleanTrack(
    coords: List<CoordinatePair>,
    maxSpeedMps: Double = 55.0,
): List<CoordinatePair> {
    val out = ArrayList<CoordinatePair>(coords.size)
    var prev: CoordinatePair? = null
    for (c in coords) {
        // No-fix sentinel — never draw a leg to the Gulf of Guinea.
        if (abs(c.latitude) < 1e-6 && abs(c.longitude) < 1e-6) continue
        val p = prev
        if (p != null) {
            val dtSec = (c.timestampMs - p.timestampMs) / 1000.0
            if (dtSec > 0) {
                val dist = haversineMeters(p.latitude, p.longitude, c.latitude, c.longitude)
                if (dist / dtSec > maxSpeedMps) continue // teleport outlier — drop
            }
        }
        out.add(c)
        prev = c
    }
    return out
}

/**
 * Serialize a polygon outer ring to a minimal GeoJSON `Polygon` string. Coordinates
 * are written `[lon, lat]` per the GeoJSON spec; the ring is auto-closed.
 */
fun polygonToGeoJson(points: List<GeoPoint>): String {
    val ring = JSONArray()
    points.forEach { p -> ring.put(JSONArray().put(p.longitude).put(p.latitude)) }
    if (points.size >= 2) {
        val first = points.first()
        val last = points.last()
        if (first.latitude != last.latitude || first.longitude != last.longitude) {
            ring.put(JSONArray().put(first.longitude).put(first.latitude))
        }
    }
    return JSONObject()
        .put("type", "Polygon")
        .put("coordinates", JSONArray().put(ring))
        .toString()
}

/**
 * Parse a GeoJSON `Polygon` (outer/first ring) back into points. Returns an empty
 * list on any malformed input so a bad row can never crash the map.
 */
fun geoJsonToPolygon(json: String): List<GeoPoint> = runCatching {
    val coords = JSONObject(json).getJSONArray("coordinates")
    if (coords.length() == 0) return emptyList()
    val ring = coords.getJSONArray(0)
    val out = ArrayList<GeoPoint>(ring.length())
    for (i in 0 until ring.length()) {
        val pair = ring.getJSONArray(i)
        out.add(GeoPoint(pair.getDouble(1), pair.getDouble(0))) // [lon,lat] → GeoPoint(lat,lon)
    }
    out
}.getOrDefault(emptyList())

/** Compact human label for the measure ruler, e.g. "128 m · 045°" or "1.4 km · 045°". */
fun measureLabel(distanceM: Double, bearingDeg: Double): String {
    val dist = if (distanceM >= 1000) "%.2f km".format(distanceM / 1000.0)
               else "%.0f m".format(distanceM)
    return "$dist · %03.0f°".format(bearingDeg)
}
