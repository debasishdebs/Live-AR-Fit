package com.debasish.livefit.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 2: glasses music screen — `lf_queue` (QueueFrame) and `Command.PlayQueueItem` (a v1 phone can't decode the new command).
 * 3: glasses pages by voice — `lf_page` (PageRequest) and `Command.ShowGlassesPage` (a v2 glasses app would drop the request).
 */
const val PROTOCOL_VERSION = 3

/** CXR custom-command names (spec §4.2). */
object GlassesChannels {
    const val STATE = "lf_state"
    const val SETTINGS = "lf_settings"
    const val COMMAND = "lf_cmd"
    const val LISTEN = "lf_listen"
    const val AUDIO = "lf_audio"
    const val LISTEN_END = "lf_listen_end"
    /** Phone → glasses: the YouTube Music queue window for the music screen ([QueueFrame]), on change and on (re)connect. */
    const val QUEUE = "lf_queue"
    /** Phone → glasses: make the glasses discoverable so the companion pairing picker can list them. */
    const val DISCOVERABLE = "lf_discoverable"
    /** Phone → glasses: show this HUD page ([PageRequest]), e.g. after voice "playlist view". */
    const val PAGE = "lf_page"
}

/** Wearable Data Layer message paths (spec §4.2). */
object WatchPaths {
    const val STATE = "/lf/state"
    const val SETTINGS = "/lf/settings"
    const val COMMAND = "/lf/cmd"
    const val DELTA = "/lf/delta"
    const val ACK = "/lf/ack"
    const val CLAIM = "/lf/claim"
    const val EXERCISE_REQ = "/lf/exercise_req"
    const val EXERCISE_RES = "/lf/exercise_res"
    const val EXERCISE_STATE = "/lf/exercise_state"
    const val BATTERY_REQ = "/lf/battery_req"
    const val BATTERY = "/lf/battery"
    /** Phone → watch: make the watch discoverable so the companion pairing picker can list it. */
    const val DISCOVERABLE = "/lf/discoverable"
}

/**
 * V1 wire codec. Defaults ARE encoded so protocolVersion always travels; discriminator is "cmd"
 * because several messages have their own `type` property.
 */
object Wire {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "cmd" }

    inline fun <reified T> encode(value: T): String = json.encodeToString(value)
    inline fun <reified T> decode(text: String): T = json.decodeFromString(text)

    fun versionOf(text: String): Int? = runCatching {
        json.parseToJsonElement(text).jsonObject["protocolVersion"]?.jsonPrimitive?.int
    }.getOrNull()
}
