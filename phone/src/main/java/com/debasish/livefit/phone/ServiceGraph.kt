package com.debasish.livefit.phone

import android.content.Context
import android.os.BatteryManager
import com.debasish.livefit.confirm.DefaultConfirmationService
import com.debasish.livefit.history.HistoryDatabase
import com.debasish.livefit.history.RoomSessionStore
import com.debasish.livefit.model.Command
import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.DeviceState
import com.debasish.livefit.model.Devices
import com.debasish.livefit.model.HudSettingsFrame
import com.debasish.livefit.model.LinkState
import com.debasish.livefit.model.StateFrame
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.Clock
import com.debasish.livefit.services.GlassesEvent
import com.debasish.livefit.services.GlassesLinkService
import com.debasish.livefit.services.HistoryStore
import com.debasish.livefit.services.MusicService
import com.debasish.livefit.services.VoiceService
import com.debasish.livefit.services.WatchExerciseGateway
import com.debasish.livefit.services.WatchLinkService
import com.debasish.livefit.services.glasses.CxrGlassesLink
import com.debasish.livefit.services.glasses.FakeGlassesLink
import com.debasish.livefit.services.music.FakeMusicService
import com.debasish.livefit.services.music.MusicAction
import com.debasish.livefit.services.music.WorkoutMusicPolicy
import com.debasish.livefit.services.music.YtmMediaSessionService
import com.debasish.livefit.services.voice.FakeVoiceService
import com.debasish.livefit.services.watch.DataLayerWatchLink
import com.debasish.livefit.services.watch.FakeWatchLink
import com.debasish.livefit.services.workout.HubWorkoutService
import com.debasish.livefit.services.workout.SimulatedWatchGateway
import com.debasish.livefit.sync.HubCommandRouter
import com.debasish.livefit.sync.StateBroadcaster
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.UUID

/** Which services are Live. One place to flip bindings (spec §3.1 principle 2). */
data class Bindings(val liveWatch: Boolean, val liveGlasses: Boolean, val liveMusic: Boolean, val liveVoice: Boolean)

