package com.debasish.livefit.services.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class EnergyVadTest {
    /** 100 ms chunk at 16 kHz of a sine with [amp], or silence when amp = 0. */
    private fun chunk(amp: Int): ByteArray {
        val b = ByteBuffer.allocate(1_600 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 1_600) b.putShort((amp * sin(i / 5.0)).toInt().toShort())
        return b.array()
    }

    @Test fun endsAfter800msOfSilenceFollowingSpeech() {
        val vad = EnergyVad()
        repeat(5) { assertEquals(VadDecision.Continue, vad.feed(chunk(4_000))) }
        repeat(7) { assertEquals(VadDecision.Continue, vad.feed(chunk(0))) }
        assertEquals(VadDecision.EndOfSpeech, vad.feed(chunk(0)))
    }

    @Test fun leadingSilenceDoesNotEndBeforeSpeech() {
        val vad = EnergyVad()
        repeat(20) { assertEquals(VadDecision.Continue, vad.feed(chunk(0))) }
    }

    @Test fun capsAtSixSeconds() {
        val vad = EnergyVad()
        repeat(59) { assertEquals(VadDecision.Continue, vad.feed(chunk(4_000))) }
        assertEquals(VadDecision.MaxReached, vad.feed(chunk(4_000)))
    }
}
