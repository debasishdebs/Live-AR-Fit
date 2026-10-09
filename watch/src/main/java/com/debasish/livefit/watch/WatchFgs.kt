package com.debasish.livefit.watch

import android.content.pm.ServiceInfo

/** Foreground-service types for the exercise service (spec §2.1): `health`, plus `location` for a GPS workout with permission. */
object WatchFgs {
    fun types(gpsWorkout: Boolean, fineLocationGranted: Boolean): Int =
        ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or if (gpsWorkout && fineLocationGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
}
