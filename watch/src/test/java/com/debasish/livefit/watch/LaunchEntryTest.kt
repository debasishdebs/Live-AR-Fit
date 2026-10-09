package com.debasish.livefit.watch

import com.debasish.livefit.model.DiscoverableRequest
import com.debasish.livefit.model.Wire
import kotlin.test.Test
import kotlin.test.assertEquals

class LaunchEntryTest {
    private val req = Wire.encode(DiscoverableRequest(seconds = 120))

    @Test fun workoutOnlyOpensTheUi() {
        assertEquals(LaunchEntry.OpenWorkoutUi, LaunchEntry.of("livefit", "workout", null, null))
        assertEquals(LaunchEntry.OpenWorkoutUi, LaunchEntry.of("livefit", "workout", "", null))
        // Query parameters never turn into commands: there is no entry that carries one.
        assertEquals(LaunchEntry.OpenWorkoutUi, LaunchEntry.of("livefit", "workout", "/", "start"))
    }

    @Test fun discoverableOnlyShowsThePromptForACurrentRequest() {
        assertEquals(LaunchEntry.ShowDiscoverablePrompt(120), LaunchEntry.of("livefit", "discoverable", null, req))
        assertEquals(LaunchEntry.ShowDiscoverablePrompt(300), LaunchEntry.of("livefit", "discoverable", null, Wire.encode(DiscoverableRequest(seconds = 9_999))))
        assertEquals(LaunchEntry.Ignore, LaunchEntry.of("livefit", "discoverable", null, null))
        assertEquals(LaunchEntry.Ignore, LaunchEntry.of("livefit", "discoverable", null, "{not json"))
        assertEquals(LaunchEntry.Ignore, LaunchEntry.of("livefit", "discoverable", null, """{"protocolVersion":1,"seconds":60}"""))
    }

    @Test fun unknownPathsHostsAndSchemesAreIgnored() {
        assertEquals(LaunchEntry.Ignore, LaunchEntry.of("livefit", "workout", "/start", null))
        assertEquals(LaunchEntry.Ignore, LaunchEntry.of("livefit", "workout", "/stop", null))
        assertEquals(LaunchEntry.Ignore, LaunchEntry.of("livefit", "takeover", null, null))
        assertEquals(LaunchEntry.Ignore, LaunchEntry.of("livefit", null, null, null))
        assertEquals(LaunchEntry.Ignore, LaunchEntry.of("https", "workout", null, null))
        assertEquals(LaunchEntry.Ignore, LaunchEntry.of(null, null, null, null))
    }

    @Test fun anOversizedRequestIsIgnoredWithoutParsing() =
        assertEquals(LaunchEntry.Ignore, LaunchEntry.of("livefit", "discoverable", null, req + " ".repeat(LaunchEntry.MAX_REQUEST_CHARS)))
}
