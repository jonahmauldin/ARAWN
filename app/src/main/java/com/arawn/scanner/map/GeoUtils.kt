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
 * Strip GPS artifacts from a session track before it is drawn. Passes run from the
 * cheapest / most reliable signal to the purely geometric ones:
 *
 *  Pass 0 (per-point validity) drops fixes that are bad on their own merits,
 *   independent of any neighbour:
 *   - null-island fixes (lat≈0 AND lon≈0) logged when there was no GPS lock;
 *   - low-quality fixes whose reported horizontal accuracy exceeds
 *     [accuracyLimitM]. This is the single most reliable outlier signal: a fix
 *     that "teleports" a quarter mile is almost always one the GPS itself flagged
 *     as a wide-radius (cell / network / multipath) estimate. Fixes with no
 *     accuracy estimate (`accuracyM < 0`) are kept — we can't judge them here.
 *
 *  Pass 0.5 (stale lead-in trim) drops a cold-start lock still pointing at the
 *   *previous* session's location before it can become pass 1's anchor and drag
 *   the entire track over to it. Only fires when the first leg is physically
 *   impossible (a teleport) yet the next leg is plausible — so a genuinely fast
 *   start is never trimmed.
 *
 *  Pass 1 (teleport gate) drops outliers whose implied speed from the previous
 *   *kept* point exceeds [maxSpeedMps] (~55 m/s ≈ 200 km/h — generous for vehicle
 *   recon, but well under the GPS jumps that draw spurious straight lines). When
 *   two fixes share a timestamp (batched write, dt≈0) speed is undefined, so we
 *   fall back to a flat [maxCoincidentJumpM] cap — you cannot move 60 m in zero
 *   time. Because the final fix is tested against its predecessor here, a
 *   teleported *last* point is caught too.
 *
 *  Pass 2 (3-point spike detector) catches the "dogleg" pattern passes 0–1 miss:
 *   a single bad fix at a *believable* distance and a *believable* (optimistic)
 *   accuracy — the line dives out for ~400 m and snaps right back. The speed gate
 *   can't see it because the bad arm is short enough to look like normal movement;
 *   the round-trip detour vs. the through-chord reveals it. If
 *   `(d(prev,cur) + d(cur,next)) / d(prev,next) > [spikeRatio]` AND the spike arm
 *   is over [minSpikeArmM] (so we don't smooth real wiggles), drop the middle point.
 *
 * Filtering, not smoothing: every *kept* point is drawn exactly where it was
 * logged, so real road curvature and corners survive untouched. We only ever
 * remove points that are physically implausible — we never average positions
 * (which would round off the very corners we want to preserve).
 *
 * This removes the stray "yellow lines going to spots I wasn't at" without touching
 * the shared [com.arawn.core.database.WirelessDao.getSessionCoordinates] projection
 * (also used by the recon offline map and CSV export). The DAO orders by timestamp,
 * so single forward passes suffice.
 */
fun cleanTrack(
    coords: List<CoordinatePair>,
    maxSpeedMps: Double = 55.0,
    accuracyLimitM: Float = 50f,
    spikeRatio: Double = 3.0,
    minSpikeArmM: Double = 30.0,
    maxCoincidentJumpM: Double = 60.0,
): List<CoordinatePair> {
    // Pass 0: per-point validity gates (no neighbour needed).
    val valid = ArrayList<CoordinatePair>(coords.size)
    for (c in coords) {
        // No-fix sentinel — never draw a leg to the Gulf of Guinea.
        if (abs(c.latitude) < 1e-6 && abs(c.longitude) < 1e-6) continue
        // Wide-radius fix the GPS itself distrusted. accuracyM < 0 == "no estimate"
        // and is kept; only a positive, over-limit radius is rejected.
        if (c.accuracyM >= 0f && c.accuracyM > accuracyLimitM) continue
        valid.add(c)
    }
    if (valid.size < 3) return valid

    // Pass 0.5: peel a stale leading fix so pass 1 anchors on a real position.
    // Bounded to a few points so a short burst of bad lead-in fixes clears without
    // ever eating into a genuine track.
    var lead = 0
    while (valid.size - lead >= 3 && lead < 3) {
        val p0 = valid[lead]; val p1 = valid[lead + 1]; val p2 = valid[lead + 2]
        if (isTeleport(p0, p1, maxSpeedMps, maxCoincidentJumpM) &&
            !isTeleport(p1, p2, maxSpeedMps, maxCoincidentJumpM)) {
            lead++
        } else break
    }
    val seed = if (lead > 0) valid.subList(lead, valid.size) else valid

    // Pass 1: teleport gate (sequential, vs the last *kept* point).
    val gated = ArrayList<CoordinatePair>(seed.size)
    for (c in seed) {
        val last = gated.lastOrNull()
        if (last == null || !isTeleport(last, c, maxSpeedMps, maxCoincidentJumpM)) {
            gated.add(c)
        }
    }
    if (gated.size < 3) return gated

    // Pass 2: 3-point spike pass. Walks the survivors, comparing each interior
    // candidate to the last *kept* point and the next raw point. A spike has a
    // detour ratio much larger than 1 — for a true out-and-back, the chord
    // collapses and the ratio explodes.
    val out = ArrayList<CoordinatePair>(gated.size)
    out.add(gated.first())
    var i = 1
    while (i < gated.size - 1) {
        val last = out.last()
        val cur  = gated[i]
        val next = gated[i + 1]
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
    out.add(gated.last())
    return out
}

/**
 * Is moving directly from [a] to [b] physically implausible? Over [maxSpeedMps]
 * when time elapsed between the fixes, or over [maxCoincidentJumpM] metres when
 * they share a timestamp (speed undefined). Used by the lead-in trim and the main
 * teleport gate so both apply the exact same rule.
 */
private fun isTeleport(
    a: CoordinatePair,
    b: CoordinatePair,
    maxSpeedMps: Double,
    maxCoincidentJumpM: Double,
): Boolean {
    val dist = haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
    val dtSec = (b.timestampMs - a.timestampMs) / 1000.0
    return if (dtSec > 0) dist / dtSec > maxSpeedMps else dist > maxCoincidentJumpM
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
