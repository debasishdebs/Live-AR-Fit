package com.debasish.livefit.services.watch

import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.ExerciseResult
import com.debasish.livefit.model.ExerciseStateReport
import com.debasish.livefit.model.PROTOCOL_VERSION
import com.debasish.livefit.model.SessionClaim
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.WatchPaths
import com.debasish.livefit.model.Wire

sealed interface WatchInbound {
    data class Delta(val delta: SessionDelta) : WatchInbound
    data class Result(val result: ExerciseResult) : WatchInbound
    data class State(val report: ExerciseStateReport) : WatchInbound
    data class Claim(val claim: SessionClaim) : WatchInbound
    data class Cmd(val envelope: CommandEnvelope) : WatchInbound
    data class Battery(val pct: Int) : WatchInbound
    data class Outdated(val version: Int?) : WatchInbound
}

/** Pure decoding of watch → phone messages; version-checks every JSON message. */
object WatchMessageCodec {
    fun decode(path: String, bytes: ByteArray): WatchInbound? {
        val text = String(bytes)
        if (path == WatchPaths.BATTERY) return text.trim().toIntOrNull()?.let { WatchInbound.Battery(it) }
        if (path !in jsonPaths) return null
        val version = Wire.versionOf(text) ?: return null
        if (version != PROTOCOL_VERSION) return WatchInbound.Outdated(version)
        return runCatching {
            when (path) {
                WatchPaths.DELTA -> WatchInbound.Delta(Wire.decode(text))
                WatchPaths.EXERCISE_RES -> WatchInbound.Result(Wire.decode(text))
                WatchPaths.EXERCISE_STATE -> WatchInbound.State(Wire.decode(text))
                WatchPaths.CLAIM -> WatchInbound.Claim(Wire.decode(text))
                else -> WatchInbound.Cmd(Wire.decode(text))
            }
        }.getOrNull()
    }

    private val jsonPaths = setOf(WatchPaths.DELTA, WatchPaths.EXERCISE_RES, WatchPaths.EXERCISE_STATE, WatchPaths.CLAIM, WatchPaths.COMMAND)
}
