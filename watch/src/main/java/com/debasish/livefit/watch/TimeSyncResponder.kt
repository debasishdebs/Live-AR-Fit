package com.debasish.livefit.watch

import com.debasish.livefit.model.PROTOCOL_VERSION
import com.debasish.livefit.model.TimeSyncRequest
import com.debasish.livefit.model.TimeSyncResponse
import com.debasish.livefit.model.Wire

/** Answers the phone's clock-calibration ping at once with the watch wall clock (spec §2.1). */
object TimeSyncResponder {
    fun reply(request: String, nowMs: Long): ByteArray? {
        if (Wire.versionOf(request) != PROTOCOL_VERSION) return null
        val r = runCatching { Wire.decode<TimeSyncRequest>(request) }.getOrNull() ?: return null
        return Wire.encode(TimeSyncResponse(id = r.id, t0 = r.t0, tw = nowMs)).toByteArray()
    }
}
