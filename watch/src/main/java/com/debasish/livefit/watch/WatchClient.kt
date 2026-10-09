package com.debasish.livefit.watch

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.Confirmation
import com.debasish.livefit.model.ConfirmationKind
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.ExerciseError
import com.debasish.livefit.model.ExerciseState
import com.debasish.livefit.model.ExerciseStateReport
import com.debasish.livefit.model.LinkState
import com.debasish.livefit.model.LivePosition
import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.model.NowPlaying
import com.debasish.livefit.model.PageSettings
import com.debasish.livefit.model.QueueFrame
import com.debasish.livefit.model.QueueWindow
import com.debasish.livefit.model.StateFrame
import com.debasish.livefit.model.WatchPaths
import com.debasish.livefit.model.WatchSettingsFrame
import com.debasish.livefit.model.Wire
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.Clock
import com.debasish.livefit.sync.LivenessMonitor
import com.debasish.livefit.watch.map.WatchMapTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

data class WatchUiState(
    val snapshot: WorkoutSnapshot = WorkoutSnapshot(),
    val music: NowPlaying? = null,
    val confirmation: Confirmation? = null,
    val phoneOnline: Boolean = false,
    val glassesOnline: Boolean = false,
    /** Phone unreachable while this watch holds a session (spec §4.4 step 4). */
    val offline: Boolean = false,
    val hrHistory: List<Int> = emptyList(),
    val needsPermissions: List<String> = emptyList(),
    val outdated: Boolean = false,
    val toast: String? = null,
    /** Settings → Pages from the phone (spec §3.2). */
    val pages: PageSettings = PageSettings(),
    /** YouTube Music queue window for the Playlist page (spec §6). */
    val queue: QueueWindow = QueueWindow(),
    /** This session's own route from route.bin (spec §2.6: the watch map never uses phone fixes). */
    val route: List<LocationFix> = emptyList(),
    /** The watch Map marker: last fix that was usable-live on arrival (WatchMapTracker). */
    val live: LivePosition? = null,
)

/** Renders hub frames, falls back to the local session while the phone is offline, re-claims on reconnect. */
object WatchClient {
    private const val VOLUME_WINDOW_MS = 100L

    private val liveness = LivenessMonitor(Clock { System.currentTimeMillis() })
    private val _ui = MutableStateFlow(WatchUiState())
    val ui: StateFlow<WatchUiState> = _ui
    private var lastFrame: StateFrame? = null
    private var wasOnline = false
    private var started = false
    private var lastVolumeSentMs = 0L
    private var volumeJob: Job? = null
    /** Takeover question asked on the watch itself while the phone is offline. */
    private var localConfirm: Pair<Confirmation, WorkoutType>? = null
    /** Offline sessions whose Summary the user dismissed (in memory; the data stays held for sync). */
    private var dismissedLocal: String? = null
    private var lastResendMs = 0L
    private var localToast: Pair<String, Long>? = null
    private val pagesFile by lazy { PageSettingsFile(File(WatchRuntime.app.filesDir, "pages.json")) }
    private var pages = PageSettings()
    private var queue = QueueWindow()
    private val mapTracker = WatchMapTracker()

    fun start() {
        if (started) return
        started = true
        WatchRuntime.scope.launch { pages = pagesFile.load(); refresh() }
        WatchRuntime.scope.launch { WatchRuntime.routes.route.collect { mapTracker.onRoute(it, System.currentTimeMillis()); refresh() } }
        WatchRuntime.scope.launch {
            while (true) {
                try { tick() } catch (e: CancellationException) { throw e } catch (e: Exception) { WatchRuntime.log("tick failed: $e") }
                delay(1_000)
            }
        }
        WatchRuntime.scope.launch { WatchRuntime.controller.lastError.collect { refresh() } }
    }

