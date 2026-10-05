package com.debasish.livefit.services.watch

import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.DeviceStatus
import com.debasish.livefit.model.LinkState
import com.debasish.livefit.model.StateFrame
import com.debasish.livefit.services.WatchLinkService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

class FakeWatchLink : WatchLinkService {
    override val status: StateFlow<DeviceStatus> =
        MutableStateFlow(DeviceStatus("Galaxy Watch6 Classic", LinkState.Connected, batteryPct = 64, detail = "Demo data"))
    override val commands: Flow<CommandEnvelope> = emptyFlow()
    override suspend fun push(frame: StateFrame) = Unit
}
