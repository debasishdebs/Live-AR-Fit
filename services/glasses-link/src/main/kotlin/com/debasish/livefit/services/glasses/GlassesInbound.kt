package com.debasish.livefit.services.glasses

import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.GlassesChannels
import com.debasish.livefit.model.PROTOCOL_VERSION
import com.debasish.livefit.model.PageState
import com.debasish.livefit.model.Wire
import com.debasish.livefit.services.GlassesEvent
import java.util.Base64

/**
 * Pure decoding and protocol-version gate for glasses → phone messages (spec §4.7).
 * lf_cmd and lf_listen carry protocolVersion; lf_audio / lf_listen_end are forwarded only after an
 * accepted (matching-version) lf_listen, so an outdated glasses app can't drive voice (review #10).
 */
class GlassesInbound {
    private var listenAccepted = false

    /** A new CXR session: nothing from the previous one is in flight. */
    @Synchronized fun reset() { listenAccepted = false }

    @Synchronized fun onMessage(cmd: String, text: String): List<GlassesEvent> = when (cmd) {
        GlassesChannels.LISTEN -> {
            val v = Wire.versionOf(text)
            listenAccepted = v == PROTOCOL_VERSION
            listOf(if (listenAccepted) GlassesEvent.Listen else GlassesEvent.Outdated(v))
        }
        // Audio is Base64 text on lf_audio (spike-verified; deliberate deviation from a raw-bytes argument).
        GlassesChannels.AUDIO -> if (!listenAccepted) emptyList()
            else listOfNotNull(runCatching { Base64.getDecoder().decode(text) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { GlassesEvent.Audio(it) })
        GlassesChannels.LISTEN_END -> if (!listenAccepted) emptyList() else { listenAccepted = false; listOf(GlassesEvent.ListenEnd) }
        GlassesChannels.COMMAND -> when (val v = Wire.versionOf(text)) {
            null -> emptyList()
            PROTOCOL_VERSION -> listOfNotNull(runCatching { Wire.decode<CommandEnvelope>(text) }.getOrNull()?.let { GlassesEvent.Issue(it) })
            else -> listOf(GlassesEvent.Outdated(v))
        }
        else -> emptyList()
    }

    /** lf_page_state (spec §2.5); another version is ignored (lf_cmd already reports a mismatch). */
    fun pageState(cmd: String, text: String): PageState? = if (cmd == GlassesChannels.PAGE_STATE) PageState.parse(text) else null
}
