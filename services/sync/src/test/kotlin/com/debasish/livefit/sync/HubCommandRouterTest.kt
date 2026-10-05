package com.debasish.livefit.sync

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.Confirmation
import com.debasish.livefit.model.ConfirmationKind
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.NowPlaying
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.ConfirmationOutcome
import com.debasish.livefit.services.ConfirmationService
import com.debasish.livefit.services.MusicService
import com.debasish.livefit.services.WorkoutService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HubCommandRouterTest {
    private val calls = mutableListOf<String>()
    private var askOutcome = ConfirmationOutcome.Yes

    private val workout = object : WorkoutService {
        override val snapshot = MutableStateFlow(WorkoutSnapshot())
        override fun start(type: WorkoutType) { calls += "start:$type" }
        override fun pause() { calls += "pauseWorkout" }
        override fun resume() { calls += "resumeWorkout" }
        override fun stop() { calls += "stopWorkout" }
        override fun dismissSummary() { calls += "dismiss" }
    }
    private val music = object : MusicService {
        override val nowPlaying = MutableStateFlow<NowPlaying?>(null)
        override val volume = MutableStateFlow(0.5f)
        override fun playPause() { calls += "playPause" }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pauseMusic" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun toggleLike() { calls += "like" }
        override fun setVolume(level: Float) { calls += "vol:${"%.1f".format(level)}" }
    }
    private val confirm = object : ConfirmationService {
        override val pending = MutableStateFlow<Confirmation?>(null)
        override suspend fun ask(kind: ConfirmationKind, title: String, message: String, defaultYes: Boolean): ConfirmationOutcome { calls += "ask:$kind"; return askOutcome }
        override fun answer(confirmationId: String, yes: Boolean): Boolean { calls += "answer:$confirmationId:$yes"; return true }
    }
    private val toasts = mutableListOf<String>()
    private fun TestScope.router() = HubCommandRouter(workout, music, confirm, backgroundScope, toast = { toasts += it })
    private fun env(id: String, c: Command, origin: DeviceKind = DeviceKind.Watch, version: Int = 1) =
        CommandEnvelope(protocolVersion = version, id = id, origin = origin, command = c)

    @Test fun routesEveryCommand() = runTest {
        val r = router()
        listOf(
            Command.StartWorkout(WorkoutType.Run), Command.PauseWorkout, Command.ResumeWorkout, Command.StopWorkout, Command.DismissSummary,
            Command.PlayPause, Command.PlayMusic, Command.PauseMusic, Command.NextTrack, Command.PreviousTrack, Command.LikeTrack,
            Command.Volume(up = true), Command.SetVolume(0.2f), Command.Answer("c1", yes = false),
        ).forEachIndexed { i, c -> r.dispatch(env("id$i", c)) }
        runCurrent()
        assertEquals(listOf("start:Run", "pauseWorkout", "resumeWorkout", "stopWorkout", "dismiss", "playPause", "play", "pauseMusic",
            "next", "previous", "like", "vol:0.6", "vol:0.2", "answer:c1:false"), calls)
    }

    @Test fun duplicateIdsAreAppliedOnce() = runTest {
        val r = router()
        r.dispatch(env("same", Command.NextTrack)); r.dispatch(env("same", Command.NextTrack)); runCurrent()
        assertEquals(listOf("next"), calls)
    }

    @Test fun versionMismatchIsIgnoredAndFlagged() = runTest {
        val r = router()
        r.dispatch(env("x", Command.NextTrack, origin = DeviceKind.Glasses, version = 99)); runCurrent()
        assertTrue(calls.isEmpty())
        assertEquals(DeviceKind.Glasses, r.outdated.value)
        assertTrue(toasts.single().contains("Update LiveFit on your glasses"))
    }

    @Test fun voiceStopAsksFirst() = runTest {
        val r = router()
        askOutcome = ConfirmationOutcome.No
        r.dispatchVoice(Command.StopWorkout); runCurrent()
        assertEquals(listOf("ask:StopWorkoutByVoice"), calls)
        askOutcome = ConfirmationOutcome.Yes
        r.dispatchVoice(Command.StopWorkout); runCurrent()
        assertEquals("stopWorkout", calls.last())
    }

    @Test fun markOutdatedFlagsDeviceAndToasts() = runTest {
        val r = router()
        r.markOutdated(DeviceKind.Watch)
        assertEquals(DeviceKind.Watch, r.outdated.value)
        assertEquals(listOf("Update LiveFit on your watch"), toasts)
        assertTrue(calls.isEmpty())
    }

    @Test fun userCommandsToastTheirDescription() = runTest {
        val r = router()
        r.dispatch(env("a", Command.NextTrack))
        r.dispatch(env("b", Command.SetVolume(1.7f)))
        r.dispatch(env("c", Command.Answer("c1", yes = true)))
        r.dispatch(env("d", Command.StartWorkout(WorkoutType.Run)))
        r.dispatch(env("e", Command.StopWorkout))
        runCurrent()
        assertEquals(listOf("Next song", "Volume 100%"), toasts)
    }
}
