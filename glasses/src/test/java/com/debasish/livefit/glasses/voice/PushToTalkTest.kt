package com.debasish.livefit.glasses.voice

import com.debasish.livefit.model.GlassesChannels
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushToTalkTest {
    private fun awaitIdle(p: PushToTalk) {
        val end = System.currentTimeMillis() + 3_000
        while (p.recording.value && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    @Test fun failingRecorderResetsRecordingAndSendsNothing() {
        val sent = CopyOnWriteArrayList<String>(); val errors = CopyOnWriteArrayList<String>()
        val p = PushToTalk({ ch, _ -> sent += ch }, onError = { errors += it }, sourceFactory = { error("boom") })
        p.start(); awaitIdle(p)
        assertFalse(p.recording.value)
        assertTrue(sent.isEmpty())
        assertEquals(1, errors.size)
    }

    @Test fun missingPermissionSkipsWithoutRecording() {
        val sent = CopyOnWriteArrayList<String>(); val errors = CopyOnWriteArrayList<String>()
        val p = PushToTalk({ ch, _ -> sent += ch }, hasPermission = { false }, onError = { errors += it })
        p.start()
        assertFalse(p.recording.value)
        assertTrue(sent.isEmpty())
        assertEquals(1, errors.size)
    }

    @Test fun readFailureAfterListenStillSendsListenEnd() {
        val sent = CopyOnWriteArrayList<String>()
        val src = object : PcmSource { override fun start() {}; override fun read(buf: ByteArray): Int = throw RuntimeException("x"); override fun close() {} }
        val p = PushToTalk({ ch, _ -> sent += ch }, sourceFactory = { src })
        p.start(); awaitIdle(p)
        assertFalse(p.recording.value)
        assertEquals(listOf(GlassesChannels.LISTEN, GlassesChannels.LISTEN_END), sent.toList())
    }

    /** Blocks in read() until released, like AudioRecord waiting for the next buffer. */
    private class BlockedSource : PcmSource {
        val release = java.util.concurrent.Semaphore(0)
        @Volatile var closed = false
        override fun start() {}
        override fun read(buf: ByteArray): Int { release.acquire(); return if (closed) -1 else buf.size }
        override fun close() { closed = true }
    }

    private fun awaitCount(list: List<String>, channel: String, n: Int) {
        val end = System.currentTimeMillis() + 3_000
        while (list.count { it == channel } < n && System.currentTimeMillis() < end) Thread.sleep(5)
    }

    /** Review #8: a replacement confirmation stops the old capture and must still open its own. */
    @Test fun startAfterStopWhileTheOldCaptureIsBlockedOpensANewCapture() {
        val sent = CopyOnWriteArrayList<String>()
        val sources = CopyOnWriteArrayList<BlockedSource>()
        val p = PushToTalk({ ch, _ -> sent += ch }, sourceFactory = { BlockedSource().also { sources += it } })
        p.start(); awaitCount(sent, GlassesChannels.LISTEN, 1)
        p.stop(); p.start() // old capture still blocked in read()
        sources[0].release.release() // its read returns; the loop sees the stop
        awaitCount(sent, GlassesChannels.LISTEN, 2)
        assertEquals(2, sources.size, "second capture opened")
        assertEquals(listOf(GlassesChannels.LISTEN, GlassesChannels.LISTEN_END, GlassesChannels.LISTEN), sent.filter { it != GlassesChannels.AUDIO })
        assertTrue(p.recording.value)
        p.stop(); sources[1].release.release(); awaitIdle(p)
        assertFalse(p.recording.value)
    }

    @Test fun stopCancelsAQueuedRestart() {
        val sent = CopyOnWriteArrayList<String>()
        val sources = CopyOnWriteArrayList<BlockedSource>()
        val p = PushToTalk({ ch, _ -> sent += ch }, sourceFactory = { BlockedSource().also { sources += it } })
        p.start(); awaitCount(sent, GlassesChannels.LISTEN, 1)
        p.stop(); p.start(); p.stop() // the replacement was itself resolved before the old capture closed
        sources[0].release.release(); awaitIdle(p)
        assertFalse(p.recording.value)
        assertEquals(1, sources.size)
    }

    @Test fun startWhileRecordingIsStillANoOp() {
        val sent = CopyOnWriteArrayList<String>()
        val sources = CopyOnWriteArrayList<BlockedSource>()
        val p = PushToTalk({ ch, _ -> sent += ch }, sourceFactory = { BlockedSource().also { sources += it } })
        p.start(); awaitCount(sent, GlassesChannels.LISTEN, 1)
        p.start()
        p.stop(); sources[0].release.release(); awaitIdle(p)
        assertEquals(1, sources.size)
    }
}
