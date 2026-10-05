package com.debasish.livefit.phone

import android.content.Context
import com.debasish.livefit.model.Command
import com.debasish.livefit.model.HudFrame
import com.debasish.livefit.model.LinkState
import com.debasish.livefit.services.GlassesEvent
import com.debasish.livefit.services.GlassesLinkService
import com.debasish.livefit.services.glasses.CxrGlassesLink
import com.debasish.livefit.services.MetricsSource
import com.debasish.livefit.services.MusicService
import com.debasish.livefit.services.VoiceService
import com.debasish.livefit.services.WatchLinkService
import com.debasish.livefit.services.WorkoutService
import com.debasish.livefit.services.glasses.FakeGlassesLink
import com.debasish.livefit.services.metrics.FakeMetricsSource
import com.debasish.livefit.services.music.FakeMusicService
import com.debasish.livefit.services.voice.FakeVoiceService
import com.debasish.livefit.services.watch.FakeWatchLink
import com.debasish.livefit.services.workout.DefaultWorkoutService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The phone's service wiring. Each service is chosen here and only here, so moving a service
 * from Fake to Live is a one-line change. Also routes [Command]s and publishes the glasses HUD.
 */
class ServiceGraph private constructor(
    val scope: CoroutineScope,
    val metrics: MetricsSource,
    val workout: WorkoutService,
    val glasses: GlassesLinkService,
    val watch: WatchLinkService,
    val music: MusicService,
    val voice: VoiceService,
    val settings: SettingsStore,
) {
    private val _lastHud = MutableStateFlow<HudFrame?>(null)
    /** Last frame sent to the glasses; the phone's HUD preview renders this. */
    val lastHud: StateFlow<HudFrame?> = _lastHud

    private val _toast = MutableStateFlow<String?>(null)
    /** Last command confirmation ("Next song"), shown on phone and glasses. */
    val toast: StateFlow<String?> = _toast

    fun dispatch(command: Command) {
        when (command) {
            is Command.StartWorkout -> workout.start(command.type)
            Command.PauseWorkout -> workout.pause()
            Command.ResumeWorkout -> workout.resume()
            Command.StopWorkout -> workout.stop()
            Command.DismissSummary -> workout.dismissSummary()
            Command.PlayPause -> music.playPause()
            Command.PlayMusic -> music.play()
            Command.PauseMusic -> music.pause()
            Command.NextTrack -> music.next()
            Command.PreviousTrack -> music.previous()
            Command.LikeTrack -> music.toggleLike()
            is Command.Volume -> music.setVolume(music.volume.value + if (command.up) 0.1f else -0.1f)
        }
        flash(describe(command))
    }

    private var appContext: Context? = null
    private fun phoneBattery(): Int? = appContext?.getSystemService(android.os.BatteryManager::class.java)
        ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun flash(text: String) {
        _toast.value = text
        scope.launch { delay(1_500); if (_toast.value == text) _toast.value = null }
    }

    private fun start() {
        scope.launch { voice.commands.collect { dispatch(it) } }
        scope.launch { watch.commands.collect { dispatch(it) } }
        scope.launch {
            glasses.events.collect { e ->
                when (e) {
                    GlassesEvent.Listen -> voice.listen()
                    is GlassesEvent.Issue -> dispatch(e.command)
                }
            }
        }
        // Glasses HUD: one frame per second while the app runs.
        scope.launch {
            while (true) {
                val frame = HudFrame(
                        workout = workout.snapshot.value,
                        watch = watch.status.value.link,
                        phone = LinkState.Connected,
                        music = music.nowPlaying.value,
                        voice = voice.state.value,
                        toast = toast.value,
                        settings = settings.hud.value,
                        phoneBattery = phoneBattery(),
                        watchBattery = watch.status.value.batteryPct,
                        sentAtMs = System.currentTimeMillis(),
                    )
                _lastHud.value = frame
                glasses.push(frame)
                // The watch mirrors the same hub state (workout + music) as the glasses.
                if (watch.status.value.link == LinkState.Connected) watch.push(frame)
                delay(1_000)
            }
        }
    }

    companion object {
        /** Bindings: everything Fake except the glasses / watch links when their live flags are set. */
        fun create(context: Context, useFakes: Boolean, liveGlasses: Boolean, liveWatch: Boolean): ServiceGraph {
            check(useFakes) { "Live services not wired yet" }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val metrics = FakeMetricsSource()
            return ServiceGraph(
                scope = scope,
                metrics = metrics,
                workout = DefaultWorkoutService(scope, metrics),
                glasses = if (liveGlasses) CxrGlassesLink(context) else FakeGlassesLink(),
                watch = if (liveWatch) com.debasish.livefit.services.watch.DataLayerWatchLink(context, scope) else FakeWatchLink(),
                music = FakeMusicService(scope),
                voice = FakeVoiceService(scope),
                settings = SettingsStore(context),
            ).also { it.appContext = context.applicationContext; it.start() }
        }

        fun describe(command: Command): String = when (command) {
            is Command.StartWorkout -> "${command.type.label} started"
            Command.PauseWorkout -> "Paused"
            Command.ResumeWorkout -> "Resumed"
            Command.StopWorkout -> "Workout stopped"
            Command.DismissSummary -> "Done"
            Command.PlayPause -> "Play / pause"
            Command.PlayMusic -> "Playing"
            Command.PauseMusic -> "Music paused"
            Command.NextTrack -> "Next song"
            Command.PreviousTrack -> "Previous song"
            Command.LikeTrack -> "Liked"
            is Command.Volume -> if (command.up) "Volume up" else "Volume down"
        }
    }
}

val Context.services: ServiceGraph get() = (applicationContext as LiveFitApp).services
