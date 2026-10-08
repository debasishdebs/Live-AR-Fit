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
        assertTrue(Wire.encode(frame).contains("\"protocolVersion\":$PROTOCOL_VERSION"), "version must be encoded even though it is a default")
    }

    @Test fun commandEnvelopesRoundTrip() {
        val commands = listOf(
            Command.StartWorkout(WorkoutType.Cycle), Command.SetVolume(0.75f), Command.Answer("c1", yes = true),
            Command.PauseWorkout, Command.Volume(up = false), Command.PlayQueueItem(9_007_199_254_740_993L),
            Command.ShowGlassesPage(HudPage.Playlist),
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

    @Test fun queueFrameRoundTripsWithVersion() {
        val frame = QueueFrame(window = QueueWindow(listOf(QueueItem(3, "Song", "Artist"), QueueItem(4, "Next")), currentIndex = 0))
        roundTrip(frame)
        roundTrip(QueueFrame(window = QueueWindow()))
        assertEquals(PROTOCOL_VERSION, Wire.versionOf(Wire.encode(frame)))
        assertEquals(QueueItem(3, "Song", "Artist"), frame.window.current)
        assertEquals(null, QueueWindow(listOf(QueueItem(1, "a")), currentIndex = null).current)
    }

    @Test fun playQueueItemUsesTheCmdDiscriminator() {
        val json = Wire.encode(CommandEnvelope(id = "i", origin = DeviceKind.Glasses, command = Command.PlayQueueItem(42)))
        assertTrue(json.contains("\"cmd\":\"com.debasish.livefit.model.Command.PlayQueueItem\"") && json.contains("\"queueId\":42"), json)
    }

    /** §4.7: v2 added lf_queue and PlayQueueItem; v3 added lf_page (PageRequest) and ShowGlassesPage. Older peers are outdated. */
    @Test fun protocolVersionIsThree() = assertEquals(3, PROTOCOL_VERSION)

    @Test fun glassesPagesAreGlanceWorkoutPlaylist() = assertEquals(listOf(HudPage.Glance, HudPage.Workout, HudPage.Playlist), HudPage.entries)

    /** P2: voice "playlist view" → lf_page; the glasses apply only a current-version request. */
    @Test fun pageRequestParsesOnlyCurrentVersion() {
        for (p in HudPage.entries) assertEquals(p, PageRequest.parse(Wire.encode(PageRequest(page = p))))
        assertTrue(Wire.encode(PageRequest(page = HudPage.Glance)).contains("\"protocolVersion\":$PROTOCOL_VERSION"))
        assertEquals(null, PageRequest.parse("""{"protocolVersion":2,"page":"Glance"}"""), "a v2 phone is outdated since v3")
        assertEquals(null, PageRequest.parse("""{"protocolVersion":$PROTOCOL_VERSION,"page":"Nope"}"""), "unknown page")
        assertEquals(null, PageRequest.parse("garbage"))
    }

    @Test fun hudSettingsFrameRoundTrips() =
        roundTrip(HudSettingsFrame(settings = HudSettings(scale = 0.5f, position = HudPosition.TopRight, items = setOf(HudItem.HeartRate))))

    @Test fun versionOfReadsAnyMessage() {
        assertEquals(PROTOCOL_VERSION, Wire.versionOf(Wire.encode(DeltaAck(sessionId = "s", seq = 1))))
        assertEquals(null, Wire.versionOf("not json"))
        assertEquals(null, Wire.versionOf("{\"seq\":1}"))
    }

    @Test fun zoneLabelUsesDashBelowZoneOne() {
        assertEquals("–", zoneLabel(null))
        assertEquals("–", zoneLabel(0))
        assertEquals("Z3", zoneLabel(3))
    }

    /** D2: the peer makes itself discoverable for CDM only on a current-version request. */
    @Test fun discoverableRequestParsesOnlyCurrentVersion() {
        assertEquals(120, DiscoverableRequest.parse(Wire.encode(DiscoverableRequest())))
        assertTrue(Wire.encode(DiscoverableRequest()).contains("\"protocolVersion\":$PROTOCOL_VERSION"))
        assertEquals(null, DiscoverableRequest.parse("""{"protocolVersion":0,"seconds":120}"""), "outdated sender")
        assertEquals(null, DiscoverableRequest.parse("""{"protocolVersion":1,"seconds":120}"""), "a v1 phone is outdated since v2")
        assertEquals(null, DiscoverableRequest.parse("""{"protocolVersion":2,"seconds":120}"""), "a v2 phone is outdated since v3")
        assertEquals(null, DiscoverableRequest.parse("""{"seconds":120}"""), "unversioned")
        assertEquals(null, DiscoverableRequest.parse("garbage"))
        assertEquals(300, DiscoverableRequest.parse("""{"protocolVersion":$PROTOCOL_VERSION,"seconds":9999}"""), "clamped to the platform maximum")
        assertEquals(1, DiscoverableRequest.parse("""{"protocolVersion":$PROTOCOL_VERSION,"seconds":-5}"""))
    }
}
