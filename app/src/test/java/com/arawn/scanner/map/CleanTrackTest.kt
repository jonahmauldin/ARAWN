package com.arawn.scanner.map

import com.arawn.core.database.CoordinatePair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

/**
 * JVM unit tests for [cleanTrack], the track-overlay outlier scrubber.
 *
 * Fixtures are built in real-world metres from a base point (lat 47°N) and
 * converted to lat/lon, so the assertions read as physical movement: a steady
 * walk, a wide-radius junk fix, a fast "teleport", an out-and-back dogleg spike,
 * a stale cold-start lock, and — crucially — a genuine 90° corner that must
 * survive (the scrubber filters, it never smooths).
 *
 * Runs on the host JVM during `./gradlew :app:test` — no Android context.
 */
class CleanTrackTest {

    private val baseLat = 47.0
    private val baseLon = 11.0
    private val mPerDegLat = 111_320.0
    private val mPerDegLon = 111_320.0 * cos(Math.toRadians(baseLat))

    /** A fix [eastM]/[northM] metres from base, at [tSec] s, accuracy [accM] m. */
    private fun fix(eastM: Double, northM: Double, tSec: Double, accM: Float = 5f) =
        CoordinatePair(
            latitude    = baseLat + northM / mPerDegLat,
            longitude   = baseLon + eastM / mPerDegLon,
            timestampMs = (tSec * 1000).toLong(),
            accuracyM   = accM,
        )

    /** Metres east of base for a result point — handy for "no point is way out there". */
    private fun CoordinatePair.eastM() = (longitude - baseLon) * mPerDegLon

    /** A straight eastbound walk: [n] fixes, one per second, [stepM] apart. */
    private fun straightWalk(n: Int, stepM: Double = 10.0) =
        (0 until n).map { i -> fix(i * stepM, 0.0, i.toDouble()) }

    // ── No false positives ───────────────────────────────────────────────────

    @Test
    fun cleanWalkIsLeftUntouched() {
        val track = straightWalk(12)
        assertEquals("a clean track must pass through unchanged", track, cleanTrack(track))
    }

    @Test
    fun sharpCornerIsPreserved() {
        // 6 fixes east, hard left, 6 fixes north — a real 90° turn. The corner
        // vertex must NOT be mistaken for a spike (its detour ratio is only ~1.4).
        val track = buildList {
            for (i in 0..5) add(fix(i * 15.0, 0.0, i.toDouble()))
            for (j in 1..5) add(fix(75.0, j * 15.0, (5 + j).toDouble()))
        }
        val cleaned = cleanTrack(track)
        assertEquals("no point should be dropped from a clean cornered path", track.size, cleaned.size)
        assertTrue("the corner vertex must survive", cleaned.any { it.eastM() in 74.0..76.0 })
    }

    @Test
    fun unknownAccuracyFixesAreKept() {
        // accuracyM = -1 ("no estimate") must never be filtered on accuracy alone.
        val track = straightWalk(6).map { it.copy(accuracyM = -1f) }
        assertEquals(track.size, cleanTrack(track).size)
    }

    // ── Outlier removal ──────────────────────────────────────────────────────

    @Test
    fun wideRadiusFixIsDropped() {
        val track = listOf(
            fix(0.0, 0.0, 0.0),
            fix(10.0, 0.0, 1.0),
            fix(20.0, 0.0, 2.0, accM = 200f), // GPS itself flagged this as ±200 m
            fix(30.0, 0.0, 3.0),
            fix(40.0, 0.0, 4.0),
        )
        val cleaned = cleanTrack(track)
        assertEquals(4, cleaned.size)
        assertFalse("no kept fix should exceed the accuracy limit", cleaned.any { it.accuracyM > 50f })
    }

    @Test
    fun fastTeleportIsDropped() {
        val track = listOf(
            fix(0.0, 0.0, 0.0),
            fix(10.0, 0.0, 1.0),
            fix(500.0, 0.0, 2.0),  // 490 m in 1 s ≈ 490 m/s — impossible
            fix(20.0, 0.0, 3.0),
            fix(30.0, 0.0, 4.0),
        )
        val cleaned = cleanTrack(track)
        assertEquals(4, cleaned.size)
        assertTrue("the teleport point must be gone", cleaned.all { it.eastM() < 100.0 })
    }

    @Test
    fun interiorDoglegSpikeIsDropped() {
        // The reported symptom: under the speed gate (50 m/s) and good accuracy,
        // but jogs 300 m off the line and snaps back. Pass 2 must catch it.
        val track = listOf(
            fix(0.0, 0.0, 0.0),
            fix(100.0, 0.0, 6.0),
            fix(100.0, 300.0, 12.0), // dogleg: 300 m out, 50 m/s, accuracy 5 m
            fix(110.0, 0.0, 18.0),
            fix(200.0, 0.0, 24.0),
        )
        val cleaned = cleanTrack(track)
        assertEquals(4, cleaned.size)
        assertTrue("start point must be preserved", cleaned.first().eastM() < 1.0)
        assertTrue("no surviving fix should be 300 m off the path",
            cleaned.all { abs(it.latitude - baseLat) * mPerDegLat < 50.0 })
    }

    @Test
    fun staleLeadInLockIsDropped() {
        // Cold-start lock 2 km away from the previous session, then the real walk.
        // Optimistic accuracy means pass 0 can't catch it — pass 0.5 must, and the
        // track must NOT collapse onto the stale anchor.
        val track = listOf(
            fix(2000.0, 0.0, 0.0),  // stale lock, 2 km east, accuracy 5 m
            fix(0.0, 0.0, 6.0),
            fix(10.0, 0.0, 7.0),
            fix(20.0, 0.0, 8.0),
            fix(30.0, 0.0, 9.0),
        )
        val cleaned = cleanTrack(track)
        assertEquals(4, cleaned.size)
        assertTrue("stale 2 km lead-in must be gone", cleaned.all { it.eastM() < 100.0 })
    }

    // ── Degenerate inputs never crash ────────────────────────────────────────

    @Test
    fun shortAndEmptyInputsAreSafe() {
        assertTrue(cleanTrack(emptyList()).isEmpty())
        assertEquals(1, cleanTrack(straightWalk(1)).size)
        assertEquals(2, cleanTrack(straightWalk(2)).size)
    }

    @Test
    fun nullIslandFixesAreDropped() {
        val track = listOf(
            fix(0.0, 0.0, 0.0),
            CoordinatePair(0.0, 0.0, 1000L, 5f), // (0,0) no-lock sentinel
            fix(10.0, 0.0, 2.0),
            fix(20.0, 0.0, 3.0),
        )
        val cleaned = cleanTrack(track)
        assertEquals(3, cleaned.size)
        assertFalse(cleaned.any { abs(it.latitude) < 1e-6 && abs(it.longitude) < 1e-6 })
    }
}
