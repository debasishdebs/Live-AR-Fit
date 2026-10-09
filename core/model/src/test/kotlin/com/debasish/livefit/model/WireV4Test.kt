package com.debasish.livefit.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WireV4Test {
    private inline fun <reified T> roundTrip(value: T) = assertEquals(value, Wire.decode<T>(Wire.encode(value)))

    /** §6: v4 adds lf_map, lf_page_state, pages + gestures, watch settings, watch queue and location fixes. */
    @Test fun protocolVersionIsFour() = assertEquals(4, PROTOCOL_VERSION)

    @Test fun pageOrderIsTheSharedCycle() = assertEquals(
        listOf(HudPage.Glance, HudPage.Workout, HudPage.Stats, HudPage.Playlist, HudPage.Map, HudPage.MusicControls),
        HudPage.entries,
    )

    @Test fun pageLabelsAreUserFacing() = assertEquals("Music controls", HudPage.MusicControls.label)

    @Test fun newChannelsAndPaths() {
        assertEquals("lf_map", GlassesChannels.MAP)
        assertEquals("lf_page_state", GlassesChannels.PAGE_STATE)
        assertEquals("/lf/time_req", WatchPaths.TIME_REQ)
        assertEquals("/lf/time_res", WatchPaths.TIME_RES)
        assertEquals("/lf/queue", WatchPaths.QUEUE)
    }

    @Test fun deltaCarriesLocationFixesAndGpsStart() = roundTrip(
        SessionDelta(
            sessionId = "s", seq = 0,
            events = listOf(SessionEvent.Started(1_000, WorkoutType.Run, gps = true)),
            locations = listOf(LocationFix(12.9716, 77.5946, 4.5f, 90f, 1_000), LocationFix(12.9717, 77.5946, 6f, null, 2_000)),
            provenance = Provenance.Live("galaxy-watch/health-services"),
        ),
    )

    /** Review #8: a fix without accuracy travels as an explicit null (unknown), never as a borderline number. */
    @Test fun unknownAccuracyIsAnExplicitNull() {
        val f = LocationFix(12.9716, 77.5946, null, null, 3_000)
        roundTrip(f)
        assertTrue(Wire.encode(f).contains("\"accuracyM\":null"))
    }

    @Test fun olderDeltaJsonWithoutLocationsOrGpsStillDecodes() {
        val json = """{"protocolVersion":4,"sessionId":"s","seq":0,"events":[{"cmd":"com.debasish.livefit.model.SessionEvent.Started","tMs":1,"type":"Walk"}],"provenance":{"cmd":"com.debasish.livefit.model.Provenance.Fake"}}"""
        val d = Wire.decode<SessionDelta>(json)
        assertTrue(d.locations.isEmpty())
        assertFalse((d.events.single() as SessionEvent.Started).gps)
    }

    @Test fun snapshotCarriesGps() = roundTrip(WorkoutSnapshot(phase = WorkoutPhase.Active, sessionId = "s", gps = true))

    @Test fun settingsFrameCarriesPagesAndGestures() {
        val f = HudSettingsFrame(settings = HudSettings(), pages = PageSettings(disabled = setOf(HudPage.Map)), gestures = GestureSettings(idleTimeoutS = 8))
        roundTrip(f)
        assertEquals(GestureAction.EnterScroll, f.gestures.page.getValue(HudPage.Playlist).getValue(Gesture.Tap))
        assertTrue(Wire.encode(f).contains("\"Tap\":\"Talk\""), "the gesture table is a compact name → name map")
    }

    @Test fun workoutCannotBeDisabled() {
        val p = PageSettings(disabled = setOf(HudPage.Workout, HudPage.Stats))
        assertTrue(p.isEnabled(HudPage.Workout))
        assertFalse(p.isEnabled(HudPage.Stats))
        assertTrue(p.isEnabled(HudPage.Map))
    }

    @Test fun gestureDefaultsMatchSpecTable() {
        val d = GestureSettings()
        for (p in listOf(HudPage.Glance, HudPage.Workout, HudPage.Stats, HudPage.Map)) {
            assertEquals(
                mapOf(
                    Gesture.Tap to GestureAction.Talk, Gesture.DoubleTap to GestureAction.CloseApp,
                    Gesture.ShortForward to GestureAction.NextPage, Gesture.ShortBack to GestureAction.PreviousPage,
                    Gesture.LongForward to GestureAction.NextPage2, Gesture.LongBack to GestureAction.PreviousPage2,
                ),
                d.page.getValue(p), "page mode $p",
            )
        }
        for (p in listOf(HudPage.Playlist, HudPage.MusicControls)) assertEquals(GestureAction.EnterScroll, d.page.getValue(p).getValue(Gesture.Tap))
        assertEquals(
            mapOf(
                Gesture.Tap to GestureAction.PlayHighlighted, Gesture.DoubleTap to GestureAction.CloseApp,
                Gesture.ShortForward to GestureAction.HighlightNext, Gesture.ShortBack to GestureAction.HighlightPrevious,
                Gesture.LongForward to GestureAction.HighlightNext2, Gesture.LongBack to GestureAction.HighlightPrevious2,
            ),
            d.scroll.getValue(HudPage.Playlist),
        )
        assertEquals(
            mapOf(
                Gesture.Tap to GestureAction.PressSelected, Gesture.DoubleTap to GestureAction.CloseApp,
                Gesture.ShortForward to GestureAction.SelectorNext, Gesture.ShortBack to GestureAction.SelectorPrevious,
                Gesture.LongForward to GestureAction.VolumeUp, Gesture.LongBack to GestureAction.VolumeDown,
            ),
            d.scroll.getValue(HudPage.MusicControls),
        )
        assertEquals(setOf(HudPage.Playlist, HudPage.MusicControls), d.scroll.keys)
        assertEquals(5, d.idleTimeoutS)
        assertTrue(d.askBeforeClose)
    }

    @Test fun watchMessagesRoundTrip() {
        roundTrip(WatchSettingsFrame(pages = PageSettings(disabled = setOf(HudPage.Glance))))
        roundTrip(TimeSyncRequest(id = 7, t0 = 1_000))
        roundTrip(TimeSyncResponse(id = 7, t0 = 1_000, tw = 6_100))
    }

    @Test fun mapFramesParseOnlyCurrentVersion() {
        val epoch = MapFrame(kind = MapFrameKind.Epoch, renderEpoch = -42)
        val image = MapFrame(kind = MapFrameKind.Image, renderEpoch = -42, sessionId = "s", renderSeq = 3)
        assertEquals(epoch, MapFrame.parse(Wire.encode(epoch)))
        assertEquals(image, MapFrame.parse(Wire.encode(image)))
        assertNull(MapFrame.parse("""{"protocolVersion":3,"kind":"Epoch","renderEpoch":1}"""))
        assertNull(MapFrame.parse("garbage"))
    }

    @Test fun pageStateParsesOnlyCurrentVersion() {
        val s = PageState(page = HudPage.Map, seq = 9)
        assertEquals(s, PageState.parse(Wire.encode(s)))
        assertNull(PageState.parse("""{"protocolVersion":3,"page":"Map","seq":1}"""))
        assertNull(PageState.parse("""{"protocolVersion":4,"page":"Nope","seq":1}"""))
    }
}
