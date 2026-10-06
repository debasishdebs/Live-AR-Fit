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
    /** Debug log of recognised text and what it parsed to (never audio), to aid device testing. */
    private val log: (String) -> Unit = {},
) : VoiceService {
    private val _state = MutableStateFlow(VoiceState.Idle)
    override val state: StateFlow<VoiceState> = _state
    private var session: SttSession? = null
    private var capture = 0L // id of the capture that owns [session]; ids are never reused
    // Bound when listening starts (review #5): the answer belongs to the prompt the user heard, not whatever is pending later.
    private var pack: LanguagePack? = null
    private var confirmationAtStart: String? = null
    private var listenGuard: Job? = null
    private val lock = Any() // audio threads, the glasses event loop and the guard all transition state

    override fun listen() { phoneMic?.invoke() }

    override fun startExternal(): Long? = synchronized(lock) {
        if (_state.value == VoiceState.Listening) return@synchronized null
        // A capture still recognising for a prompt that is no longer the pending one (or for a command, when a prompt has since
        // arrived) can only be discarded: let the new prompt's answer in (review M3).
        val preempt = _state.value == VoiceState.Processing
        if (preempt && pendingConfirmationId() == confirmationAtStart) return@synchronized null
        val loc = locale()
        val pack = LanguageRegistry.forLocale(loc)
        if (pack == null || !stt.isAvailable(loc)) {
            toast("Voice needs the ${pack?.displayName ?: loc} pack")
            return@synchronized null
        }
        // Release the superseded recognizer first: some devices answer a second on-device recognizer with ERROR_RECOGNIZER_BUSY.
        if (preempt) session?.cancel()
        val s = stt.start(loc)
        session = s
        val id = ++capture
        this.pack = pack
        confirmationAtStart = pendingConfirmationId()
        _state.value = VoiceState.Listening
        // A lost lf_listen_end must not leave us Listening forever.
        listenGuard?.cancel()
        listenGuard = scope.launch {
            delay(LISTEN_GUARD_MS)
            endExternal(id)
        }
        id
    }

    override fun feed(capture: Long, pcm: ByteArray) {
        synchronized(lock) { if (capture == this.capture && _state.value == VoiceState.Listening) session?.feed(pcm) }
    }

    override fun endExternal(capture: Long) {
        val (s, pack, confirmationId) = synchronized(lock) {
            val s = session ?: return
            if (capture != this.capture || _state.value != VoiceState.Listening) return
            listenGuard?.cancel()
            listenGuard = null
            _state.value = VoiceState.Processing
            s.end()
            Triple(s, pack, confirmationAtStart)
        }
        scope.launch {
            val text = s.awaitFinal(5_000)
            // Release the mic before acting: a command may wait on a confirmation whose answer is spoken (stop → "yes").
            val current = synchronized(lock) {
                (capture == this@LiveVoiceService.capture).also { if (it) { session = null; _state.value = VoiceState.Idle } }
            }
            if (!current) return@launch // superseded by a newer capture while recognising
            when {
                text.isNullOrBlank() || pack == null -> { log("voice: no transcript"); toast("Didn't catch that") }
                // The prompt this capture answered expired or was replaced while recognising: drop it quietly.
                confirmationId != null && pendingConfirmationId() != confirmationId -> log("voice \"$text\" -> stale answer for $confirmationId, dropped")
                confirmationId != null -> pack.parseYesNo(text).also { log("voice \"$text\" -> answer $it for $confirmationId") }
                    ?.let { onAnswer(confirmationId, it) } ?: toast("Say yes or no")
                else -> runCommands(pack, text)
            }
        }
    }

    /**
     * Composite utterances (F5): clauses run in spoken order; [onCommand] suspends while a stop waits for its confirmation,
     * so later clauses wait for the answer. Clauses that matched nothing are named in a toast after the rest ran.
     */
    private suspend fun runCommands(pack: LanguagePack, text: String) {
        val parsed = pack.parseUtterance(text)
        log("voice \"$text\" -> ${parsed.commands}" + if (parsed.notUnderstood.isEmpty()) "" else " not understood ${parsed.notUnderstood}")
        if (parsed.commands.isEmpty()) { toast("Didn't catch that"); return }
        for (c in parsed.commands) onCommand(c)
        if (parsed.notUnderstood.isNotEmpty()) toast("Didn't catch " + parsed.notUnderstood.joinToString(", ") { "\"$it\"" })
    }

    private companion object { const val LISTEN_GUARD_MS = 8_000L }
}
