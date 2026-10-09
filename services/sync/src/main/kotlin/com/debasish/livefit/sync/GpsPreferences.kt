package com.debasish.livefit.sync

import com.debasish.livefit.model.Wire
import com.debasish.livefit.model.WorkoutType
import java.io.File

/** The phone's last GPS choice per workout type, on disk so offline starts after a watch restart still honour it. */
class GpsPreferences(private val file: File) {
    /** Read on first use, not in the constructor: the watch builds this on the main thread (spec §6). */
    private val map: MutableMap<WorkoutType, Boolean> by lazy {
        runCatching { Wire.decode<Map<WorkoutType, Boolean>>(file.readText()).toMutableMap() }.getOrDefault(mutableMapOf())
    }

    /** Unknown type (never started from the phone) → off: step-based distance. */
    fun get(type: WorkoutType): Boolean = map[type] ?: false

    fun set(type: WorkoutType, on: Boolean) {
        if (map[type] == on) return
        map[type] = on
        try { // a failed write only loses the offline GPS default; it must never break a start
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(Wire.encode(map.toMap()))
            tmp.renameTo(file)
        } catch (e: java.io.IOException) {
            java.util.logging.Logger.getLogger("GpsPreferences").warning("gps.json write failed: $e")
        }
    }
}
