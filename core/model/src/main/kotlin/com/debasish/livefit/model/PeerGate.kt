package com.debasish.livefit.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Spec §4 (exported components): a Wearable listener acts only on messages whose source node advertises the peer
 * capability ([CAPABILITY_PHONE] on the watch, [CAPABILITY_WATCH] on the phone). The advertised node ids are cached; an
 * unknown sender triggers a fresh [lookup] at most once per [refreshAfterMs], so a just-installed peer is accepted on
 * its first message after the capability propagates.
 *
 * An already-verified sender is answered before the mutex, so it never queues behind another sender's lookup. A
 * lookup is abandoned after [lookupTimeoutMs]. A failed (or timed-out) lookup rejects the message, caches nothing, and
 * blocks further lookups for that node for [failureBackoffMs], so a stranger can't force a lookup per message.
 */
class PeerGate(
    private val lookup: suspend () -> Set<String>,
    private val nowMs: () -> Long,
    private val refreshAfterMs: Long = 10_000,
    private val lookupTimeoutMs: Long = LOOKUP_TIMEOUT_MS,
    private val failureBackoffMs: Long = FAILURE_BACKOFF_MS,
) {
    private val mutex = Mutex()
    @Volatile private var known: Set<String> = emptySet()
    private var lastLookupMs: Long? = null
    private val failedAtMs = HashMap<String, Long>()

    /** Non-suspending fast path (time-sync replies): true only for an already-verified sender. */
    fun known(sourceNodeId: String): Boolean = sourceNodeId in known

    suspend fun allows(sourceNodeId: String): Boolean {
        if (sourceNodeId in known) return true
        return mutex.withLock {
            if (sourceNodeId in known) return@withLock true
            val now = nowMs()
            val last = lastLookupMs
            if (last != null && now - last < refreshAfterMs) return@withLock false
            val failed = failedAtMs[sourceNodeId]
            if (failed != null && now - failed < failureBackoffMs) return@withLock false
            val fresh = try {
                withTimeoutOrNull(lookupTimeoutMs) { lookup() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (fresh == null) {
                if (failedAtMs.size >= MAX_TRACKED_FAILURES) failedAtMs.clear()
                failedAtMs[sourceNodeId] = nowMs()
                return@withLock false
            }
            known = fresh
            lastLookupMs = nowMs()
            failedAtMs.clear()
            sourceNodeId in fresh
        }
    }

    companion object {
        const val LOOKUP_TIMEOUT_MS = 5_000L
        const val FAILURE_BACKOFF_MS = 2_000L
        private const val MAX_TRACKED_FAILURES = 64
    }
}

/**
 * The peer node ids a [PeerGate] trusts: nodes advertising the peer capability, or — when none does (an older peer
 * build without the capability) — every connected node. Shared by the phone and the watch listeners.
 */
suspend fun peerNodeIds(
    advertised: suspend () -> Collection<String>,
    connected: suspend () -> Collection<String>,
): Set<String> = advertised().toHashSet().ifEmpty { connected().toHashSet() }
