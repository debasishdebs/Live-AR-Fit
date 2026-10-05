package com.debasish.livefit.watch

import com.debasish.livefit.services.MusicService
import com.debasish.livefit.services.WorkoutService

/**
 * Watch-side wiring. The phone is the hub, so workout and music are the phone's (via [PhoneHub]).
 * Live sensors later: the watch's Health Services source feeds the phone's workout, not a local one.
 */
object WatchGraph {
    val workout: WorkoutService get() = PhoneHub.workout
    val music: MusicService get() = PhoneHub.music
}
