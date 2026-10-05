package com.debasish.livefit.glasses.hud

import kotlin.test.Test
import kotlin.test.assertEquals

class HudConnectionTest {
    @Test fun connectingForFirst12sThenOpenPhoneApp() {
        assertEquals(HudConnection.Connecting, connectionFor(hasEverReceived = false, online = false, outdated = false, sinceStartMs = 11_000))
        assertEquals(HudConnection.OpenPhoneApp, connectionFor(hasEverReceived = false, online = false, outdated = false, sinceStartMs = 12_000))
    }
    @Test fun liveWhileFramesArrive() = assertEquals(HudConnection.Live, connectionFor(true, online = true, outdated = false, sinceStartMs = 60_000))
    @Test fun lostAfterFramesShowsConnectingNotOpenApp() =
        assertEquals(HudConnection.Connecting, connectionFor(hasEverReceived = true, online = false, outdated = false, sinceStartMs = 60_000))
    @Test fun outdatedWins() = assertEquals(HudConnection.Outdated, connectionFor(true, online = true, outdated = true, sinceStartMs = 1))
}
