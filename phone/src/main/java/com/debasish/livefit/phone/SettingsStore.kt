package com.debasish.livefit.phone

import android.content.Context
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.HudSettings
import com.debasish.livefit.model.Wire
import com.debasish.livefit.services.music.MusicOnStart
import com.debasish.livefit.services.music.QueueWindowing
import com.debasish.livefit.services.voice.VoiceCommandGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


/** Persists user settings (currently the glasses HUD layout) as JSON in SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", 0)

    private val _hud = MutableStateFlow(
        prefs.getString(KEY_HUD, null)?.let { runCatching { Wire.json.decodeFromString(HudSettings.serializer(), it) }.getOrNull() } ?: HudSettings(),
    )
    val hud: StateFlow<HudSettings> = _hud

    fun updateHud(transform: (HudSettings) -> HudSettings) {
        val next = transform(_hud.value)
        _hud.value = next
        prefs.edit().putString(KEY_HUD, Wire.json.encodeToString(HudSettings.serializer(), next)).apply()
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

    /** Songs listed on the glasses music screen (N, 5–50, default 25; spec §5.5). */
    private val _glassesQueueSize = pref("glassesQueueSize", QueueWindowing.DEFAULT_SIZE) { it.toIntOrNull()?.let(QueueWindowing::clampSize) }
    val glassesQueueSize: StateFlow<Int> = _glassesQueueSize
    fun setGlassesQueueSize(v: Int) {
        val n = QueueWindowing.clampSize(v)
        _glassesQueueSize.value = n; prefs.edit().putString("glassesQueueSize", n.toString()).apply()
    }

    private val _gpsOutdoors = pref("gpsOutdoors", true) { it.toBooleanStrictOrNull() }
    val gpsOutdoors: StateFlow<Boolean> = _gpsOutdoors
    fun setGpsOutdoors(v: Boolean) { _gpsOutdoors.value = v; prefs.edit().putString("gpsOutdoors", v.toString()).apply() }

    /** Settings → Nearby devices (R2): start the hub when this companion-paired device comes nearby. Default on. */
    private val _startWhenNearby = MutableStateFlow(
        NEARBY_KINDS.associateWith { prefs.getString(nearbyKey(it), null)?.toBooleanStrictOrNull() ?: true },
    )
    val startWhenNearby: StateFlow<Map<DeviceKind, Boolean>> = _startWhenNearby
    fun startWhenNearby(kind: DeviceKind): Boolean = _startWhenNearby.value[kind] ?: true
    fun setStartWhenNearby(kind: DeviceKind, v: Boolean) {
        _startWhenNearby.value = _startWhenNearby.value + (kind to v)
        prefs.edit().putString(nearbyKey(kind), v.toString()).apply()
    }
    private fun nearbyKey(kind: DeviceKind) = "startWhenNearby.${kind.name}"
    /** Settings → Voice → Voice commands: groups turned off (default none; yes/no answers can't be). */
    private val _disabledVoiceGroups = pref("disabledVoiceGroups", emptySet<VoiceCommandGroup>()) { VoiceCommandGroup.decode(it) }
    val disabledVoiceGroups: StateFlow<Set<VoiceCommandGroup>> = _disabledVoiceGroups
    fun setVoiceGroupEnabled(group: VoiceCommandGroup, enabled: Boolean) {
        val next = VoiceCommandGroup.withEnabled(_disabledVoiceGroups.value, group, enabled)
        _disabledVoiceGroups.value = next; prefs.edit().putString("disabledVoiceGroups", VoiceCommandGroup.encode(next)).apply()
    }

    private val _setupDone = MutableStateFlow(prefs.getBoolean("setupDone", false))
    val setupDone: StateFlow<Boolean> = _setupDone
    fun setSetupDone() { _setupDone.value = true; prefs.edit().putBoolean("setupDone", true).apply() }

    companion object {
        private const val KEY_HUD = "hud"
        /** Companion-paired devices listed under Settings → Nearby devices. */
        val NEARBY_KINDS = listOf(DeviceKind.Glasses, DeviceKind.Watch)
    }
}
