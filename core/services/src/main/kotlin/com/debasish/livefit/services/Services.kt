package com.debasish.livefit.services

import com.debasish.livefit.model.DeviceStatus
import com.debasish.livefit.model.NowPlaying
import com.debasish.livefit.model.VoiceState
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Service contracts. Each lives in its own :services:* module with a Fake (mock-ups)
 * and, later, a Live implementation; apps only ever see these interfaces.
 */

/** Owns the workout state machine: Idle -> Starting -> Active <-> Paused -> Stopping -> Summary. */
interface WorkoutService {
    val snapshot: StateFlow<WorkoutSnapshot>
    fun start(type: WorkoutType)
    fun pause()
    fun resume()
    fun stop()
    fun dismissSummary()
}

interface GlassesLinkService {
    val status: StateFlow<DeviceStatus>
    /** Requests from the glasses: push-to-talk audio and commands. */
    val events: Flow<GlassesEvent>
    /** Opens the link and launches the HUD app on the glasses. No-op for fakes. */
    fun connect() {}
    /** One connect attempt when the hub (re)starts (F1); no retry loop unless the glasses are reported present. */
    fun connectOnce() { connect() }
    /** Results of Hi Rokid authorization attempts (true = authorized), so the UI can confirm a silent success. */
    val authResults: Flow<Boolean> get() = kotlinx.coroutines.flow.emptyFlow()
    /** Asks the glasses app to make the glasses discoverable for companion pairing; false when it could not be sent. */
    suspend fun requestDiscoverable(): Boolean = false
    suspend fun push(frame: com.debasish.livefit.model.StateFrame)
    suspend fun pushSettings(frame: com.debasish.livefit.model.HudSettingsFrame)
    /** Music-screen queue window (lf_queue); sent on change and on every (re)connect. */
    suspend fun pushQueue(frame: com.debasish.livefit.model.QueueFrame) {}
    /** Shows a HUD page on the glasses (lf_page, voice "playlist view"); dropped while not connected. */
    suspend fun pushPage(request: com.debasish.livefit.model.PageRequest) {}
    /** Map epoch header or image on lf_map (spec §2.5); false when not sent (not connected or the send failed). */
    suspend fun pushMap(frame: com.debasish.livefit.model.MapFrame, png: ByteArray?): Boolean = false
    /** lf_page_state from the glasses: the visible page, on every page change and every (re)connect. */
    val pageStates: Flow<com.debasish.livefit.model.PageState> get() = kotlinx.coroutines.flow.emptyFlow()
}

sealed interface GlassesEvent {
    data object Listen : GlassesEvent
    class Audio(val pcm: ByteArray) : GlassesEvent
    data object ListenEnd : GlassesEvent
    data class Issue(val envelope: com.debasish.livefit.model.CommandEnvelope) : GlassesEvent
    data class Outdated(val version: Int?) : GlassesEvent
}

interface WatchLinkService {
    val status: StateFlow<DeviceStatus>
    /** Commands tapped on the watch. */
    val commands: Flow<com.debasish.livefit.model.CommandEnvelope>
    /** Asks the watch app to make the watch discoverable for companion pairing; false when it could not be sent. */
    suspend fun requestDiscoverable(): Boolean = false
    suspend fun push(frame: com.debasish.livefit.model.StateFrame)
    /** Settings → Pages on /lf/settings, on change and on every (re)connect (spec §3.2). */
    suspend fun pushSettings(frame: com.debasish.livefit.model.WatchSettingsFrame) {}
    /** Queue window on /lf/queue for the watch Playlist page, on change and on every (re)connect (spec §6). */
    suspend fun pushQueue(frame: com.debasish.livefit.model.QueueFrame) {}
}

interface MusicService {
    val nowPlaying: StateFlow<NowPlaying?>
    /** 0..1 */
    val volume: StateFlow<Float>
    fun playPause()
    fun play()
    fun pause()
    fun next()
    fun previous()
    fun toggleLike()
    fun setVolume(level: Float)
    /** The part of the player's queue the glasses music screen lists (spec §5.5); empty when there is no queue. */
    val queue: StateFlow<com.debasish.livefit.model.QueueWindow>
    /** Jumps to a queue entry by its [com.debasish.livefit.model.QueueItem.queueId]. */
    fun playQueueItem(queueId: Long)
}

interface VoiceService {
    val state: StateFlow<VoiceState>
    /** Push-to-talk with the phone microphone. */
    fun listen()
    /** External audio (glasses, phone mic thread): returns the capture id that owns voice, or null if busy or voice is unavailable. */
    fun startExternal(): Long?
    /** Audio and end for [capture]; ignored unless that capture still owns voice, so one source can't feed or end another's. */
    fun feed(capture: Long, pcm: ByteArray)
    fun endExternal(capture: Long)
}

