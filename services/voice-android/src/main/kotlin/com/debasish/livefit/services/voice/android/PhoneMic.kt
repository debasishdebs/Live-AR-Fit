package com.debasish.livefit.services.voice.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.debasish.livefit.services.VoiceService
import com.debasish.livefit.services.voice.EnergyVad
import com.debasish.livefit.services.voice.VadDecision
import kotlin.concurrent.thread

/** Phone mic push-to-talk: same pipeline as the glasses (16 kHz mono, VAD-terminated). */
class PhoneMic(private val context: Context, private val voice: () -> VoiceService, private val toast: (String) -> Unit) {
    @SuppressLint("MissingPermission") // checked below
    fun record() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Microphone permission needed"); return
        }
        val v = voice()
        if (!v.startExternal()) return
        thread(name = "phone-mic") {
            var rec: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6_400))
                if (rec.state != AudioRecord.STATE_INITIALIZED) { toast("Microphone unavailable"); return@thread }
                val vad = EnergyVad()
                val chunk = ByteArray(3_200) // 100 ms
                rec.startRecording()
                while (true) {
                    val n = rec.read(chunk, 0, chunk.size)
                    if (n <= 0) break
                    val bytes = chunk.copyOf(n)
                    v.feed(bytes)
                    if (vad.feed(bytes) != VadDecision.Continue) break
                }
            } catch (e: Exception) {
                toast("Microphone unavailable")
            } finally {
                rec?.let { runCatching { it.stop() }; runCatching { it.release() } }
                v.endExternal()
            }
        }
    }
}
