package com.debasish.livefit.phone

import android.content.pm.ServiceInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class HubLocationPolicyTest {
    /** Spec §2.1: a hub started from the background can't hold location; the visible Activity re-promotes it. */
    @Test fun hubHoldsLocationOnlyWhenVisibleAndGranted() {
        val cd = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val loc = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        assertEquals(cd or loc, HubLocationPolicy.fgsTypes(fineGranted = true, activityVisible = true))
        assertEquals(cd, HubLocationPolicy.fgsTypes(fineGranted = true, activityVisible = false))
        assertEquals(cd, HubLocationPolicy.fgsTypes(fineGranted = false, activityVisible = true), "denial: connectedDevice only")
    }

    @Test fun phoneGpsStatusLabels() {
        assertEquals("Phone GPS off", HubLocationPolicy.phoneGpsLabel(fineGranted = false, hubHasLocation = false))
        assertEquals("Phone GPS available after opening LiveFit", HubLocationPolicy.phoneGpsLabel(fineGranted = true, hubHasLocation = false))
        assertEquals("Phone GPS ready (map fallback)", HubLocationPolicy.phoneGpsLabel(fineGranted = true, hubHasLocation = true))
    }

    @Test fun fusedProviderOnAndroid12Plus() {
        assertEquals("fused", HubLocationPolicy.providerFor(31, hasFused = true))
        assertEquals("gps", HubLocationPolicy.providerFor(31, hasFused = false))
        assertEquals("gps", HubLocationPolicy.providerFor(30, hasFused = true))
    }

    @Test fun fixTimeUsesTheFixsOwnElapsedTime() =
        assertEquals(9_500L, HubLocationPolicy.fixTimeMs(nowMs = 10_000, nowElapsedNanos = 5_000_000_000, fixElapsedNanos = 4_500_000_000))
}
