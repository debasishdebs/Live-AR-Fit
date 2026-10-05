package com.debasish.livefit.phone

import android.content.Context
import com.debasish.livefit.model.HudSettings
import com.debasish.livefit.model.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class MusicOnStart { DontTouch, Resume, PlaySearch }

/** Persists user settings (currently the glasses HUD layout) as JSON in SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", 0)

    private val _hud = MutableStateFlow(
        prefs.getString(KEY_HUD, null)?.let { runCatching { Protocol.json.decodeFromString(HudSettings.serializer(), it) }.getOrNull() } ?: HudSettings(),
    )
    val hud: StateFlow<HudSettings> = _hud

    fun updateHud(transform: (HudSettings) -> HudSettings) {
        val next = transform(_hud.value)
        _hud.value = next
        prefs.edit().putString(KEY_HUD, Protocol.json.encodeToString(HudSettings.serializer(), next)).apply()
    }

    private fun <T> pref(key: String, default: T, read: (String) -> T?): MutableStateFlow<T> =
        MutableStateFlow(prefs.getString(key, null)?.let(read) ?: default)

    private val _voiceLocale = pref("voiceLocale", "en-IN") { it }
    val voiceLocale: StateFlow<String> = _voiceLocale
    fun setVoiceLocale(v: String) { _voiceLocale.value = v; prefs.edit().putString("voiceLocale", v).apply() }

    private val _musicOnStart = pref("musicOnStart", MusicOnStart.Resume) { runCatching { MusicOnStart.valueOf(it) }.getOrNull() }
    val musicOnStart: StateFlow<MusicOnStart> = _musicOnStart
    fun setMusicOnStart(v: MusicOnStart) { _musicOnStart.value = v; prefs.edit().putString("musicOnStart", v.name).apply() }

    private val _musicSearch = pref("musicSearch", "workout mix") { it }
    val musicSearch: StateFlow<String> = _musicSearch
    fun setMusicSearch(v: String) { _musicSearch.value = v; prefs.edit().putString("musicSearch", v).apply() }

    private val _pauseMusicOnStop = pref("pauseMusicOnStop", true) { it.toBooleanStrictOrNull() }
    val pauseMusicOnStop: StateFlow<Boolean> = _pauseMusicOnStop
    fun setPauseMusicOnStop(v: Boolean) { _pauseMusicOnStop.value = v; prefs.edit().putString("pauseMusicOnStop", v.toString()).apply() }

    private val _gpsOutdoors = pref("gpsOutdoors", true) { it.toBooleanStrictOrNull() }
    val gpsOutdoors: StateFlow<Boolean> = _gpsOutdoors
    fun setGpsOutdoors(v: Boolean) { _gpsOutdoors.value = v; prefs.edit().putString("gpsOutdoors", v.toString()).apply() }

    private companion object { const val KEY_HUD = "hud" }
}
