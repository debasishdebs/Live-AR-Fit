package com.debasish.livefit.services.glasses

/** Pure bookkeeping for the single in-flight Hi Rokid authorization attempt. */
class AuthGate(private val timeoutMs: Long = AUTH_TIMEOUT_MS) {
    var authorized = false
        private set
    private var inFlight = false
    private var startedAt = 0L
    private var rejected = false // the SDK rejected a token since the last started session

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

    /**
     * The SDK rejected the saved token (TOKEN_EXPIRED / NOT_AUTHENTICATED): authorization is gone.
     * Returns true when the caller should re-authorize; false when a token was already rejected since the
     * last started session (i.e. right after re-authorizing), so the rejection can't relaunch authorization in a loop.
     */
    fun tokenRejected(): Boolean {
        authorized = false
        inFlight = false
        return !rejected.also { rejected = true }
    }

    /** A session started with the current token: a later rejection may re-authorize again. */
    fun sessionStarted() { rejected = false }

    companion object { const val AUTH_TIMEOUT_MS = 30_000L }
}
