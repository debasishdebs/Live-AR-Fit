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
}
