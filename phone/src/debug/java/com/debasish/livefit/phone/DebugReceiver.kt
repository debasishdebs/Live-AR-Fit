package com.debasish.livefit.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.debasish.livefit.model.MapFrame
import com.debasish.livefit.model.MapFrameKind
import com.debasish.livefit.services.glasses.CxrGlassesLink
import com.rokid.cxr.session.AiInterceptMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Spike-only: drive experiments from adb while the phone is locked.
 * adb shell am broadcast -n com.livear.fit/com.debasish.livefit.phone.DebugReceiver --es cmd connect_allow
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val cmd = intent.getStringExtra("cmd") ?: return
        SpikeLog.i("debug cmd=$cmd")
        when (cmd) {
            "connect_allow" -> RokidLink.connect(app, AiInterceptMode.ALLOW_WITH_PAUSE)
            "connect_block" -> RokidLink.connect(app, AiInterceptMode.BLOCK_AI)
            "mic_start" -> RokidLink.startMic(app)
            "mic_stop" -> RokidLink.stopMic()
            "close" -> RokidLink.close()
            "asr_file" -> SpeechSpike.recognizeFile(app, java.io.File(app.getExternalFilesDir(null), intent.getStringExtra("file") ?: "glasses-mic.raw"), intent.getBooleanExtra("cloud", false))
            "watch_start" -> CoroutineScope(Dispatchers.Main).launch { WatchControl.launchActivity(app, "start", force = intent.getBooleanExtra("force", false)) }
            "watch_real_start" -> CoroutineScope(Dispatchers.Main).launch { WatchControl.message(app, WatchControl.PATH_START, force = intent.getBooleanExtra("force", false)) }
            // Device check D1 (plan Task 22): one PNG-sized payload on lf_map over CXR — raw bytes, or Base64 in the JSON (b64).
            // adb shell am broadcast -n com.livear.fit/com.debasish.livefit.phone.DebugReceiver --es cmd map_probe --ei kb 38 [--ez b64 true]
            "map_probe" -> CoroutineScope(Dispatchers.Main).launch {
                val glasses = CxrGlassesLink.instance ?: return@launch
                val size = intent.getIntExtra("kb", 38) * 1024
                val b64 = intent.getBooleanExtra("b64", false)
                val payload = ByteArray(size).also { java.util.Random(42).nextBytes(it); byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10).copyInto(it) }
                val crc = java.util.zip.CRC32().apply { update(payload) }.value.toString(16)
                val epoch = MapFrame(kind = MapFrameKind.Epoch, renderEpoch = 4242)
                val image = MapFrame(kind = MapFrameKind.Image, renderEpoch = 4242, sessionId = "probe", renderSeq = System.currentTimeMillis() / 1_000)
                val sentEpoch = glasses.pushMap(epoch, null)
                val sentImage = if (b64) glasses.pushMap(image.copy(pngBase64 = java.util.Base64.getEncoder().encodeToString(payload)), null)
                else glasses.pushMap(image, payload)
                android.util.Log.i("LiveFitMap", "map_probe kb=${size / 1024} b64=$b64 epochSent=$sentEpoch imageSent=$sentImage bytes=$size crc=$crc")
            }
            "watch_stop" -> CoroutineScope(Dispatchers.Main).launch { WatchControl.message(app, WatchControl.PATH_STOP, force = false) }
        }
    }
}
