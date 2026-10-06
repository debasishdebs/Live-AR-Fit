package com.debasish.livefit.services.voice

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.VoiceState
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.SpeechToText
import com.debasish.livefit.services.SttSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiveVoiceServiceTest {
    private class FakeStt(var available: Boolean = true) : SpeechToText {
        var sessions = 0
        var ended = 0
        val result = CompletableDeferred<String?>()
        override fun isAvailable(locale: String) = available
        override fun start(locale: String): SttSession { sessions++; return object : SttSession {
            override fun feed(pcm: ByteArray) = Unit
            override fun end() { ended++ }
            override suspend fun awaitFinal(timeoutMs: Long) = withTimeoutOrNull(timeoutMs) { result.await() }
        } }
    }

    private val commands = mutableListOf<Command>()
    private val answers = mutableListOf<Pair<String, Boolean>>()
    private val toasts = mutableListOf<String>()
    private var pending: String? = null
    private fun TestScope.voice(stt: FakeStt) = LiveVoiceService(backgroundScope, stt, locale = { "en-IN" }, pendingConfirmationId = { pending },
        onCommand = { commands += it }, onAnswer = { id, yes -> answers += id to yes }, toast = { toasts += it })

    @Test fun recognisedCommandIsDispatched() = runTest {
        val stt = FakeStt(); val v = voice(stt)
        assertTrue(v.startExternal()); assertEquals(VoiceState.Listening, v.state.value)
        v.feed(ByteArray(3_200)); v.endExternal(); runCurrent()
        assertEquals(VoiceState.Processing, v.state.value)
        stt.result.complete("Start work out"); runCurrent()
        assertEquals(listOf<Command>(Command.StartWorkout(WorkoutType.Walk)), commands)
        assertEquals(VoiceState.Idle, v.state.value)
    }

    @Test fun pendingConfirmationUsesYesNo() = runTest {
        val stt = FakeStt(); val v = voice(stt); pending = "c1"
        v.startExternal(); v.endExternal(); stt.result.complete("yeah"); runCurrent()
        assertEquals(listOf("c1" to true), answers)
        assertTrue(commands.isEmpty())
    }

    /** Review #5: audio captured for C1 must not answer its replacement C2. */
    @Test fun delayedYesForAReplacedConfirmationIsDiscarded() = runTest {
        val stt = FakeStt(); val v = voice(stt); pending = "c1"
        assertTrue(v.startExternal()); v.endExternal(); runCurrent()
        pending = "c2" // C1 replaced while recognition is still running
        stt.result.complete("yes"); runCurrent()
        assertTrue(answers.isEmpty(), "C2 was never answered")
        assertTrue(commands.isEmpty())
        assertTrue(toasts.isEmpty(), "a stale answer is dropped quietly")
        assertEquals(VoiceState.Idle, v.state.value)
    }

    @Test fun delayedYesForAnExpiredConfirmationIsDiscarded() = runTest {
        val stt = FakeStt(); val v = voice(stt); pending = "c1"
        v.startExternal(); v.endExternal(); runCurrent()
        pending = null
        stt.result.complete("yes"); runCurrent()
        assertTrue(answers.isEmpty())
        assertTrue(commands.isEmpty())
    }

    /** Review #5: a confirmation that appears mid-capture is not answered by speech meant as a command. */
    @Test fun confirmationAppearingMidCaptureIsNotAnswered() = runTest {
        val stt = FakeStt(); val v = voice(stt)
        v.startExternal(); pending = "c1"
        v.endExternal(); stt.result.complete("yes"); runCurrent()
        assertTrue(answers.isEmpty())
    }

    @Test fun localeIsBoundWhenListeningStarts() = runTest {
        var loc = "en-IN"
        val stt = FakeStt()
        val v = LiveVoiceService(backgroundScope, stt, locale = { loc }, pendingConfirmationId = { pending },
            onCommand = { commands += it }, onAnswer = { id, yes -> answers += id to yes }, toast = { toasts += it })
        v.startExternal(); loc = "xx-XX" // no pack for this locale
        v.endExternal(); stt.result.complete("next song"); runCurrent()
        assertEquals(listOf<Command>(Command.NextTrack), commands)
    }

    @Test fun unrecognisedSpeechToastsAndChangesNothing() = runTest {
        val stt = FakeStt(); val v = voice(stt)
        v.startExternal(); v.endExternal(); stt.result.complete("what's the weather"); runCurrent()
        assertEquals(listOf("Didn't catch that"), toasts)
    }

    /** Review Focus #4. */
    @Test fun secondListenWhileBusyIsIgnored() = runTest {
        val stt = FakeStt(); val v = voice(stt)
        assertTrue(v.startExternal())
        assertFalse(v.startExternal())
        v.endExternal(); runCurrent()
        assertFalse(v.startExternal(), "still processing")
        assertEquals(1, stt.sessions)
    }

    /** Codex P1: "stop workout" waits on its confirmation; the auto-opened mic must accept the spoken "yes". */
    @Test fun spokenAnswerIsAcceptedWhileTheCommandAwaitsConfirmation() = runTest {
        val heard = ArrayDeque(listOf("stop workout", "yes"))
        val stt = object : SpeechToText {
            override fun isAvailable(locale: String) = true
            override fun start(locale: String): SttSession = object : SttSession {
                override fun feed(pcm: ByteArray) = Unit
                override fun end() = Unit
                override suspend fun awaitFinal(timeoutMs: Long): String? = heard.removeFirst()
            }
        }
        val confirmed = CompletableDeferred<Unit>()
        val v = LiveVoiceService(backgroundScope, stt, locale = { "en-IN" }, pendingConfirmationId = { pending },
            onCommand = { commands += it; pending = "c1"; confirmed.await() }, // like dispatchVoice(StopWorkout) inside confirm.ask
            onAnswer = { id, yes -> answers += id to yes; pending = null; confirmed.complete(Unit) },
            toast = { toasts += it })
        assertTrue(v.startExternal()); v.endExternal(); runCurrent()
        assertEquals(listOf<Command>(Command.StopWorkout), commands)
        assertTrue(v.startExternal(), "the mic must open for the spoken answer while stop waits")
        v.endExternal(); runCurrent()
        assertEquals(listOf("c1" to true), answers)
        assertEquals(VoiceState.Idle, v.state.value)
    }

    @Test fun missingPackDisablesVoiceWithHint() = runTest {
        val v = voice(FakeStt(available = false))
        assertFalse(v.startExternal())
        assertEquals(listOf("Voice needs the English (India) pack"), toasts)
    }

    /** Controller ruling B: a lost lf_listen_end must not strand voice in Listening. */
    @Test fun listeningTimesOutIfListenEndIsLost() = runTest {
        val stt = FakeStt(); val v = voice(stt)
        assertTrue(v.startExternal())
        advanceTimeBy(7_999); runCurrent()
        assertEquals(VoiceState.Listening, v.state.value, "guard must not fire early")
        advanceTimeBy(2); runCurrent()
        assertEquals(VoiceState.Processing, v.state.value, "guard ended the session")
        assertEquals(1, stt.ended, "guard called session.end()")
        v.endExternal() // a late/duplicate end must not double-dispatch
        assertEquals(1, stt.ended)
        advanceTimeBy(5_001); runCurrent() // nothing was recognised
        assertEquals(VoiceState.Idle, v.state.value)
        assertEquals(listOf("Didn't catch that"), toasts)
        assertTrue(v.startExternal(), "voice is usable again")
    }
}
