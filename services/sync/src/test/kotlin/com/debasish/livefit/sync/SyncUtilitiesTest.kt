package com.debasish.livefit.sync

import com.debasish.livefit.services.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SyncUtilitiesTest {
    @Test fun burstOfChangesIsCoalescedInto100ms() = runTest {
        val sends = mutableListOf<Long>()
        val b = StateBroadcaster(backgroundScope, send = { sends += testScheduler.currentTime })
        b.start(); runCurrent()
        repeat(5) { b.markDirty(); advanceTimeBy(10) }
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf(100L), sends)
    }

    @Test fun heartbeatEvery5sWithoutChanges() = runTest {
        val sends = mutableListOf<Long>()
        val b = StateBroadcaster(backgroundScope, send = { sends += testScheduler.currentTime })
        b.start(); runCurrent()
        advanceTimeBy(10_001); runCurrent()
        assertEquals(listOf(5_000L, 10_000L), sends)
    }

    @Test fun changeAfterHeartbeatStillFastInsideCoalesceWindow() = runTest {
        val sends = mutableListOf<Long>()
        val b = StateBroadcaster(backgroundScope, send = { sends += testScheduler.currentTime })
        b.start(); runCurrent()
        advanceTimeBy(2_000); b.markDirty(); advanceTimeBy(101); runCurrent()
        assertEquals(listOf(2_100L), sends)
    }

    @Test fun sendFailureDoesNotStopTheBroadcaster() = runTest {
        val sends = mutableListOf<Long>()
        var calls = 0
        val b = StateBroadcaster(backgroundScope, send = {
            if (calls++ == 0) throw IllegalStateException("transient")
            sends += testScheduler.currentTime
        })
        b.start(); runCurrent()
        advanceTimeBy(10_001); runCurrent()
        assertEquals(listOf(10_000L), sends)
    }

    @Test fun pausedSessionWithHeartbeatsNeverLooksOffline() = runTest {
        val clock = Clock { testScheduler.currentTime }
        val m = LivenessMonitor(clock)
        m.onFrame()
        repeat(10) { advanceTimeBy(5_000); assertTrue(m.isOnline()); m.onFrame() }
    }

    @Test fun offlineAfter12sOrImmediatelyOnDisconnect() = runTest {
        val clock = Clock { testScheduler.currentTime }
        val m = LivenessMonitor(clock)
        assertFalse(m.isOnline(), "no frame yet")
        m.onFrame(); advanceTimeBy(11_999); assertTrue(m.isOnline())
        advanceTimeBy(2); assertFalse(m.isOnline())
        m.onFrame(); assertTrue(m.isOnline())
        m.onTransportDisconnected(); assertFalse(m.isOnline())
    }

    @Test fun backoffSteps() {
        val b = Backoff()
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 30_000L, 30_000L), List(5) { b.next() })
        b.reset()
        assertEquals(2_000L, b.next())
    }

    @Test fun deduperRemembersRecentIds() {
        val d = CommandDeduper(capacity = 2)
        assertTrue(d.firstTime("a")); assertFalse(d.firstTime("a"))
        assertTrue(d.firstTime("b")); assertTrue(d.firstTime("c"))
        assertTrue(d.firstTime("a"), "evicted after capacity")
    }

    /** Review #4: a screen-off batch keeps every heart-rate point at its own time; totals are the latest. */
    @Test fun heartRateBatchKeepsEveryPoint() {
        val samples = batchSamples(listOf(2_000L to 100, 1_000L to 190), nowMs = 3_000, steps = 42, km = 0.5, kcal = 30.0, speedKmh = 6.0)
        assertEquals(listOf(1_000L to 190, 2_000L to 100), samples.map { it.tMs to it.hr })
        assertTrue(samples.all { it.stepsTotal == 42 && it.distanceKmTotal == 0.5 && it.kcalTotal == 30.0 && it.speedKmh == 6.0 })
        val a = com.debasish.livefit.services.workout.SessionAssembler("s")
        a.add(com.debasish.livefit.model.SessionDelta(sessionId = "s", seq = 0, samples = samples, provenance = com.debasish.livefit.model.Provenance.Fake))
        assertEquals(190, a.snapshot().maxHeartRate)
        assertEquals(100, a.snapshot().metrics.heartRate, "live display shows the newest point")
    }

    @Test fun updateWithoutHeartRateIsOneTotalsSampleAndFutureTimesAreClamped() {
        assertEquals(listOf(com.debasish.livefit.model.Sample(3_000, null, 42)), batchSamples(emptyList(), 3_000, 42, 0.0, 0.0, null))
        assertEquals(3_000, batchSamples(listOf(9_000L to 120), 3_000, 0, 0.0, 0.0, null).single().tMs)
    }
}
