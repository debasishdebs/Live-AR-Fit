package com.debasish.livefit.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rokid.cxr.session.AiInterceptMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Spike-only: drive experiments from adb while the phone is locked.
 * adb shell am broadcast -n com.debasish.livefit/.phone.DebugReceiver --es cmd connect_allow
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
            "watch_stop" -> CoroutineScope(Dispatchers.Main).launch { WatchControl.message(app, WatchControl.PATH_STOP, force = false) }
        }
    }
}
