package com.debasish.livefit.phone.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ThrottleTest {
    @Test fun allowsAtMostOnePerInterval() {
        var t = 0L
        val th = Throttle(100) { t }
        val allowed = (0 until 30).count { t = it * 10L; th.allow() }
        assertEquals(3, allowed) // t = 0, 100, 200 within 0..290 ms
    }
}
