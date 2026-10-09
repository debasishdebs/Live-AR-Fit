package com.debasish.livefit.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Spec §4 (exported components): a Wearable listener acts only on messages whose source node advertises the peer
 * capability ([CAPABILITY_PHONE] on the watch, [CAPABILITY_WATCH] on the phone). The advertised node ids are cached; an
 * unknown sender triggers a fresh [lookup] at most once per [refreshAfterMs], so a just-installed peer is accepted on
 * its first message after the capability propagates. A failed lookup rejects the message and caches nothing.
 */
class PeerGate(
    private val lookup: suspend () -> Set<String>,
    private val nowMs: () -> Long,
    private val refreshAfterMs: Long = 10_000,
) {
    private val mutex = Mutex()
    @Volatile private var known: Set<String> = emptySet()
    private var lastLookupMs: Long? = null

    /** Non-suspending fast path (time-sync replies): true only for an already-verified sender. */
    fun known(sourceNodeId: String): Boolean = sourceNodeId in known

    suspend fun allows(sourceNodeId: String): Boolean = mutex.withLock {
        if (sourceNodeId in known) return@withLock true
        val last = lastLookupMs
        if (last != null && nowMs() - last < refreshAfterMs) return@withLock false
        val fresh = try {
            lookup()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withLock false
        }
        known = fresh
        lastLookupMs = nowMs()
        sourceNodeId in fresh
    }
}
