package com.debasish.livefit.sync

import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.GpsStatus
import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.services.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationTimingTest {
    private fun fix(t: Long, acc: Float = 5f, northM: Double = 0.0) = LocationFix(12.9716 + northM / 111_195.0, 77.5946, acc, null, t)

    // ---- Clock calibration (spec §2.1) ----

    @Test fun offsetIsMidpointCorrected() = runTest {
        val s = WatchClockSync(Clock { testScheduler.currentTime })
        assertTrue(s.calibrate { t0 -> delay(200); t0 + 100 + 5_000 }) // watch 5 s ahead, 100 ms each way
        assertEquals(5_000L, s.offsetMs.value)
        assertEquals(1_000L, s.toPhoneTime(6_000))
    }

    @Test fun slowRoundTripsAreRetriedUpToFiveTimes() = runTest {
        var calls = 0
        val s = WatchClockSync(Clock { testScheduler.currentTime })
        assertFalse(s.calibrate { t0 -> calls++; delay(1_001); t0 })
        assertEquals(5, calls)
        assertNull(s.offsetMs.value)
        assertNull(s.toPhoneTime(1_000), "uncalibrated: no phone time, so never live")
    }

    @Test fun aLaterFastPingIsAccepted() = runTest {
        var calls = 0
        val s = WatchClockSync(Clock { testScheduler.currentTime })
        assertTrue(s.calibrate { t0 -> calls++; delay(if (calls < 3) 1_500L else 400L); t0 + 2_000 + 200 })
        assertEquals(3, calls)
        assertEquals(2_000L, s.offsetMs.value)
    }

    @Test fun noReplyCountsAsAFailedTry() = runTest {
        var calls = 0
        val s = WatchClockSync(Clock { testScheduler.currentTime })
        assertFalse(s.calibrate { calls++; null })
        assertEquals(5, calls)
    }

    @Test fun aFailedRecalibrationKeepsThePreviousOffset() = runTest {
        val s = WatchClockSync(Clock { testScheduler.currentTime })
        s.calibrate { it + 700 }
        assertFalse(s.calibrate { null })
        assertEquals(700L, s.offsetMs.value)
        assertTrue(s.calibrated)
    }

    // ---- Freshness (spec §2.1) ----

    @Test fun freshnessWindowIncludesTwoSecondsOfFuture() {
        val now = 100_000L
        assertTrue(Freshness.isUsableLive(now + 2_000, 5f, now))
        assertFalse(Freshness.isUsableLive(now + 2_001, 5f, now), "further in the future is not live")
        assertTrue(Freshness.isUsableLive(now - 10_000, 5f, now))
        assertFalse(Freshness.isUsableLive(now - 10_001, 5f, now))
        assertFalse(Freshness.isUsableLive(null, 5f, now), "uncalibrated")
    }

    @Test fun accuracyGate() {
        assertTrue(Freshness.isUsableLive(0, 30f, 0))
        assertFalse(Freshness.isUsableLive(0, 30.1f, 0))
        assertFalse(Freshness.isUsableLive(0, null, 0), "unknown accuracy is never usable-live (review #8)")
    }

    @Test fun statusThresholds() {
        assertEquals(GpsStatus.Waiting, Freshness.status(null, 0))
        assertEquals(GpsStatus.Live, Freshness.status(0, 10_000))
        assertEquals(GpsStatus.Delayed, Freshness.status(0, 10_001))
        assertEquals(GpsStatus.Delayed, Freshness.status(0, 30_000))
        assertEquals(GpsStatus.Lost, Freshness.status(0, 30_001))
    }

    // ---- Source selection (spec §2.1) ----

    @Test fun fallbackStartsAfter15sWithoutALiveWatchFix() {
        val sel = LiveLocationSelector().apply { begin(gpsWorkout = true, nowMs = 0) }
        assertFalse(sel.update(14_999))
        assertTrue(sel.update(15_000))
    }

    @Test fun notAGpsWorkoutNeverFallsBack() {
        val sel = LiveLocationSelector().apply { begin(gpsWorkout = false, nowMs = 0) }
        assertFalse(sel.update(60_000))
    }

    @Test fun uncalibratedWatchFixesNeverCount() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        for (s in 0..20L) sel.onWatchFix(fix(s * 1_000), phoneTimeMs = null, nowMs = s * 1_000)
        assertTrue(sel.update(20_000))
        assertEquals(GpsStatus.Waiting, sel.status(20_000))
        assertNull(sel.current(20_000))
    }

    @Test fun tenContinuousSecondsOfLiveWatchFixesStopFallback() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        assertTrue(sel.update(15_000))
        for (s in 20..29L) sel.onWatchFix(fix(s * 1_000, northM = s * 5.0), s * 1_000, s * 1_000)
        assertTrue(sel.fallback, "9 s of live watch fixes is not enough")
        sel.onWatchFix(fix(30_000, northM = 150.0), 30_000, 30_000)
        assertFalse(sel.fallback)
    }

    @Test fun aGapRestartsTheTenSecondCount() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        sel.update(15_000)
        for (s in (20..25L) + (29..38L)) sel.onWatchFix(fix(s * 1_000), s * 1_000, s * 1_000)
        assertTrue(sel.fallback)
        sel.onWatchFix(fix(39_000), 39_000, 39_000)
        assertFalse(sel.fallback)
    }

    /** Spec §8: accurate phone fallback vs 10 s of inaccurate watch fixes keeps the phone. */
    @Test fun inaccurateWatchFixesKeepThePhone() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        sel.update(15_000)
        for (s in 16..32L) {
            sel.onPhoneFix(fix(s * 1_000, northM = s * 4.0), s * 1_000)
            sel.onWatchFix(fix(s * 1_000, acc = 50f), s * 1_000, s * 1_000)
        }
        assertTrue(sel.fallback)
        assertEquals(FixSource.Phone, sel.current(32_000)?.source)
    }

    /** Spec §2.1: replay after a reconnect can't flip the source. */
    @Test fun replayedOldWatchFixesNeverStopFallback() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        sel.update(15_000)
        for (s in 20..40L) sel.onWatchFix(fix(s * 1_000 - 60_000), s * 1_000 - 60_000, s * 1_000)
        assertTrue(sel.fallback)
    }

    /** Review Focus #2: a 30 s screen-off batch arrives late; while held back the map says delayed, and it can't flip the source. */
    @Test fun lateBatchedBurstNeitherLiveNorFlipsSource() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        for (s in 0..10L) sel.onWatchFix(fix(s * 1_000, northM = s * 5.0), s * 1_000, s * 1_000)
        assertEquals(GpsStatus.Delayed, sel.status(30_000))
        assertTrue(sel.update(30_000), "15 s without a live watch fix → phone fallback")
        for (s in 11..44L) sel.onWatchFix(fix(s * 1_000, northM = s * 5.0), s * 1_000, nowMs = 45_000) // whole batch at once
        assertTrue(sel.fallback, "one batch is one observation: no observed recovery run yet")
        assertEquals(FixSource.Watch, sel.current(45_000)?.source, "a live watch fix still wins the marker")
    }

    /** Review #3: a delayed batch stamped 35..45 s and delivered at 45 s spans the whole live window but is 0 s of recovery. */
    @Test fun oneBatchSpanningTheLiveWindowDoesNotStopFallback() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        assertTrue(sel.update(15_000))
        for (s in 35..45L) sel.onWatchFix(fix(s * 1_000, northM = s * 5.0), s * 1_000, nowMs = 45_000)
        assertTrue(sel.fallback, "10 s of measurement time, 0 s of observed time")
        assertEquals(45_000L, sel.current(45_000)?.fixTimeMs, "the marker still takes the newest live fix")
    }

    /** Review #3: after that batch, 10 s of usable fixes arriving on time do stop the fallback. */
    @Test fun tenObservedSecondsAfterTheBatchStopFallback() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        sel.update(15_000)
        for (s in 35..45L) sel.onWatchFix(fix(s * 1_000, northM = s * 5.0), s * 1_000, nowMs = 45_000)
        for (s in 46..54L) sel.onWatchFix(fix(s * 1_000, northM = s * 5.0), s * 1_000, s * 1_000)
        assertTrue(sel.fallback, "9 observed seconds")
        sel.onWatchFix(fix(55_000, northM = 275.0), 55_000, 55_000)
        assertFalse(sel.fallback)
    }

    /** Review #8: fresh fixes without accuracy can neither move the marker nor stop the phone fallback. */
    @Test fun fixesWithoutAccuracyAreNeverLive() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        assertTrue(sel.update(15_000))
        for (s in 16..40L) sel.onWatchFix(LocationFix(12.9716 + s * 5.0 / 111_195.0, 77.5946, null, null, s * 1_000), s * 1_000, s * 1_000)
        assertTrue(sel.fallback)
        assertNull(sel.current(40_000), "no marker from unknown-accuracy watch fixes")
        assertEquals(GpsStatus.Waiting, sel.status(40_000))
        sel.onPhoneFix(LocationFix(12.9716, 77.5946, null, null, 40_000), 40_000)
        assertNull(sel.current(40_000), "nor from unknown-accuracy phone fixes")
    }

    @Test fun watchWinsWhileBothAreLive() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        sel.onPhoneFix(fix(1_000), 1_000)
        sel.onWatchFix(fix(900, northM = 10.0), 900, 1_000)
        assertEquals(FixSource.Watch, sel.current(1_000)?.source)
    }

    /** Spec §2.1: the 3 m thinning is for drawing only; an accurate stationary fix stays live. */
    @Test fun stationaryAccurateFixesStayLive() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        for (s in 0..30L) sel.onWatchFix(fix(s * 1_000), s * 1_000, s * 1_000)
        assertEquals(GpsStatus.Live, sel.status(30_000))
        assertFalse(sel.fallback)
    }

    @Test fun degradedStatusKeepsTheLastPoint() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        sel.onWatchFix(fix(0, northM = 7.0), 0, 0)
        assertEquals(GpsStatus.Delayed, sel.status(15_000))
        assertNotNull(sel.current(15_000))
        assertEquals(GpsStatus.Lost, sel.status(31_000))
        assertNotNull(sel.current(31_000), "GPS lost keeps the last point")
    }
}
