package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.Confirmation

/** Touchpad rules while a confirmation is shown (spec §6.3): swipe moves, tap confirms, back = No. */
class ConfirmInput {
    private var current: Confirmation? = null
    val hasPending: Boolean get() = current != null
    var highlightYes: Boolean = true
        private set

    /** Returns true when a new confirmation appeared (caller opens the mic for ~6 s). */
    fun onConfirmation(c: Confirmation?): Boolean {
        val isNew = c != null && c.id != current?.id
        current = c
        if (isNew) highlightYes = c!!.defaultYes
        return isNew
    }

    private var micFor: String? = null

    /**
     * True once per confirmation id: open the mic for a spoken answer. Kept apart from [onConfirmation]'s "new" result,
     * which a touchpad event syncing the prompt first would consume (the auto mic then never opened).
     */
    fun takeMicRequest(c: Confirmation?): Boolean {
        if (c == null || c.id == micFor) return false
        micFor = c.id
        return true
    }

    fun onSwipe() { if (current != null) highlightYes = !highlightYes }
    fun onTap(): Command.Answer? = current?.let { Command.Answer(it.id, highlightYes) }
    fun onBack(): Command.Answer? = current?.let { Command.Answer(it.id, yes = false) }
}