/** Injected time source so state machines are testable with virtual time. */
fun interface Clock { fun nowMs(): Long }

enum class ConfirmationOutcome { Yes, No, Timeout, Superseded }

/** At most one pending confirmation; first answer from any device wins; silence = No after 15 s. */
interface ConfirmationService {
    val pending: StateFlow<com.debasish.livefit.model.Confirmation?>
    suspend fun ask(kind: com.debasish.livefit.model.ConfirmationKind, title: String, message: String, defaultYes: Boolean = true): ConfirmationOutcome
    /** Returns true only for the first answer to the currently pending confirmation. */
    fun answer(confirmationId: String, yes: Boolean): Boolean
}

/** Phone side of hub → watch exercise control and watch → phone session data (spec §4.4, §4.8). */
interface WatchExerciseGateway {
    suspend fun send(request: com.debasish.livefit.model.ExerciseRequest)
    suspend fun ack(ack: com.debasish.livefit.model.DeltaAck)
    val results: Flow<com.debasish.livefit.model.ExerciseResult>
    val stateReports: Flow<com.debasish.livefit.model.ExerciseStateReport>
    val deltas: Flow<com.debasish.livefit.model.SessionDelta>
    val claims: Flow<com.debasish.livefit.model.SessionClaim>
}

/** Durable session storage on the phone. */
interface SessionStore {
    /** Stores durably (idempotent by sessionId+seq); returns the highest contiguous seq now stored. */
    suspend fun storeDelta(delta: com.debasish.livefit.model.SessionDelta): Long
    suspend fun deltas(sessionId: String): List<com.debasish.livefit.model.SessionDelta>
    /** Sessions with stored deltas but no finalized summary, oldest first. */
    suspend fun openSessionIds(): List<String>
    suspend fun finalize(summary: com.debasish.livefit.model.SessionSummary)
    /** Deletes the session's data but keeps a Discarded tombstone, so late data for it is still ignored after a restart. */
    suspend fun discard(sessionId: String)
    /** What the hub must remember across a phone restart (spec §4.8 abandoned starts, §4.9 end time); null = never seen. */
    suspend fun lifecycle(sessionId: String): SessionLifecycle?
    /** Records the end event. The first [endedAtMs] is kept (it starts the 24 h deadline); a later non-null reason fills a missing one. */
    suspend fun markEnded(sessionId: String, endReason: com.debasish.livefit.model.EndReason?, endedAtMs: Long)
}

enum class StoredSessionState { Open, Finalized, Discarded }

data class SessionLifecycle(
    val state: StoredSessionState,
    val endReason: com.debasish.livefit.model.EndReason? = null,
    val endedAtMs: Long? = null,
)

/** Phone history: finished and open sessions plus 1 Hz samples (spec §5.6). */
interface HistoryStore : SessionStore {
    /** Finalized sessions, newest first (Complete and Incomplete; Demo = Fake provenance). */
    val sessions: Flow<List<com.debasish.livefit.model.SessionSummary>>
    suspend fun samples(sessionId: String): List<com.debasish.livefit.model.Sample>
    /**
     * "Clear history": removes finalized sessions (Complete/Incomplete) and their samples from view and storage.
     * Open sessions keep their data, and lifecycle tombstones stay so stale traffic is still rejected.
     */
    suspend fun clearFinished()
}

/**
 * Durable route rows per session (spec §2.2, route_point). Identity (sessionId, source, deviceTimeMs) never changes;
 * phoneTimeMs is null until the watch clock is calibrated and is rewritten by [normalizeWatchTimes] (review #2).
 */
interface RouteStore {
    /** Insert-or-ignore by identity; nothing is written for a Discarded (or Cleared) session — its tombstone wins. */
    suspend fun storeRouteFixes(sessionId: String, fixes: List<com.debasish.livefit.model.RouteFix>)
    /** phoneTimeMs = deviceTimeMs − [watchOffsetMs] for every Watch row of [sessionId] that is null or mapped with another offset. */
    suspend fun normalizeWatchTimes(sessionId: String, watchOffsetMs: Long)
    /** Phone-time order, watch first on ties; rows without a phone time last, by device time. */
    suspend fun routeFixes(sessionId: String): List<com.debasish.livefit.model.RouteFix>
}

/** Platform speech-to-text, on-device only (spec §5.4). */
interface SpeechToText {
    /** True when the on-device pack for [locale] is installed. */
    fun isAvailable(locale: String): Boolean
    fun start(locale: String): SttSession
}

interface SttSession {
    /** 16 kHz mono PCM16 little-endian. */
    fun feed(pcm: ByteArray)
    fun end()
    /** Abandons recognition (a newer capture superseded this one): releases the recognizer; [awaitFinal] then returns promptly. */
    fun cancel() {}
    /** Final (or last partial) text; null on error/timeout. */
    suspend fun awaitFinal(timeoutMs: Long): String?
}
