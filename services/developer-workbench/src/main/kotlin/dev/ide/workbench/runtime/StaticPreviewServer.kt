package dev.ide.workbench.runtime

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Small read-only loopback HTTP server, not an execution or privileged app bridge. */
internal class StaticPreviewServer(private val root: Path, private val entry: String) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val pool = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8), { task -> Thread(task, "runtime-static-http").apply { isDaemon = true } })
    val origin = "http://127.0.0.1:${server.localPort}"
    val url: String = origin + URI(null, null, "/$entry", null).rawPath

    init {
        require(Files.isRegularFile(safeProjectPath(root, entry)))
        Thread({
            while (!closed.get()) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                clients += socket
                try { pool.execute { handle(socket) } } catch (_: Exception) { clients -= socket; socket.close() }
            }
        }, "runtime-static-accept").apply { isDaemon = true; start() }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use {
                socket.soTimeout = 5_000
                val input = BufferedInputStream(socket.getInputStream())
                val request = line(input)?.split(' ') ?: return
                if (request.size != 3) return
                val headers = mutableMapOf<String, String>()
                var count = 0
                while (true) {
                    val h = line(input) ?: return
                    if (h.isEmpty()) break
                    if (':' !in h || ++count > 50) return
                    val name = h.substringBefore(':').lowercase()
                    if (name in headers && name in setOf("host", "origin")) return
                    headers[name] = h.substringAfter(':').trim()
                }
                if (headers["host"] != "127.0.0.1:${server.localPort}" || headers["origin"]?.let { it != origin } == true) {
                    respond(socket, 403, "text/plain", "Forbidden origin".toByteArray()); return
                }
                if (request[0] !in setOf("GET", "HEAD")) {
                    respond(socket, 405, "text/plain", "Read-only preview".toByteArray()); return
                }
                val rawPath = request[1].substringBefore('?')
                require(rawPath.startsWith('/') && !rawPath.startsWith("//"))
                val decoded = URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8").removePrefix("/").ifEmpty { entry }
                if (decoded.split('/').any { it.startsWith('.') } || decoded.contains('\\')) {
                    respond(socket, 403, "text/plain", "Private path".toByteArray()); return
                }
                val path = runCatching { safeProjectPath(root, decoded) }.getOrNull()
                if (path == null || !Files.isRegularFile(path)) {
                    respond(socket, 404, "text/plain", "Not found".toByteArray()); return
                }
                if (Files.size(path) > 16 * 1024 * 1024) {
                    respond(socket, 413, "text/plain", "Preview file exceeds 16 MiB".toByteArray()); return
                }
                val mime = when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
                    "html", "htm" -> "text/html; charset=utf-8"
                    "js", "mjs" -> "text/javascript; charset=utf-8"
                    "css" -> "text/css; charset=utf-8"
                    "json", "map" -> "application/json"
                    "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "svg" -> "image/svg+xml"
                    "wasm" -> "application/wasm"; "ico" -> "image/x-icon"
                    else -> "application/octet-stream"
                }
                respond(socket, 200, mime, Files.readAllBytes(path), request[0] == "HEAD")
            }
        } catch (_: Exception) { }
        finally { clients -= socket }
    }

    private fun line(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= 8192) {
            val c = input.read()
            if (c < 0) return null
            if (c == 10) return bytes.toByteArray().toString(StandardCharsets.US_ASCII).trimEnd('\r')
            bytes += c.toByte()
        }
        throw IllegalArgumentException("Header too long")
    }

    private fun respond(socket: Socket, code: Int, mime: String, bytes: ByteArray, head: Boolean = false) {
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 $code Response\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        if (!head) output.write(bytes)
        output.flush()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.close()
        clients.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }
}