    fun onFrame(json: String) {
        val frame = runCatching { Wire.decode<StateFrame>(json) }.getOrNull() ?: return
        lastFrame?.workout?.takeIf { it.phase == WorkoutPhase.Summary && frame.workout.phase != WorkoutPhase.Summary }
            ?.sessionId?.let(WatchRuntime.routes::markDismissed)
        lastFrame = frame
        liveness.onFrame()
        if (!wasOnline) WatchRuntime.scope.launch { onReconnected() }
        wasOnline = true
        refresh()
        WatchFront.onConfirmation(WatchRuntime.app, frame.confirmation?.id)
    }

    fun onSettings(json: String) {
        val f = runCatching { Wire.decode<WatchSettingsFrame>(json) }.getOrNull() ?: return
        pages = f.pages
        pagesFile.save(f.pages)
        refresh()
    }

    fun onQueue(json: String) {
        queue = runCatching { Wire.decode<QueueFrame>(json).window }.getOrNull() ?: return
        refresh()
    }

    private fun routeFor(sessionId: String?): List<LocationFix> =
        WatchRuntime.routes.route.value?.takeIf { it.sessionId == sessionId }?.fixes ?: emptyList()

    fun onOutdated() { _ui.value = _ui.value.copy(outdated = true) }

    /** Sends to the phone; an unreachable phone must never crash the watch. */
    private suspend fun safeSend(path: String, bytes: ByteArray) {
        try { WatchRuntime.send(path, bytes) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* phone unreachable */ }
    }

