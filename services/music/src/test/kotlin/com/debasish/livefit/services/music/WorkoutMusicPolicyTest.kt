package com.debasish.livefit.services.music

import com.debasish.livefit.model.WorkoutPhase
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkoutMusicPolicyTest {
    private fun act(from: WorkoutPhase, to: WorkoutPhase, start: MusicOnStart = MusicOnStart.Resume, pauseOnStop: Boolean = true) =
        WorkoutMusicPolicy.actionFor(from, to, start, pauseOnStop)

    @Test fun startResumesByDefault() = assertEquals(MusicAction.Resume, act(WorkoutPhase.Starting, WorkoutPhase.Active))
    @Test fun startCanPlaySearchOrDoNothing() {
        assertEquals(MusicAction.PlaySearch, act(WorkoutPhase.Starting, WorkoutPhase.Active, MusicOnStart.PlaySearch))
        assertEquals(MusicAction.None, act(WorkoutPhase.Starting, WorkoutPhase.Active, MusicOnStart.DontTouch))
    }
    @Test fun pauseDoesNotTouchMusic() = assertEquals(MusicAction.None, act(WorkoutPhase.Active, WorkoutPhase.Paused))
    @Test fun resumeFromPauseDoesNotTouchMusic() = assertEquals(MusicAction.None, act(WorkoutPhase.Paused, WorkoutPhase.Active))
    @Test fun stopPausesMusicByDefault() {
        assertEquals(MusicAction.Pause, act(WorkoutPhase.Active, WorkoutPhase.Stopping))
        assertEquals(MusicAction.None, act(WorkoutPhase.Active, WorkoutPhase.Stopping, pauseOnStop = false))
    }
    @Test fun stopStraightToSummaryPausesMusic() {
        for (from in listOf(WorkoutPhase.Active, WorkoutPhase.Paused, WorkoutPhase.Syncing)) {
            assertEquals(MusicAction.Pause, act(from, WorkoutPhase.Summary))
            assertEquals(MusicAction.None, act(from, WorkoutPhase.Summary, pauseOnStop = false))
        }
    }
    @Test fun syncingAdoptionDoesNotStartMusic() = assertEquals(MusicAction.None, act(WorkoutPhase.Syncing, WorkoutPhase.Active))

    @Test fun resumeQueryUsesSavedSearchOrNullWhenBlank() {
        assertEquals("workout mix", resumeQuery("  workout mix "))
        assertEquals(null, resumeQuery(""))
        assertEquals(null, resumeQuery(null))
    }

    /** D5: a search plays through the session (no UI) whenever YouTube Music has one that supports it. */
    @Test fun searchUsesTheSessionWhenAvailable() {
        assertEquals(SearchRoute.Session, SearchRoute.of(hasSession = true, appInForeground = true))
        assertEquals(SearchRoute.Session, SearchRoute.of(hasSession = true, appInForeground = false))
    }
    @Test fun searchUsesTheActivityOnlyWhileLiveFitIsInFront() =
        assertEquals(SearchRoute.ActivityThenReturn, SearchRoute.of(hasSession = false, appInForeground = true))

    /** B2: Android blocks a background activity start, so with no session (phone in pocket) playback starts without UI. */
    @Test fun noSessionInTheBackgroundStartsHeadless() =
        assertEquals(SearchRoute.Headless, SearchRoute.of(hasSession = false, appInForeground = false))

    /** B2: once the headless start brings YouTube Music's session up, the saved search (if any) is applied on it. */
    @Test fun headlessStartAppliesTheSearchOnceTheSessionAppears() {
        assertEquals(HeadlessStep.PlayFromSearch("workout mix"), HeadlessStep.onSession(query = "workout mix", isPlaying = true))
        assertEquals(HeadlessStep.PlayFromSearch("workout mix"), HeadlessStep.onSession(query = "workout mix", isPlaying = false))
    }

    @Test fun headlessResumeOnlyPressesPlayIfTheResumedSessionIsNotPlaying() {
        assertEquals(HeadlessStep.Play, HeadlessStep.onSession(query = null, isPlaying = false))
        assertEquals(HeadlessStep.Done, HeadlessStep.onSession(query = null, isPlaying = true))
    }
}
