package com.debasish.livefit.sync

import com.debasish.livefit.model.DeltaAck
import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionClaim
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutType
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchSessionRecorderTest {
    private val live = Provenance.Live("galaxy-watch/health-services")
    private fun tmp(): File = Files.createTempDirectory("lfbuf").toFile()

    @Test fun deltasArePersistedBeforeSendAndPrunedOnAck() = runTest {
        val root = tmp()
        val sent = mutableListOf<SessionDelta>()
        val rec = WatchSessionRecorder(root, live, send = { d ->
            assertTrue(File(root, "s/d-${d.seq}.json").exists(), "persisted before send")
            sent += d
        })
        rec.begin("s", WorkoutType.Run, 1_000)
        rec.sample(Sample(2_000, hr = 120))
        rec.sample(Sample(3_000, hr = 125))
        assertEquals(listOf(0L, 1L, 2L), sent.map { it.seq })
        rec.onAck(DeltaAck(sessionId = "s", seq = 1))
        assertEquals(listOf(2L), FileDeltaBuffer(File(root, "s")).unacked().map { it.seq })
    }

    @Test fun ackForAnotherSessionIsIgnored() = runTest {
        val root = tmp()
        val rec = WatchSessionRecorder(root, live, send = {})
        rec.begin("s", WorkoutType.Walk, 0)
        rec.onAck(DeltaAck(sessionId = "other", seq = 0))
        assertEquals(1, FileDeltaBuffer(File(root, "s")).unacked().size)
    }

    @Test fun finalAckClearsTheSession() = runTest {
        val root = tmp()
        val rec = WatchSessionRecorder(root, live, send = {})
        rec.begin("s", WorkoutType.Walk, 0)
        rec.event(SessionEvent.Stopped(5_000, EndReason.User), final = true)
        assertTrue(rec.isFinalized)
        rec.onAck(DeltaAck(sessionId = "s", seq = 1))
        assertFalse(File(root, "s").exists())
        assertNull(rec.sessionId)
        assertFalse(rec.holdsData)
    }

    @Test fun survivesProcessRestartAndResendsUnacked() = runTest {
        val root = tmp()
        WatchSessionRecorder(root, live, send = { error("phone unreachable") }).apply {
            begin("s", WorkoutType.Walk, 0)
            sample(Sample(1_000, hr = 100))
        }
        val resent = mutableListOf<Long>()
        val rec = WatchSessionRecorder(root, live, send = { resent += it.seq })
        assertEquals("s", rec.sessionId)
        rec.resendUnacked()
        assertEquals(listOf(0L, 1L), resent)
        rec.sample(Sample(2_000, hr = 101))
        assertEquals(2L, resent.last(), "seq continues after restart")
    }

    @Test fun claimDescribesTheHeldSession() = runTest {
        val rec = WatchSessionRecorder(tmp(), live, send = {})
        rec.begin("s", WorkoutType.Run, 0)
        rec.sample(Sample(10_000, hr = 130))
        rec.event(SessionEvent.Paused(10_000))
        val c = rec.claim()!!
        assertEquals("s", c.sessionId)
        assertEquals(WorkoutType.Run, c.type)
        assertEquals(WorkoutPhase.Paused, c.phase)
        assertEquals(10_000, c.activeMs)
        assertEquals(2, c.lastSeq)
    }

    /** Codex P1: acked (deleted) deltas must still count after a watch restart. */
    @Test fun restartAfterPruningRestoresStateFromTheCheckpoint() = runTest {
        val root = tmp()
        WatchSessionRecorder(root, live, send = {}).apply {
            begin("s", WorkoutType.Walk, 0)
            sample(Sample(10_000, hr = 110, stepsTotal = 20))
            event(SessionEvent.Paused(10_000))
            onAck(DeltaAck(sessionId = "s", seq = 2)) // phone stored everything; files deleted
        }
        assertTrue(FileDeltaBuffer(File(root, "s")).unacked().isEmpty())
        val rec = WatchSessionRecorder(root, live, send = {})
        assertEquals(WorkoutPhase.Paused, rec.assembler!!.phase())
        assertEquals(10_000, rec.assembler!!.activeMs())
        assertEquals(20, rec.assembler!!.snapshot().metrics.steps)
        assertEquals(WorkoutPhase.Paused, rec.claim()!!.phase)
        assertEquals(10_000, rec.claim()!!.activeMs)
        rec.sample(Sample(11_000, hr = 111))
        assertEquals(3L, FileDeltaBuffer(File(root, "s")).unacked().single().seq)
    }

    /** Average/max HR still cover the whole session after a restart, though only a 120-sample tail is kept. */
    @Test fun restartKeepsFullSessionHeartRateStats() = runTest {
        val root = tmp()
        WatchSessionRecorder(root, live, send = {}).apply {
            begin("s", WorkoutType.Run, 0)
            sample(Sample(1_000, hr = 190)) // the peak, soon outside the tail
            for (t in 2..300) sample(Sample(t * 1_000L, hr = 100))
            onAck(DeltaAck(sessionId = "s", seq = 300))
        }
        val rec = WatchSessionRecorder(root, live, send = {})
        val snap = rec.assembler!!.snapshot()
        assertEquals(190, snap.maxHeartRate)
        assertEquals((190 + 299 * 100) / 300, snap.avgHeartRate)
        assertEquals(FileDeltaBuffer.CHECKPOINT_SAMPLES, rec.assembler!!.hrHistory(1_000).size)
    }

    /** Codex P1: stop A offline → start B → reconnect → both recovered, A first. */
    @Test fun endedSessionIsKeptWhenAnotherStartsAndSyncsFirst() = runTest {
        val root = tmp()
        WatchSessionRecorder(root, live, send = { error("phone unreachable") }).apply {
            begin("a", WorkoutType.Walk, 0)
            event(SessionEvent.Stopped(5_000, EndReason.User), final = true)
            begin("b", WorkoutType.Run, 10_000)
            sample(Sample(11_000, hr = 140))
        }
        val sent = mutableListOf<Pair<String, Long>>()
        val claims = mutableListOf<SessionClaim>()
        val rec = WatchSessionRecorder(root, live, send = { sent += it.sessionId to it.seq }, sendClaim = { claims += it })
        assertEquals("b", rec.sessionId, "the newest session is the one shown and recorded")
        rec.resync() // reconnect
        assertEquals(listOf("a"), claims.map { it.sessionId })
        assertEquals(listOf("a" to 0L, "a" to 1L), sent, "b waits until a is fully stored")
        rec.sample(Sample(12_000, hr = 141)) // b keeps recording, still held back
        assertEquals(2, sent.size)
        rec.onAck(DeltaAck(sessionId = "a", seq = 1)) // a complete → b's turn
        assertFalse(File(root, "a").exists())
        assertEquals(listOf("a", "b"), claims.map { it.sessionId })
        assertEquals(listOf("b" to 0L, "b" to 1L, "b" to 2L), sent.drop(2))
        rec.sample(Sample(13_000, hr = 142))
        assertEquals("b" to 3L, sent.last(), "b is now sent live")
    }
}
