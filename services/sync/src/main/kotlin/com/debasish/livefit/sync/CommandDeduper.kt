package com.debasish.livefit.sync

/** Applies each command id once (spec §4.5). Bounded memory. */
class CommandDeduper(private val capacity: Int = 256) {
    private val seen = LinkedHashSet<String>()

    @Synchronized
    fun firstTime(id: String): Boolean {
        if (!seen.add(id)) return false
        if (seen.size > capacity) seen.remove(seen.first())
        return true
    }
}
