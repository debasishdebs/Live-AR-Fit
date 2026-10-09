package com.debasish.livefit.watch

import com.debasish.livefit.watch.HealthPermissions.ACTIVITY_RECOGNITION
import com.debasish.livefit.watch.HealthPermissions.BODY_SENSORS
import com.debasish.livefit.watch.HealthPermissions.READ_HEART_RATE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthPermissionsTest {
    @Test fun api30And33And35UseBodySensors() {
        for (sdk in listOf(30, 33, 35)) assertEquals(listOf(BODY_SENSORS, ACTIVITY_RECOGNITION), HealthPermissions.requiredHealthPermissions(sdk), "API $sdk")
    }

    @Test fun api36UsesTheGranularHeartRatePermission() =
        assertEquals(listOf(READ_HEART_RATE, ACTIVITY_RECOGNITION), HealthPermissions.requiredHealthPermissions(36))

    /** The old bug: requiring both on every OS meant a watch below API 36 could never start a workout. */
    @Test fun neverBothHeartRatePermissions() {
        for (sdk in 30..37) {
            val p = HealthPermissions.requiredHealthPermissions(sdk)
            assertFalse(BODY_SENSORS in p && READ_HEART_RATE in p, "API $sdk")
        }
    }

    @Test fun firstRequestIsHealthPlusNotificationsFrom33WithoutLocation() {
        assertEquals(listOf(BODY_SENSORS, ACTIVITY_RECOGNITION), HealthPermissions.runtimeRequest(30))
        assertEquals(listOf(BODY_SENSORS, ACTIVITY_RECOGNITION, HealthPermissions.POST_NOTIFICATIONS), HealthPermissions.runtimeRequest(33))
        assertEquals(listOf(READ_HEART_RATE, ACTIVITY_RECOGNITION, HealthPermissions.POST_NOTIFICATIONS), HealthPermissions.runtimeRequest(36))
        assertFalse(HealthPermissions.FINE_LOCATION in HealthPermissions.runtimeRequest(36), "location only after its disclosure")
        assertTrue(HealthPermissions.FINE_LOCATION in HealthPermissions.LOCATION)
    }
}
