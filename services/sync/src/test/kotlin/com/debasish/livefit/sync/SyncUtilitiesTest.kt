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
}
