package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.WorkoutPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloseActionTest {
    /** Spec §4.4 General: "Ask before closing during a workout" (on/off). */
    @Test fun asksOnlyWhileRecordingAndWhenEnabled() {
        val (asked, a1) = CloseConfirm().onClose(WorkoutPhase.Active, ask = true, nowMs = 5)
        assertEquals(DoubleTapAction.AskClose, a1); assertTrue(asked.shown)
        val (left, a2) = CloseConfirm().onClose(WorkoutPhase.Active, ask = false, nowMs = 5)
        assertEquals(DoubleTapAction.Leave, a2); assertFalse(left.shown)
        assertEquals(DoubleTapAction.Leave, CloseConfirm().onClose(WorkoutPhase.Summary, ask = true, nowMs = 5).second)
    }
}
