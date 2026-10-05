package com.debasish.livefit.phone.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Wraps the platform on-device recognizer's language-pack query (API 33) and download (API 34). */
object SpeechPacks {
    data class Packs(val supported: List<String>, val installed: Set<String>, val pending: Set<String>)

    sealed interface Result {
        data object Success : Result
        data object Scheduled : Result
        data class Error(val message: String) : Result
    }

    private fun intentFor(tag: String?) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .apply { if (tag != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag) }

    suspend fun query(context: Context): Packs = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return@withContext Packs(emptyList(), emptySet(), emptySet())
        }
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        try {
            suspendCancellableCoroutine { cont ->
                recognizer.checkRecognitionSupport(intentFor(null), ContextCompat.getMainExecutor(context), object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        val installed = support.installedOnDeviceLanguages.toSet()
                        val pending = support.pendingOnDeviceLanguages.toSet()
                        val all = (support.supportedOnDeviceLanguages + installed + pending).distinct()
                        if (cont.isActive) cont.resume(Packs(all, installed, pending))
                    }

                    override fun onError(error: Int) {
                        if (cont.isActive) cont.resume(Packs(emptyList(), emptySet(), emptySet()))
                    }
                })
            }
        } finally {
            recognizer.destroy()
        }
    }

    suspend fun download(context: Context, tag: String, onProgress: (Float?) -> Unit): Result = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < 34) return@withContext Result.Error("Needs Android 14 or newer")
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        try {
            onProgress(null)
            suspendCancellableCoroutine<Result> { cont ->
                recognizer.triggerModelDownload(intentFor(tag), ContextCompat.getMainExecutor(context), object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) = onProgress(completedPercent / 100f)
                    override fun onSuccess() { if (cont.isActive) cont.resume(Result.Success) }
                    override fun onScheduled() { if (cont.isActive) cont.resume(Result.Scheduled) }
                    override fun onError(error: Int) { if (cont.isActive) cont.resume(Result.Error("Download failed (code $error)")) }
                })
            }
        } finally {
            recognizer.destroy()
        }
    }
}
