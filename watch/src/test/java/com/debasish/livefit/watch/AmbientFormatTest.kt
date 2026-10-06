package com.debasish.livefit.watch

import com.debasish.livefit.watch.ui.ambientElapsed
import kotlin.test.Test
import kotlin.test.assertEquals

/** B1: the always-on screen refreshes about once a minute, so it shows whole minutes, never a frozen seconds count. */
class AmbientFormatTest {
    @Test fun wholeMinutesBelowAnHour() {
        assertEquals("0 min", ambientElapsed(59_999))
        assertEquals("12 min", ambientElapsed(12 * 60_000L + 59_000))
    }

    @Test fun hoursAndMinutesFromAnHour() {
        assertEquals("1 h 05 min", ambientElapsed(65 * 60_000L))
    }
}
