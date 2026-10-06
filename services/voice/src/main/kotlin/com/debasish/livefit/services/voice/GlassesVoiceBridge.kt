package com.debasish.livefit.services.voice

import com.debasish.livefit.services.VoiceService

/**
 * Glasses push-to-talk events → [voice]. Audio and end are forwarded only while the glasses own the
 * capture, so a listen rejected because the phone mic is busy can't feed or end the phone's session (review #9).
 * Called from one thread (the hub's glasses event loop).
 */
class GlassesVoiceBridge(private val voice: VoiceService) {
    private var capture: Long? = null

    fun onListen() {
        capture?.let(voice::endExternal) // our previous lf_listen_end was lost
        capture = voice.startExternal()
    }

    fun onAudio(pcm: ByteArray) { capture?.let { voice.feed(it, pcm) } }

    fun onListenEnd() {
        capture?.let(voice::endExternal)
        capture = null
    }
}
