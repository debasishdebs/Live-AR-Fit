package com.debasish.livefit.glasses.agent

import com.debasish.livefit.model.Command
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class HttpReply(val status: Int, val body: String)

/** Request head → reply: parse, plan against the glasses' state, and hand the command to the hub link. */
class AgentEndpoint(
    private val context: () -> AgentContext,
    /** Sends on the glasses → phone command path; false (or a throw) = not delivered. */
    private val send: (Command) -> Boolean,
    private val log: (String) -> Unit = {},
) {
    fun handle(head: String): HttpReply {
        val req = AgentRequestParser.parse(head)
        if (req !is AgentRequest.Run) {
            val status = when (req) {
                AgentRequest.NotFound -> 404
                AgentRequest.BadMethod -> 405
                AgentRequest.TooLarge -> 431
                else -> 400
            }
            log("rejected status=$status")
            return HttpReply(status, AgentJson.reply(false, AgentReplies.CANT))
        }
        val plan = AgentReplies.plan(req.command, context())
        val delivered = plan.send?.let { c -> runCatching { send(c) }.getOrDefault(false) }
        log("cmd=${req.command.wire} ok=${plan.ok} sent=$delivered")
        return when {
            plan.send == null && !plan.ok -> HttpReply(503, AgentJson.reply(false, plan.say))
            delivered == false -> HttpReply(503, AgentJson.reply(false, AgentReplies.NOT_CONNECTED))
            else -> HttpReply(200, AgentJson.reply(plan.ok, plan.say))
        }
    }
}

/**
 * Minimal HTTP/1.1 receiver for the Hi Rokid LiveFit agent, bound to the loopback address only (the agent page runs on
 * these glasses and fetches 127.0.0.1). Plain [ServerSocket]: a daemon accept thread plus at most [maxConnections]
 * daemon connection threads; one request per connection (`Connection: close`). A request head must arrive within
 * [readTimeoutMs] and fit in [AgentRequestParser.MAX_REQUEST_BYTES], else 408 / 431. Nothing runs on the main thread,
 * the socket is bound on the accept thread too.
 */
class AgentServer(
    private val port: Int = PORT,
    private val handle: (String) -> HttpReply,
    private val log: (String) -> Unit = {},
    private val readTimeoutMs: Int = 2_000,
    private val maxConnections: Int = 4,
) {
    private val slots = Semaphore(maxConnections)
    private val bound = CountDownLatch(1)
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile var boundAddress: InetAddress? = null
        private set
    @Volatile var localPort: Int = -1
        private set

    fun start(): AgentServer {
        if (running) return this
        running = true
        thread(name = "LiveFitAgent-accept", isDaemon = true) { acceptLoop() }
        return this
    }

    /** True once the port is bound; false on timeout or bind failure. */
    fun awaitBound(timeoutMs: Long): Boolean = bound.await(timeoutMs, TimeUnit.MILLISECONDS) && boundAddress != null

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    private fun acceptLoop() {
        val ss = runCatching {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), BACKLOG) }
        }.getOrElse { log("bind failed: ${it.javaClass.simpleName}"); running = false; bound.countDown(); return }
        socket = ss
        boundAddress = ss.inetAddress
        localPort = ss.localPort
        bound.countDown()
        if (!running) { runCatching { ss.close() }; return } // stopped while binding
        log("listening on loopback:$localPort")
        while (running) {
            val client = try { ss.accept() } catch (e: Exception) { if (running) log("accept failed: ${e.javaClass.simpleName}"); break }
            if (!slots.tryAcquire(SLOT_WAIT_MS, TimeUnit.MILLISECONDS)) {
                log("busy")
                respond(client, HttpReply(503, AgentJson.reply(false, "LiveFit is busy, try again")), drainMs = 100)
                continue
            }
            runCatching {
                thread(name = "LiveFitAgent-conn", isDaemon = true) { try { serve(client) } finally { slots.release() } }
            }.onFailure { slots.release(); runCatching { client.close() } }
        }
        runCatching { ss.close() }
    }

    private fun serve(client: Socket) {
        val reply = runCatching {
            when (val head = readHead(client)) {
                Head.Timeout -> HttpReply(408, AgentJson.reply(false, AgentReplies.CANT))
                Head.TooLarge -> HttpReply(431, AgentJson.reply(false, AgentReplies.CANT))
                is Head.Text -> handle(head.text)
            }
        }.getOrElse { log("handler failed: ${it.javaClass.simpleName}"); HttpReply(500, AgentJson.reply(false, AgentReplies.CANT)) }
        respond(client, reply, drainMs = 200)
    }

    private sealed interface Head {
        data class Text(val text: String) : Head
        data object Timeout : Head
        data object TooLarge : Head
    }

    /** Bytes up to the blank line (or EOF), within the overall [readTimeoutMs] deadline and the size cap. */
    private fun readHead(client: Socket): Head {
        val deadline = System.nanoTime() + readTimeoutMs * 1_000_000L
        val input: InputStream = client.getInputStream()
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(512)
        while (true) {
            val left = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
            if (left <= 0) return Head.Timeout
            client.soTimeout = left
            val n = try { input.read(chunk) } catch (_: SocketTimeoutException) { return Head.Timeout }
            if (n < 0) return Head.Text(buf.toString(Charsets.ISO_8859_1.name()))
            buf.write(chunk, 0, n)
            val text = buf.toString(Charsets.ISO_8859_1.name())
            if (text.contains("\r\n\r\n") || text.contains("\n\n")) {
                return if (text.substringBefore("\n\n").substringBefore("\r\n\r\n").length > AgentRequestParser.MAX_REQUEST_BYTES) Head.TooLarge
                else Head.Text(text)
            }
            if (buf.size() > AgentRequestParser.MAX_REQUEST_BYTES) return Head.TooLarge
        }
    }

    /** Writes the reply, then drains what the client still sends so closing doesn't reset the connection first. */
    private fun respond(client: Socket, reply: HttpReply, drainMs: Int) {
        runCatching {
            val body = reply.body.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 ${reply.status} ${reason(reply.status)}\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
            client.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(body); flush() }
            client.shutdownOutput()
            client.soTimeout = drainMs
            val input = client.getInputStream()
            val sink = ByteArray(512)
            var total = 0
            while (total < DRAIN_BYTES) { val n = input.read(sink); if (n < 0) break; total += n }
        }
        runCatching { client.close() }
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 408 -> "Request Timeout"
        431 -> "Request Header Fields Too Large"; 503 -> "Service Unavailable"; else -> "Internal Server Error"
    }

    companion object {
        /** The LiveFit agent page fetches http://127.0.0.1:47123/lf?cmd=… (rokid-agent/livefit). */
        const val PORT = 47123
        private const val BACKLOG = 8
        private const val SLOT_WAIT_MS = 200L
        private const val DRAIN_BYTES = 8_192
    }
}
