package com.debasish.livefit.services.watch

import com.debasish.livefit.model.DeviceStatus
import com.debasish.livefit.model.LinkState
import com.debasish.livefit.services.WatchLinkService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeWatchLink : WatchLinkService {
    override val status: StateFlow<DeviceStatus> =
        MutableStateFlow(DeviceStatus("Galaxy Watch6 Classic", LinkState.Connected, batteryPct = 64, detail = "Demo data"))
}
