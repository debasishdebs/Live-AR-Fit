package com.debasish.livefit.sync

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.Confirmation
import com.debasish.livefit.model.ConfirmationKind
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.NowPlaying
import com.debasish.livefit.model.PROTOCOL_VERSION
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.ConfirmationOutcome
import com.debasish.livefit.services.ConfirmationService
import com.debasish.livefit.services.MusicService
import com.debasish.livefit.services.WorkoutService
import com.debasish.livefit.services.voice.VoiceCommandGate
import com.debasish.livefit.services.voice.VoiceCommandGroup
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
        override val queue = MutableStateFlow(com.debasish.livefit.model.QueueWindow())
        override fun playQueueItem(queueId: Long) { calls += "queue:$queueId" }
    }
    private val confirm = object : ConfirmationService {
        override val pending = MutableStateFlow<Confirmation?>(null)
        override suspend fun ask(kind: ConfirmationKind, title: String, message: String, defaultYes: Boolean): ConfirmationOutcome { calls += "ask:$kind"; return askOutcome }
        override fun answer(confirmationId: String, yes: Boolean): Boolean { calls += "answer:$confirmationId:$yes"; return true }
    }
    private val toasts = mutableListOf<String>()
    private fun TestScope.router() = HubCommandRouter(workout, music, confirm, backgroundScope, toast = { toasts += it })
    private fun env(id: String, c: Command, origin: DeviceKind = DeviceKind.Watch, version: Int = PROTOCOL_VERSION) =
        CommandEnvelope(protocolVersion = version, id = id, origin = origin, command = c)

    @Test fun routesEveryCommand() = runTest {
        val r = router()
        listOf(
            Command.StartWorkout(WorkoutType.Run), Command.PauseWorkout, Command.ResumeWorkout, Command.StopWorkout, Command.DismissSummary,
            Command.PlayPause, Command.PlayMusic, Command.PauseMusic, Command.NextTrack, Command.PreviousTrack, Command.LikeTrack,
            Command.Volume(up = true), Command.SetVolume(0.2f), Command.PlayQueueItem(42), Command.Answer("c1", yes = false),
        ).forEachIndexed { i, c -> r.dispatch(env("id$i", c)) }
        runCurrent()
        assertEquals(listOf("start:Run", "pauseWorkout", "resumeWorkout", "stopWorkout", "dismiss", "playPause", "play", "pauseMusic",
            "next", "previous", "like", "vol:0.6", "vol:0.2", "queue:42", "answer:c1:false"), calls)
    }

    /** M1: the glasses music screen's tap plays the highlighted queue entry, with a short toast. */
    @Test fun playQueueItemFromTheGlassesSkipsToThatEntry() = runTest {
        val r = router()
        r.dispatch(env("q", Command.PlayQueueItem(7), origin = DeviceKind.Glasses)); runCurrent()
        assertEquals(listOf("queue:7"), calls)
        assertEquals(listOf("Playing selected song"), toasts)
    }

    /** P2: voice "playlist view" is forwarded to the glasses (lf_page) with a short toast; repeats just resend the same page. */
    @Test fun showGlassesPageIsForwardedToTheGlasses() = runTest {
        val pages = mutableListOf<com.debasish.livefit.model.HudPage>()
        val r = HubCommandRouter(workout, music, confirm, backgroundScope, toast = { toasts += it }, showGlassesPage = { pages += it })
        r.dispatchVoice(Command.ShowGlassesPage(com.debasish.livefit.model.HudPage.Playlist))
        r.dispatchVoice(Command.ShowGlassesPage(com.debasish.livefit.model.HudPage.Glance))
        r.dispatch(env("p", Command.ShowGlassesPage(com.debasish.livefit.model.HudPage.Workout), origin = DeviceKind.Phone))
        runCurrent()
        assertEquals(listOf(com.debasish.livefit.model.HudPage.Playlist, com.debasish.livefit.model.HudPage.Glance, com.debasish.livefit.model.HudPage.Workout), pages)
        assertEquals(listOf("Playlist view", "Glance view", "Workout view"), toasts)
        assertTrue(calls.isEmpty(), "no workout or music side effects")
    }

    /** P1: a workout start lands the glasses on the workout page; sent before the start so a later "glance view" clause wins. */
    @Test fun workoutStartShowsTheWorkoutPage() = runTest {
        val r = HubCommandRouter(workout, music, confirm, backgroundScope, toast = {}, showGlassesPage = { calls += "page:$it" })
        r.dispatch(env("s", Command.StartWorkout(WorkoutType.Run), origin = DeviceKind.Watch))
        r.dispatchVoice(Command.StartWorkout(WorkoutType.Walk))
        r.dispatchVoice(Command.ShowGlassesPage(com.debasish.livefit.model.HudPage.Glance))
        runCurrent()
        assertEquals(listOf("page:Workout", "start:Run", "page:Workout", "start:Walk", "page:Glance"), calls)
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

    // ---- Hi Rokid agent (CommandVia.Agent): spoken commands, so they take the voice path (gate + Stop confirmation) ----
    private fun agent(id: String, c: Command) =
        CommandEnvelope(id = id, origin = DeviceKind.Glasses, command = c, via = com.debasish.livefit.model.CommandVia.Agent)

    /** The phone wiring: Settings → Voice gate in front of dispatchVoice (ServiceGraph). */
    private fun TestScope.gatedRouter(disabled: Set<VoiceCommandGroup>): HubCommandRouter {
        lateinit var r: HubCommandRouter
        val gate = VoiceCommandGate(disabled = { disabled }, toast = { toasts += it }, dispatch = { r.dispatchVoice(it) })
        r = HubCommandRouter(workout, music, confirm, backgroundScope, toast = { toasts += it }, onAgentCommand = { gate(it) })
        return r
    }

    @Test fun agentCommandInADisabledGroupIsRefusedWithTheVoiceToast() = runTest {
        val r = gatedRouter(setOf(VoiceCommandGroup.NextPrevious, VoiceCommandGroup.StopWorkout))
        r.dispatch(agent("a1", Command.NextTrack))
        r.dispatch(agent("a2", Command.StopWorkout))
        runCurrent()
        assertTrue(calls.isEmpty(), "nothing dispatched: $calls")
        assertEquals(listOf(VoiceCommandGroup.NextPrevious.blockedMessage, VoiceCommandGroup.StopWorkout.blockedMessage), toasts)
    }

    @Test fun agentCommandInAnEnabledGroupRuns() = runTest {
        val r = gatedRouter(setOf(VoiceCommandGroup.Volume))
        r.dispatch(agent("a1", Command.PauseMusic)); runCurrent()
        assertEquals(listOf("pauseMusic"), calls)
        r.dispatch(agent("a1", Command.PauseMusic)); runCurrent() // a resend is applied once
        assertEquals(listOf("pauseMusic"), calls)
    }

    @Test fun agentStopAsksFirst() = runTest {
        val r = gatedRouter(emptySet())
        askOutcome = ConfirmationOutcome.No
        r.dispatch(agent("s1", Command.StopWorkout)); runCurrent()
        assertEquals(listOf("ask:StopWorkoutByVoice"), calls)
        assertEquals(listOf("Cancelled"), toasts)
        askOutcome = ConfirmationOutcome.Yes
        r.dispatch(agent("s2", Command.StopWorkout)); runCurrent()
        assertEquals(listOf("ask:StopWorkoutByVoice", "ask:StopWorkoutByVoice", "stopWorkout"), calls)
    }

    /** Without phone wiring the agent path still confirms Stop (default = dispatchVoice). */
    @Test fun agentStopAsksFirstByDefault() = runTest {
        val r = router()
        r.dispatch(agent("s1", Command.StopWorkout)); runCurrent()
        assertEquals(listOf("ask:StopWorkoutByVoice", "stopWorkout"), calls)
    }

    /** Touchpad commands (via = Direct) bypass the voice gate and never ask, even with every group off. */
    @Test fun touchpadCommandsAreUnaffected() = runTest {
        val r = gatedRouter(VoiceCommandGroup.entries.toSet())
        r.dispatch(env("t1", Command.NextTrack, origin = DeviceKind.Glasses))
        r.dispatch(env("t2", Command.StopWorkout, origin = DeviceKind.Glasses))
        runCurrent()
        assertEquals(listOf("next", "stopWorkout"), calls)
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

    /** F2: the hub learns where a start came from (null = voice), so it can open the watch screen unless the watch asked. */
    @Test fun startOriginIsReportedBeforeTheStartIsApplied() = runTest {
        val origins = mutableListOf<DeviceKind?>()
        val r = HubCommandRouter(workout, music, confirm, backgroundScope, toast = {}, onStartRequested = { origins += it; calls += "origin:$it" })
        r.dispatch(env("w", Command.StartWorkout(WorkoutType.Walk), origin = DeviceKind.Watch))
        r.dispatch(env("g", Command.StartWorkout(WorkoutType.Run), origin = DeviceKind.Glasses))
        r.dispatchVoice(Command.StartWorkout(WorkoutType.Cycle))
        r.dispatch(env("n", Command.NextTrack, origin = DeviceKind.Glasses))
        runCurrent()
        assertEquals(listOf(DeviceKind.Watch, DeviceKind.Glasses, null), origins)
        assertEquals(listOf("origin:Watch", "start:Walk", "origin:Glasses", "start:Run", "origin:null", "start:Cycle", "next"), calls)
    }

    /** Spec §5: a disabled page explains itself and is not shown; voice page commands move the glasses only. */
    @Test fun disabledPageIsNotShownAndExplains() = runTest {
        val shown = mutableListOf<HudPage>()
        val r = HubCommandRouter(workout, music, confirm, backgroundScope, toast = { toasts += it }, showGlassesPage = { shown += it }, pageEnabled = { it != HudPage.Map })
        r.dispatchVoice(Command.ShowGlassesPage(HudPage.Map))
        r.dispatchVoice(Command.ShowGlassesPage(HudPage.MusicControls))
        assertEquals(listOf(HudPage.MusicControls), shown)
        assertEquals(listOf("Map page is turned off in Settings", "Music controls view"), toasts)
    }

    @Test fun startStillLandsOnWorkoutWhateverThePageSettings() = runTest {
        val shown = mutableListOf<HudPage>()
        val r = HubCommandRouter(workout, music, confirm, backgroundScope, toast = { toasts += it }, showGlassesPage = { shown += it }, pageEnabled = { false })
        r.dispatch(env("s1", Command.StartWorkout(WorkoutType.Run)))
        assertEquals(listOf(HudPage.Workout), shown)
    }
}
