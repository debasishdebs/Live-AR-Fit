package com.debasish.livefit.model

import kotlinx.serialization.Serializable

@Serializable
enum class ConfirmationKind { TakeOverWorkout, StopWorkoutByVoice }

/** Shown on all three devices at once; the first Answer from any device wins (spec §4.6). */
@Serializable
data class Confirmation(
    val id: String,
    val kind: ConfirmationKind,
    val title: String,
    val message: String,
    val yesLabel: String = "Yes",
    val noLabel: String = "No",
    /** Which choice the glasses highlight first. */
    val defaultYes: Boolean = true,
    val expiresAtMs: Long,
)

/** Phone → watch and glasses on every change (coalesced 100 ms) and every 5 s heartbeat. */
@Serializable
data class StateFrame(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val workout: WorkoutSnapshot,
    val music: NowPlaying? = null,
    val devices: Devices = Devices(),
    val voice: VoiceState = VoiceState.Idle,
    val confirmation: Confirmation? = null,
    val toast: String? = null,
    /** Set when the hub detected a protocol mismatch with this device. */
    val outdated: DeviceKind? = null,
    val sentAtMs: Long = 0,
)

/** Phone → glasses on change and on every (re)connect; v4 adds the page set and the gesture table (spec §3.2, §4.4). */
@Serializable
data class HudSettingsFrame(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val settings: HudSettings,
    val pages: PageSettings = PageSettings(),
    val gestures: GestureSettings = GestureSettings(),
)

/** Glasses → phone on lf_listen: opens push-to-talk. Versioned so an outdated glasses app can't drive voice (spec §4.7). */
@Serializable
data class ListenRequest(val protocolVersion: Int = PROTOCOL_VERSION)

/** Every command travels with a unique id so resends are applied once (spec §4.5). */
@Serializable
data class CommandEnvelope(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val id: String,
    val origin: DeviceKind,
    val command: Command,
)

/**
 * Phone → watch / glasses when the user taps Pair: the companion picker only lists discoverable devices,
 * so the peer asks its user to make it discoverable for [seconds] (ACTION_REQUEST_DISCOVERABLE).
 */
@Serializable
data class DiscoverableRequest(val protocolVersion: Int = PROTOCOL_VERSION, val seconds: Int = 120) {
    companion object {
        /** Seconds to stay discoverable (clamped to the platform's 1..300), or null unless [text] is a current-version request. */
        fun parse(text: String): Int? {
            if (Wire.versionOf(text) != PROTOCOL_VERSION) return null
            return runCatching { Wire.decode<DiscoverableRequest>(text).seconds.coerceIn(1, 300) }.getOrNull()
        }
    }
}

/** The shared page set of glasses and watch, in cycling order (spec §3.1). */
@Serializable
enum class HudPage(val label: String) {
    Glance("Glance"),
    Workout("Workout"),
    Stats("Stats"),
    Playlist("Playlist"),
    Map("Map"),
    MusicControls("Music controls"),
}

/**
 * Phone → glasses on lf_page: show [page] (voice "glance view" / "workout view" / "playlist view"). Idempotent: applying
 * it again leaves the glasses on the same page. Ignored unless `protocolVersion` matches.
 */
@Serializable
data class PageRequest(val protocolVersion: Int = PROTOCOL_VERSION, val page: HudPage) {
    companion object {
        /** The requested page, or null unless [text] is a current-version request for a known page. */
        fun parse(text: String): HudPage? {
            if (Wire.versionOf(text) != PROTOCOL_VERSION) return null
            return runCatching { Wire.decode<PageRequest>(text).page }.getOrNull()
        }
    }
}

/** Glasses → phone on lf_page_state: the visible page, on every page change and every (re)connect; [seq] grows per glasses process. */
@Serializable
data class PageState(val protocolVersion: Int = PROTOCOL_VERSION, val page: HudPage, val seq: Long) {
    companion object {
        fun parse(text: String): PageState? {
            if (Wire.versionOf(text) != PROTOCOL_VERSION) return null
            return runCatching { Wire.decode<PageState>(text) }.getOrNull()
        }
    }
}

@Serializable
enum class MapFrameKind { Epoch, Image }

/**
 * Phone → glasses on lf_map (spec §2.5). [MapFrameKind.Epoch] announces a new [renderEpoch] (no image);
 * [MapFrameKind.Image] carries a PNG in the CXR bytes argument (or [pngBase64] when CxrGlassesLink.MAP_AS_BASE64 is on).
 */
@Serializable
data class MapFrame(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val kind: MapFrameKind,
    val renderEpoch: Long,
    val sessionId: String? = null,
    val renderSeq: Long = 0,
    val pngBase64: String? = null,
) {
    companion object {
        fun parse(text: String): MapFrame? {
            if (Wire.versionOf(text) != PROTOCOL_VERSION) return null
            return runCatching { Wire.decode<MapFrame>(text) }.getOrNull()
        }
    }
}

/** Phone → watch on /lf/settings: the page set, on change and on every (re)connect (spec §3.2). */
@Serializable
data class WatchSettingsFrame(val protocolVersion: Int = PROTOCOL_VERSION, val pages: PageSettings)

/** Phone → watch: clock calibration ping (spec §2.1). */
@Serializable
data class TimeSyncRequest(val protocolVersion: Int = PROTOCOL_VERSION, val id: Long, val t0: Long)

/** Watch → phone: [tw] = watch wall clock when the ping arrived. */
@Serializable
data class TimeSyncResponse(val protocolVersion: Int = PROTOCOL_VERSION, val id: Long, val t0: Long, val tw: Long)
