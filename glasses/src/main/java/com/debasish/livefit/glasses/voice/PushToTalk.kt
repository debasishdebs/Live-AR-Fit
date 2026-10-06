package com.debasish.livefit.glasses.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.debasish.livefit.model.GlassesChannels
import com.debasish.livefit.model.ListenRequest
import com.debasish.livefit.model.Wire
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

/** Blocking PCM16 mono 16 kHz source; [read] returns bytes read, <= 0 on end/error. */
interface PcmSource {
    fun start()
    fun read(buf: ByteArray): Int
    fun close()
}

private class AudioRecordSource : PcmSource {
    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO first
    private val rec: AudioRecord = run {
        val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioRecord(MediaRecorder.AudioSource.MIC, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6_400))
    }.also { if (it.state != AudioRecord.STATE_INITIALIZED) { it.release(); error("AudioRecord not initialized") } }
    override fun start() = rec.startRecording()
    override fun read(buf: ByteArray) = rec.read(buf, 0, buf.size)
    override fun close() { runCatching { rec.stop() }; rec.release() }
}

/**
 * Tap-to-talk on the glasses: records with the glasses' own mic (the CXR-L stream is silent —
 * spec §2.1), stops on VAD or [maxMs], streams 100 ms chunks to the phone.
 * [onError] is called (from the recording thread) when the mic can't be used; nothing is sent then.
 */
class PushToTalk(
    private val sendRaw: (String, String) -> Unit,
    private val hasPermission: () -> Boolean = { true },
    private val onError: (String) -> Unit = {},
    private val sourceFactory: () -> PcmSource = { AudioRecordSource() },
) {
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording
    private val lock = Any()
    private var running = false // a capture thread is alive (it may already be closing)
    private var stopRequested = false
    private var restartMaxMs: Int? = null // start() while the old capture closes: reopen when it exits (review #8)

    fun toggle(maxMs: Int = 6_000) = if (_recording.value) stop() else start(maxMs)

    fun stop() = synchronized(lock) { stopRequested = true; restartMaxMs = null }

    fun start(maxMs: Int = 6_000) {
        if (!hasPermission()) { onError("RECORD_AUDIO not granted"); return }
        synchronized(lock) {
            if (running) { if (stopRequested) restartMaxMs = maxMs; return }
            running = true
            stopRequested = false
            _recording.value = true
        }
        thread(name = "ptt") {
            var next: Int? = maxMs
            try {
                while (next != null) {
                    capture(next)
                    next = takeRestartOrFinish()
                }
            } finally {
                if (next != null) synchronized(lock) { running = false; restartMaxMs = null; _recording.value = false } // thread died
            }
        }
    }

    /** Atomically: hand the thread a queued restart, or mark capture finished so the next start() opens a new one. */
    private fun takeRestartOrFinish(): Int? = synchronized(lock) {
        restartMaxMs.also { r ->
            restartMaxMs = null
            if (r != null) stopRequested = false else { running = false; _recording.value = false }
        }
    }

    private fun capture(maxMs: Int) {
        var source: PcmSource? = null
        var listenSent = false
        try {
            source = sourceFactory()
            source.start()
            sendRaw(GlassesChannels.LISTEN, Wire.encode(ListenRequest())) // versioned: the hub ignores voice from a mismatched app
            listenSent = true
            val vad = EnergyVad(maxMs = maxMs)
            val chunk = ByteArray(3_200)
            while (!synchronized(lock) { stopRequested }) {
                val n = source.read(chunk)
                if (n <= 0) break
                val bytes = chunk.copyOf(n)
                sendRaw(GlassesChannels.AUDIO, AudioChunks.encode(bytes))
                if (vad.feed(bytes) != VadDecision.Continue) break
            }
        } catch (e: Exception) {
            onError("Mic unavailable: ${e.message}")
        } finally {
            runCatching { source?.close() }
            if (listenSent) runCatching { sendRaw(GlassesChannels.LISTEN_END, "{}") }
        }
    }
}
