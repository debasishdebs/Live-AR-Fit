package com.debasish.livefit.sync

/** Reconnect delays 2 s → 5 s → 10 s → 30 s, then 30 s forever (spec §5.3). */
class Backoff(private val stepsMs: List<Long> = listOf(2_000, 5_000, 10_000, 30_000)) {
    private var i = 0
    fun next(): Long = stepsMs[minOf(i++, stepsMs.lastIndex)]
    fun reset() { i = 0 }
}
