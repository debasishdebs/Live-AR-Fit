package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.Confirmation
import com.debasish.livefit.model.ConfirmationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfirmInputTest {
    private fun c(id: String, defaultYes: Boolean = true) = Confirmation(id, ConfirmationKind.TakeOverWorkout, "Take over?", "", defaultYes = defaultYes, expiresAtMs = 0)

    @Test fun newConfirmationStartsOnDefaultAndRequestsMicOnce() {
        val i = ConfirmInput()
        assertTrue(i.onConfirmation(c("a")))
        assertTrue(i.highlightYes)
        assertFalse(i.onConfirmation(c("a")), "same id: no second auto mic")
        assertTrue(i.onConfirmation(c("b", defaultYes = false)))
        assertFalse(i.highlightYes)
    }

    @Test fun swipeTogglesAndTapAnswersHighlighted() {
        val i = ConfirmInput(); i.onConfirmation(c("a"))
        i.onSwipe()
        assertEquals(Command.Answer("a", yes = false), i.onTap())
    }

    @Test fun backMeansNo() {
        val i = ConfirmInput(); i.onConfirmation(c("a"))
        assertEquals(Command.Answer("a", yes = false), i.onBack())
    }

    @Test fun nothingPendingMeansNoAnswer() {
        val i = ConfirmInput(); i.onConfirmation(null)
        assertNull(i.onTap()); assertNull(i.onBack())
    }
}
