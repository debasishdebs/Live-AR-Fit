package com.debasish.livefit.services.watch

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.ExerciseResult
import com.debasish.livefit.model.ExerciseState
import com.debasish.livefit.model.PROTOCOL_VERSION
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.WatchPaths
import com.debasish.livefit.model.Wire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class WatchMessageCodecTest {
    private fun bytes(s: String) = s.toByteArray()

    @Test fun decodesEachPath() {
        assertIs<WatchInbound.Delta>(WatchMessageCodec.decode(WatchPaths.DELTA, bytes(Wire.encode(SessionDelta(sessionId = "s", seq = 0, provenance = Provenance.Fake)))))
        assertIs<WatchInbound.Result>(WatchMessageCodec.decode(WatchPaths.EXERCISE_RES, bytes(Wire.encode(ExerciseResult(requestId = "r", sessionId = "s", ok = true, state = ExerciseState.Active)))))
        assertIs<WatchInbound.Cmd>(WatchMessageCodec.decode(WatchPaths.COMMAND, bytes(Wire.encode(CommandEnvelope(id = "1", origin = DeviceKind.Watch, command = Command.NextTrack)))))
        assertEquals(WatchInbound.Battery(83), WatchMessageCodec.decode(WatchPaths.BATTERY, bytes("83")))
    }

    @Test fun versionMismatchIsReported() {
        val json = Wire.encode(SessionDelta(protocolVersion = PROTOCOL_VERSION + 1, sessionId = "s", seq = 0, provenance = Provenance.Fake))
        assertEquals(WatchInbound.Outdated(PROTOCOL_VERSION + 1), WatchMessageCodec.decode(WatchPaths.DELTA, bytes(json)))
    }

    @Test fun unknownPathOrGarbageIsNull() {
        assertNull(WatchMessageCodec.decode("/other", bytes("{}")))
        assertNull(WatchMessageCodec.decode(WatchPaths.DELTA, bytes("not json")))
    }
}
