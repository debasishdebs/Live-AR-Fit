package com.debasish.livefit.sync

import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.GpsStatus
import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.RouteFix
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.Clock
import com.debasish.livefit.services.RouteStore
import com.debasish.livefit.services.workout.InMemorySessionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RouteHubTest {
    /** route_point in memory, with the same identity, ordering, normalization and tombstone rules as Room (Task 13). */
    private class MemoryRoutes : RouteStore {
        val rows = LinkedHashMap<Triple<String, FixSource, Long>, RouteFix>()
        val discarded = mutableSetOf<String>()
        var fail = false
        var failNextWrites = 0
        override suspend fun storeRouteFixes(sessionId: String, fixes: List<RouteFix>) {
            if (fail) error("disk full")
            if (failNextWrites > 0) { failNextWrites--; error("disk busy") }
            if (sessionId in discarded) return
            for (f in fixes) rows.merge(Triple(sessionId, f.source, f.deviceTimeMs), f) { old, new -> old.copy(receivedAtMs = minOf(old.receivedAtMs, new.receivedAtMs)) }
        }
        override suspend fun normalizeWatchTimes(sessionId: String, watchOffsetMs: Long) {
            if (fail) error("disk full")
            rows.replaceAll { k, f -> if (k.first == sessionId && f.source == FixSource.Watch) f.copy(phoneTimeMs = f.deviceTimeMs - watchOffsetMs) else f }
        }
        override suspend fun routeFixes(sessionId: String): List<RouteFix> = rows.filterKeys { it.first == sessionId }.values
            .sortedWith(compareBy<RouteFix>({ it.phoneTimeMs == null }, { it.phoneTimeMs }, { it.source != FixSource.Watch }, { it.deviceTimeMs }))
    }

    private class Rig(scope: TestScope, val sessions: InMemorySessionStore = InMemorySessionStore(), val store: MemoryRoutes = MemoryRoutes()) {
        val clock = Clock { scope.testScheduler.currentTime }
        val sync = WatchClockSync(clock)
        val hub = RouteHub(scope.backgroundScope, clock, sync, store, sessions)
    }

    private fun fix(t: Long, northM: Double, acc: Float? = 5f) = LocationFix(12.9716 + northM / 111_195.0, 77.5946, acc, null, t)
    private fun delta(fixes: List<LocationFix>, session: String = "s", seq: Long = 1) =
        SessionDelta(sessionId = session, seq = seq, locations = fixes, provenance = Provenance.Fake)
    private val running = WorkoutSnapshot(phase = WorkoutPhase.Active, type = WorkoutType.Run, sessionId = "s", gps = true)

    @Test fun watchFixesBuildTheRouteInPhoneTime() = runTest {
        val r = Rig(this)
        r.sync.calibrate { it + 5_000 } // watch clock 5 s ahead
        advanceTimeBy(10_000)
        r.hub.onWorkout(running)
        r.hub.onWatchDelta(delta(listOf(fix(14_000, 0.0), fix(15_000, 10.0))))
        val s = r.hub.state.value
        assertEquals(listOf(9_000L, 10_000L), s.route.map { it.fixTimeMs })
        assertEquals(listOf(14_000L, 15_000L), s.route.map { it.deviceTimeMs })
        assertEquals(GpsStatus.Live, s.status)
        assertEquals(FixSource.Watch, s.live?.source)
        assertEquals(0f, s.live?.bearingDeg ?: -1f, 0.5f, "bearing from the last two points")
        assertEquals(listOf(9_000L, 10_000L), r.store.routeFixes("s").map { it.phoneTimeMs })
    }

    @Test fun uncalibratedWatchFixesAreStoredButNeitherDrawnNorLive() = runTest {
        val r = Rig(this)
        advanceTimeBy(1_000)
        r.hub.onWorkout(running)
        r.hub.onWatchDelta(delta(listOf(fix(1_000, 0.0), fix(2_000, 10.0))))
        assertTrue(r.hub.state.value.route.isEmpty(), "no phone time yet: not ordered, not drawn")
        assertEquals(listOf(null, null), r.store.routeFixes("s").map { it.phoneTimeMs }, "durable by device time")
        assertNull(r.hub.state.value.live)
        assertEquals(GpsStatus.Waiting, r.hub.state.value.status)
        advanceTimeBy(15_000)
        r.hub.onWatchDelta(delta(listOf(fix(16_000, 20.0)), seq = 2))
        assertTrue(r.hub.fallbackWanted.value, "uncalibrated fixes can't stop the phone fallback")
    }

    /**
     * Review #2: the watch replays before the first sync, the phone then calibrates (small and > 2 min offsets),
     * the replay continues — nothing is lost or rejected as "future", the order is corrected (phone fallback points
     * under the replay get hidden), every stored row gets its phone time, and no marker appears until a live fix.
     */
    @Test fun replayBeforeSyncThenCalibrationSmallOffset() = runTest { replayAcrossCalibration(offsetMs = 1_000) }

    @Test fun replayBeforeSyncThenCalibrationMoreThanTwoMinutesAhead() = runTest { replayAcrossCalibration(offsetMs = 180_000) }

    private suspend fun TestScope.replayAcrossCalibration(offsetMs: Long) {
        val r = Rig(this)
        advanceTimeBy(600_000)
        r.hub.onWorkout(running)
        fun watchAt(phoneT: Long, n: Int) = fix(phoneT + offsetMs, 100.0 + n * 5) // measured at phone time phoneT
        r.hub.onPhoneFix(fix(530_000, 252.0)) // a fallback point recorded while the watch was away
        r.hub.onWatchDelta(delta((0 until 60).map { watchAt(500_000L + it * 1_000, it) }))
        assertEquals(listOf(FixSource.Phone), r.hub.state.value.route.map { it.source }, "uncalibrated replay not drawn yet")
        assertNull(r.hub.state.value.live)

        r.sync.calibrate { it + offsetMs }
        r.hub.onWatchDelta(delta((60 until 90).map { watchAt(500_000L + it * 1_000, it) }, seq = 2))
        val s = r.hub.state.value
        assertEquals(90, s.route.size, "no fix lost, none rejected as future")
        assertTrue(s.route.all { it.source == FixSource.Watch }, "the phone point under the replay is now hidden")
        assertEquals((0 until 90).map { 500_000L + it * 1_000 }, s.route.map { it.fixTimeMs }, "corrected chronological order")
        assertNull(s.live, "replayed fixes never move the marker")
        assertNotEquals(GpsStatus.Live, s.status)
        val stored = r.store.routeFixes("s")
        assertEquals(91, stored.size, "phone row kept for diagnostics")
        assertTrue(stored.filter { it.source == FixSource.Watch }.all { it.phoneTimeMs == it.deviceTimeMs - offsetMs }, "normalized")

        r.hub.onWatchDelta(delta(listOf(watchAt(600_000, 200)), seq = 3)) // first fix that is live on arrival
        assertEquals(FixSource.Watch, r.hub.state.value.live?.source)
        assertEquals(600_000L, r.hub.state.value.live?.fixTimeMs)
    }

    /** Review Focus #1: phone restart; the watch's minutes-old replay arrives before, and right after, the first time-sync. */
    @Test fun delayedFirstReplayAfterPhoneRestartDoesNotLookLive() = runTest {
        val r = Rig(this)
        r.store.storeRouteFixes("s", listOf(RouteFix(FixSource.Watch, 12.9716, 77.5946, 5f, 1_000, 1_000, receivedAtMs = 1_000))) // before the restart
        advanceTimeBy(600_000)
        r.hub.onWorkout(running)
        assertEquals(1, r.hub.state.value.route.size, "stored route reloaded")
        r.hub.onWatchDelta(delta((0 until 60).map { fix(300_000L + it * 1_000, 50.0 + it * 5) }))
        assertNull(r.hub.state.value.live)
        r.sync.calibrate { it }
        r.hub.onWatchDelta(delta((60 until 120).map { fix(300_000L + it * 1_000, 50.0 + it * 5) }, seq = 2))
        val s = r.hub.state.value
        assertNotEquals(GpsStatus.Live, s.status)
        assertNull(s.live)
        assertEquals(121, s.route.size)
        assertEquals(s.route.map { it.fixTimeMs }.sorted(), s.route.map { it.fixTimeMs }, "drawn in time order")
        advanceTimeBy(15_000)
        r.hub.onWorkout(running)
        assertTrue(r.hub.fallbackWanted.value, "replay never stopped the fallback")
    }

    /** Review #1: the phone died after a delta was stored and acked but before RouteHub wrote any row; a restart rebuilds them. */
    @Test fun routeRowsMissingAfterAnAckAreRebuiltFromStoredDeltas() = runTest {
        val sessions = InMemorySessionStore()
        val fixes = (0 until 30).map { fix(1_000L + it * 1_000, it * 5.0) }
        sessions.storeDelta(delta(fixes.take(15), seq = 0))
        sessions.storeDelta(delta(fixes.drop(15), seq = 1))
        val r = Rig(this, sessions) // a fresh phone process: empty route_point
        r.sync.calibrate { it }
        advanceTimeBy(60_000)
        r.hub.onWorkout(running)
        assertEquals(30, r.hub.state.value.route.size, "full route after the restart")
        val stored = r.store.routeFixes("s")
        assertEquals(30, stored.size, "missing rows written back")
        assertTrue(stored.all { it.phoneTimeMs == it.deviceTimeMs })
        assertNull(r.hub.state.value.live, "rebuilt fixes are history, not live")
    }

    /** Review #1: a route write fails once; it is retried by the ticker (no new fix needed) and a replay stays idempotent. */
    @Test fun aFailedRouteWriteIsRetriedAndReplayIsIdempotent() = runTest {
        val r = Rig(this)
        r.sync.calibrate { it }
        r.hub.start()
        r.hub.onWorkout(running)
        r.store.failNextWrites = 1
        val d = delta(listOf(fix(0, 0.0), fix(1_000, 10.0)))
        r.hub.onWatchDelta(d)
        assertTrue(r.store.routeFixes("s").isEmpty(), "first write failed")
        assertEquals(2, r.hub.state.value.route.size, "tracking unaffected")
        advanceTimeBy(1_100); runCurrent()
        assertEquals(2, r.store.routeFixes("s").size, "retried by the ticker")
        r.hub.onWatchDelta(d) // the watch resends after a lost ack
        assertEquals(2, r.store.routeFixes("s").size)
        assertEquals(2, r.hub.state.value.route.size)
    }

    @Test fun aFailedWriteIsAlsoRecoveredByTheNextReplay() = runTest {
        val r = Rig(this)
        r.sync.calibrate { it }
        r.hub.onWorkout(running)
        r.store.failNextWrites = 1
        val d = delta(listOf(fix(0, 0.0), fix(1_000, 10.0)))
        r.hub.onWatchDelta(d)
        r.hub.onWatchDelta(d)
        assertEquals(listOf(0L, 1_000L), r.store.routeFixes("s").map { it.deviceTimeMs })
    }

    /**
     * Review r2 #2: a calibrated, accurate fix > 2 min ahead of its receipt is rejected live, and stays absent from history,
     * after a restart with a slightly different offset (full rebuild + normalization) and after its bad time has passed.
     */
    @Test fun aFutureFixStaysRejectedEverywhere() = runTest {
        val r = Rig(this)
        r.sync.calibrate { it }
        advanceTimeBy(600_000)
        r.hub.onWorkout(running)
        r.hub.onWatchDelta(delta(listOf(fix(599_000, 0.0), fix(800_000, 50.0)))) // the second is 200 s ahead
        assertEquals(listOf(599_000L), r.hub.state.value.route.map { it.fixTimeMs })
        val stored = r.store.routeFixes("s")
        assertEquals(2, stored.size, "kept raw for diagnostics")
        assertEquals(listOf(599_000L), stored.mapNotNull { it.historyPoint() }.map { it.fixTimeMs }, "history after finalization")
        advanceTimeBy(600_000) // its bad time is in the past now
        val restarted = Rig(this, r.sessions, r.store)
        restarted.sync.calibrate { it + 1 }
        restarted.hub.onWorkout(running)
        assertEquals(1, restarted.hub.state.value.route.size, "still absent after restart and time advance")
        assertEquals(1, restarted.store.routeFixes("s").mapNotNull { it.historyPoint() }.size)
    }

    /**
     * Review r3: a calibrated fix 121 s ahead is received at 600 000; its first write fails; the watch replays it at 605 000
     * (only 116 s ahead of that later receipt) and this write succeeds. The saved receipt must stay 600 000, so the point is
     * excluded live, in history and after a restart.
     */
    @Test fun aReplayAfterAFailedWriteKeepsTheEarliestReceipt() = runTest {
        val r = Rig(this)
        r.sync.calibrate { it }
        advanceTimeBy(600_000)
        r.hub.onWorkout(running)
        r.store.failNextWrites = 1
        val d = delta(listOf(fix(721_000, 0.0)))
        r.hub.onWatchDelta(d)
        assertTrue(r.store.routeFixes("s").isEmpty(), "first write failed")
        advanceTimeBy(5_000)
        r.hub.onWatchDelta(d.copy(seq = 2)) // the watch resends after the lost ack
        val saved = r.store.routeFixes("s").single()
        assertEquals(600_000L, saved.receivedAtMs, "earliest receipt saved")
        assertNull(saved.historyPoint(), "excluded from history")
        assertTrue(r.hub.state.value.route.isEmpty(), "excluded live")
        val restarted = Rig(this, r.sessions, r.store)
        restarted.sync.calibrate { it }
        restarted.hub.onWorkout(running)
        assertTrue(restarted.hub.state.value.route.isEmpty(), "excluded after restart")
        assertNull(restarted.store.routeFixes("s").single().historyPoint())
    }

    @Test fun phoneFallbackFixesDriveTheMarkerWhileTheWatchIsSilent() = runTest {
        val r = Rig(this)
        r.sync.calibrate { it }
        r.hub.onWorkout(running)
        advanceTimeBy(16_000)
        r.hub.onPhoneFix(fix(16_000, 0.0))
        val s = r.hub.state.value
        assertTrue(r.hub.fallbackWanted.value)
        assertEquals(FixSource.Phone, s.live?.source)
        assertEquals(GpsStatus.Live, s.status)
        assertEquals(listOf(FixSource.Phone), s.route.map { it.source })
    }

    /** Review #8: fixes without accuracy are neither stored, drawn nor live (phone or watch). */
    @Test fun unknownAccuracyIsIgnoredEverywhere() = runTest {
        val r = Rig(this)
        r.sync.calibrate { it }
        r.hub.onWorkout(running)
        advanceTimeBy(16_000)
        r.hub.onPhoneFix(fix(16_000, 0.0, acc = null))
        r.hub.onWatchDelta(delta(listOf(fix(16_000, 5.0, acc = null))))
        assertTrue(r.hub.state.value.route.isEmpty())
        assertNull(r.hub.state.value.live)
        assertTrue(r.store.routeFixes("s").isEmpty())
        assertTrue(r.hub.fallbackWanted.value)
    }

    @Test fun anotherSessionsReplayIsStoredNotDrawn() = runTest {
        val r = Rig(this)
        r.hub.onWorkout(running)
        r.hub.onWatchDelta(delta(listOf(fix(1_000, 0.0), fix(2_000, 9.0, acc = 50f)), session = "old"))
        assertTrue(r.hub.state.value.route.isEmpty())
        assertEquals(1, r.store.routeFixes("old").size, "inaccurate fix not stored")
    }

    /** A discarded session's tombstone: its late replay is dropped by the store, and RouteHub does not keep retrying it. */
    @Test fun discardedSessionsReplayIsNeverStored() = runTest {
        val r = Rig(this)
        r.store.discarded += "gone"
        r.hub.start()
        r.hub.onWorkout(running)
        r.hub.onWatchDelta(delta(listOf(fix(1_000, 0.0)), session = "gone"))
        advanceTimeBy(5_000); runCurrent()
        assertTrue(r.store.routeFixes("gone").isEmpty())
    }

    @Test fun storageFailureDoesNotBreakTracking() = runTest {
        val r = Rig(this)
        r.sync.calibrate { it }
        r.store.fail = true
        r.hub.onWorkout(running)
        r.hub.onWatchDelta(delta(listOf(fix(0, 0.0), fix(1_000, 10.0))))
        assertEquals(2, r.hub.state.value.route.size)
    }

    @Test fun phoneFixesIgnoredOutsideAGpsWorkout() = runTest {
        val r = Rig(this)
        r.hub.onWorkout(running.copy(gps = false))
        advanceTimeBy(20_000)
        r.hub.onPhoneFix(fix(20_000, 0.0))
        assertTrue(r.hub.state.value.route.isEmpty())
        assertFalse(r.hub.fallbackWanted.value)
    }

    @Test fun idleClearsTheRoute() = runTest {
        val r = Rig(this)
        r.sync.calibrate { it }
        r.hub.onWorkout(running)
        r.hub.onWatchDelta(delta(listOf(fix(0, 0.0))))
        assertEquals(1, r.hub.state.value.route.size)
        r.hub.onWorkout(WorkoutSnapshot())
        assertNull(r.hub.state.value.sessionId)
        assertTrue(r.hub.state.value.route.isEmpty())
    }

    @Test fun theTickerAgesTheStatus() = runTest {
        val r = Rig(this)
        r.sync.calibrate { it }
        r.hub.start()
        r.hub.onWorkout(running)
        r.hub.onWatchDelta(delta(listOf(fix(0, 0.0))))
        advanceTimeBy(15_500); runCurrent()
        assertEquals(GpsStatus.Delayed, r.hub.state.value.status)
        advanceTimeBy(16_000); runCurrent()
        assertEquals(GpsStatus.Lost, r.hub.state.value.status)
    }
}
