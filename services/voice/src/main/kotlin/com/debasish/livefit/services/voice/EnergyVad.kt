package com.debasish.livefit.services.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

enum class VadDecision { Continue, EndOfSpeech, MaxReached }

/** Energy VAD: end after [silenceMs] of quiet following speech, or at [maxMs] (spec §5.4). */
class EnergyVad(
    private val sampleRate: Int = 16_000,
    private val silenceMs: Int = 800,
    private val maxMs: Int = 6_000,
    private val minSpeechRms: Double = 300.0,
) {
    private var totalMs = 0.0
    private var silentMs = 0.0
    private var heardSpeech = false

    fun reset() { totalMs = 0.0; silentMs = 0.0; heardSpeech = false }

    fun feed(pcm16le: ByteArray): VadDecision {
        val samples = pcm16le.size / 2
        if (samples == 0) return VadDecision.Continue
        val buf = ByteBuffer.wrap(pcm16le).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        repeat(samples) { val s = buf.short.toDouble(); sum += s * s }
        val rms = sqrt(sum / samples)
        val ms = samples * 1000.0 / sampleRate
        totalMs += ms
        if (rms >= minSpeechRms) { heardSpeech = true; silentMs = 0.0 } else if (heardSpeech) silentMs += ms
        return when {
            totalMs >= maxMs -> VadDecision.MaxReached
            heardSpeech && silentMs >= silenceMs -> VadDecision.EndOfSpeech
            else -> VadDecision.Continue
        }
    }
}
