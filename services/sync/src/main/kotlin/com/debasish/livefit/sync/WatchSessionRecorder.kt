package com.debasish.livefit.sync

import com.debasish.livefit.model.DeltaAck
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionClaim
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.workout.HrStats
import com.debasish.livefit.services.workout.SessionAssembler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Watch side of spec §4.4: every delta is persisted before it is sent and deleted only when the phone
 * acks it; a session's directory is removed once its final delta is acked.
 *
 * Several sessions can be held (an ended one awaiting its final ack + a newer one). Only the **oldest**
 * is sent, so the phone replays one session at a time and never adopts the newer one while the older
 * is still Syncing (adoption would finalize the older one as Incomplete).
 *
 * Recording never waits for the network: a delta is on disk (or in the retry list) when record returns, and
 * transmission runs in order on [scope] — so a stalled send can't hold back the sensor collector or Stop.
 */
class WatchSessionRecorder(
    private val root: File,
    private val provenance: Provenance,
    /** Single-threaded, like every caller; the send queue is drained here. */
    private val scope: CoroutineScope,
    private val send: suspend (SessionDelta) -> Unit,
    private val sendClaim: suspend (SessionClaim) -> Unit = {},
) {
    private class Held(val buffer: FileDeltaBuffer, var header: WatchSessionHeader, val assembler: SessionAssembler) {
        /** Deltas whose disk write failed: still sent by [resendUnacked] and re-written on the next record. */
        val retry = sortedMapOf<Long, SessionDelta>()
    }

    /** Oldest first. */
    private val held = mutableListOf<Held>()

    private sealed interface Outgoing {
        data class Delta(val d: SessionDelta) : Outgoing
        data class Claim(val c: SessionClaim) : Outgoing
    }
    /** Sent strictly in order by one drain coroutine at a time. */
    private val outbox = ArrayDeque<Outgoing>()
    private var draining = false

    init {
        root.mkdirs()
        root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
            val buffer = FileDeltaBuffer(dir)
            val stored = buffer.readHeader() ?: run { buffer.delete(); return@mapNotNull null }
            val cp = buffer.readCheckpoint()
            val pending = buffer.unacked()
            // A crash between put(d) and writeHeader leaves the header stale: trust the files too.
            val h = stored.copy(
                lastSeq = maxOf(stored.lastSeq, cp?.delta?.seq ?: -1, pending.maxOfOrNull { it.seq } ?: -1),
                finalSeq = stored.finalSeq ?: pending.lastOrNull { it.final }?.seq,
            ).also { if (it != stored) buffer.writeHeader(it) }
            if (h.finalSeq != null && cp != null && cp.delta.seq >= h.finalSeq) { buffer.delete(); return@mapNotNull null } // crashed after the final ack
            val a = SessionAssembler(h.sessionId)
            cp?.let { a.addCheckpoint(it.delta, HrStats(it.hrSum, it.hrCount, it.hrMax)) }
            pending.forEach { a.add(it) }
            Held(buffer, h, a)
        }.sortedBy { it.header.startMs }.let { held += it }
    }

    private val newest get() = held.lastOrNull()

    val sessionId: String? get() = newest?.header?.sessionId
    val assembler: SessionAssembler? get() = newest?.assembler
    val type: WorkoutType? get() = newest?.header?.type
    val isFinalized: Boolean get() = newest?.header?.finalSeq != null
    val holdsData: Boolean get() = held.isNotEmpty()

    fun begin(sessionId: String, type: WorkoutType, tMs: Long) {
        check(newest == null || isFinalized) { "session ${this.sessionId} is still recording" }
        val buffer = FileDeltaBuffer(File(root, sessionId))
        val h = WatchSessionHeader(sessionId, type, tMs, lastSeq = -1).also { buffer.writeHeader(it) }
        held += Held(buffer, h, SessionAssembler(sessionId))
        record(listOf(SessionEvent.Started(tMs, type)), emptyList(), final = false)
    }

    fun event(e: SessionEvent, final: Boolean = false) = record(listOf(e), emptyList(), final)
    fun sample(s: Sample) = record(emptyList(), listOf(s), final = false)

    private fun record(events: List<SessionEvent>, samples: List<Sample>, final: Boolean) {
        val h = newest?.takeIf { it.header.finalSeq == null } ?: return
        val d = SessionDelta(sessionId = h.header.sessionId, seq = h.header.lastSeq + 1, events = events, samples = samples, provenance = provenance, final = final)
        h.header = h.header.copy(lastSeq = d.seq, finalSeq = if (final) d.seq else null)
        // Disk trouble must not stop tracking: failed deltas stay in the retry list (still sent, re-written next record).
        h.retry[d.seq] = d
        flushRetry(h)
        h.assembler.add(d)
        if (h === held.first()) enqueue(Outgoing.Delta(d)) // unreachable phone, or an older session first: stays buffered
    }

    private fun flushRetry(h: Held) {
        try {
            for (r in h.retry.values.toList()) { h.buffer.put(r); h.retry.remove(r.seq) }
            h.buffer.writeHeader(h.header)
        } catch (e: java.io.IOException) {
            java.util.logging.Logger.getLogger("WatchSessionRecorder").warning("buffer write failed: $e")
        }
    }

    /** Queues for sending; a delta already waiting isn't queued twice (periodic resends while the link stalls). */
    private fun enqueue(o: Outgoing) {
        if (o is Outgoing.Delta && outbox.any { it is Outgoing.Delta && it.d.sessionId == o.d.sessionId && it.d.seq == o.d.seq }) return
        outbox.addLast(o)
        if (draining) return
        draining = true
        // Undispatched: a send that doesn't suspend completes before the caller continues, as before.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                while (true) {
                    when (val next = outbox.removeFirstOrNull() ?: break) {
                        is Outgoing.Delta -> trySend { send(next.d) }
                        is Outgoing.Claim -> trySend { sendClaim(next.c) } // retried on next resync
                    }
                }
            } finally {
                draining = false
            }
        }
    }

    /** Failures keep the delta buffered; cancellation must propagate. */
    private suspend fun trySend(op: suspend () -> Unit) {
        try { op() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    fun onAck(ack: DeltaAck) {
        val h = held.firstOrNull { it.header.sessionId == ack.sessionId } ?: return
        h.buffer.ackUpTo(ack.seq)
        h.retry.keys.removeAll { it <= ack.seq }
        outbox.removeAll { it is Outgoing.Delta && it.d.sessionId == ack.sessionId && it.d.seq <= ack.seq }
        val f = h.header.finalSeq ?: return
        if (ack.seq < f) return
        val wasOldest = h === held.first()
        h.buffer.delete()
        held.remove(h)
        if (wasOldest && held.isNotEmpty()) resync() // next session's turn
    }

    /** Re-sends the oldest session's unacked deltas (every 5 s while unacked, spec §4.4 step 3). */
    fun resendUnacked() {
        val h = held.firstOrNull() ?: return
        if (h.retry.isNotEmpty()) flushRetry(h)
        val toSend = (h.buffer.unacked() + h.retry.values).distinctBy { it.seq }.sortedBy { it.seq }
        for (d in toSend) enqueue(Outgoing.Delta(d))
    }

    /** Claims the oldest held session and replays it (on reconnect, and when the previous session completes). */
    fun resync() {
        claim()?.let { enqueue(Outgoing.Claim(it)) } // queued ahead of the replay
        resendUnacked()
    }

    fun claim(): SessionClaim? {
        val h = held.firstOrNull() ?: return null
        return SessionClaim(
            sessionId = h.header.sessionId,
            type = h.header.type,
            startMs = h.header.startMs,
            phase = h.assembler.phase(),
            activeMs = h.assembler.activeMs(),
            lastSeq = h.header.lastSeq,
        )
    }
}
