package dev.ide.android.workbench.acceptance

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * A real HTTP/1.1 endpoint on the device, with no MockWebServer or UI-test dependency.
 * Every connection closes after one response. Headers, chunks, bodies and shutdown are bounded.
 * Request credentials remain in memory and are deliberately omitted from diagnostics.
 */
internal class LoopbackHttpServer : Closeable {
    class Request(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray) {
        fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
        fun bodyText(): String = body.toString(Charsets.UTF_8)
        override fun toString(): String = "LoopbackRequest(method=$method, payload=redacted)"
    }

    class Reply(val status: Int = 200, val body: String = "{}", val headers: Map<String, String> = emptyMap())
    private class Step(val label: String, val respond: (Request) -> Reply)

    private val socket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16)
        soTimeout = 500
    }
    private val running = AtomicBoolean(true)
    private val failure = AtomicReference<String?>(null)
    private val steps = LinkedBlockingQueue<Step>()
    private val received = Collections.synchronizedList(mutableListOf<Request>())
    @Volatile private var activeSocket: Socket? = null
    val port: Int = socket.localPort
    val origin: String = "http://127.0.0.1:$port/"
    val requestCount: Int get() = received.size
    val requests: List<Request> get() = synchronized(received) { received.toList() }

    private val worker = thread(name = "workbench-acceptance-loopback", isDaemon = true) {
        while (running.get()) {
            val connection = try {
                socket.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                if (running.get()) fail("accept")
                break
            }
            activeSocket = connection
            try {
                connection.use { client ->
                    client.soTimeout = 5_000
                    check(client.inetAddress.isLoopbackAddress) { "Only loopback peers are permitted" }
                    check(requestCount < MAX_REQUESTS) { "Request limit exceeded" }
                    val request = parse(client.getInputStream().buffered())
                    received += request
                    val step = steps.poll()
                    val reply = if (step == null) {
                        fail("unexpected request")
                        Reply(500, "{}")
                    } else {
                        try {
                            step.respond(request)
                        } catch (_: Throwable) {
                            fail(step.label)
                            Reply(500, "{}")
                        }
                    }
                    send(client, reply)
                }
            } catch (_: Exception) {
                if (running.get()) fail("bounded HTTP parser or response")
            } finally {
                activeSocket = null
            }
        }
    }

    fun expect(label: String, respond: (Request) -> Reply) {
        check(steps.size < MAX_REQUESTS)
        // Labels are developer-authored fixed descriptions, never server/client payloads.
        steps.put(Step(label, respond))
    }

    fun assertSatisfied(expectedRequests: Int) {
        failure.get()?.let { throw AssertionError("Loopback acceptance failed at $it; payload withheld") }
        if (steps.isNotEmpty()) throw AssertionError("Not all scripted loopback requests were sent")
        if (requestCount != expectedRequests) throw AssertionError("Expected $expectedRequests loopback requests, received $requestCount")
    }

    private fun fail(label: String) {
        failure.compareAndSet(null, label)
    }

    override fun close() {
        running.set(false)
        runCatching { activeSocket?.close() }
        runCatching { socket.close() }
        worker.join(6_000)
        received.clear()
        steps.clear()
        if (worker.isAlive) throw AssertionError("Loopback acceptance thread did not terminate")
    }

    private fun parse(input: BufferedInputStream): Request {
        val start = line(input, MAX_LINE_BYTES).split(' ')
        require(start.size == 3 && start[0].matches(Regex("[A-Z]{1,12}")))
        require(start[1].startsWith('/') && !start[1].contains("://"))
        require(start[2] == "HTTP/1.1" || start[2] == "HTTP/1.0")
        val headers = linkedMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val next = line(input, MAX_LINE_BYTES)
            headerBytes += next.length + 2
            require(headerBytes <= MAX_HEADER_BYTES)
            if (next.isEmpty()) break
            require(headers.size < 64)
            val colon = next.indexOf(':')
            require(colon > 0)
            val name = next.substring(0, colon).lowercase(Locale.ROOT)
            require(name.matches(Regex("[a-z0-9!#$%&'*+.^_`|~-]+")) && name !in headers)
            headers[name] = next.substring(colon + 1).trim()
        }
        val transfer = headers["transfer-encoding"]
        val length = headers["content-length"]
        require(transfer == null || length == null) { "Ambiguous HTTP framing" }
        val body = when {
            transfer != null -> {
                require(transfer.equals("chunked", ignoreCase = true))
                chunks(input)
            }
            length != null -> {
                val count = length.toLongOrNull() ?: error("Invalid HTTP body length")
                require(count in 0..MAX_BODY_BYTES.toLong())
                exact(input, count.toInt())
            }
            else -> ByteArray(0)
        }
        return Request(start[0], start[1], headers, body)
    }

    private fun chunks(input: InputStream): ByteArray {
        val body = ByteArrayOutputStream()
        var chunkCount = 0
        while (true) {
            require(++chunkCount <= 4_096)
            val chunkLine = line(input, 1_024)
            val count = chunkLine.substringBefore(';').trim().toLongOrNull(16)
                ?: error("Invalid HTTP chunk size")
            require(count in 0..(MAX_BODY_BYTES - body.size()).toLong())
            if (count == 0L) {
                var trailerBytes = 0
                var trailerCount = 0
                while (true) {
                    val trailer = line(input, MAX_LINE_BYTES)
                    trailerBytes += trailer.length + 2
                    require(trailerBytes <= MAX_HEADER_BYTES && ++trailerCount <= 64)
                    if (trailer.isEmpty()) return body.toByteArray()
                    require(trailer.indexOf(':') > 0)
                }
            }
            body.write(exact(input, count.toInt()))
            require(input.read() == 13 && input.read() == 10)
        }
    }

    private fun line(input: InputStream, limit: Int): String {
        val output = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) throw EOFException("Incomplete HTTP framing")
            if (value == 13) {
                require(input.read() == 10)
                return output.toString(Charsets.US_ASCII.name())
            }
            require(value in 32..126 || value == 9)
            require(output.size() < limit)
            output.write(value)
        }
    }

    private fun exact(input: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(bytes, offset, count - offset)
            if (read < 0) throw EOFException("Incomplete HTTP body")
            if (read > 0) offset += read
        }
        return bytes
    }

    private fun send(client: Socket, reply: Reply) {
        val body = reply.body.toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_BODY_BYTES && reply.status in 200..599)
        val headers = buildString {
            append("HTTP/1.1 ${reply.status} Test Reply\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\nConnection: close\r\n")
            reply.headers.forEach { (name, value) ->
                require('\r' !in name && '\n' !in name && '\r' !in value && '\n' !in value)
                append("$name: $value\r\n")
            }
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        client.getOutputStream().apply { write(headers); write(body); flush() }
    }

    private companion object {
        const val MAX_REQUESTS = 64
        const val MAX_LINE_BYTES = 8_192
        const val MAX_HEADER_BYTES = 32_768
        const val MAX_BODY_BYTES = 1_048_576
    }
}
