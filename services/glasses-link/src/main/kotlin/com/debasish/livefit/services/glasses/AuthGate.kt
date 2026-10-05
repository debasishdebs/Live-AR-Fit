package com.debasish.livefit.services.glasses

/** Pure bookkeeping for the single in-flight Hi Rokid authorization attempt. */
class AuthGate(private val timeoutMs: Long = AUTH_TIMEOUT_MS) {
    var authorized = false
        private set
    private var inFlight = false
    private var startedAt = 0L

    /** True when the caller should launch the authorization UI now (none is in flight, or the last one is stale). */
    fun begin(now: Long): Boolean {
        if (inFlight && now - startedAt < timeoutMs) return false
        inFlight = true
        startedAt = now
        return true
    }

    /** Drops an attempt older than the timeout (e.g. before a manual connect). */
    fun clearStale(now: Long) { if (inFlight && now - startedAt >= timeoutMs) inFlight = false }

    /** The timeout fired without any result. */
    fun expire() { inFlight = false }

    /** Returns true if this result should be applied; late/duplicate reports for a resolved attempt are ignored. */
    fun result(ok: Boolean): Boolean {
        if (authorized && !inFlight) return false
        inFlight = false
        if (ok) authorized = true
        return true
    }

    companion object { const val AUTH_TIMEOUT_MS = 30_000L }
}
