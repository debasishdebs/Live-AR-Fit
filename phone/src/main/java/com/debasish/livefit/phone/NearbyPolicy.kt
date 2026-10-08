package com.debasish.livefit.phone

import com.debasish.livefit.model.DeviceKind

/**
 * Pure rules for Settings → Nearby devices (R2, spec §5.2): per companion-paired device, "Start LiveFit when nearby".
 * Enabled → Android observes its presence and an appearance starts the hub; disabled → presence observation is
 * stopped and any late event for it is ignored. Default: enabled for every device.
 */
object NearbyPolicy {
    data class Observation(val observe: Set<Int>, val stopObserving: Set<Int>)

    /** [paired] maps each paired kind to its association id; unpaired kinds are absent. */
    fun observation(paired: Map<DeviceKind, Int>, enabled: (DeviceKind) -> Boolean): Observation {
        val (on, off) = paired.entries.partition { enabled(it.key) }
        return Observation(on.map { it.value }.toSet(), off.map { it.value }.toSet())
    }

    enum class Hub { Start, Stop, Keep, Ignore }

    /**
     * One presence event for an association. [present] is its merged presence after recording the event,
     * [anyPresent] whether any enabled device is still present, [workoutIdle] whether no workout is running.
     */
    fun onPresence(enabled: Boolean, present: Boolean, workoutIdle: Boolean, anyPresent: Boolean): Hub = when {
        !enabled -> Hub.Ignore
        present -> Hub.Start
        workoutIdle && !anyPresent -> Hub.Stop
        else -> Hub.Keep
    }

    fun subtitle(paired: Boolean, enabled: Boolean): String =
        if (!paired) "Not paired — pair in Linked services" else "Start LiveFit when nearby · " + if (enabled) "On" else "Off"
}
