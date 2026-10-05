package com.debasish.livefit.services.voice

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.VoiceState
import com.debasish.livefit.services.VoiceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Each listen() "hears" the next scripted phrase and runs it through the real [CommandParser]. */
class FakeVoiceService(private val scope: CoroutineScope) : VoiceService {
    private val script = listOf("next song", "like this song", "pause workout", "resume workout")
    private var turn = 0

    private val _state = MutableStateFlow(VoiceState.Idle)
    override val state: StateFlow<VoiceState> = _state
    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 4)
    override val commands: SharedFlow<Command> = _commands

    override fun listen() {
        if (_state.value != VoiceState.Idle) return
        scope.launch {
            _state.value = VoiceState.Listening
            delay(1_800)
            _state.value = VoiceState.Processing
            delay(600)
            CommandParser.parse(script[turn++ % script.size])?.let { _commands.emit(it) }
            _state.value = VoiceState.Idle
        }
    }
}
