package com.debasish.livefit.sync

import com.debasish.livefit.model.Wire
import com.debasish.livefit.model.WorkoutType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpsPreferencesLazyTest {
    /** Spec §6: constructing it (main thread, WatchRuntime.init) must not read the disk. */
    @Test fun theFileIsReadOnFirstUseNotInTheConstructor() {
        val f = Files.createTempDirectory("gps").toFile().resolve("gps.json")
        val prefs = GpsPreferences(f)
        f.writeText(Wire.encode(mapOf(WorkoutType.Run to true)))
        assertTrue(prefs.get(WorkoutType.Run), "read after construction")
        assertFalse(prefs.get(WorkoutType.Walk))
    }
}
