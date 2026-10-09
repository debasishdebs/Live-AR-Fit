package com.debasish.livefit.sync

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CrashLogTest {
    private val dir = Files.createTempDirectory("crash").toFile()
    private lateinit var saved: Thread.UncaughtExceptionHandler
    private val seen = mutableListOf<Throwable>()

    @BeforeTest fun up() {
        saved = Thread.getDefaultUncaughtExceptionHandler() ?: Thread.UncaughtExceptionHandler { _, _ -> }
        Thread.setDefaultUncaughtExceptionHandler { _, e -> seen += e }
    }

    @AfterTest fun down() = Thread.setDefaultUncaughtExceptionHandler(saved)

    @Test fun recordsVersionThreadAndStack() {
        val log = CrashLog(dir.resolve(CrashLog.FILE_NAME), "1.0.0", nowMs = { 0 })
        assertNull(log.last())
        log.record(Thread.currentThread(), IllegalStateException("boom"))
        val text = log.last()!!
        assertTrue(text.startsWith("Live AR Fit 1.0.0\nthread: "), text)
        assertTrue("time: 1970-01-01T00:00:00Z" in text)
        assertTrue("java.lang.IllegalStateException: boom" in text)
        log.clear()
        assertNull(log.last())
    }

    @Test fun aHugeStackIsCapped() {
        val log = CrashLog(dir.resolve(CrashLog.FILE_NAME), "1.0.0")
        log.record(Thread.currentThread(), RuntimeException("x".repeat(200_000)))
        assertEquals(CrashLog.MAX_CHARS, log.last()!!.length)
    }

    @Test fun installChainsToThePreviousHandlerOnce() {
        val log = CrashLog(dir.resolve(CrashLog.FILE_NAME), "1.0.0")
        log.install()
        val installed = Thread.getDefaultUncaughtExceptionHandler()
        log.install()
        assertSame(installed, Thread.getDefaultUncaughtExceptionHandler(), "idempotent")
        val e = RuntimeException("crash")
        installed!!.uncaughtException(Thread.currentThread(), e)
        assertEquals(listOf<Throwable>(e), seen, "the platform handler still runs (process dies as before)")
        assertTrue("RuntimeException: crash" in log.last()!!)
    }

    @Test fun aFailingWriteNeverMasksTheCrash() {
        val blocked = dir.resolve("blocked").also { it.mkdirs(); it.resolve(CrashLog.FILE_NAME + ".tmp").mkdirs() }
        val log = CrashLog(blocked.resolve(CrashLog.FILE_NAME), "1.0.0")
        log.install()
        val e = RuntimeException("crash")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), e)
        assertEquals(listOf<Throwable>(e), seen)
    }
}
