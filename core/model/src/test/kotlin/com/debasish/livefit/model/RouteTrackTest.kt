package com.debasish.livefit.model

import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteTrackTest {
    private val lat0 = 12.9716
    private val lon0 = 77.5946
    private fun p(src: FixSource, tMs: Long, northM: Double, acc: Float = 5f, eastM: Double = 0.0) = RoutePoint(
        src, lat0 + northM / 111_195.0, lon0 + eastM / (111_195.0 * cos(Math.toRadians(lat0))), acc, deviceTimeMs = tMs, fixTimeMs = tMs,
    )
    private val now = 1_000_000L

    @Test fun geoDistanceAndBearing() {
        assertEquals(111_195.0, Geo.distanceM(0.0, 0.0, 1.0, 0.0), 1.0)
        assertEquals(0f, Geo.bearingDeg(0.0, 0.0, 1.0, 0.0), 0.01f)
        assertEquals(90f, Geo.bearingDeg(0.0, 0.0, 0.0, 1.0), 0.01f)
    }

    /** Spec §2.2: sorted by fixTimeMs, not arrival; an older replayed watch fix lands chronologically. */
    @Test fun keepsPointsSortedByFixTimeNotArrival() {
        val t = RouteTrack()
        assertEquals(RouteAdd.Added, t.add(p(FixSource.Watch, 3_000, 30.0), now))
        assertEquals(RouteAdd.Added, t.add(p(FixSource.Watch, 1_000, 10.0), now))
        assertEquals(RouteAdd.Added, t.add(p(FixSource.Watch, 2_000, 20.0), now))
        assertEquals(listOf(1_000L, 2_000L, 3_000L), t.points.map { it.fixTimeMs })
    }

    /** Review Focus #2: a 30 s screen-off batch arriving late slots in behind phone points by time. */
    @Test fun lateBatchInsertsChronologically() {
        val t = RouteTrack()
        for (s in 0..10) t.add(p(FixSource.Watch, s * 1_000L, s * 10.0), now)
        for (s in 15..40) t.add(p(FixSource.Phone, s * 1_000L, s * 10.0 + 2.0), now) // fallback while the watch was silent
        for (s in 11..40) t.add(p(FixSource.Watch, s * 1_000L, s * 10.0), now)       // the late batch
        val times = t.points.map { it.fixTimeMs }
        assertEquals(times.sorted(), times, "chronological")
        val drawn = t.drawn()
        assertTrue(drawn.all { it.source == FixSource.Watch }, "phone points within ±5 s of watch points are hidden")
        assertEquals(41, drawn.size)
        assertEquals(26, t.points.count { it.source == FixSource.Phone }, "phone points stay stored for diagnostics")
    }

    @Test fun phonePointsOutsideWatchCoverageAreDrawn() {
        val t = RouteTrack()
        t.add(p(FixSource.Watch, 0, 0.0), now)
        t.add(p(FixSource.Phone, 4_000, 10.0), now)  // within 5 s → hidden
        t.add(p(FixSource.Phone, 6_000, 20.0), now)  // 6 s after the only watch point → drawn
        assertEquals(listOf(FixSource.Watch, FixSource.Phone), t.drawn().map { it.source })
    }

    @Test fun dropsInaccurateFixes() {
        val t = RouteTrack()
        assertEquals(RouteAdd.Inaccurate, t.add(p(FixSource.Watch, 0, 0.0, acc = 30.5f), now))
        assertEquals(RouteAdd.Added, t.add(p(FixSource.Watch, 0, 0.0, acc = 30f), now))
    }

    @Test fun dropsPointsCloserThan3mToTheirSameSourceNeighbour() {
        val t = RouteTrack()
        t.add(p(FixSource.Watch, 0, 0.0), now)
        assertEquals(RouteAdd.TooClose, t.add(p(FixSource.Watch, 1_000, 2.0), now))
        assertEquals(RouteAdd.Added, t.add(p(FixSource.Phone, 1_000, 2.0), now), "other source: no spacing rule")
        assertEquals(RouteAdd.Added, t.add(p(FixSource.Watch, 2_000, 3.5), now))
        assertEquals(RouteAdd.TooClose, t.add(p(FixSource.Watch, 1_500, 1.0), now), "too close to the earlier neighbour")
    }

    /** Spec §2.2: replay/resend safe. */
    @Test fun duplicatesAreIgnored() {
        val t = RouteTrack()
        t.add(p(FixSource.Watch, 10_000, 0.0), now)
        val resend = p(FixSource.Watch, 10_040, 0.5).copy(fixTimeMs = 10_300) // re-mapped with a newer offset
        assertEquals(RouteAdd.Duplicate, t.add(resend, now))
        assertEquals(1, t.points.size)
    }

    @Test fun rejectsFixesMoreThanTwoMinutesInTheFuture() {
        val t = RouteTrack()
        assertEquals(RouteAdd.Future, t.add(p(FixSource.Watch, now + 120_001, 0.0), now))
        assertEquals(RouteAdd.Added, t.add(p(FixSource.Watch, now + 119_000, 0.0), now))
    }

    @Test fun tiesBreakWatchFirst() {
        val t = RouteTrack()
        t.add(p(FixSource.Phone, 5_000, 0.0), now)
        t.add(p(FixSource.Watch, 5_000, 50.0), now)
        assertEquals(listOf(FixSource.Watch, FixSource.Phone), t.points.map { it.source })
    }

    @Test fun bearingFromTheLastTwoDrawnPoints() {
        val t = RouteTrack()
        assertNull(t.lastBearing())
        t.add(p(FixSource.Watch, 0, 0.0), now)
        t.add(p(FixSource.Watch, 1_000, 0.0, eastM = 20.0), now)
        assertEquals(90f, assertNotNull(t.lastBearing()), 0.5f)
    }

    /** Review #8: unknown accuracy is never accurate — no route row, no route point, never live (one shared rule). */
    @Test fun unknownAccuracyFailsTheAccuracyGate() {
        assertFalse(FixQuality.accurate(null))
        assertTrue(FixQuality.accurate(30f))
        assertFalse(FixQuality.accurate(30.01f))
        assertNull(LocationFix(lat0, lon0, null, null, 1_000).toRouteFix(FixSource.Watch, 1_000, receivedAtMs = 1_000))
        assertNull(LocationFix(lat0, lon0, 31f, null, 1_000).toRouteFix(FixSource.Watch, 1_000, receivedAtMs = 1_000))
    }

    /** Review #2: an uncalibrated row keeps its device-time identity and takes no part in ordering until it has a phone time. */
    @Test fun routeFixWithoutPhoneTimeIsNotARoutePointYet() {
        val f = assertNotNull(LocationFix(lat0, lon0, 5f, 90f, 7_000).toRouteFix(FixSource.Watch, phoneTimeMs = null, receivedAtMs = 7_500))
        assertEquals(7_000L, f.deviceTimeMs)
        assertNull(f.point())
        val mapped = assertNotNull(f.copy(phoneTimeMs = 2_000).point())
        assertEquals(2_000L, mapped.fixTimeMs)
        assertEquals(7_000L, mapped.deviceTimeMs, "identity unchanged by calibration")
        assertEquals(90f, mapped.bearingDeg)
        assertEquals(7_000L, assertNotNull(f.historyPoint()).fixTimeMs, "history fallback: device time")
        assertEquals(7_500L, f.copy(phoneTimeMs = 2_000).receivedAtMs, "receipt time survives normalization")
    }

    /**
     * Review r2 #2: a calibrated fix more than 2 min after its receipt time is invalid durably — in every rebuild, after
     * time has passed, and in history. A legitimate uncalibrated fix from a watch whose clock is 3 min ahead is invalid
     * only until normalization puts it at its real time.
     */
    @Test fun receiptTimeMakesFutureRejectionDurable() {
        val ahead = assertNotNull(LocationFix(lat0, lon0, 5f, null, 900_000).toRouteFix(FixSource.Watch, phoneTimeMs = 900_000, receivedAtMs = 700_000))
        assertNull(ahead.point(), "200 s after receipt")
        assertNull(ahead.historyPoint(), "and not in history, however much later it is rendered")
        assertNotNull(ahead.copy(phoneTimeMs = 819_000).point(), "within 2 min of receipt")
        val raw = assertNotNull(LocationFix(lat0, lon0, 5f, null, 880_000).toRouteFix(FixSource.Watch, phoneTimeMs = null, receivedAtMs = 700_000))
        assertNull(raw.point())
        assertEquals(700_000L, assertNotNull(raw.copy(phoneTimeMs = 880_000 - 180_000).point()).fixTimeMs, "valid once normalized")
    }

    @Test fun ofWithNowRejectsFuturePoints() {
        val pts = listOf(p(FixSource.Watch, now + 200_000, 0.0), p(FixSource.Watch, now - 1_000, 10.0))
        assertEquals(listOf(now - 1_000), RouteTrack.of(pts, now).points.map { it.fixTimeMs })
    }

    @Test fun ofRestoresStoredPointsWhateverTheirAge() {
        val stored = listOf(p(FixSource.Watch, 2_000, 10.0), p(FixSource.Watch, 1_000, 0.0))
        assertEquals(listOf(1_000L, 2_000L), RouteTrack.of(stored).points.map { it.fixTimeMs })
    }
}
