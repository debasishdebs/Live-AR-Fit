package com.debasish.livefit.watch

import com.debasish.livefit.model.PageSettings
import com.debasish.livefit.model.Wire
import java.io.File
import java.io.IOException

/** The last page set from the phone, kept so offline workouts and restarts show the same pages (spec §3.2). */
class PageSettingsFile(private val file: File) {
    fun load(): PageSettings = runCatching { Wire.decode<PageSettings>(file.readText()) }.getOrDefault(PageSettings())

    fun save(p: PageSettings) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(Wire.encode(p))
            tmp.renameTo(file)
        } catch (e: IOException) {
            android.util.Log.w(WatchRuntime.TAG, "pages.json write failed", e)
        }
    }
}
