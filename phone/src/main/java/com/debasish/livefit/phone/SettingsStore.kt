package com.debasish.livefit.phone

import android.content.Context
import com.debasish.livefit.model.HudSettings
import com.debasish.livefit.model.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    private companion object { const val KEY_HUD = "hud" }
}
