package com.debasish.livefit.glasses.hud

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Local time for the HUD status row (F4): "HH:mm" or "h:mm" per the device's 12/24-hour setting; glasses-local clock. */
object HudClock {
    private val h24 = DateTimeFormatter.ofPattern("HH:mm")
    private val h12 = DateTimeFormatter.ofPattern("h:mm")

    fun format(epochMs: Long, is24h: Boolean, zone: ZoneId = ZoneId.systemDefault()): String =
        (if (is24h) h24 else h12).format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** Delay until the minute changes, so the clock updates exactly once per minute. */
    fun msToNextMinute(epochMs: Long): Long = 60_000 - epochMs % 60_000
}
