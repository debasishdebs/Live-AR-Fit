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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
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
        val rec = WatchSessionRecorder(root, live, backgroundScope, send = { d ->
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

    @Test fun failedDiskWriteKeepsDeltaInRetryListAndRecoversOnNextRecord() = runTest {
        val root = tmp()
        val sent = mutableListOf<SessionDelta>()
        var online = false
        val rec = WatchSessionRecorder(root, live, backgroundScope, send = { d -> if (!online) error("offline"); sent += d })
        rec.begin("s", WorkoutType.Run, 0)
        val blocker = File(root, "s/d-1.json.tmp").also { it.mkdirs() } // makes the write of seq 1 fail
        rec.sample(Sample(1_000, hr = 100))
        assertFalse(File(root, "s/d-1.json").exists(), "disk write failed")
        online = true
        rec.resendUnacked()
        assertEquals(listOf(0L, 1L), sent.map { it.seq }, "failed delta is still resent from memory")
        blocker.delete()
        rec.sample(Sample(2_000, hr = 101))
        assertEquals(listOf(0L, 1L, 2L), FileDeltaBuffer(File(root, "s")).unacked().map { it.seq }, "retried write landed")
    }

    @Test fun ackForAnotherSessionIsIgnored() = runTest {
        val root = tmp()
        val rec = WatchSessionRecorder(root, live, backgroundScope, send = {})
        rec.begin("s", WorkoutType.Walk, 0)
        rec.onAck(DeltaAck(sessionId = "other", seq = 0))
        assertEquals(1, FileDeltaBuffer(File(root, "s")).unacked().size)
    }

    @Test fun finalAckClearsTheSession() = runTest {
        val root = tmp()
        val rec = WatchSessionRecorder(root, live, backgroundScope, send = {})
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
        WatchSessionRecorder(root, live, backgroundScope, send = { error("phone unreachable") }).apply {
            begin("s", WorkoutType.Walk, 0)
            sample(Sample(1_000, hr = 100))
        }
        val resent = mutableListOf<Long>()
        val rec = WatchSessionRecorder(root, live, backgroundScope, send = { resent += it.seq })
        assertEquals("s", rec.sessionId)
        rec.resendUnacked()
        assertEquals(listOf(0L, 1L), resent)
        rec.sample(Sample(2_000, hr = 101))
        assertEquals(2L, resent.last(), "seq continues after restart")
    }

    @Test fun claimDescribesTheHeldSession() = runTest {
        val rec = WatchSessionRecorder(tmp(), live, backgroundScope, send = {})
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
        WatchSessionRecorder(root, live, backgroundScope, send = {}).apply {
            begin("s", WorkoutType.Walk, 0)
            sample(Sample(10_000, hr = 110, stepsTotal = 20))
            event(SessionEvent.Paused(10_000))
            onAck(DeltaAck(sessionId = "s", seq = 2)) // phone stored everything; files deleted
        }
        assertTrue(FileDeltaBuffer(File(root, "s")).unacked().isEmpty())
        val rec = WatchSessionRecorder(root, live, backgroundScope, send = {})
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
        WatchSessionRecorder(root, live, backgroundScope, send = {}).apply {
            begin("s", WorkoutType.Run, 0)
            sample(Sample(1_000, hr = 190)) // the peak, soon outside the tail
            for (t in 2..300) sample(Sample(t * 1_000L, hr = 100))
            onAck(DeltaAck(sessionId = "s", seq = 300))
        }
        val rec = WatchSessionRecorder(root, live, backgroundScope, send = {})
        val snap = rec.assembler!!.snapshot()
        assertEquals(190, snap.maxHeartRate)
        assertEquals((190 + 299 * 100) / 300, snap.avgHeartRate)
        assertEquals(FileDeltaBuffer.CHECKPOINT_SAMPLES, rec.assembler!!.hrHistory(1_000).size)
    }

    /** Codex P1: stop A offline → start B → reconnect → both recovered, A first. */
    @Test fun endedSessionIsKeptWhenAnotherStartsAndSyncsFirst() = runTest {
        val root = tmp()
        WatchSessionRecorder(root, live, backgroundScope, send = { error("phone unreachable") }).apply {
            begin("a", WorkoutType.Walk, 0)
            event(SessionEvent.Stopped(5_000, EndReason.User), final = true)
            begin("b", WorkoutType.Run, 10_000)
            sample(Sample(11_000, hr = 140))
        }
        val sent = mutableListOf<Pair<String, Long>>()
        val claims = mutableListOf<SessionClaim>()
        val rec = WatchSessionRecorder(root, live, backgroundScope, send = { sent += it.sessionId to it.seq }, sendClaim = { claims += it })
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

    private fun snapshotFiles(dir: File): Map<String, String> =
        dir.listFiles { f -> f.name.startsWith("d-") }!!.associate { it.name to it.readText() }

    @Test fun crashBetweenCheckpointAndDeleteDoesNotDoubleCount() = runTest {
        val root = tmp()
        val rec = WatchSessionRecorder(root, live, backgroundScope, send = {})
        rec.begin("s", WorkoutType.Walk, 0)
        rec.sample(Sample(10_000, hr = 110, stepsTotal = 20))
        rec.sample(Sample(20_000, hr = 130, stepsTotal = 40))
        val before = snapshotFiles(File(root, "s"))
        rec.onAck(DeltaAck(sessionId = "s", seq = 2))
        before.forEach { (n, t) -> File(root, "s/$n").writeText(t) } // crash before deletion
        val r = WatchSessionRecorder(root, live, backgroundScope, send = {})
        assertTrue(FileDeltaBuffer(File(root, "s")).unacked().isEmpty())
        val snap = r.assembler!!.snapshot()
        assertEquals(40, snap.metrics.steps)
        assertEquals(120, snap.avgHeartRate)
        assertEquals(130, snap.maxHeartRate)
        assertEquals(20_000, r.assembler!!.activeMs())
        r.onAck(DeltaAck(sessionId = "s", seq = 2))
        assertTrue(File(root, "s").listFiles { f -> f.name.startsWith("d-") }!!.isEmpty())
    }

    @Test fun crashAfterFinalAckRemovesTheSession() = runTest {
        val root = tmp()
        val rec = WatchSessionRecorder(root, live, backgroundScope, send = {})
        rec.begin("s", WorkoutType.Walk, 0)
        rec.event(SessionEvent.Stopped(5_000, EndReason.User), final = true)
        val dir = File(root, "s")
        val header = File(dir, "header.json").readText()
        val files = snapshotFiles(dir)
        rec.onAck(DeltaAck(sessionId = "s", seq = 1))
        // crash after the checkpoint but before the directory was deleted
        FileDeltaBuffer(dir).also { b ->
            b.writeHeader(com.debasish.livefit.model.Wire.decode<WatchSessionHeader>(header))
            val tmpDir = tmp()
            files.forEach { (n, t) -> File(tmpDir, n).writeText(t) }
            val cpBuf = FileDeltaBuffer(File(tmpDir, "x"))
            files.forEach { (n, t) -> File(cpBuf.dir, n).writeText(t) }
            cpBuf.writeHeader(com.debasish.livefit.model.Wire.decode<WatchSessionHeader>(header))
            cpBuf.ackUpTo(1)
            File(cpBuf.dir, "checkpoint.json").copyTo(File(dir, "checkpoint.json"))
        }
        val r = WatchSessionRecorder(root, live, backgroundScope, send = {})
        assertFalse(r.holdsData)
        assertFalse(dir.exists())
    }

    @Test fun secondAckMergesIntoTheExistingCheckpoint() = runTest {
        val root = tmp()
        val rec = WatchSessionRecorder(root, live, backgroundScope, send = {})
        rec.begin("s", WorkoutType.Run, 0)
        rec.sample(Sample(1_000, hr = 100))
        rec.onAck(DeltaAck(sessionId = "s", seq = 1))
        rec.sample(Sample(2_000, hr = 180))
        rec.event(SessionEvent.Paused(2_000))
        rec.onAck(DeltaAck(sessionId = "s", seq = 3))
        val cp = FileDeltaBuffer(File(root, "s")).readCheckpoint()!!
        assertEquals(3L, cp.delta.seq)
        assertEquals(280L, cp.hrSum)
        assertEquals(2, cp.hrCount)
        assertEquals(180, cp.hrMax)
        assertEquals(2, cp.delta.events.size)
        assertEquals(2, cp.delta.samples.size)
    }

    @Test fun crashBeforeHeaderUpdateKeepsTheDelta() = runTest {
        val root = tmp()
        WatchSessionRecorder(root, live, backgroundScope, send = {}).apply { begin("s", WorkoutType.Walk, 0) }
        val buf = FileDeltaBuffer(File(root, "s"))
        // put(d) landed, writeHeader did not
        buf.put(SessionDelta(sessionId = "s", seq = 1, events = listOf(SessionEvent.Stopped(5_000, EndReason.User)), provenance = live, final = true))
        val sent = mutableListOf<Long>()
        val r = WatchSessionRecorder(root, live, backgroundScope, send = { sent += it.seq })
        assertTrue(r.isFinalized)
        assertEquals(1L, buf.readHeader()!!.finalSeq)
        r.sample(Sample(6_000, hr = 90)) // finalized: ignored, must not overwrite d-1
        assertEquals(1L, buf.unacked().last().seq)
        assertTrue(buf.unacked().last().final)
        r.resendUnacked()
        assertEquals(listOf(0L, 1L), sent)
    }

    /** Review #1: recording returns once the delta is on disk; a stalled send only delays later sends, in order. */
    @Test fun stalledSendNeitherBlocksRecordingNorReordersDeltas() = runTest {
        val root = tmp()
        val gate = CompletableDeferred<Unit>()
        val sent = mutableListOf<Long>()
        val rec = WatchSessionRecorder(root, live, backgroundScope, send = { d -> if (d.seq == 1L) gate.await(); sent += d.seq })
        rec.begin("s", WorkoutType.Run, 0)
        rec.sample(Sample(1_000, hr = 100)) // send stalls
        rec.sample(Sample(2_000, hr = 101))
        rec.event(SessionEvent.Stopped(3_000, EndReason.User), final = true)
        assertEquals(listOf(0L, 1L, 2L, 3L), FileDeltaBuffer(File(root, "s")).unacked().map { it.seq })
        assertEquals(listOf(0L), sent)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(0L, 1L, 2L, 3L), sent)
    }
}
