package com.debasish.livefit.glasses.hud

import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class HudClockTest {
    private val utc = ZoneOffset.UTC
    private val t1405 = 14L * 3_600_000 + 5 * 60_000 + 59_000 // 14:05:59 UTC, 1 Jan 1970

    @Test fun followsTheDevice24HourSetting() {
        assertEquals("14:05", HudClock.format(t1405, is24h = true, zone = utc))
        assertEquals("2:05", HudClock.format(t1405, is24h = false, zone = utc))
        assertEquals("00:30", HudClock.format(30 * 60_000L, is24h = true, zone = utc))
        assertEquals("12:30", HudClock.format(30 * 60_000L, is24h = false, zone = utc))
    }

    @Test fun usesTheLocalZone() {
        assertEquals("19:35", HudClock.format(t1405, is24h = true, zone = ZoneOffset.ofHoursMinutes(5, 30)))
    }

    @Test fun nextTickIsTheStartOfTheNextMinute() {
        assertEquals(1_000L, HudClock.msToNextMinute(t1405))
        assertEquals(60_000L, HudClock.msToNextMinute(60_000L))
    }
}
