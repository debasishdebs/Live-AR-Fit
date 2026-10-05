package com.debasish.livefit.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val PROTOCOL_VERSION = 1

/** CXR custom-command names (spec §4.2). */
object GlassesChannels {
    const val STATE = "lf_state"
    const val SETTINGS = "lf_settings"
    const val COMMAND = "lf_cmd"
    const val LISTEN = "lf_listen"
    const val AUDIO = "lf_audio"
    const val LISTEN_END = "lf_listen_end"
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
