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
