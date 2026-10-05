package com.debasish.livefit.sync

import com.debasish.livefit.services.Clock

/** Offline only after [timeoutMs] without a frame or on an explicit transport disconnect (spec §4.3). */
class LivenessMonitor(private val clock: Clock, private val timeoutMs: Long = 12_000) {
    private var lastFrameMs: Long? = null
    private var disconnected = true

    @Synchronized fun onFrame() { lastFrameMs = clock.nowMs(); disconnected = false }
    @Synchronized fun onTransportDisconnected() { disconnected = true }
    @Synchronized fun isOnline(): Boolean = !disconnected && lastFrameMs?.let { clock.nowMs() - it < timeoutMs } == true
}
