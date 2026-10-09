package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.NowPlaying
import kotlin.test.Test
import kotlin.test.assertEquals

class MusicControlsModelTest {
    @Test fun progressIsAClampedFraction() {
        assertEquals(0f, MusicControlsModel.progress(null))
        assertEquals(0f, MusicControlsModel.progress(NowPlaying("t", "a", true, positionMs = 10, durationMs = 0)))
        assertEquals(0.25f, MusicControlsModel.progress(NowPlaying("t", "a", true, positionMs = 30_000, durationMs = 120_000)))
        assertEquals(1f, MusicControlsModel.progress(NowPlaying("t", "a", true, positionMs = 200_000, durationMs = 120_000)))
    }
}
