package com.debasish.livefit.sync

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Spec §6: the last uncaught crash (app version, thread, time, stack) in app-private storage. Nothing is sent
 * anywhere: the phone's Settings → About → "Share last crash" hands it to a share sheet. The previously installed
 * handler still runs afterwards, so the process dies exactly as before.
 */
class CrashLog(private val file: File, private val version: String, private val nowMs: () -> Long = System::currentTimeMillis) {
    /** Idempotent: a second install (another entry point of the same process) keeps the first. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(this, previous))
    }

    fun record(thread: Thread, error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = "Live AR Fit $version\nthread: ${thread.name}\ntime: ${Instant.ofEpochMilli(nowMs())}\n\n$stack"
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text.take(MAX_CHARS))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun last(): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()

    fun clear() { file.delete() }

    private class Handler(private val log: CrashLog, private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try { log.record(t, e) } catch (_: Throwable) { /* never mask the original crash */ }
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        const val FILE_NAME = "last-crash.txt"
        const val MAX_CHARS = 64 * 1024
    }
}
