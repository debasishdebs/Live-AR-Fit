package com.debasish.livefit.phone.setup

import android.Manifest

/** First-run steps (spec §6.1). Map fallback (location) and Voice are optional. */
enum class SetupStep { Welcome, Glasses, Watch, Music, Map, Voice, Done }

class SetupFlow(start: SetupStep = SetupStep.Welcome) {
    var step: SetupStep = start
        private set
    var voiceEnabled: Boolean = false
        private set
    private var packInstalled = false

    fun voicePackInstalled() { packInstalled = true }

    fun next() {
        if (step == SetupStep.Voice) voiceEnabled = packInstalled
        if (step != SetupStep.Done) step = SetupStep.entries[step.ordinal + 1]
    }

    fun skip() {
        if (step == SetupStep.Voice) voiceEnabled = false
        if (step != SetupStep.Done) step = SetupStep.entries[step.ordinal + 1]
    }

    fun back() { if (step != SetupStep.Welcome) step = SetupStep.entries[step.ordinal - 1] }
}

/** Runtime permissions the Welcome step requests: mic, nearby devices (API 31+), notifications (API 33+). */
fun setupPermissions(sdk: Int): Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (sdk >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
    if (sdk >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

/** How a setup step ended: the user granted/completed it, was refused, failed, or it was already done on entry. */
enum class StepOutcome { Granted, Denied, Failed, AlreadySatisfied }

/**
 * Whether Setup shows the green tick and moves on by itself. Denied/failed never advance (the existing message stays).
 * A step with a Play-policy disclosure never advances until that disclosure has been shown at least once, even if the
 * permission is already held.
 */
fun shouldAutoAdvance(outcome: StepOutcome, disclosureRequired: Boolean, disclosureShown: Boolean): Boolean = when (outcome) {
    StepOutcome.Denied, StepOutcome.Failed -> false
    StepOutcome.Granted, StepOutcome.AlreadySatisfied -> !disclosureRequired || disclosureShown
}

/** Tick animation + the pause before advancing (spec: ~700-900 ms in total). */
const val TICK_DRAW_MS = 500
const val TICK_ADVANCE_MS = 800L
