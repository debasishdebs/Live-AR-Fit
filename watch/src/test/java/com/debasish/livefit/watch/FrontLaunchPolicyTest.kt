package com.debasish.livefit.watch

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrontLaunchPolicyTest {
    private val p = FrontLaunchPolicy(throttleMs = 10_000)

    @Test fun hubStartWhileRecordingAndHiddenRaises() = assertTrue(p.onHubStart(recording = true, visible = false, nowMs = 0))

    @Test fun hubStartNotRecordingDoesNothing() = assertFalse(p.onHubStart(recording = false, visible = false, nowMs = 0))

    @Test fun alreadyVisibleDoesNothing() = assertFalse(p.onHubStart(recording = true, visible = true, nowMs = 0))

    @Test fun throttlesRepeatedRaises() {
        assertTrue(p.onHubStart(recording = true, visible = false, nowMs = 0))
        assertFalse(p.onConfirmation("c1", visible = false, nowMs = 5_000))
        assertTrue(p.onConfirmation("c2", visible = false, nowMs = 10_000))
    }

    @Test fun eachConfirmationRaisesOnce() {
        assertTrue(p.onConfirmation("c1", visible = false, nowMs = 0))
        assertFalse(p.onConfirmation("c1", visible = false, nowMs = 60_000))
        assertFalse(p.onConfirmation(null, visible = false, nowMs = 120_000))
    }

    @Test fun confirmationSeenWhileVisibleIsNotRaisedLater() {
        assertFalse(p.onConfirmation("c1", visible = true, nowMs = 0))
        assertFalse(p.onConfirmation("c1", visible = false, nowMs = 60_000))
    }
}
