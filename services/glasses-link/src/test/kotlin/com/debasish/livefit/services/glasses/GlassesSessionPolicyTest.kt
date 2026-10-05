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

    @Test fun manualConnectReplacesAStuckConnectingAttemptButNotAnOpenSession() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent)
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.manualConnect())
        p.onEvent(LinkEvent.Started)
        assertTrue(p.manualConnect().isEmpty())
        p.onEvent(LinkEvent.Paused)
        assertTrue(p.manualConnect().isEmpty())
    }

    @Test fun connectTimeoutBehavesLikeFailureOnlyWhileConnecting() {
        val p = GlassesSessionPolicy()
        assertTrue(p.onEvent(LinkEvent.ConnectTimeout).isEmpty(), "nothing in flight")
        p.onEvent(LinkEvent.DevicePresent)
        assertEquals(listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(2_000)), p.onEvent(LinkEvent.ConnectTimeout))
        assertTrue(p.onEvent(LinkEvent.ConnectTimeout).isEmpty(), "already waiting to retry")
    }

    @Test fun glassesExitDoesNotAutoRetryUntilManualConnect() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent); p.onEvent(LinkEvent.Started)
        assertEquals(listOf(LinkAction.MarkClosedOnGlasses), p.onEvent(LinkEvent.GlassesExited))
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty())
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.manualConnect())
    }

    @Test fun linkLossStillRetriesWithBackoff() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent); p.onEvent(LinkEvent.Started)
        assertEquals(listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(2_000)), p.onEvent(LinkEvent.Closed))
    }

    @Test fun authFailureStopsAutoRetry() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent)
        assertEquals(listOf(LinkAction.MarkAuthNeeded), p.onEvent(LinkEvent.AuthFailed))
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty())
    }
}
