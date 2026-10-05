package com.debasish.livefit.services.glasses

import com.debasish.livefit.model.DeviceStatus
import com.debasish.livefit.model.HudFrame
import com.debasish.livefit.model.LinkState
import com.debasish.livefit.services.GlassesEvent
import com.debasish.livefit.services.GlassesLinkService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow

/** Pretends to be connected Rokid glasses; keeps the last frame so the phone can preview the HUD. */
class FakeGlassesLink : GlassesLinkService {
    override val status: StateFlow<DeviceStatus> =
        MutableStateFlow(DeviceStatus("Rokid Glasses", LinkState.Connected, batteryPct = 82, detail = "Demo data"))

    override val events: Flow<GlassesEvent> = emptyFlow()

    private val _lastFrame = MutableStateFlow<HudFrame?>(null)
    val lastFrame: StateFlow<HudFrame?> = _lastFrame

    override suspend fun push(frame: HudFrame) {
        _lastFrame.value = frame
    }
}
