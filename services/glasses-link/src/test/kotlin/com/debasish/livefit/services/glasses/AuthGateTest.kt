package com.debasish.livefit.services.glasses

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthGateTest {
    @Test fun launchesOncePerAttemptUntilItGoesStale() {
        val g = AuthGate(timeoutMs = 30_000)
        assertTrue(g.begin(0))
        assertFalse(g.begin(10_000))
        assertTrue(g.begin(30_000), "stale attempt may be relaunched")
    }

    @Test fun expiryAndStaleClearFreeTheGate() {
        val g = AuthGate(30_000)
        g.begin(0); g.expire()
        assertTrue(g.begin(1))
        g.clearStale(31_001)
        assertTrue(g.begin(31_002))
    }

    @Test fun lateFailureAfterSuccessIsIgnored() {
        val g = AuthGate()
        g.begin(0)
        assertTrue(g.result(true))
        assertFalse(g.result(false))
        assertFalse(g.result(true), "duplicate success")
        assertTrue(g.authorized)
    }

    @Test fun failureIsAppliedAndClearsInFlight() {
        val g = AuthGate()
        g.begin(0)
        assertTrue(g.result(false))
        assertTrue(g.begin(1))
    }

    /** Review #11: TOKEN_EXPIRED / NOT_AUTHENTICATED drop the in-process authorization. */
    @Test fun rejectedTokenRevokesAuthorizationAndAllowsOneReauthorization() {
        val g = AuthGate()
        g.begin(0); g.result(true)
        assertTrue(g.tokenRejected(), "first rejection: go through authorization again")
        assertFalse(g.authorized)
        assertTrue(g.begin(1), "a new attempt may launch at once")
        assertTrue(g.result(true))
        assertTrue(g.authorized)
    }

    @Test fun tokenRejectedAgainRightAfterReauthorizingGivesUp() {
        val g = AuthGate()
        g.begin(0); g.result(true)
        assertTrue(g.tokenRejected())
        g.begin(1); g.result(true)
        assertFalse(g.tokenRejected(), "no relaunch loop")
        assertFalse(g.authorized)
    }

    @Test fun aStartedSessionRearmsReauthorization() {
        val g = AuthGate()
        g.begin(0); g.result(true)
        g.tokenRejected(); g.begin(1); g.result(true)
        g.sessionStarted()
        assertTrue(g.tokenRejected(), "a later expiry is a new rejection")
    }
}