    private suspend fun onReconnected() {
        try {
            val rec = WatchRuntime.recorder
            if (rec.holdsData) {
                rec.resync() // claims the oldest held session and replays it; newer ones follow after its final ack
            } else {
                // Tell the hub we hold nothing (lets it finalize a session whose data is gone, spec §4.9).
                safeSend(WatchPaths.EXERCISE_STATE, Wire.encode(ExerciseStateReport(sessionId = "", state = ExerciseState.Idle, atMs = System.currentTimeMillis())).toByteArray())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WatchRuntime.log("reconnect sync failed: $e")
        }
    }

    private suspend fun tick() {
        val online = liveness.isOnline()
        if (wasOnline && !online) wasOnline = false
        // Unacked deltas are re-sent every 5 s while online (spec §4.4 step 3).
        val now = System.currentTimeMillis()
        if (online && WatchRuntime.recorder.holdsData && now - lastResendMs >= 5_000) {
            lastResendMs = now
            WatchRuntime.recorder.resendUnacked()
        }
        refresh()
    }

    private fun refresh() {
        val online = liveness.isOnline()
        val local = WatchRuntime.recorder.assembler
        val f = lastFrame
        val missing = (WatchRuntime.controller.lastError.value as? ExerciseError.PermissionMissing)?.permissions ?: emptyList()
        val now = System.currentTimeMillis()
        if (localConfirm?.first?.let { now >= it.expiresAtMs } == true) { localConfirm = null; localToast = "Cancelled" to now + 3_000 }
        val toastLocal = localToast?.takeIf { now < it.second }?.first
        val outdated = _ui.value.outdated
        _ui.value = if (!online && local != null) {
            // The local session stays Stopping until the final ack; offline, show it as Summary, then Ready once dismissed.
            val dismissed = WatchRuntime.recorder.sessionId == dismissedLocal && dismissedLocal != null
            val snap = local.snapshot(atMs = now) // own clock: the offline timer ticks between readings (B1)
            WatchUiState(
                snapshot = when {
                    dismissed -> WorkoutSnapshot()
                    snap.phase == WorkoutPhase.Stopping -> snap.copy(phase = WorkoutPhase.Summary)
                    else -> snap
                },
                phoneOnline = false, offline = true,
                hrHistory = if (dismissed) emptyList() else local.hrHistory(60),
                needsPermissions = missing, outdated = outdated, toast = toastLocal,
                confirmation = localConfirm?.first,
                pages = pages, queue = QueueWindow(), route = routeFor(snap.sessionId),
                live = mapTracker.liveFor(snap.sessionId),
            )
        } else WatchUiState(
            snapshot = f?.workout ?: WorkoutSnapshot(),
            music = f?.music,
            confirmation = localConfirm?.first ?: f?.confirmation,
            phoneOnline = online,
            glassesOnline = f?.devices?.glasses?.link == LinkState.Connected,
            hrHistory = local?.hrHistory(60) ?: emptyList(),
            needsPermissions = missing,
            outdated = outdated,
            toast = toastLocal ?: f?.toast,
            pages = pages, queue = queue, route = routeFor(f?.workout?.sessionId),
            live = mapTracker.liveFor(f?.workout?.sessionId),
        )
    }

    private fun toastLocally(text: String) { localToast = text to System.currentTimeMillis() + 3_000 }

    /** Spec §4.4 "Started on the watch with no phone": same checks as a hub start, takeover asked on the watch. */
    private suspend fun offlineStart(type: WorkoutType, force: Boolean) {
        when (WatchRuntime.controller.localStart(type, force)) {
            null -> WatchRuntime.ensureExerciseService()
            is ExerciseError.OtherAppTracking -> localConfirm = Confirmation(
                id = "local-takeover-${System.currentTimeMillis()}", kind = ConfirmationKind.TakeOverWorkout,
                title = "Take over workout?", message = "Another app is tracking a workout on your watch. Take over?",
                expiresAtMs = System.currentTimeMillis() + 15_000,
            ) to type
            is ExerciseError.PermissionMissing -> Unit // the permission card appears via lastError
            else -> toastLocally("Couldn't start workout")
        }
    }

    fun command(c: Command) {
        WatchRuntime.scope.launch {
            try {
                if (c == Command.DismissSummary) _ui.value.snapshot.sessionId?.let(WatchRuntime.routes::markDismissed)
                if (!liveness.isOnline()) {
                    // Offline: only workout controls work, applied locally and recorded as events.
                    when (c) {
                        is Command.StartWorkout -> offlineStart(c.type, force = false)
                        Command.PauseWorkout -> if (!WatchRuntime.controller.localPause()) toastLocally("Couldn't pause workout")
                        Command.ResumeWorkout -> if (!WatchRuntime.controller.localResume()) toastLocally("Couldn't resume workout")
                        Command.DismissSummary -> dismissedLocal = WatchRuntime.recorder.sessionId
                        Command.StopWorkout -> if (!WatchRuntime.controller.localStop()) toastLocally("Couldn't stop workout")
                        is Command.Answer -> localConfirm?.takeIf { it.first.id == c.confirmationId }?.let { (_, type) ->
                            localConfirm = null
                            if (c.yes) offlineStart(type, force = true) else toastLocally("Kept the other workout")
                        }
                        else -> Unit
                    }
                    refresh()
                    return@launch
                }
                if (c is Command.Answer && localConfirm?.first?.id == c.confirmationId) { localConfirm = null; refresh(); return@launch } // phone came back mid-question
                val env = CommandEnvelope(id = UUID.randomUUID().toString(), origin = DeviceKind.Watch, command = c)
                safeSend(WatchPaths.COMMAND, Wire.encode(env).toByteArray())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WatchRuntime.log("command $c failed: $e")
                toastLocally("Something went wrong")
                refresh()
            }
        }
    }

    /** Arc drag / bezel: at most 10 commands per second; the latest level is always sent once the window ends. */
    fun setVolume(level: Float) {
        val v = level.coerceIn(0f, 1f)
        volumeJob?.cancel()
        volumeJob = null
        val wait = lastVolumeSentMs + VOLUME_WINDOW_MS - System.currentTimeMillis()
        if (wait <= 0) {
            lastVolumeSentMs = System.currentTimeMillis()
            command(Command.SetVolume(v))
        } else {
            volumeJob = WatchRuntime.scope.launch {
                delay(wait)
                lastVolumeSentMs = System.currentTimeMillis()
                command(Command.SetVolume(v))
            }
        }
    }
}
