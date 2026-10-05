package com.debasish.livefit.glasses.hud

import android.util.Log
import com.debasish.livefit.model.Command
import com.debasish.livefit.model.HudFrame
import com.debasish.livefit.model.LinkState
import com.debasish.livefit.model.Protocol
import com.debasish.livefit.model.VoiceState
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.metrics.FakeMetricsSource
import com.debasish.livefit.services.music.FakeMusicService
import com.debasish.livefit.services.voice.CommandParser
import com.debasish.livefit.services.workout.DefaultWorkoutService
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Feeds the HUD. Live frames from the phone (CXR channel lf_hud) win; until one arrives,
 * a local demo (same Fake services as the phone) drives the HUD so the design can be reviewed alone.
 */
class HudController(private val scope: CoroutineScope, private val bridge: CXRServiceBridge) {
    private val _frame = MutableStateFlow<HudFrame?>(null)
    val frame: StateFlow<HudFrame?> = _frame

    private val _hrHistory = MutableStateFlow<List<Int>>(emptyList())
    /** One heart-rate sample per workout second, for the HUD trend line. */
    val hrHistory: StateFlow<List<Int>> = _hrHistory
    private var lastSampleSecond = -1L

    private var live = false
    private var demoJob: Job? = null

    // Demo services: identical modules to the phone, so the HUD sees realistic frames.
    private val demoWorkout = DefaultWorkoutService(scope, FakeMetricsSource())
    private val demoMusic = FakeMusicService(scope)
    private var demoVoice = VoiceState.Idle
    private var demoToast: String? = null
    private val demoScript = listOf("next song", "like this song", "pause workout", "resume workout")
    private var demoTurn = 0

    fun start() {
        val rc = bridge.subscribe(Protocol.CH_HUD, CXRServiceBridge.MsgCallback { _, caps, _ -> onLiveFrame(caps) })
        Log.i(TAG, "subscribe(${Protocol.CH_HUD}) rc=$rc")
        demoWorkout.start(WorkoutType.Run)
        demoJob = scope.launch {
            while (!live) {
                publish(HudFrame(
                    workout = demoWorkout.snapshot.value,
                    watch = LinkState.Connected,
                    phone = LinkState.Disconnected,
                    music = demoMusic.nowPlaying.value,
                    voice = demoVoice,
                    toast = demoToast,
                    watchBattery = 64,
                ))
                delay(250)
            }
        }
    }

    private fun onLiveFrame(caps: Caps?) {
        val text = runCatching { caps?.at(0)?.string }.getOrNull() ?: return
        val frame = runCatching { Protocol.decodeHud(text) }.getOrElse { Log.w(TAG, "bad frame", it); return }
        if (!live) { live = true; demoJob?.cancel(); Log.i(TAG, "live frames from phone") }
        publish(frame)
    }

    private fun publish(frame: HudFrame) {
        _frame.value = frame
        val second = frame.workout.elapsedMs / 1000
        val hr = frame.workout.metrics.heartRate
        if (hr != null && second != lastSampleSecond) {
            lastSampleSecond = second
            _hrHistory.value = (_hrHistory.value + hr).takeLast(HR_HISTORY)
        }
    }

    /** Touchpad tap = push-to-talk. Live: ask the phone; demo: simulate a recognised command. */
    fun listen() {
        if (live) {
            bridge.sendMessage(Protocol.CH_LISTEN, Caps().apply { write("{}") })
            return
        }
        if (demoVoice != VoiceState.Idle) return
        scope.launch {
            demoVoice = VoiceState.Listening; delay(1_800)
            demoVoice = VoiceState.Processing; delay(500)
            demoVoice = VoiceState.Idle
            val cmd = CommandParser.parse(demoScript[demoTurn++ % demoScript.size])
            when (cmd) {
                Command.NextTrack -> { demoMusic.next(); flash("Next song") }
                Command.LikeTrack -> { demoMusic.toggleLike(); flash("Liked") }
                Command.PauseWorkout -> { demoWorkout.pause(); flash("Paused") }
                Command.ResumeWorkout -> { demoWorkout.resume(); flash("Resumed") }
                else -> Unit
            }
        }
    }

    private suspend fun flash(text: String) {
        demoToast = text; delay(1_500); demoToast = null
    }

    companion object { const val TAG = "LiveFitGlasses" }
}