/** The phone hub's wiring; owned by LiveFitApp, kept alive by LiveFitHubService. */
class ServiceGraph(private val app: Context, bindings: Bindings) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val clock = Clock { System.currentTimeMillis() }
    val settings = SettingsStore(app)
    val history: HistoryStore = RoomSessionStore(HistoryDatabase.shared(app))
    val confirm = DefaultConfirmationService(clock)

    // ---- Service bindings: one line each, so later tasks flip them independently ----
    private val dataLayer: DataLayerWatchLink? = if (bindings.liveWatch) DataLayerWatchLink(app, scope) else null
    val watchGateway: WatchExerciseGateway = dataLayer ?: SimulatedWatchGateway(scope, clock)
    val watch: WatchLinkService = dataLayer ?: FakeWatchLink()
    val glasses: GlassesLinkService = if (bindings.liveGlasses) CxrGlassesLink(app, scope) else FakeGlassesLink() // Task 14
    private val ytm: YtmMediaSessionService? = if (bindings.liveMusic) YtmMediaSessionService(app, scope) else null // Task 15
    val music: MusicService = ytm ?: FakeMusicService(scope)
    val musicConnected: StateFlow<Boolean> = ytm?.connected ?: MutableStateFlow(true)
    // ---- end bindings ----

    val workout = HubWorkoutService(scope, watchGateway, history, confirm, clock,
        gpsFor = { type -> type != WorkoutType.Walk && settings.gpsOutdoors.value })

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast
    val router = HubCommandRouter(workout, music, confirm, scope, toast = ::flash)

    // ---- Voice binding (after the router, which it feeds) ----
    val voice: VoiceService = FakeVoiceService(scope) { router.dispatchVoice(it) } // Task 16
    // ---- end voice binding ----

    private val _lastFrame = MutableStateFlow<StateFrame?>(null)
    val lastFrame: StateFlow<StateFrame?> = _lastFrame
    private val broadcaster = StateBroadcaster(scope, send = ::sendFrames)

    fun markDirty() = broadcaster.markDirty()

    /** Phone UI commands go through the same router (dedup id is fresh). */
    fun localCommand(command: Command) =
        router.dispatch(CommandEnvelope(id = UUID.randomUUID().toString(), origin = DeviceKind.Phone, command = command))

    fun start() {
        broadcaster.start()
        // Any state change -> push (coalesced).
        scope.launch {
            merge(workout.snapshot, music.nowPlaying, music.volume, voice.state, confirm.pending, toast, watch.status, glasses.status, router.outdated)
                .collect { markDirty() }
        }
        scope.launch { workout.notices.collect(::flash) }
        // ---- Link wiring: one block per device ----
        scope.launch { watch.commands.collect(router::dispatch) }
        dataLayer?.let { link -> scope.launch { link.outdated.collect { router.markOutdated(DeviceKind.Watch) } } }
        scope.launch {
            glasses.events.collect { e ->
                when (e) {
                    GlassesEvent.Listen -> voice.startExternal()
                    is GlassesEvent.Audio -> voice.feed(e.pcm)
                    GlassesEvent.ListenEnd -> voice.endExternal()
                    is GlassesEvent.Issue -> router.dispatch(e.envelope)
                    is GlassesEvent.Outdated -> router.markOutdated(DeviceKind.Glasses)
                }
            }
        }
        // HUD settings: on change and whenever the glasses (re)connect.
        scope.launch {
            combine(settings.hud, glasses.status) { hud, st -> hud to st.link }
                .distinctUntilChanged()
                .collect { (hud, link) -> if (link == LinkState.Connected) glasses.pushSettings(HudSettingsFrame(settings = hud)) }
        }
        // Workout music policy (settings -> Music).
        scope.launch {
            var previous = workout.snapshot.value.phase
            workout.snapshot.collect { s ->
                when (WorkoutMusicPolicy.actionFor(previous, s.phase, settings.musicOnStart.value, settings.pauseMusicOnStop.value)) {
                    MusicAction.Resume -> music.play()
                    MusicAction.PlaySearch -> ytm?.playSearch(settings.musicSearch.value) ?: music.play()
                    MusicAction.Pause -> music.pause()
                    MusicAction.None -> Unit
                }
                previous = s.phase
            }
        }
        // ---- end link wiring ----
        // Connecting is owned by the authorization flow (AuthActivity / GlassesSessionPolicy): CXR-L must be authorized in-process first.
        // Presence re-arm only; the glasses connect when CompanionPresenceService reports them or the app opens.
        CompanionLinker.observePresence(app)
    }

    private fun buildFrame() = StateFrame(
        workout = workout.snapshot.value,
        music = music.nowPlaying.value?.copy(volume = music.volume.value),
        devices = Devices(
            phone = DeviceState(LinkState.Connected, phoneBattery()),
            watch = watch.status.value.let { DeviceState(it.link, it.batteryPct) },
            glasses = glasses.status.value.let { DeviceState(it.link, it.batteryPct) },
        ),
        voice = voice.state.value,
        confirmation = confirm.pending.value,
        toast = toast.value,
        outdated = router.outdated.value,
        sentAtMs = clock.nowMs(),
    )

    private suspend fun sendFrames() {
        val frame = buildFrame()
        _lastFrame.value = frame
        pushTo("glasses") { glasses.push(frame) }
        pushTo("watch") { watch.push(frame) }
    }

    /** One failing link must not block the other; cancellation still propagates. */
    private suspend fun pushTo(name: String, push: suspend () -> Unit) {
        try { push() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w("LiveFitHub", "$name push failed", e) }
    }

    private fun phoneBattery(): Int? =
        app.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun flash(text: String) {
        _toast.value = text
        scope.launch { delay(1_500); if (_toast.value == text) _toast.value = null }
    }
}

val Context.services: ServiceGraph get() = (applicationContext as LiveFitApp).services
