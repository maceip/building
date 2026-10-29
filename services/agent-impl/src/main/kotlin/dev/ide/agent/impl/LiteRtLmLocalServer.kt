package dev.ide.agent.impl

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Loopback-only HTTP/1.1 inference server (`127.0.0.1:<port>`) hosted by the IDE's Self-Serve LiteRT-LM Corner.
 *
 * Exposes both:
 * 1. OpenAI-compatible endpoints (`GET /v1/models`, `POST /v1/chat/completions` with JSON & SSE streaming)
 * 2. Native LiteRT-LM endpoints (`GET /v1/litert/status`, `GET /v1/litert/models`, `POST /v1/litert/generate`)
 *
 * Guest apps running on the phone (Android, Swift/iOS, JVM, Node.js, Python) connect to `127.0.0.1:<port>`
 * to run local inference against the phone's verified `.litertlm` artifacts without bundling 2GB models
 * into their own binaries.
 */
class LiteRtLmLocalServer(
    private val manager: LiteRtLmServingManager,
    private val port: Int = LiteRtLmCatalog.DEFAULT_SERVER_PORT,
) : AutoCloseable {

    private val lock = Any()
    private var serverSocket: ServerSocket? = null

    val boundPort: Int
        get() = synchronized(lock) { serverSocket?.localPort ?: -1 }

    fun start(): Int {
        synchronized(lock) {
            check(serverSocket == null) { "LiteRT-LM local server already bound on port $boundPort" }
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(LOOPBACK, port))
            serverSocket = socket
            Thread(::acceptLoop, "litert-lm-http-accept").apply { isDaemon = true }.start()
            return socket.localPort
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { serverSocket?.close() }
            serverSocket = null
        }
    }

    private fun acceptLoop() {
        while (true) {
            val srv = synchronized(lock) { serverSocket } ?: return
            if (srv.isClosed) return
            val client = try {
                srv.accept()
            } catch (_: IOException) {
                return
            }
            Thread({ handleConnection(client) }, "litert-lm-http-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handleConnection(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = READ_TIMEOUT_MS
                val req = readHttpRequest(sock.getInputStream())
                if (!req.isLocalRequest()) {
                    writeJsonResponse(sock.getOutputStream(), 403, """{"error":"Forbidden: loopback only"}""")
                    return
                }
                val cleanPath = req.path.substringBefore('?')
                when {
                    req.method == "GET" && (cleanPath == "/v1/models" || cleanPath == "/v1/litert/models") -> {
                        writeJsonResponse(sock.getOutputStream(), 200, handleListModels())
                    }
                    req.method == "GET" && (cleanPath == "/v1/litert/status" || cleanPath == "/health") -> {
                        writeJsonResponse(sock.getOutputStream(), 200, handleStatus())
                    }
                    req.method == "POST" && cleanPath == "/v1/litert/generate" -> {
                        val (status, json) = handleNativeGenerate(req.body)
                        writeJsonResponse(sock.getOutputStream(), status, json)
                    }
                    req.method == "POST" && cleanPath == "/v1/chat/completions" -> {
                        handleChatCompletions(req.body, sock.getOutputStream())
                    }
                    else -> {
                        writeJsonResponse(
                            sock.getOutputStream(),
                            404,
                            """{"error":{"message":"Unknown LiteRT-LM endpoint: ${req.method} $cleanPath"}}""",
                        )
                    }
                }
            } catch (_: IOException) {
                // Client disconnected or timed out.
            } catch (e: Exception) {
                runCatching {
                    val escaped = (e.message ?: "Internal server error").replace("\"", "\\\"")
                    writeJsonResponse(sock.getOutputStream(), 500, """{"error":{"message":"$escaped"}}""")
                }
            }
        }
    }

    private fun handleListModels(): String {
        val snap = manager.currentSnapshot()
        val installed = snap.entries.filter {
            it.status == LiteRtModelInstallState.INSTALLED || it.status == LiteRtModelInstallState.ACTIVE
        }
        return buildJsonObject {
            put("object", "list")
            put("active_model", snap.activeModelId)
            put("active_delegate", snap.selectedDelegate.id)
            put(
                "data",
                buildJsonArray {
                    installed.forEach { entry ->
                        add(
                            buildJsonObject {
                                put("id", entry.spec.id)
                                put("object", "model")
                                put("owned_by", "litert-lm-on-device")
                                put("display_name", entry.spec.displayName)
                                put("family", entry.spec.family)
                                put("quantization", entry.spec.quantization)
                                put("context_length", entry.spec.contextLength)
                                put("size_bytes", entry.sizeOnDiskBytes)
                                put("sha256", entry.sha256)
                                put("active", entry.status == LiteRtModelInstallState.ACTIVE)
                            },
                        )
                    }
                },
            )
        }.toString()
    }

    private fun handleStatus(): String {
        val snap = manager.currentSnapshot()
        return buildJsonObject {
            put("ok", true)
            put("running", true)
            put("port", boundPort)
            put("soc_name", snap.hardware.socName)
            put("active_model", snap.activeModelId)
            put("selected_delegate", snap.selectedDelegate.id)
            put("installed_models_count", snap.entries.count { it.sizeOnDiskBytes > 0L })
            put("requests_served", snap.requestsServed)
            put("tokens_generated", snap.tokensGenerated)
            put("last_latency_ms", snap.lastLatencyMs)
            put("ipc_authority", snap.ipcAuthority)
        }.toString()
    }

    private fun handleNativeGenerate(rawBody: String): Pair<Int, String> {
        val obj = parseArgsObject(rawBody)
        val prompt = obj["prompt"].asStr().orEmpty()
        if (prompt.isBlank()) {
            return 400 to """{"ok":false,"error":"Missing required 'prompt' field."}"""
        }
        val modelId = obj["model"].asStr()
        val system = obj["system"].asStr()
        val delegate = LiteRtHardwareDelegate.fromId(obj["delegate"].asStr())
        val maxTokens = obj["max_tokens"].asInt() ?: 512
        val thinking = obj["thinking"].asBool() ?: false

        val res = manager.generate(
            prompt = prompt,
            system = system,
            modelId = modelId,
            delegate = delegate,
            maxTokens = maxTokens,
            thinking = thinking,
        )
        if (!res.ok) {
            val errJson = buildJsonObject {
                put("ok", false)
                put("error", res.error)
            }.toString()
            return 400 to errJson
        }
        val okJson = buildJsonObject {
            put("ok", true)
            put("model", res.modelId)
            put("delegate", res.delegate.id)
            put("text", res.text)
            if (res.thinking.isNotBlank()) put("thinking", res.thinking)
            put("artifact_sha256", res.artifactSha256)
            put("latency_ms", res.latencyMs)
            put(
                "usage",
                buildJsonObject {
                    put("prompt_tokens", res.promptTokens)
                    put("completion_tokens", res.completionTokens)
                    put("total_tokens", res.promptTokens + res.completionTokens)
                },
            )
        }.toString()
        return 200 to okJson
    }

    private fun handleChatCompletions(rawBody: String, output: OutputStream) {
        val obj = parseArgsObject(rawBody)
        val modelId = obj["model"].asStr()
        val stream = obj["stream"].asBool() ?: false
        val maxTokens = obj["max_tokens"].asInt() ?: 512
        val delegate = LiteRtHardwareDelegate.fromId(obj["delegate"].asStr())

        val messagesArr = obj["messages"].asArr()
        var systemPrompt: String? = null
        val userParts = mutableListOf<String>()
        messagesArr?.forEach { elem ->
            val msgObj = elem.asObj() ?: return@forEach
            val role = msgObj["role"].asStr().orEmpty()
            val contentElem = msgObj["content"]
            val text = contentElem.asStr()
                ?: contentElem.asArr()?.mapNotNull { part ->
                    part.asObj()?.get("text").asStr()
                }?.joinToString("\n")
                .orEmpty()
            if (role == "system") {
                systemPrompt = if (systemPrompt == null) text else "$systemPrompt\n$text"
            } else if (role == "user" || role == "tool") {
                if (text.isNotBlank()) userParts += text
            }
        }
        val prompt = userParts.lastOrNull().orEmpty().ifBlank { "Hello" }
        val res = manager.generate(
            prompt = prompt,
            system = systemPrompt,
            modelId = modelId,
            delegate = delegate,
            maxTokens = maxTokens,
            thinking = true,
        )
        if (!res.ok) {
            val errJson = buildJsonObject {
                put(
                    "error",
                    buildJsonObject {
                        put("message", res.error)
                        put("type", "invalid_request_error")
                    },
                )
            }.toString()
            writeJsonResponse(output, 400, errJson)
            return
        }

        val completionId = "chatcmpl-litert-" + UUID.randomUUID().toString().take(8)
        val created = System.currentTimeMillis() / 1000L

        if (!stream) {
            val body = buildJsonObject {
                put("id", completionId)
                put("object", "chat.completion")
                put("created", created)
                put("model", res.modelId)
                put("litert_delegate", res.delegate.id)
                put(
                    "choices",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("index", 0)
                                put(
                                    "message",
                                    buildJsonObject {
                                        put("role", "assistant")
                                        put("content", res.text)
                                        if (res.thinking.isNotBlank()) {
                                            put("reasoning_content", res.thinking)
                                        }
                                    },
                                )
                                put("finish_reason", "stop")
                            },
                        )
                    },
                )
                put(
                    "usage",
                    buildJsonObject {
                        put("prompt_tokens", res.promptTokens)
                        put("completion_tokens", res.completionTokens)
                        put("total_tokens", res.promptTokens + res.completionTokens)
                    },
                )
            }.toString()
            writeJsonResponse(output, 200, body)
        } else {
            val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
            writer.write("HTTP/1.1 200 OK\r\n")
            writer.write("Connection: close\r\n")
            writer.write("Content-Type: text/event-stream; charset=utf-8\r\n")
            writer.write("Cache-Control: no-cache\r\n\r\n")

            if (res.thinking.isNotBlank()) {
                val thinkChunk = buildJsonObject {
                    put("id", completionId)
                    put("object", "chat.completion.chunk")
                    put("created", created)
                    put("model", res.modelId)
                    put(
                        "choices",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("index", 0)
                                    put("delta", buildJsonObject { put("reasoning_content", res.thinking) })
                                },
                            )
                        },
                    )
                }.toString()
                writer.write("data: $thinkChunk\n\n")
            }

            val contentChunk = buildJsonObject {
                put("id", completionId)
                put("object", "chat.completion.chunk")
                put("created", created)
                put("model", res.modelId)
                put(
                    "choices",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("index", 0)
                                put(
                                    "delta",
                                    buildJsonObject {
                                        put("role", "assistant")
                                        put("content", res.text)
                                    },
                                )
                            },
                        )
                    },
                )
            }.toString()
            writer.write("data: $contentChunk\n\n")

            val stopChunk = buildJsonObject {
                put("id", completionId)
                put("object", "chat.completion.chunk")
                put("created", created)
                put("model", res.modelId)
                put(
                    "choices",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("index", 0)
                                put("delta", buildJsonObject {})
                                put("finish_reason", "stop")
                            },
                        )
                    },
                )
                put(
                    "usage",
                    buildJsonObject {
                        put("prompt_tokens", res.promptTokens)
                        put("completion_tokens", res.completionTokens)
                        put("total_tokens", res.promptTokens + res.completionTokens)
                    },
                )
            }.toString()
            writer.write("data: $stopChunk\n\n")
            writer.write("data: [DONE]\n\n")
            writer.flush()
        }
    }

    private fun writeJsonResponse(output: OutputStream, status: Int, jsonBody: String) {
        val bytes = jsonBody.toByteArray(StandardCharsets.UTF_8)
        val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
        val phrase = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }
        writer.write("HTTP/1.1 $status $phrase\r\n")
        writer.write("Connection: close\r\n")
        writer.write("Content-Type: application/json; charset=utf-8\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n\r\n")
        writer.flush()
        output.write(bytes)
        output.flush()
    }

    private fun readHttpRequest(input: InputStream): ParsedHttpRequest {
        val stream = BufferedInputStream(input)
        val requestLine = readLineIso(stream) ?: throw IOException("Empty HTTP request")
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1) ?: "/"
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLineIso(stream) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        val contentLength = (headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, MAX_BODY_BYTES)
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val n = stream.read(buf, offset, contentLength - offset)
                if (n < 0) break
                offset += n
            }
            String(buf, 0, offset, StandardCharsets.UTF_8)
        } else {
            ""
        }
        return ParsedHttpRequest(method, path, headers, body)
    }

    private fun readLineIso(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            when {
                b < 0 -> return if (out.size() == 0) null else String(out.toByteArray(), StandardCharsets.ISO_8859_1)
                b == '\n'.code -> return String(out.toByteArray(), StandardCharsets.ISO_8859_1).removeSuffix("\r")
                else -> out.write(b)
            }
        }
    }

    private data class ParsedHttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    ) {
        fun isLocalRequest(): Boolean {
            val hostHeader = headers["host"]?.let {
                if (it.startsWith("[")) it.substringAfter('[').substringBefore(']') else it.substringBefore(':')
            }
            if (hostHeader != null && hostHeader !in ALLOWED_LOCAL_HOSTS) return false
            val origin = headers["origin"] ?: return true
            val originHost = runCatching { URI(origin).host }.getOrNull() ?: return false
            return originHost.trim('[', ']') in ALLOWED_LOCAL_HOSTS
        }
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024
        private val ALLOWED_LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")
    }
}
