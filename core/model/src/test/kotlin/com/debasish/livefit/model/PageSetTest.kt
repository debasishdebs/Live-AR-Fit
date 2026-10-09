package com.debasish.livefit.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageSetTest {
    private val all = PageSet.available(PageSettings(), mapEligible = true)

    @Test fun orderIsGlanceWorkoutStatsPlaylistMapMusicControls() = assertEquals(
        listOf(HudPage.Glance, HudPage.Workout, HudPage.Stats, HudPage.Playlist, HudPage.Map, HudPage.MusicControls), all,
    )

    @Test fun workoutCannotBeDisabled() =
        assertTrue(HudPage.Workout in PageSet.available(PageSettings(disabled = HudPage.entries.toSet()), mapEligible = true))

    /** Spec §3.1: the Map page is skipped when no GPS workout is active. */
    @Test fun mapSkippedWithoutGpsWorkout() {
        assertFalse(HudPage.Map in PageSet.available(PageSettings(), mapEligible = false))
        assertFalse(HudPage.Map in PageSet.available(PageSettings(disabled = setOf(HudPage.Map)), mapEligible = true))
    }

    @Test fun mapEligibleOnlyWhileAGpsWorkoutRecords() {
        assertTrue(PageSet.mapEligible(WorkoutSnapshot(phase = WorkoutPhase.Active, gps = true)))
        assertTrue(PageSet.mapEligible(WorkoutSnapshot(phase = WorkoutPhase.Paused, gps = true)))
        assertFalse(PageSet.mapEligible(WorkoutSnapshot(phase = WorkoutPhase.Active, gps = false)))
        assertFalse(PageSet.mapEligible(WorkoutSnapshot(phase = WorkoutPhase.Stopping, gps = true)))
        assertFalse(PageSet.mapEligible(WorkoutSnapshot(phase = WorkoutPhase.Summary, gps = true)))
    }

    @Test fun stepCyclesBothWays() {
        assertEquals(HudPage.Glance, PageSet.step(HudPage.MusicControls, 1, all))
        assertEquals(HudPage.MusicControls, PageSet.step(HudPage.Glance, -1, all))
        assertEquals(HudPage.Glance, PageSet.step(HudPage.Map, 2, all))
        assertEquals(HudPage.Map, PageSet.step(HudPage.Glance, -2, all))
    }

    @Test fun stepSkipsDisabledPages() {
        val pages = PageSet.available(PageSettings(disabled = setOf(HudPage.Stats)), mapEligible = false)
        assertEquals(HudPage.Playlist, PageSet.step(HudPage.Workout, 1, pages))
        assertEquals(HudPage.MusicControls, PageSet.step(HudPage.Playlist, 1, pages), "Map skipped")
    }

    /** Spec §3.3: a visible page that becomes unavailable switches to Workout. */
    @Test fun unavailablePageFallsBackToWorkout() {
        val noStats = PageSet.available(PageSettings(disabled = setOf(HudPage.Stats)), mapEligible = true)
        assertEquals(HudPage.Workout, PageSet.resolve(HudPage.Stats, noStats))
        assertEquals(HudPage.Workout, PageSet.resolve(HudPage.Map, PageSet.available(PageSettings(), mapEligible = false)))
        assertEquals(HudPage.Playlist, PageSet.resolve(HudPage.Playlist, noStats))
        assertEquals(HudPage.Workout, PageSet.step(HudPage.Stats, 1, noStats), "stepping from a vanished page lands on Workout")
    }
}
