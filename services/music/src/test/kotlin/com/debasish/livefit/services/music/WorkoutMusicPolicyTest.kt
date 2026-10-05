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
}
