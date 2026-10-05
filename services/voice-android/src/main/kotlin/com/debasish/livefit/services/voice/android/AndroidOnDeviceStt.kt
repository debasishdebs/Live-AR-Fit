package com.debasish.livefit.services.voice.android

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.debasish.livefit.services.SpeechToText
import com.debasish.livefit.services.SttSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** On-device recognizer only — never the network recognizer (spec §5.4). Needs API 33; unavailable below. */
class AndroidOnDeviceStt(context: Context) : SpeechToText {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var installed: Set<String> = emptySet()
    private val refreshing = AtomicBoolean(false)

    /** Refresh the installed-pack cache (at start, lazily on a miss, and after Languages downloads). */
    suspend fun refresh() {
        try { installed = SpeechPacks.query(app).installed }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.w("LiveFitVoice", "pack refresh failed", e) }
    }

    /** On a cache miss, refresh in the background so the next press sees a freshly installed pack. */
    override fun isAvailable(locale: String): Boolean {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(app)) return false
        if (locale in installed) return true
        if (refreshing.compareAndSet(false, true)) scope.launch { try { refresh() } finally { refreshing.set(false) } }
        return false
    }

    override fun start(locale: String): SttSession {
        check(Build.VERSION.SDK_INT >= 33) { "On-device speech needs Android 13" }
        val pipe = ParcelFileDescriptor.createPipe()
        val read = pipe[0]
        val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        val writer = Executors.newSingleThreadExecutor()
        val final = CompletableDeferred<String?>()
        val lastPartial = AtomicReference<String?>(null)
        val recognizer = AtomicReference<SpeechRecognizer?>(null)
        val cleaned = AtomicBoolean(false)
        fun cleanup() {
            if (!cleaned.compareAndSet(false, true)) return
            main.post { recognizer.getAndSet(null)?.let { runCatching { it.cancel() }; runCatching { it.destroy() } } }
            runCatching { read.close() }
            writer.execute { runCatching { out.close() }; writer.shutdown() }
        }
        main.post {
            if (cleaned.get()) return@post
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
            recognizer.set(r)
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle) { final.complete(b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: lastPartial.get()); cleanup() }
                override fun onPartialResults(b: Bundle) { b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { lastPartial.set(it) } }
                override fun onError(error: Int) { final.complete(lastPartial.get()); cleanup() }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000))
        }
        return object : SttSession {
            override fun feed(pcm: ByteArray) { runCatching { writer.execute { runCatching { out.write(pcm) } } } }
            override fun end() { runCatching { writer.execute { runCatching { out.close() } } } } // EOF tells the recognizer to finish
            override suspend fun awaitFinal(timeoutMs: Long): String? {
                val text = withTimeoutOrNull(timeoutMs) { final.await() } ?: lastPartial.get()
                cleanup() // timeout / error paths: release recognizer and descriptors
                return text
            }
        }
    }
}
