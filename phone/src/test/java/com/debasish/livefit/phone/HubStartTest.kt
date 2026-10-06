package com.debasish.livefit.phone

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Review #13: the connectedDevice foreground service needs a runtime prerequisite on Android 14+. */
class HubStartTest {
    @Test fun belowAndroid14NoPrerequisiteIsNeeded() {
        assertTrue(LiveFitHubService.canStart(sdk = 33, bluetoothGranted = false, hasAssociation = false))
    }

    @Test fun android14NeedsBluetoothOrCompanionAssociation() {
        assertFalse(LiveFitHubService.canStart(sdk = 34, bluetoothGranted = false, hasAssociation = false))
        assertTrue(LiveFitHubService.canStart(sdk = 34, bluetoothGranted = true, hasAssociation = false))
        assertTrue(LiveFitHubService.canStart(sdk = 35, bluetoothGranted = false, hasAssociation = true))
    }

    @Test fun ensureRunningStartsOnlyWhenStoppedAndAllowed() {
        assertTrue(LiveFitHubService.shouldStart(running = false, canStart = true))
        assertFalse(LiveFitHubService.shouldStart(running = true, canStart = true), "idempotent")
        assertFalse(LiveFitHubService.shouldStart(running = false, canStart = false), "would only fail and stop again")
    }

    /** F1: after an update or reboot the hub reconnects the glasses once when they are linked and nearby. */
    @Test fun hubStartConnectsLinkedNearbyGlassesOnce() {
        assertTrue(LiveFitHubService.shouldConnectGlasses(linkIdle = true, associated = true, present = true, btConnected = false))
        assertTrue(LiveFitHubService.shouldConnectGlasses(linkIdle = true, associated = true, present = false, btConnected = true))
        assertFalse(LiveFitHubService.shouldConnectGlasses(linkIdle = true, associated = true, present = false, btConnected = false), "not nearby")
        assertFalse(LiveFitHubService.shouldConnectGlasses(linkIdle = true, associated = false, present = true, btConnected = true), "not linked")
        assertFalse(LiveFitHubService.shouldConnectGlasses(linkIdle = false, associated = true, present = true, btConnected = true), "already connecting (app opened)")
    }
}
