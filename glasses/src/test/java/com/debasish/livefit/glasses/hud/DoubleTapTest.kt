package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.WorkoutPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DoubleTapDetectorTest {
    @Test fun twoNotificationKeysCloseTogetherAreADoubleTap() {
        val d = DoubleTapDetector()
        assertFalse(d.onNotificationKey(1_000), "one key alone is not a double-tap")
        assertTrue(d.onNotificationKey(1_150))
    }

    @Test fun keysFurtherApartThanTheWindowAreNot() {
        val d = DoubleTapDetector()
        assertFalse(d.onNotificationKey(1_000))
        assertFalse(d.onNotificationKey(1_000 + DoubleTapDetector.WINDOW_MS + 1))
        assertTrue(d.onNotificationKey(1_000 + DoubleTapDetector.WINDOW_MS + 100), "the late key starts a new pair")
    }

    @Test fun backIsADoubleTapOnItsOwn() = assertTrue(DoubleTapDetector().onBack(0))

    @Test fun oneGestureReportedTwiceFiresOnce() {
        val d = DoubleTapDetector()
        d.onNotificationKey(1_000); assertTrue(d.onNotificationKey(1_150))
        assertFalse(d.onBack(1_300), "firmware that also sends BACK for the same double-tap")
        assertFalse(d.onNotificationKey(1_400)); assertFalse(d.onNotificationKey(1_450))
        assertTrue(d.onBack(1_150 + DoubleTapDetector.SETTLE_MS + 500), "a later double-tap counts")
    }

    @Test fun backThenNotificationKeysFireOnce() {
        val d = DoubleTapDetector()
        assertTrue(d.onBack(1_000))
        assertFalse(d.onNotificationKey(1_100)); assertFalse(d.onNotificationKey(1_250))
    }

    @Test fun aSwipeOrTapBetweenTwoTouchesIsNotADoubleTap() {
        // Every touchpad gesture starts with key 83: a quick swipe then a touch must not close the app.
        val d = DoubleTapDetector()
        assertFalse(d.onNotificationKey(0)); d.onGestureKey()
        assertFalse(d.onNotificationKey(200), "the swipe's touch then the next touch")
        assertTrue(d.onNotificationKey(350), "a real double-tap right after still counts")
    }

    @Test fun aThirdKeyDoesNotPairWithTheSecond() {
        val d = DoubleTapDetector()
        d.onNotificationKey(0); assertTrue(d.onNotificationKey(150))
        assertFalse(d.onNotificationKey(DoubleTapDetector.SETTLE_MS + 200), "pair consumed; a new first key")
    }
}

class CloseConfirmTest {
    @Test fun pendingHubConfirmationAnswersNo() {
        for (phase in WorkoutPhase.entries) assertEquals(DoubleTapAction.AnswerNo, CloseConfirm().onDoubleTap(hubPending = true, phase = phase, nowMs = 0).second)
        assertEquals(DoubleTapAction.AnswerNo, CloseConfirm().show(0).onDoubleTap(hubPending = true, phase = WorkoutPhase.Active, nowMs = 0).second, "hub prompt wins over ours")
    }

    @Test fun recordingWorkoutAsksBeforeClosing() {
        for (phase in listOf(WorkoutPhase.Starting, WorkoutPhase.Active, WorkoutPhase.Paused, WorkoutPhase.Syncing)) {
            val (c, action) = CloseConfirm().onDoubleTap(hubPending = false, phase = phase, nowMs = 500)
            assertEquals(DoubleTapAction.AskClose, action, "$phase")
            assertTrue(c.shown); assertTrue(c.highlightYes, "a deliberate double-tap: Close is preselected")
        }
    }

    @Test fun idleOrSummaryLeavesStraightAway() {
        for (phase in listOf(null, WorkoutPhase.Idle, WorkoutPhase.Stopping, WorkoutPhase.Summary)) {
            val (c, action) = CloseConfirm().onDoubleTap(hubPending = false, phase = phase, nowMs = 0)
            assertEquals(DoubleTapAction.Leave, action, "$phase"); assertFalse(c.shown)
        }
    }

    @Test fun doubleTapOnOurConfirmMeansStay() {
        val (c, action) = CloseConfirm().show(0).onDoubleTap(hubPending = false, phase = WorkoutPhase.Active, nowMs = 100)
        assertEquals(DoubleTapAction.Stay, action); assertFalse(c.shown)
    }

    @Test fun swipeMovesAndTapAnswers() {
        val c = CloseConfirm().show(0)
        assertEquals(true, c.onTap().second, "yes = close")
        val (after, close) = c.onSwipe().onTap()
        assertEquals(false, close); assertFalse(after.shown)
        assertNull(CloseConfirm().onTap().second, "nothing shown: tap is not ours")
        assertEquals(CloseConfirm(), CloseConfirm().onSwipe())
    }

    @Test fun expiresAfterEightSecondsAsStay() {
        val c = CloseConfirm().show(1_000)
        assertEquals(c, c.timedOut(1_000 + CloseConfirm.TIMEOUT_MS - 1))
        assertFalse(c.timedOut(1_000 + CloseConfirm.TIMEOUT_MS).shown)
    }
}
