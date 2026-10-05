package com.debasish.livefit.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** Every message the three devices exchange must survive encode -> decode unchanged. */
class ProtocolTest {

    private val allCommands: List<Command> = listOf(
        Command.StartWorkout(WorkoutType.Walk), Command.StartWorkout(WorkoutType.Run),
        Command.StartWorkout(WorkoutType.Cycle), Command.StartWorkout(WorkoutType.Auto),
        Command.PauseWorkout, Command.ResumeWorkout, Command.StopWorkout, Command.DismissSummary,
        Command.PlayPause, Command.PlayMusic, Command.PauseMusic,
        Command.NextTrack, Command.PreviousTrack, Command.LikeTrack,
        Command.Volume(up = true), Command.Volume(up = false),
    )

    @Test fun everyCommandRoundTrips() {
        for (c in allCommands) assertEquals(c, Protocol.decodeCommand(Protocol.encodeCommand(c)), "command $c")
    }

    @Test fun hudFrameRoundTrips() {
        val frame = HudFrame(
            workout = WorkoutSnapshot(
                phase = WorkoutPhase.Active, type = WorkoutType.Auto, detectedType = WorkoutType.Run, elapsedMs = 65_000,
                metrics = Metrics(heartRate = 142, calories = 212, steps = 3204, distanceKm = 2.1, speedKmh = 9.4),
                avgHeartRate = 131, maxHeartRate = 150,
            ),
            watch = LinkState.Connected, phone = LinkState.Connected,
            music = NowPlaying("Waka Waka", "Shakira", isPlaying = true, liked = true, positionMs = 1_000, durationMs = 202_000),
            voice = VoiceState.Listening, toast = "Next song",
            settings = HudSettings(scale = 0.4f, position = HudPosition.TopLeft, items = setOf(HudItem.HeartRate, HudItem.Timer)),
            phoneBattery = 45, watchBattery = 83, sentAtMs = 123,
        )
        assertEquals(frame, Protocol.decodeHud(Protocol.encodeHud(frame)))
    }

    @Test fun defaultFrameRoundTrips() {
        val frame = HudFrame(WorkoutSnapshot(), LinkState.Disconnected, LinkState.Connected)
        assertEquals(frame, Protocol.decodeHud(Protocol.encodeHud(frame)))
    }

    @Test fun unknownFieldsAreIgnored() {
        // Newer phone build sending a field an older glasses build doesn't know.
        val json = Protocol.encodeHud(HudFrame(WorkoutSnapshot(), LinkState.Connected, LinkState.Connected)).dropLast(1) + ""","future":1}"""
        Protocol.decodeHud(json)
    }
}
