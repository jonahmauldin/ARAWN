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
 * Strip GPS artifacts from a session track before it is drawn. Two passes:
 *
 *  Pass 1 (per-point gates) drops:
 *   - null-island fixes (lat≈0 AND lon≈0) logged when there was no GPS lock;
 *   - "teleport" outliers whose implied speed from the previous *kept* point
 *     exceeds [maxSpeedMps] (~55 m/s ≈ 200 km/h — generous for vehicle recon, but
 *     well under the GPS jumps that draw spurious straight lines across the map).
 *
 *  Pass 2 (3-point spike detector) catches the "dogleg" pattern that pass 1 misses:
 *   a single bad fix at a *believable* distance — the line dives out for ~400 m and
 *   snaps right back. The speed gate can't see it because the bad arm is short
 *   enough to look like normal movement; the round-trip detour vs. the through-
 *   chord reveals it. If `(d(prev,cur) + d(cur,next)) / d(prev,next) > [spikeRatio]`
 *   AND the spike arm is over [minSpikeArmM] (so we don't smooth real wiggles),
 *   drop the middle point. We always keep the first and last points so the track
 *   never shrinks at its endpoints.
 *
 * This is what removes the stray "yellow lines going to spots I wasn't at" without
 * touching the shared [com.arawn.core.database.WirelessDao.getSessionCoordinates]
 * projection (which is also used by the recon offline map and CSV export). The DAO
 * already orders by timestamp, so single forward passes suffice.
 */
fun cleanTrack(
    coords: List<CoordinatePair>,
    maxSpeedMps: Double = 55.0,
    spikeRatio: Double = 3.0,
    minSpikeArmM: Double = 30.0,
): List<CoordinatePair> {
    // Pass 1: per-point gates.
    val passOne = ArrayList<CoordinatePair>(coords.size)
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
        passOne.add(c)
        prev = c
    }

    if (passOne.size < 3) return passOne

    // Pass 2: 3-point spike pass. Walks the survivors, comparing each interior
    // candidate to the last *kept* point and the next raw point. A spike has a
    // detour ratio much larger than 1 — for a true out-and-back, the chord
    // collapses and the ratio explodes.
    val out = ArrayList<CoordinatePair>(passOne.size)
    out.add(passOne.first())
    var i = 1
    while (i < passOne.size - 1) {
        val last = out.last()
        val cur  = passOne[i]
        val next = passOne[i + 1]
        val dPrevCur = haversineMeters(last.latitude, last.longitude, cur.latitude,  cur.longitude)
        val dCurNext = haversineMeters(cur.latitude,  cur.longitude,  next.latitude, next.longitude)
        val dPrevNext = haversineMeters(last.latitude, last.longitude, next.latitude, next.longitude)
        val ratio = if (dPrevNext > 1.0) (dPrevCur + dCurNext) / dPrevNext
                    else Double.POSITIVE_INFINITY
        if (ratio > spikeRatio && dPrevCur > minSpikeArmM) {
            i++ // skip cur — it's a dogleg outlier
            continue
        }
        out.add(cur)
        i++
    }
    out.add(passOne.last())
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
