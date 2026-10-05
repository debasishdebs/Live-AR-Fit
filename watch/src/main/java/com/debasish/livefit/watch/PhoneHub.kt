package com.debasish.livefit.watch

import android.content.Context
import android.util.Log
import com.debasish.livefit.model.Command
import com.debasish.livefit.model.HudFrame
import com.debasish.livefit.model.NowPlaying
import com.debasish.livefit.model.Protocol
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.MusicService
import com.debasish.livefit.services.WorkoutService
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * The phone is the hub: it owns workout + music state. The watch renders the phone's frames
 * (Data Layer [Protocol.PATH_STATE]) and sends taps back as [Command]s ([Protocol.PATH_COMMAND]).
 */
object PhoneHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: Context

    private val _frame = MutableStateFlow<HudFrame?>(null)
    val frame: StateFlow<HudFrame?> = _frame
    private val _lastFrameAt = MutableStateFlow(0L)
    val lastFrameAt: StateFlow<Long> = _lastFrameAt

    private val _hrHistory = MutableStateFlow<List<Int>>(emptyList())
    val hrHistory: StateFlow<List<Int>> = _hrHistory
    private var lastSecond = -1L

    fun init(context: Context) { app = context.applicationContext }

    fun onState(json: String) {
        val f = runCatching { Protocol.decodeHud(json) }.getOrElse { Log.w(PhoneLink.TAG, "bad state", it); return }
        _frame.value = f
        _lastFrameAt.value = System.currentTimeMillis()
        val sec = f.workout.elapsedMs / 1000
        val hr = f.workout.metrics.heartRate
        if (hr != null && sec != lastSecond) { lastSecond = sec; _hrHistory.value = (_hrHistory.value + hr).takeLast(60) }
        if (f.workout.elapsedMs == 0L) _hrHistory.value = emptyList()
    }

    fun send(command: Command) {
        scope.launch {
            runCatching {
                val nodes = Wearable.getNodeClient(app).connectedNodes.await()
                nodes.forEach { Wearable.getMessageClient(app).sendMessage(it.id, Protocol.PATH_COMMAND, Protocol.encodeCommand(command).toByteArray()).await() }
            }.onFailure { Log.w(PhoneLink.TAG, "send $command failed", it) }
        }
    }

    /** WorkoutService view of the hub: state from frames, actions as commands. */
    val workout: WorkoutService = object : WorkoutService {
        override val snapshot: StateFlow<WorkoutSnapshot> =
            frame.map { it?.workout ?: WorkoutSnapshot() }.stateIn(scope, SharingStarted.Eagerly, WorkoutSnapshot())
        override fun start(type: WorkoutType) = send(Command.StartWorkout(type))
        override fun pause() = send(Command.PauseWorkout)
        override fun resume() = send(Command.ResumeWorkout)
        override fun stop() = send(Command.StopWorkout)
        override fun dismissSummary() = send(Command.DismissSummary)
    }

    val music: MusicService = object : MusicService {
        override val nowPlaying: StateFlow<NowPlaying?> = frame.map { it?.music }.stateIn(scope, SharingStarted.Eagerly, null)
        override val volume: StateFlow<Float> = MutableStateFlow(0.6f)
        override fun playPause() = send(Command.PlayPause)
        override fun play() = send(Command.PlayMusic)
        override fun pause() = send(Command.PauseMusic)
        override fun next() = send(Command.NextTrack)
        override fun previous() = send(Command.PreviousTrack)
        override fun toggleLike() = send(Command.LikeTrack)
        override fun setVolume(level: Float) = send(Command.Volume(up = level > volume.value))
    }
}
