package com.debasish.livefit.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WireTest {
    private inline fun <reified T> roundTrip(value: T) = assertEquals(value, Wire.decode<T>(Wire.encode(value)))

    @Test fun stateFrameRoundTripsWithVersion() {
        val frame = StateFrame(
            workout = WorkoutSnapshot(phase = WorkoutPhase.Syncing, type = WorkoutType.Run, sessionId = "s1", elapsedMs = 61_000),
            music = NowPlaying("Song", "Artist", isPlaying = true, volume = 0.4f),
            devices = Devices(watch = DeviceState(LinkState.Connected, 81), glasses = DeviceState(LinkState.Connecting, 100)),
            confirmation = Confirmation("c1", ConfirmationKind.TakeOverWorkout, "Take over?", "Samsung Health is tracking", expiresAtMs = 99),
            toast = "Next song", sentAtMs = 5,
        )
        roundTrip(frame)
        assertTrue(Wire.encode(frame).contains("\"protocolVersion\":1"), "version must be encoded even though it is a default")
    }

    @Test fun commandEnvelopesRoundTrip() {
        val commands = listOf(
            Command.StartWorkout(WorkoutType.Cycle), Command.SetVolume(0.75f), Command.Answer("c1", yes = true),
            Command.PauseWorkout, Command.Volume(up = false),
        )
        for (c in commands) roundTrip(CommandEnvelope(id = "id-$c", origin = DeviceKind.Watch, command = c))
    }

    @Test fun sessionMessagesRoundTrip() {
        roundTrip(
            SessionDelta(
                sessionId = "s1", seq = 7,
                events = listOf(SessionEvent.Started(1, WorkoutType.Walk), SessionEvent.Paused(2), SessionEvent.Resumed(3),
                    SessionEvent.TypeDetected(4, WorkoutType.Run), SessionEvent.Stopped(5, EndReason.OtherApp)),
                samples = listOf(Sample(tMs = 1, hr = 120, stepsTotal = 10, distanceKmTotal = 0.01, kcalTotal = 1.5, speedKmh = 5.0)),
                provenance = Provenance.Live("galaxy-watch/health-services"), final = true,
            ),
        )
        roundTrip(DeltaAck(sessionId = "s1", seq = 7))
        roundTrip(SessionClaim(sessionId = "s1", type = WorkoutType.Walk, startMs = 1, phase = WorkoutPhase.Paused, activeMs = 60_000, lastSeq = 42))
    }

    @Test fun exerciseMessagesRoundTrip() {
        for (op in listOf(ExerciseOp.Start(WorkoutType.Run, force = true, gps = true), ExerciseOp.Pause, ExerciseOp.Resume, ExerciseOp.Stop)) {
            roundTrip(ExerciseRequest(requestId = "r", sessionId = "s", op = op))
        }
        val errors = listOf(
            ExerciseError.PermissionMissing(listOf("android.permission.health.READ_HEART_RATE")),
            ExerciseError.OtherAppTracking("RUNNING_TREADMILL"), ExerciseError.SensorUnavailable,
            ExerciseError.WrongSession("other"), ExerciseError.NoSuchSession, ExerciseError.Internal("boom"),
        )
        for (e in errors) roundTrip(ExerciseResult(requestId = "r", sessionId = "s", ok = false, error = e, state = ExerciseState.Idle, activeSessionId = "x"))
        roundTrip(ExerciseStateReport(sessionId = "s", state = ExerciseState.Ended, endedBy = EndReason.OtherApp))
    }

    @Test fun hudSettingsFrameRoundTrips() =
        roundTrip(HudSettingsFrame(settings = HudSettings(scale = 0.5f, position = HudPosition.TopRight, items = setOf(HudItem.HeartRate))))

    @Test fun versionOfReadsAnyMessage() {
        assertEquals(1, Wire.versionOf(Wire.encode(DeltaAck(sessionId = "s", seq = 1))))
        assertEquals(null, Wire.versionOf("not json"))
        assertEquals(null, Wire.versionOf("{\"seq\":1}"))
    }

    @Test fun zoneLabelUsesDashBelowZoneOne() {
        assertEquals("–", zoneLabel(null))
        assertEquals("–", zoneLabel(0))
        assertEquals("Z3", zoneLabel(3))
    }
}
