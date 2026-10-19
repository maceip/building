package dev.ide.android.workbench.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback bridge connecting child runtime processes (Node.js, Python, shell) to Android's
 * native browser launching and callback redirect infrastructure.
 *
 * Runs strictly on 127.0.0.1. Validates that incoming URLs are safe HTTP(S) or codeassist:// URIs
 * before triggering Android's system browser.
 */
class RuntimeBrowserBridge private constructor(private val appContext: Context) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val port: Int = server.localPort

    @Volatile
    var lastCallbackUrl: String? = null
        private set

    private val callbackListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        val acceptThread = Thread({
            while (!closed.get()) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                bridgeScope.launch { handleSocket(socket) }
            }
        }, "runtime-browser-bridge").apply { isDaemon = true }
        acceptThread.start()
    }

    fun openUrl(candidateUrl: String): Boolean {
        val trimmed = candidateUrl.trim()
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme !in ALLOWED_SCHEMES) return false

        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun addCallbackListener(listener: (String) -> Unit) {
        callbackListeners.add(listener)
    }

    fun removeCallbackListener(listener: (String) -> Unit) {
        callbackListeners.remove(listener)
    }

    private fun handleSocket(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 3_000
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return

                val method = parts[0]
                val path = parts[1]

                // Consume remaining headers
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                }

                if (method != "GET") {
                    writeResponse(s.getOutputStream(), 405, "Method Not Allowed", "text/plain", "Method not allowed")
                    return
                }

                when {
                    path.startsWith("/open?url=") -> {
                        val encoded = path.removePrefix("/open?url=")
                        val targetUrl = URLDecoder.decode(encoded, "UTF-8")
                        val success = openUrl(targetUrl)
                        if (success) {
                            writeResponse(s.getOutputStream(), 200, "OK", "application/json", "{\"status\":\"opened\",\"url\":\"$targetUrl\"}")
                        } else {
                            writeResponse(s.getOutputStream(), 400, "Bad Request", "application/json", "{\"error\":\"invalid_url\"}")
                        }
                    }
                    path == "/callback" || path.startsWith("/callback?") -> {
                        val current = lastCallbackUrl
                        val json = if (current != null) {
                            "{\"status\":\"ready\",\"callback\":\"$current\"}"
                        } else {
                            "{\"status\":\"none\",\"callback\":null}"
                        }
                        writeResponse(s.getOutputStream(), 200, "OK", "application/json", json)
                    }
                    else -> {
                        writeResponse(s.getOutputStream(), 404, "Not Found", "text/plain", "Not found")
                    }
                }
            }
        } catch (_: Exception) {
            // Socket or I/O error on connection
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.isEmpty()) null else out.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) out.append(b.toChar())
            if (out.length > 8192) return null
        }
        return out.toString()
    }

    private fun writeResponse(out: OutputStream, code: Int, reason: String, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { server.close() }
        }
    }

    companion object {
        private val ALLOWED_SCHEMES = setOf("http", "https", "codeassist")

        @Volatile
        private var instance: RuntimeBrowserBridge? = null

        fun get(context: Context): RuntimeBrowserBridge {
            return instance ?: synchronized(this) {
                instance ?: RuntimeBrowserBridge(context.applicationContext).also { instance = it }
            }
        }

        fun recordCallback(url: String) {
            val current = instance ?: return
            current.lastCallbackUrl = url
            current.callbackListeners.forEach { runCatching { it(url) } }
        }

        fun forwardCallbackToPreview(previewUrl: String, callbackUri: Uri) {
            val current = instance ?: return
            current.bridgeScope.launch {
                try {
                    val base = URL(previewUrl)
                    val query = callbackUri.query?.let { "?$it" } ?: ""
                    val forwardUrl = URL(base.protocol, base.host, base.port, "/callback$query")
                    val connection = forwardUrl.openConnection() as HttpURLConnection
                    connection.connectTimeout = 2_000
                    connection.readTimeout = 2_000
                    connection.requestMethod = "GET"
                    connection.responseCode
                    connection.disconnect()
                } catch (_: Exception) {
                    // Preview server might handle callbacks on a different route or via polling
                }
            }
        }
    }
}
