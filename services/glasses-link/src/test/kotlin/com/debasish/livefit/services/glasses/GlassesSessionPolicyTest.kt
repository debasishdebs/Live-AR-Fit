package com.debasish.livefit.services.glasses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlassesSessionPolicyTest {
    @Test fun startedSendsSettingsAndMarksConnected() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent)
        assertEquals(listOf(LinkAction.MarkConnected, LinkAction.SendSettings), p.onEvent(LinkEvent.Started))
    }

    /** Screen off / glasses removed → session paused: keep it, no second session. */
    @Test fun pausedSessionIsKeptAndResumeResendsSettings() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent); p.onEvent(LinkEvent.Started)
        assertEquals(listOf(LinkAction.MarkConnecting), p.onEvent(LinkEvent.Paused))
        assertEquals(listOf(LinkAction.MarkConnected, LinkAction.SendSettings), p.onEvent(LinkEvent.Resumed))
    }

    @Test fun closedWhilePresentRetriesWithBackoffAndNeverDoubleConnects() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent)
        assertEquals(listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(2_000)), p.onEvent(LinkEvent.Closed))
        assertEquals(listOf(LinkAction.Connect), p.onEvent(LinkEvent.RetryTimer))
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty(), "connect already in flight")
        assertEquals(listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(5_000)), p.onEvent(LinkEvent.ConnectFailed))
        p.onEvent(LinkEvent.RetryTimer); p.onEvent(LinkEvent.Started)
        assertEquals(listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(2_000)), p.onEvent(LinkEvent.Closed), "backoff reset after success")
    }

    @Test fun deviceGoneStopsRetrying() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent)
        p.onEvent(LinkEvent.Closed)
        assertEquals(listOf(LinkAction.MarkDisconnected), p.onEvent(LinkEvent.DeviceGone))
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty())
    }

    @Test fun devicePresentConnectsOnce() {
        val p = GlassesSessionPolicy()
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.onEvent(LinkEvent.DevicePresent))
        assertTrue(p.onEvent(LinkEvent.DevicePresent).isEmpty())
    }
}
