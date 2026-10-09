package com.debasish.livefit.services.watch

import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.TimeSyncResponse
import com.debasish.livefit.model.WatchPaths
import com.debasish.livefit.model.Wire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WatchMessageCodecV4Test {
    @Test fun timeSyncResponseDecodes() {
        val r = TimeSyncResponse(id = 3, t0 = 10, tw = 20)
        assertEquals(WatchInbound.TimeRes(r), WatchMessageCodec.decode(WatchPaths.TIME_RES, Wire.encode(r).toByteArray()))
    }

    @Test fun outdatedTimeSyncIsReported() = assertEquals(
        WatchInbound.Outdated(3),
        WatchMessageCodec.decode(WatchPaths.TIME_RES, """{"protocolVersion":3,"id":1,"t0":1,"tw":2}""".toByteArray()),
    )

    @Test fun deltaWithLocationsDecodes() {
        val d = SessionDelta(sessionId = "s", seq = 1, locations = listOf(LocationFix(1.0, 2.0, 3f, null, 4)), provenance = Provenance.Fake)
        assertEquals(d.locations, assertIs<WatchInbound.Delta>(WatchMessageCodec.decode(WatchPaths.DELTA, Wire.encode(d).toByteArray())).delta.locations)
    }
}
