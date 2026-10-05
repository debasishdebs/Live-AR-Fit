package com.debasish.livefit.phone

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File

/** Spike: run glasses-captured 16 kHz mono PCM through the phone's on-device recognizer. */
object SpeechSpike {
    fun recognizeFile(context: Context, file: File, forceCloud: Boolean = false) {
        if (!file.exists()) return SpikeLog.i("asr: missing ${file.path}")
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context) && !forceCloud
        SpikeLog.i("asr: onDevice=$onDevice file=${file.length()} bytes")
        val recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        // Feed PCM through a pipe, paced like a live stream (this is how glasses audio will arrive).
        val (pfd, writeEnd) = ParcelFileDescriptor.createPipe()
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                val data = file.readBytes()
                val chunk = 3_200 // 100 ms @ 16 kHz mono 16-bit
                var i = 0
                while (i < data.size) {
                    val n = minOf(chunk, data.size - i)
                    runCatching { out.write(data, i, n) }.onFailure { return@Thread }
                    i += n
                    Thread.sleep(100)
                }
            }
        }.start()
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                SpikeLog.i("asr RESULT: ${results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)}")
                recognizer.destroy(); pfd.close()
            }
            override fun onError(error: Int) {
                SpikeLog.i("asr ERROR code=$error")
                recognizer.destroy(); pfd.close()
            }
            override fun onPartialResults(partial: Bundle) {
                SpikeLog.i("asr partial: ${partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)}")
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
        recognizer.startListening(intent)
    }
}
