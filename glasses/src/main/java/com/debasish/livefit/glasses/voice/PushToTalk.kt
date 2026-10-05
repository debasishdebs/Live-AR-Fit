package com.debasish.livefit.glasses.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.debasish.livefit.model.GlassesChannels
import com.debasish.livefit.services.voice.EnergyVad
import com.debasish.livefit.services.voice.VadDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread

object AudioChunks {
    // Base64 text in a Caps string on lf_audio: the spike-verified path (spec's bytes argument is a recorded deviation).
    fun encode(pcm: ByteArray): String = java.util.Base64.getEncoder().encodeToString(pcm)
    fun split(pcm: ByteArray, chunkBytes: Int = 3_200): List<ByteArray> =
        (pcm.indices step chunkBytes).map { pcm.copyOfRange(it, minOf(it + chunkBytes, pcm.size)) }
}

/**
 * Tap-to-talk on the glasses: records with the glasses' own mic (the CXR-L stream is silent —
 * spec §2.1), stops on VAD or [maxMs], streams 100 ms chunks to the phone.
 */
class PushToTalk(private val sendRaw: (String, String) -> Unit) {
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording
    @Volatile private var stopRequested = false

    fun toggle(maxMs: Int = 6_000) = if (_recording.value) stop() else start(maxMs)

    fun stop() { stopRequested = true }

    @SuppressLint("MissingPermission") // RECORD_AUDIO granted at install via adb / first-run prompt
    fun start(maxMs: Int = 6_000) {
        if (_recording.value) return
        _recording.value = true
        stopRequested = false
        sendRaw(GlassesChannels.LISTEN, "{}")
        thread(name = "ptt") {
            val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6_400))
            val vad = EnergyVad(maxMs = maxMs)
            val chunk = ByteArray(3_200)
            try {
                rec.startRecording()
                while (!stopRequested) {
                    val n = rec.read(chunk, 0, chunk.size)
                    if (n <= 0) break
                    val bytes = chunk.copyOf(n)
                    sendRaw(GlassesChannels.AUDIO, AudioChunks.encode(bytes))
                    if (vad.feed(bytes) != VadDecision.Continue) break
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
                sendRaw(GlassesChannels.LISTEN_END, "{}")
                _recording.value = false
            }
        }
    }
}
