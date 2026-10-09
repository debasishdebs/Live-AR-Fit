package com.debasish.livefit.model

import kotlinx.serialization.Serializable

@Serializable
enum class FixSource { Watch, Phone }

/**
 * One position fix. [fixTimeMs] is the fix's own time on the clock of the device that measured it (watch clock for watch
 * fixes); the phone maps watch times to phone time with its calibrated offset (spec §2.1). [accuracyM] null = the
 * platform reported no accuracy: unknown, which fails every accuracy gate (FixQuality, Task 3; review #8).
 */
@Serializable
data class LocationFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val bearingDeg: Float? = null,
    val fixTimeMs: Long,
)
