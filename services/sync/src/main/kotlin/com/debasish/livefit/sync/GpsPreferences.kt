package com.debasish.livefit.sync

import com.debasish.livefit.model.Wire
import com.debasish.livefit.model.WorkoutType
import java.io.File

/** The phone's last GPS choice per workout type, on disk so offline starts after a watch restart still honour it. */
class GpsPreferences(private val file: File) {
    private val map: MutableMap<WorkoutType, Boolean> =
        runCatching { Wire.decode<Map<WorkoutType, Boolean>>(file.readText()).toMutableMap() }.getOrDefault(mutableMapOf())

    /** Unknown type (never started from the phone) → off: step-based distance. */
    fun get(type: WorkoutType): Boolean = map[type] ?: false

    fun set(type: WorkoutType, on: Boolean) {
        if (map[type] == on) return
        map[type] = on
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(Wire.encode(map.toMap()))
        tmp.renameTo(file)
    }
}
