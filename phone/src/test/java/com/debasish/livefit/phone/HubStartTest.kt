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
}
