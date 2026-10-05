package com.debasish.livefit.services.workout

import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.services.SessionLifecycle
import com.debasish.livefit.services.SessionStore
import com.debasish.livefit.services.StoredSessionState
import java.util.TreeMap

class InMemorySessionStore : SessionStore {
    private val deltas = LinkedHashMap<String, TreeMap<Long, SessionDelta>>()
    private val finalized = LinkedHashMap<String, SessionSummary>()
    private val ended = HashMap<String, Pair<EndReason?, Long>>()
    private val discarded = HashSet<String>()
    val summaries: Map<String, SessionSummary> get() = finalized
    /** Order of storeDelta calls, for tests that check "store before ack". */
    val storeLog = mutableListOf<Pair<String, Long>>()

    override suspend fun storeDelta(delta: SessionDelta): Long {
        val m = deltas.getOrPut(delta.sessionId) { TreeMap() }
        m.putIfAbsent(delta.seq, delta)
        storeLog += delta.sessionId to delta.seq
        var s = -1L
        while (m.containsKey(s + 1)) s++
        return s
    }

    override suspend fun deltas(sessionId: String): List<SessionDelta> = deltas[sessionId]?.values?.toList() ?: emptyList()
    override suspend fun openSessionIds(): List<String> = deltas.keys.filter { it !in finalized && it !in discarded }
    override suspend fun finalize(summary: SessionSummary) { finalized[summary.id] = summary }
    override suspend fun discard(sessionId: String) { deltas.remove(sessionId); finalized.remove(sessionId); ended.remove(sessionId); discarded += sessionId }

    override suspend fun lifecycle(sessionId: String): SessionLifecycle? {
        val e = ended[sessionId]
        return when {
            sessionId in discarded -> SessionLifecycle(StoredSessionState.Discarded)
            sessionId in finalized -> SessionLifecycle(StoredSessionState.Finalized, e?.first, e?.second)
            sessionId in deltas || e != null -> SessionLifecycle(StoredSessionState.Open, e?.first, e?.second)
            else -> null
        }
    }

    override suspend fun markEnded(sessionId: String, endReason: EndReason?, endedAtMs: Long) {
        val old = ended[sessionId]
        ended[sessionId] = (old?.first ?: endReason) to (old?.second ?: endedAtMs)
    }
}
