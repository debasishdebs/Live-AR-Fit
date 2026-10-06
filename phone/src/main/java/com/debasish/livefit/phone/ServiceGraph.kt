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
import com.debasish.livefit.model.QueueFrame
import com.debasish.livefit.model.StateFrame
import com.debasish.livefit.model.WorkoutPhase
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
import com.debasish.livefit.services.voice.GlassesVoiceBridge
import com.debasish.livefit.services.voice.LiveVoiceService
import com.debasish.livefit.services.voice.android.AndroidOnDeviceStt
import com.debasish.livefit.services.voice.android.PhoneMic
import com.debasish.livefit.services.watch.DataLayerWatchLink
import com.debasish.livefit.services.watch.FakeWatchLink
import com.debasish.livefit.services.workout.HubWorkoutService
import com.debasish.livefit.services.workout.SimulatedWatchGateway
import com.debasish.livefit.sync.HubCommandRouter
import com.debasish.livefit.sync.LinkSender
import com.debasish.livefit.sync.StateBroadcaster
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    private val ytm: YtmMediaSessionService? = if (bindings.liveMusic) YtmMediaSessionService(app, scope, savedQuery = { settings.musicSearch.value }, queueSize = { settings.glassesQueueSize.value }) else null // Task 15
    val music: MusicService = ytm ?: FakeMusicService(scope)
    val musicConnected: StateFlow<Boolean> = ytm?.connected ?: MutableStateFlow(true)
    // ---- end bindings ----

    val workout = HubWorkoutService(scope, watchGateway, history, confirm, clock,
        gpsFor = { type -> type != WorkoutType.Walk && settings.gpsOutdoors.value })

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast
    private val watchLaunch = WatchLaunchPolicy()
    val router = HubCommandRouter(workout, music, confirm, scope, toast = ::flash, onStartRequested = watchLaunch::onStartRequested)

    // ---- Voice binding (after the router, which it feeds) ----
    private val stt: AndroidOnDeviceStt? = if (bindings.liveVoice) AndroidOnDeviceStt(app) else null
    private lateinit var phoneMic: PhoneMic
    val voice: VoiceService = if (stt != null) LiveVoiceService(
        scope, stt,
        locale = { settings.voiceLocale.value },
        pendingConfirmationId = { confirm.pending.value?.id },
        onCommand = { router.dispatchVoice(it) },
        onAnswer = { id, yes -> confirm.answer(id, yes) },
        toast = ::flash,
        phoneMic = { phoneMic.record() },
        log = { Log.d("LiveFitVoice", it) },
    ).also { v -> phoneMic = PhoneMic(app, { v }, ::flash) } else FakeVoiceService(scope) { router.dispatchVoice(it) }
    // ---- end voice binding ----

    private val _lastFrame = MutableStateFlow<StateFrame?>(null)
    val lastFrame: StateFlow<StateFrame?> = _lastFrame
    private val broadcaster = StateBroadcaster(scope, send = ::sendFrames)

    fun markDirty() = broadcaster.markDirty()

    /** Re-reads installed on-device speech packs (e.g. right after a download) so voice enables immediately. */
    suspend fun refreshVoicePacks() { stt?.refresh() }
    fun refreshVoicePacksAsync() { scope.launch { refreshVoicePacks() } }

    /** Phone UI commands go through the same router (dedup id is fresh). */
    fun localCommand(command: Command) =
        router.dispatch(CommandEnvelope(id = UUID.randomUUID().toString(), origin = DeviceKind.Phone, command = command))

    fun start() {
        broadcaster.start()
        stt?.let { s -> scope.launch { s.refresh() } }
        // Any state change -> push (coalesced). Music position ticks (1 s poll) are masked so they don't cause pushes.
        scope.launch {
            merge(workout.snapshot, music.nowPlaying.map { it?.copy(positionMs = 0) }.distinctUntilChanged(), music.volume, voice.state, confirm.pending, toast, watch.status, glasses.status, router.outdated)
                .collect { markDirty() }
        }
        scope.launch { workout.notices.collect(::flash) }
        // Low-battery warning (spec §7): one toast per device per workout at <= 15 %.
        scope.launch {
            val warned = mutableSetOf<DeviceKind>()
            lastFrame.collect { f ->
                if (f == null) return@collect
                if (f.workout.phase == com.debasish.livefit.model.WorkoutPhase.Idle) { warned.clear(); return@collect }
                listOf(DeviceKind.Phone to f.devices.phone, DeviceKind.Watch to f.devices.watch, DeviceKind.Glasses to f.devices.glasses).forEach { (k, d) ->
                    val pct = d.batteryPct ?: return@forEach
                    if (pct <= 15 && warned.add(k)) flash("${k.name} battery low · $pct%")
                }
            }
        }
        // ---- Link wiring: one block per device ----
        scope.launch { watch.commands.collect(router::dispatch) }
        dataLayer?.let { link -> scope.launch { link.outdated.collect { router.markOutdated(DeviceKind.Watch) } } }
        scope.launch {
            val glassesVoice = GlassesVoiceBridge(voice) // forwards audio/end only to a capture the glasses own
            glasses.events.collect { e ->
                when (e) {
                    GlassesEvent.Listen -> glassesVoice.onListen()
                    is GlassesEvent.Audio -> glassesVoice.onAudio(e.pcm)
                    GlassesEvent.ListenEnd -> glassesVoice.onListenEnd()
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
        // Glasses music screen: the queue window only when it changes, and again whenever the glasses (re)connect.
        scope.launch {
            combine(music.queue, glasses.status) { q, st -> q to st.link }
                .distinctUntilChanged()
                .collect { (q, link) -> if (link == LinkState.Connected) glasses.pushQueue(QueueFrame(window = q)) }
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
        // A workout starting brings the glasses back even after the user left LiveFit on them (no auto-retry otherwise).
        scope.launch {
            workout.snapshot.map { it.phase == WorkoutPhase.Starting || it.phase == WorkoutPhase.Active || it.phase == WorkoutPhase.Paused }
                .distinctUntilChanged()
                .collect { running -> if (running) glasses.connect() }
        }
        // F2/F6: bring LiveFit's screen up on the watch for a started workout and for every confirmation
        // (Samsung's media controls take the screen when music starts). Only with the live watch link.
        if (dataLayer != null) {
            val launcher = WatchLauncher(app)
            scope.launch { workout.snapshot.map { it.phase }.distinctUntilChanged().collect { if (watchLaunch.onPhase(it)) scope.launch {
                launcher.open("workout started")
                // On device, Samsung's media controls open over our screen ~1-3 s later when workout music starts: reopen once after.
                delay(WATCH_REOPEN_MS)
                if (workout.snapshot.value.phase == WorkoutPhase.Active) launcher.open("after music start")
            } } }
            scope.launch { confirm.pending.collect { c -> if (watchLaunch.onConfirmation(c)) scope.launch { launcher.open("confirmation ${c?.kind}") } } }
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

    // One sender per link (F6): a slow or failing link must neither block nor delay the other; each gets the latest frame.
    private val glassesSender = LinkSender<StateFrame>(scope, "glasses", onError = ::pushFailed) { glasses.push(it) }
    private val watchSender = LinkSender<StateFrame>(scope, "watch", onError = ::pushFailed) { watch.push(it) }

    private fun sendFrames() {
        val frame = buildFrame()
        _lastFrame.value = frame
        glassesSender.offer(frame)
        watchSender.offer(frame)
    }

    private fun pushFailed(name: String, e: Throwable) { Log.w("LiveFitHub", "$name push failed", e) }

    private fun phoneBattery(): Int? =
        app.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun flash(text: String) {
        _toast.value = text
        scope.launch { delay(1_500); if (_toast.value == text) _toast.value = null }
    }
}

val Context.services: ServiceGraph get() = (applicationContext as LiveFitApp).services

private const val WATCH_REOPEN_MS = 4_000L
