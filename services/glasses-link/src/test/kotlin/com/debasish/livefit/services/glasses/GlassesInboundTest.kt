package com.debasish.livefit.services.glasses

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.GlassesChannels
import com.debasish.livefit.model.ListenRequest
import com.debasish.livefit.model.PROTOCOL_VERSION
import com.debasish.livefit.model.Wire
import com.debasish.livefit.services.GlassesEvent
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GlassesInboundTest {
    private val audio = Base64.getEncoder().encodeToString(ByteArray(3_200))
    private val listen = Wire.encode(ListenRequest())

    @Test fun matchingListenForwardsAudioAndEnd() {
        val g = GlassesInbound()
        assertEquals(listOf<GlassesEvent>(GlassesEvent.Listen), g.onMessage(GlassesChannels.LISTEN, listen))
        assertEquals(3_200, assertIs<GlassesEvent.Audio>(g.onMessage(GlassesChannels.AUDIO, audio).single()).pcm.size)
        assertEquals(listOf<GlassesEvent>(GlassesEvent.ListenEnd), g.onMessage(GlassesChannels.LISTEN_END, "{}"))
        assertTrue(g.onMessage(GlassesChannels.AUDIO, audio).isEmpty(), "audio after the end is dropped")
    }

    /** Review #10: an incompatible glasses app can't issue voice commands. */
    @Test fun mismatchedListenReportsOutdatedAndDropsItsAudioAndEnd() {
        val g = GlassesInbound()
        val events = listOf(
            GlassesChannels.LISTEN to """{"protocolVersion":${PROTOCOL_VERSION + 1}}""",
            GlassesChannels.AUDIO to audio,
            GlassesChannels.LISTEN_END to "{}",
        ).flatMap { (c, t) -> g.onMessage(c, t) }
        assertEquals(listOf<GlassesEvent>(GlassesEvent.Outdated(PROTOCOL_VERSION + 1)), events)
    }

    @Test fun unversionedListenFromAnOlderBuildIsOutdated() {
        val g = GlassesInbound()
        assertEquals(listOf<GlassesEvent>(GlassesEvent.Outdated(null)), g.onMessage(GlassesChannels.LISTEN, "{}"))
        assertTrue(g.onMessage(GlassesChannels.AUDIO, audio).isEmpty())
        assertTrue(g.onMessage(GlassesChannels.LISTEN_END, "{}").isEmpty())
    }

    @Test fun audioWithoutAnAcceptedListenIsDropped() {
        val g = GlassesInbound()
        assertTrue(g.onMessage(GlassesChannels.AUDIO, audio).isEmpty())
        g.onMessage(GlassesChannels.LISTEN, listen); g.reset() // new CXR session
        assertTrue(g.onMessage(GlassesChannels.AUDIO, audio).isEmpty())
    }

    @Test fun commandsAreVersionChecked() {
        val g = GlassesInbound()
        val ok = CommandEnvelope(id = "a", origin = DeviceKind.Glasses, command = Command.NextTrack)
        assertEquals(listOf<GlassesEvent>(GlassesEvent.Issue(ok)), g.onMessage(GlassesChannels.COMMAND, Wire.encode(ok)))
        assertEquals(listOf<GlassesEvent>(GlassesEvent.Outdated(9)), g.onMessage(GlassesChannels.COMMAND, Wire.encode(ok.copy(protocolVersion = 9))))
        assertTrue(g.onMessage(GlassesChannels.COMMAND, "{}").isEmpty())
    }
}
