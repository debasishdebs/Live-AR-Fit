package com.debasish.livefit.services.glasses

import com.debasish.livefit.model.LinkState
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

    /** Hub (re)start (F1): one attempt; a failure while the glasses were never reported present does not retry. */
    @Test fun autoConnectTriesOnceWithoutARetryLoop() {
        val p = GlassesSessionPolicy()
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.autoConnect())
        assertEquals(listOf(LinkAction.MarkDisconnected), p.onEvent(LinkEvent.ConnectFailed), "no ScheduleRetry")
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty())
    }

    @Test fun autoConnectLeavesAnAttemptInFlightOrAnOpenSessionAlone() {
        val p = GlassesSessionPolicy()
        p.manualConnect()
        assertTrue(p.autoConnect().isEmpty(), "a connect is already in flight")
        p.onEvent(LinkEvent.Started)
        assertTrue(p.autoConnect().isEmpty(), "open session")
    }

    /** R1: the user reopened LiveFit on the glasses after closing it there: that is consent, so reconnect. */
    @Test fun glassesAppOpenedReconnectsOnlyAfterAClosedOnGlasses() {
        val p = GlassesSessionPolicy()
        assertTrue(p.onEvent(LinkEvent.GlassesAppOpened).isEmpty(), "never closed on glasses")
        p.onEvent(LinkEvent.DevicePresent); p.onEvent(LinkEvent.Started)
        assertTrue(p.onEvent(LinkEvent.GlassesAppOpened).isEmpty(), "session open")
        p.onEvent(LinkEvent.GlassesExited)
        assertTrue(p.closedOnGlasses)
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.onEvent(LinkEvent.GlassesAppOpened))
        assertTrue(!p.closedOnGlasses)
        assertTrue(p.onEvent(LinkEvent.GlassesAppOpened).isEmpty(), "connect already in flight")
    }

    @Test fun closedOnGlassesEndsOnDeviceGoneOrManualConnect() {
        val p = GlassesSessionPolicy()
        p.manualConnect(); p.onEvent(LinkEvent.Started); p.onEvent(LinkEvent.GlassesExited)
        p.onEvent(LinkEvent.DeviceGone)
        assertTrue(!p.closedOnGlasses)
        assertTrue(p.onEvent(LinkEvent.GlassesAppOpened).isEmpty())
        p.manualConnect(); p.onEvent(LinkEvent.Started); p.onEvent(LinkEvent.GlassesExited)
        p.manualConnect()
        assertTrue(!p.closedOnGlasses)
    }

    /** After a close on the glasses the hub still treats a fresh presence / hub start like Idle (unchanged). */
    @Test fun closedOnGlassesStillAllowsPresenceAndAutoConnect() {
        val p = GlassesSessionPolicy()
        p.manualConnect(); p.onEvent(LinkEvent.Started); p.onEvent(LinkEvent.GlassesExited)
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.autoConnect())
    }

    /** R1 fix a: AppActivity resume reconnects a disconnected, authorized link once; never re-prompts a declined auth. */
    @Test fun resumeReconnectsOnlyAnAuthorizedDisconnectedLink() {
        assertTrue(shouldReconnectOnResume(LinkState.Disconnected, hasToken = true, authDeclined = false))
        assertTrue(!shouldReconnectOnResume(LinkState.Disconnected, hasToken = false, authDeclined = false), "never authorized")
        assertTrue(!shouldReconnectOnResume(LinkState.Disconnected, hasToken = true, authDeclined = true), "declined")
        assertTrue(!shouldReconnectOnResume(LinkState.Connecting, hasToken = true, authDeclined = false))
        assertTrue(!shouldReconnectOnResume(LinkState.Connected, hasToken = true, authDeclined = false))
    }
}
