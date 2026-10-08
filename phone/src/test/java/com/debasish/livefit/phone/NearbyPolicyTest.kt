package com.debasish.livefit.phone

import com.debasish.livefit.model.DeviceKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** R2 "Nearby devices": which associations are observed and what a presence event does to the hub (spec §5.2). */
class NearbyPolicyTest {
    private val paired = mapOf(DeviceKind.Glasses to 7, DeviceKind.Watch to 9)

    @Test fun defaultObservesEveryPairedDevice() {
        val plan = NearbyPolicy.observation(paired) { true }
        assertEquals(setOf(7, 9), plan.observe)
        assertEquals(emptySet(), plan.stopObserving)
    }

    @Test fun disabledDeviceIsStoppedAndUnpairedIsSkipped() {
        val plan = NearbyPolicy.observation(mapOf(DeviceKind.Glasses to 7)) { it != DeviceKind.Glasses }
        assertEquals(emptySet(), plan.observe)
        assertEquals(setOf(7), plan.stopObserving)
    }

    @Test fun enabledAppearStartsTheHub() {
        assertEquals(NearbyPolicy.Hub.Start, NearbyPolicy.onPresence(enabled = true, present = true, workoutIdle = true, anyPresent = true))
        assertEquals(NearbyPolicy.Hub.Start, NearbyPolicy.onPresence(enabled = true, present = true, workoutIdle = false, anyPresent = true))
    }

    @Test fun disabledDeviceEventsAreIgnored() {
        assertEquals(NearbyPolicy.Hub.Ignore, NearbyPolicy.onPresence(enabled = false, present = true, workoutIdle = true, anyPresent = true))
        assertEquals(NearbyPolicy.Hub.Ignore, NearbyPolicy.onPresence(enabled = false, present = false, workoutIdle = true, anyPresent = false))
    }

    @Test fun lastEnabledDeviceGoneStopsAnIdleHub() {
        assertEquals(NearbyPolicy.Hub.Stop, NearbyPolicy.onPresence(enabled = true, present = false, workoutIdle = true, anyPresent = false))
    }

    @Test fun goneKeepsTheHubDuringAWorkoutOrWhileAnotherDeviceIsNearby() {
        assertEquals(NearbyPolicy.Hub.Keep, NearbyPolicy.onPresence(enabled = true, present = false, workoutIdle = false, anyPresent = false))
        assertEquals(NearbyPolicy.Hub.Keep, NearbyPolicy.onPresence(enabled = true, present = false, workoutIdle = true, anyPresent = true))
    }

    @Test fun unpairedDeviceRowSaysToPairInLinkedServices() {
        assertEquals("Not paired — pair in Linked services", NearbyPolicy.subtitle(paired = false, enabled = true))
        assertEquals("Start LiveFit when nearby · On", NearbyPolicy.subtitle(paired = true, enabled = true))
        assertEquals("Start LiveFit when nearby · Off", NearbyPolicy.subtitle(paired = true, enabled = false))
    }
}
