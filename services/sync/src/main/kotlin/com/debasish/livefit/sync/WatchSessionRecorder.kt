package com.debasish.livefit.sync

import com.debasish.livefit.model.DeltaAck
import com.debasish.livefit.model.LocationFix
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
import kotlinx.coroutines.withTimeoutOrNull
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
 * Each send is bounded by [SEND_TIMEOUT_MS]: a send that never returns counts as failed (the delta stays buffered)
 * instead of halting the queue for the process lifetime.
 */
class WatchSessionRecorder(
    private val root: File,
    private val provenance: Provenance,
    /** Single-threaded, like every caller; the send queue is drained here. */
    private val scope: CoroutineScope,
    private val send: suspend (SessionDelta) -> Unit,
    private val sendClaim: suspend (SessionClaim) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sendTimeoutMs: Long = SEND_TIMEOUT_MS,
    /** The phone acked this session's final delta: it is finalized there (WatchRouteFile.markAcked). */
    private val onFinalAcked: (String) -> Unit = {},
) {
    private class Held(val buffer: FileDeltaBuffer, var header: WatchSessionHeader, val assembler: SessionAssembler) {
        /** Deltas whose disk write failed: still sent by [resendUnacked] and re-written on the next record. */
        val retry = sortedMapOf<Long, SessionDelta>()
        /** When each unacked delta was last sent successfully; the periodic resend skips recent ones. */
        val sentAtMs = HashMap<Long, Long>()
    }

    /** Oldest first. */
    private val held = mutableListOf<Held>()

    private sealed interface Outgoing {
        data class Delta(val d: SessionDelta) : Outgoing
        data class Claim(val c: SessionClaim) : Outgoing
    }
    /** Sent strictly in order by one drain coroutine at a time. */
    private val outbox = ArrayDeque<Outgoing>()
    private var inFlight: Outgoing? = null
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
    /** True while [sessionId]'s data is still held (recording, or ended and awaiting its final ack). */
    fun holds(sessionId: String): Boolean = held.any { it.header.sessionId == sessionId }

    fun begin(sessionId: String, type: WorkoutType, tMs: Long, gps: Boolean = false) {
        check(newest == null || isFinalized) { "session ${this.sessionId} is still recording" }
        val buffer = FileDeltaBuffer(File(root, sessionId))
        val h = WatchSessionHeader(sessionId, type, tMs, lastSeq = -1).also { buffer.writeHeader(it) }
        held += Held(buffer, h, SessionAssembler(sessionId))
        record(listOf(SessionEvent.Started(tMs, type, gps)), emptyList(), final = false)
    }

    fun event(e: SessionEvent, final: Boolean = false) = record(listOf(e), emptyList(), final)
    fun sample(s: Sample) = samples(listOf(s))
    /** A batch (e.g. screen-off HR points) is one delta. */
    fun samples(s: List<Sample>) { if (s.isNotEmpty()) record(emptyList(), s, final = false) }

    /** One Health Services location batch is one delta (spec §2.1: fixes travel ordered, acked and offline-buffered). */
    fun locations(fixes: List<LocationFix>) { if (fixes.isNotEmpty()) record(emptyList(), emptyList(), final = false, locations = fixes) }

    private fun record(events: List<SessionEvent>, samples: List<Sample>, final: Boolean, locations: List<LocationFix> = emptyList()) {
        val h = newest?.takeIf { it.header.finalSeq == null } ?: return
        val d = SessionDelta(sessionId = h.header.sessionId, seq = h.header.lastSeq + 1, events = events, samples = samples, provenance = provenance, final = final, locations = locations)
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

    private fun Outgoing.isDelta(sessionId: String, seq: Long) = this is Outgoing.Delta && d.sessionId == sessionId && d.seq == seq

    /** Queues for sending; a delta already waiting or being sent isn't queued twice (periodic resends while the link stalls). */
    private fun enqueue(o: Outgoing) {
        if (o is Outgoing.Delta && (inFlight?.isDelta(o.d.sessionId, o.d.seq) == true || outbox.any { it.isDelta(o.d.sessionId, o.d.seq) })) return
        outbox.addLast(o)
        if (draining) return
        draining = true
        // Undispatched: a send that doesn't suspend completes before the caller continues, as before.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                while (true) {
                    val next = outbox.removeFirstOrNull() ?: break
                    inFlight = next
                    when (next) {
                        is Outgoing.Delta -> if (trySend { send(next.d) }) {
                            held.firstOrNull { it.header.sessionId == next.d.sessionId }?.sentAtMs?.set(next.d.seq, nowMs())
                        }
                        is Outgoing.Claim -> trySend { sendClaim(next.c) } // retried on next resync
                    }
                    inFlight = null
                }
            } finally {
                inFlight = null
                draining = false
            }
        }
    }

    /** False on failure or timeout (the delta stays buffered); cancellation of the drain itself must propagate. */
    private suspend fun trySend(op: suspend () -> Unit): Boolean =
        try { withTimeoutOrNull(sendTimeoutMs) { op(); true } ?: false } catch (e: CancellationException) { throw e } catch (_: Exception) { false }

    fun onAck(ack: DeltaAck) {
        val h = held.firstOrNull { it.header.sessionId == ack.sessionId } ?: return
        h.buffer.ackUpTo(ack.seq)
        h.retry.keys.removeAll { it <= ack.seq }
        h.sentAtMs.keys.removeAll { it <= ack.seq }
        outbox.removeAll { it is Outgoing.Delta && it.d.sessionId == ack.sessionId && it.d.seq <= ack.seq }
        val f = h.header.finalSeq ?: return
        if (ack.seq < f) return
        val wasOldest = h === held.first()
        h.buffer.delete()
        onFinalAcked(ack.sessionId)
        held.remove(h)
        if (wasOldest && held.isNotEmpty()) resync() // next session's turn
    }

    /**
     * Re-sends the oldest session's unacked deltas (every 5 s while unacked, spec §4.4 step 3). Deltas sent less than
     * [RESEND_AFTER_MS] ago are skipped, so a slow backlog isn't queued again behind itself.
     */
    fun resendUnacked() = replay(all = false)

    private fun replay(all: Boolean) {
        val h = held.firstOrNull() ?: return
        if (h.retry.isNotEmpty()) flushRetry(h)
        val now = nowMs()
        val toSend = (h.buffer.unacked() + h.retry.values).distinctBy { it.seq }.sortedBy { it.seq }
            .filter { all || h.sentAtMs[it.seq]?.let { at -> now - at >= RESEND_AFTER_MS } != false }
        for (d in toSend) enqueue(Outgoing.Delta(d))
    }

    /** Claims the oldest held session and replays all of it (on reconnect, and when the previous session completes). */
    fun resync() {
        val c = claim() ?: return
        // The claim goes ahead of every queued item of its session (stale live deltas, a backlog); the replay re-adds them.
        outbox.removeAll { (it is Outgoing.Delta && it.d.sessionId == c.sessionId) || (it is Outgoing.Claim && it.c.sessionId == c.sessionId) }
        enqueue(Outgoing.Claim(c))
        replay(all = true)
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

    companion object {
        /** Longer than WatchRuntime's own Data Layer timeout, so this is only the backstop. */
        const val SEND_TIMEOUT_MS = 15_000L
        /** A little under the 5 s resend tick, so tick jitter doesn't skip a whole round. */
        const val RESEND_AFTER_MS = 4_000L
    }
}
