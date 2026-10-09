package com.debasish.livefit.watch

/**
 * When the watch itself tries to bring the workout screen to the front (A3): the phone's RemoteActivityHelper launch
 * is delivered by the watch's companion app and can lag by minutes on some watches, so a hub Start (while recording) and each new
 * hub confirmation also raise it from the watch side. At most one raise per [throttleMs]. Pure.
 */
class FrontLaunchPolicy(private val throttleMs: Long = 10_000) {
    private var lastRaiseMs: Long? = null
    private var lastConfirmationId: String? = null

    fun onHubStart(recording: Boolean, visible: Boolean, nowMs: Long): Boolean = recording && !visible && claim(nowMs)

    fun onConfirmation(id: String?, visible: Boolean, nowMs: Long): Boolean {
        if (id == null || id == lastConfirmationId) return false
        lastConfirmationId = id
        return !visible && claim(nowMs)
    }

    private fun claim(nowMs: Long): Boolean {
        if (lastRaiseMs?.let { nowMs - it < throttleMs } == true) return false
        lastRaiseMs = nowMs
        return true
    }
}
