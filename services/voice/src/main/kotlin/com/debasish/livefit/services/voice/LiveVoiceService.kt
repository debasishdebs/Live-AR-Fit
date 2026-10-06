package com.debasish.livefit.services.voice

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.VoiceState
import com.debasish.livefit.services.SpeechToText
import com.debasish.livefit.services.SttSession
import com.debasish.livefit.services.VoiceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Push-to-talk: audio → on-device STT → language pack parser → router or confirmation answer. */
class LiveVoiceService(
    private val scope: CoroutineScope,
    private val stt: SpeechToText,
    private val locale: () -> String,
    private val pendingConfirmationId: () -> String?,
    private val onCommand: suspend (Command) -> Unit,
    private val onAnswer: (String, Boolean) -> Unit,
    private val toast: (String) -> Unit,
    private val phoneMic: (() -> Unit)? = null,
) : VoiceService {
    private val _state = MutableStateFlow(VoiceState.Idle)
    override val state: StateFlow<VoiceState> = _state
    private var session: SttSession? = null
    // Bound when listening starts (review #5): the answer belongs to the prompt the user heard, not whatever is pending later.
    private var pack: LanguagePack? = null
    private var confirmationAtStart: String? = null
    private var listenGuard: Job? = null
    private val lock = Any() // audio threads, the glasses event loop and the guard all transition state

    override fun listen() { phoneMic?.invoke() }

    override fun startExternal(): Boolean = synchronized(lock) {
        if (_state.value != VoiceState.Idle) return@synchronized false
        val loc = locale()
        val pack = LanguageRegistry.forLocale(loc)
        if (pack == null || !stt.isAvailable(loc)) {
            toast("Voice needs the ${pack?.displayName ?: loc} pack")
            return@synchronized false
        }
        val s = stt.start(loc)
        session = s
        this.pack = pack
        confirmationAtStart = pendingConfirmationId()
        _state.value = VoiceState.Listening
        // A lost lf_listen_end must not leave us Listening forever.
        listenGuard?.cancel()
        listenGuard = scope.launch {
            delay(LISTEN_GUARD_MS)
            if (session === s && _state.value == VoiceState.Listening) endExternal()
        }
        true
    }

    override fun feed(pcm: ByteArray) { synchronized(lock) { if (_state.value == VoiceState.Listening) session?.feed(pcm) } }

    override fun endExternal() {
        val (s, pack, confirmationId) = synchronized(lock) {
            val s = session ?: return
            if (_state.value != VoiceState.Listening) return
            listenGuard?.cancel()
            listenGuard = null
            _state.value = VoiceState.Processing
            s.end()
            Triple(s, pack, confirmationAtStart)
        }
        scope.launch {
            val text = s.awaitFinal(5_000)
            synchronized(lock) { session = null }
            // Release the mic before acting: a command may wait on a confirmation whose answer is spoken (stop → "yes").
            synchronized(lock) { _state.value = VoiceState.Idle }
            when {
                text.isNullOrBlank() || pack == null -> toast("Didn't catch that")
                // The prompt this capture answered expired or was replaced while recognising: drop it quietly.
                confirmationId != null && pendingConfirmationId() != confirmationId -> Unit
                confirmationId != null -> pack.parseYesNo(text)?.let { onAnswer(confirmationId, it) } ?: toast("Say yes or no")
                else -> pack.parseCommand(text)?.let { onCommand(it) } ?: toast("Didn't catch that")
            }
        }
    }

    private companion object { const val LISTEN_GUARD_MS = 8_000L }
}
