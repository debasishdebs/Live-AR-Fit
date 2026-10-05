package com.debasish.livefit.services.voice.android

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.debasish.livefit.services.SpeechToText
import com.debasish.livefit.services.SttSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** On-device recognizer only — never the network recognizer (spec §5.4). */
class AndroidOnDeviceStt(context: Context) : SpeechToText {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var installed: Set<String> = emptySet()

    /** Refresh the installed-pack cache (call at start and after Languages downloads). */
    suspend fun refresh() { installed = SpeechPacks.query(app).installed }

    override fun isAvailable(locale: String) =
        SpeechRecognizer.isOnDeviceRecognitionAvailable(app) && locale in installed

    override fun start(locale: String): SttSession {
        val (read, write) = ParcelFileDescriptor.createPipe().let { it[0] to it[1] }
        val out = ParcelFileDescriptor.AutoCloseOutputStream(write)
        val writer = Executors.newSingleThreadExecutor()
        val final = CompletableDeferred<String?>()
        val lastPartial = AtomicReference<String?>(null)
        main.post {
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle) { final.complete(b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: lastPartial.get()); r.destroy() }
                override fun onPartialResults(b: Bundle) { b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { lastPartial.set(it) } }
                override fun onError(error: Int) { final.complete(lastPartial.get()); r.destroy() }
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
            override fun feed(pcm: ByteArray) { writer.execute { runCatching { out.write(pcm) } } }
            override fun end() { writer.execute { runCatching { out.close() }; writer.shutdown() } }
            override suspend fun awaitFinal(timeoutMs: Long) = withTimeoutOrNull(timeoutMs) { final.await() } ?: lastPartial.get()
        }
    }
}
