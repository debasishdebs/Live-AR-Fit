package com.debasish.livefit.sync

import com.debasish.livefit.model.LocationFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Watch-side full-session route (spec §2.6): `root/<sessionId>/route.bin`, append-only 32-byte records
 * (lat, lon: Double; accuracy (NaN = unknown), bearing (NaN = none): Float; fix time: Long), independent of delta
 * resend retention. Deleted once the session is finalized on the phone (final ack, [markAcked]) and the watch summary is
 * dismissed ([markDismissed]). A torn trailing record after process death is ignored on load and cut off before the
 * next append, so later records stay aligned (review #4).
 */
class WatchRouteFile(private val root: File) {
    data class SessionRoute(val sessionId: String, val fixes: List<LocationFix>)

    private val _route = MutableStateFlow<SessionRoute?>(null)
    /** The route of the session most recently opened or appended to. */
    val route: StateFlow<SessionRoute?> = _route

    init { root.mkdirs() }

    @Synchronized
    fun open(sessionId: String) { _route.value = SessionRoute(sessionId, load(sessionId)) }

    @Synchronized
    fun append(sessionId: String, fixes: List<LocationFix>) {
        if (fixes.isEmpty()) return
        try {
            val f = file(sessionId)
            f.parentFile?.mkdirs()
            RandomAccessFile(f, "rw").use { raf ->
                val whole = raf.length() / RECORD * RECORD
                if (whole != raf.length()) raf.setLength(whole) // drop the torn tail first (review #4)
                raf.seek(whole)
                raf.write(encode(fixes))
            }
        } catch (e: IOException) {
            java.util.logging.Logger.getLogger("WatchRouteFile").warning("route append failed: $e") // the deltas still carry the fixes
        }
        val cur = _route.value
        _route.value = if (cur?.sessionId == sessionId) cur.copy(fixes = cur.fixes + fixes) else SessionRoute(sessionId, load(sessionId))
    }

    @Synchronized
    fun load(sessionId: String): List<LocationFix> {
        val f = file(sessionId)
        if (!f.exists()) return emptyList()
        val bytes = try { f.readBytes() } catch (e: IOException) { return emptyList() }
        val buf = ByteBuffer.wrap(bytes)
        val out = ArrayList<LocationFix>(bytes.size / RECORD)
        while (buf.remaining() >= RECORD) {
            val lat = buf.double; val lon = buf.double; val acc = buf.float; val bearing = buf.float; val t = buf.long
            out += LocationFix(lat, lon, acc.takeUnless { it.isNaN() }, bearing.takeUnless { it.isNaN() }, t)
        }
        return out
    }

    fun markAcked(sessionId: String) = mark(sessionId, ACKED)
    fun markDismissed(sessionId: String) = mark(sessionId, DISMISSED)

    /** At process start: delete routes whose session is already finalized and dismissed. */
    @Synchronized
    fun sweep() { root.listFiles { f -> f.isDirectory }.orEmpty().forEach { deleteIfDone(it.name) } }

    @Synchronized
    private fun mark(sessionId: String, name: String) {
        val dir = File(root, sessionId)
        if (!dir.exists()) return // no GPS for that session: nothing kept
        try { File(dir, name).writeText("") } catch (e: IOException) { return }
        deleteIfDone(sessionId)
    }

    private fun deleteIfDone(sessionId: String) {
        val dir = File(root, sessionId)
        if (File(dir, ACKED).exists() && File(dir, DISMISSED).exists()) {
            dir.deleteRecursively()
            if (_route.value?.sessionId == sessionId) _route.value = null
        }
    }

    private fun file(sessionId: String) = File(File(root, sessionId), "route.bin")

    private fun encode(fixes: List<LocationFix>): ByteArray {
        val b = ByteBuffer.allocate(RECORD * fixes.size)
        for (f in fixes) b.putDouble(f.lat).putDouble(f.lon).putFloat(f.accuracyM ?: Float.NaN).putFloat(f.bearingDeg ?: Float.NaN).putLong(f.fixTimeMs)
        return b.array()
    }

    companion object {
        const val RECORD = 32
        const val ACKED = "acked"
        const val DISMISSED = "dismissed"
    }
}
