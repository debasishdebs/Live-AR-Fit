package com.debasish.livefit.phone

import android.content.pm.ServiceInfo

/** Phone GPS fallback rules that don't need a device (spec §2.1). */
object HubLocationPolicy {
    /** connectedDevice always; location only while LiveFit's Activity is visible and fine location is granted. */
    fun fgsTypes(fineGranted: Boolean, activityVisible: Boolean): Int =
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or if (fineGranted && activityVisible) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0

    /** Settings → Linked services row. */
    fun phoneGpsLabel(fineGranted: Boolean, hubHasLocation: Boolean): String = when {
        !fineGranted -> "Phone GPS off"
        !hubHasLocation -> "Phone GPS available after opening LiveFit"
        else -> "Phone GPS ready (map fallback)"
    }

    /** LocationManager.FUSED_PROVIDER ("fused", API 31+) when present, else GPS_PROVIDER ("gps"). */
    fun providerFor(sdk: Int, hasFused: Boolean): String = if (sdk >= 31 && hasFused) "fused" else "gps"

    /** The fix's wall-clock time from its own elapsed-realtime stamp (Location.time may be the GNSS clock). */
    fun fixTimeMs(nowMs: Long, nowElapsedNanos: Long, fixElapsedNanos: Long): Long = nowMs - (nowElapsedNanos - fixElapsedNanos) / 1_000_000
}
