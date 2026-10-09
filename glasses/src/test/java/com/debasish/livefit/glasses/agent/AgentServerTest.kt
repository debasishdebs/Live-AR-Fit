package com.debasish.livefit.glasses.agent

import com.debasish.livefit.model.Command
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Real HTTP against the receiver on an ephemeral loopback port, with a fake dispatcher. */
class AgentServerTest {
    private val sent = Collections.synchronizedList(mutableListOf<Command>())
    @Volatile private var connected = true
    private val endpoint = AgentEndpoint(
        context = { AgentContext(connected = connected, mapEligible = true) },
        send = { sent += it; true },
    )
    private val server = AgentServer(port = 0, handle = endpoint::handle, readTimeoutMs = 500, maxConnections = 2)

    private fun started(): Int { assertTrue(server.start().awaitBound(2_000), "server bound"); return server.localPort }

    @AfterTest fun tearDown() = server.stop()

    private fun http(path: String): Pair<Int, String> {
        val c = URL("http://127.0.0.1:${server.localPort}$path").openConnection() as HttpURLConnection
        c.connectTimeout = 2_000; c.readTimeout = 3_000
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        assertEquals("application/json; charset=utf-8", c.getHeaderField("Content-Type"))
        c.disconnect()
        return code to body
    }

    private fun raw(port: Int, bytes: ByteArray): String = Socket(InetAddress.getLoopbackAddress(), port).use { s ->
        s.soTimeout = 3_000
        s.getOutputStream().write(bytes); s.getOutputStream().flush()
        s.getInputStream().readBytes().decodeToString()
    }

    @Test fun bindsToLoopbackOnly() {
        started()
        assertTrue(server.boundAddress!!.isLoopbackAddress, "bound to ${server.boundAddress}")
    }

    @Test fun knownCommandIsDispatchedAndAcknowledged() {
        started()
        assertEquals(200 to """{"ok":true,"say":"Workout paused"}""", http("/lf?cmd=pause&v=1"))
        assertEquals(listOf<Command>(Command.PauseWorkout), sent.toList())
    }

    @Test fun unknownCommandIs400() {
        started()
        assertEquals(400 to """{"ok":false,"say":"I can't do that in LiveFit yet"}""", http("/lf?cmd=dance"))
        assertEquals(400, http("/lf").first)
        assertTrue(sent.isEmpty())
    }

    @Test fun phoneNotConnectedIs503() {
        started()
        connected = false
        assertEquals(503 to """{"ok":false,"say":"Your phone isn't connected"}""", http("/lf?cmd=next"))
        assertTrue(sent.isEmpty())
    }

    @Test fun garbageGets400AndTheServerKeepsWorking() {
        val port = started()
        assertTrue(raw(port, byteArrayOf(0, 1, 2, 3, 13, 10, 13, 10)).startsWith("HTTP/1.1 400"))
        assertEquals(200, http("/lf?cmd=next").first)
    }

    @Test fun oversizeRequestGets431() {
        val port = started()
        val reply = raw(port, ("GET /lf?cmd=pause&pad=" + "x".repeat(3_000)).toByteArray())
        assertTrue(reply.startsWith("HTTP/1.1 431"), reply)
        assertTrue(sent.isEmpty())
    }

    @Test fun slowClientTimesOutWith408() {
        val port = started()
        val t0 = System.nanoTime()
        val reply = raw(port, "GET /lf?cmd=pa".toByteArray()) // never finishes the request
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(reply.startsWith("HTTP/1.1 408"), reply)
        assertTrue(ms in 400..2_500, "closed after $ms ms")
        assertTrue(sent.isEmpty())
    }

    @Test fun connectionsBeyondTheBoundAreRefusedThenServedAgain() {
        val port = started()
        val idle = List(2) { Socket(InetAddress.getLoopbackAddress(), port) } // hold both slots
        Thread.sleep(100)
        val busy = raw(port, "GET /lf?cmd=next HTTP/1.1\r\n\r\n".toByteArray())
        assertTrue(busy.startsWith("HTTP/1.1 503"), busy)
        idle.forEach { it.close() }
        Thread.sleep(700) // the held slots time out
        assertEquals(200, http("/lf?cmd=next").first)
    }

    @Test fun parallelRequestsAllSucceed() {
        started()
        val pool = Executors.newFixedThreadPool(2)
        val codes = pool.invokeAll(List(8) { Callable { http("/lf?cmd=next").first } }).map { it.get() }
        pool.shutdown()
        assertEquals(List(8) { 200 }, codes)
        assertEquals(8, sent.size)
    }

    @Test fun stopClosesThePort() {
        val port = started()
        server.stop()
        Thread.sleep(100)
        assertFailsWith<ConnectException> { Socket(InetAddress.getLoopbackAddress(), port).close() }
    }
}
