package com.debasish.livefit.watch

/**
 * Spec §4 (review P1-1): the heart-rate permission depends on the OS. API 36+ (Wear OS 6) uses the granular
 * `android.permission.health.READ_HEART_RATE`; up to API 35 it is `BODY_SENSORS` (declared with maxSdkVersion 35).
 * One list drives both the runtime request (MainActivity) and the backend's missing-permission check. Pure.
 */
object HealthPermissions {
    const val BODY_SENSORS = "android.permission.BODY_SENSORS"
    const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
    const val ACTIVITY_RECOGNITION = "android.permission.ACTIVITY_RECOGNITION"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    const val GRANULAR_HEALTH_SDK = 36

    /** What an exercise needs before it may start (location is optional: GPS workouts only). */
    fun requiredHealthPermissions(sdkInt: Int): List<String> =
        listOf(if (sdkInt >= GRANULAR_HEALTH_SDK) READ_HEART_RATE else BODY_SENSORS, ACTIVITY_RECOGNITION)

    /** MainActivity's first request: the health set and notifications (API 33+). */
    fun runtimeRequest(sdkInt: Int): List<String> = requiredHealthPermissions(sdkInt) + listOfNotNull(POST_NOTIFICATIONS.takeIf { sdkInt >= 33 })

    /** Asked separately, only after the location disclosure (spec §4). */
    val LOCATION = listOf(FINE_LOCATION, COARSE_LOCATION)
}
