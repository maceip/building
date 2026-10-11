package dev.ide.gradle

import dev.ide.platform.JsonReader
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Speaks the tooling server's length-prefixed JSON. Events that arrive while a call is in
 * flight are delivered to that call; a response closes it. The server's stderr is kept in a
 * ring so a dead JVM can be reported with its last lines.
 */
class ToolingClient(private val process: Process) : AutoCloseable {

    private val stdin = process.outputStream
    private val frames = LinkedBlockingQueue<Incoming>()
    private val stderr = ArrayDeque<String>()
    private val ids = AtomicLong(1)
    @Volatile private var open = true

    init {
        Thread({ pump(process.inputStream) }, "gradle-tooling-stdout").apply { isDaemon = true; start() }
        Thread({
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(stderr) {
                        stderr.addLast(line)
                        while (stderr.size > STDERR_RING) stderr.removeFirst()
                    }
                }
            }
        }, "gradle-tooling-stderr").apply { isDaemon = true; start() }
    }

    fun initialize(
        projectDir: String,
        distributionType: String,
        distributionValue: String?,
        javaHome: String,
        androidHome: String?,
        gradleUserHome: String,
        args: List<String>,
        jvmArgs: List<String>,
        env: Map<String, String>,
    ): Map<String, Any?> {
        val distribution = linkedMapOf<String, Any?>("type" to distributionType)
        if (distributionValue != null) distribution["value"] = distributionValue
        return call(
            "initialize",
            mapOf(
                "projectDir" to projectDir,
                "distribution" to distribution,
                "javaHome" to javaHome,
                "androidHome" to androidHome,
                "gradleUserHome" to gradleUserHome,
                "args" to args,
                "jvmArgs" to jvmArgs,
                "env" to env,
            ),
        ).result
    }

    fun fetchModel(): Call = call("fetchModel", emptyMap())

    fun execute(
        tasks: List<String>,
        args: List<String> = emptyList(),
        isCanceled: () -> Boolean = { false },
        onEvent: (Map<String, Any?>) -> Unit = {},
    ): Call = call("execute", mapOf("tasks" to tasks, "args" to args), onEvent, isCanceled)

    fun cancel() {
        runCatching { write(mapOf("id" to 0L, "method" to "cancel", "params" to emptyMap<String, Any?>())) }
    }

    fun stderrTail(): String = synchronized(stderr) { stderr.joinToString("\n") }

    override fun close() {
        open = false
        runCatching { call("shutdown", emptyMap()) }
        runCatching { process.destroy() }
        runCatching { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly() }
    }

    private fun call(
        method: String,
        params: Map<String, Any?>,
        onEvent: (Map<String, Any?>) -> Unit = {},
        isCanceled: () -> Boolean = { false },
    ): Call {
        val id = ids.getAndIncrement()
        write(mapOf("id" to id, "method" to method, "params" to params))
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(30)
        var cancelSent = false
        while (true) {
            if (isCanceled() && !cancelSent) {
                cancelSent = true
                cancel()
            }
            val wait = deadline - System.nanoTime()
            if (wait <= 0) error("Gradle tooling call '$method' timed out.\n${stderrTail()}")
            val slice = minOf(wait, TimeUnit.MILLISECONDS.toNanos(200))
            val incoming = frames.poll(slice, TimeUnit.NANOSECONDS) ?: continue
            when (incoming) {
                is Incoming.Died -> error("Gradle JVM exited.\n${stderrTail()}".trim())
                is Incoming.Frame -> {
                    val event = incoming.map["event"] as? String
                    if (event != null) {
                        onEvent(incoming.map)
                        continue
                    }
                    val mid = (incoming.map["id"] as? Number)?.toLong() ?: continue
                    if (mid != id) continue
                    val error = incoming.map["error"]
                    if (error is Map<*, *>) {
                        val message = error["message"]?.toString() ?: "Gradle tooling error"
                        throw ToolingException(message, stderrTail())
                    }
                    return Call(incoming.rawResult, incoming.map["result"])
                }
            }
        }
    }

    private fun write(value: Map<String, Any?>) {
        val body = encode(value).toByteArray(StandardCharsets.UTF_8)
        synchronized(stdin) {
            stdin.write(
                byteArrayOf(
                    (body.size ushr 24).toByte(),
                    (body.size ushr 16).toByte(),
                    (body.size ushr 8).toByte(),
                    body.size.toByte(),
                ),
            )
            stdin.write(body)
            stdin.flush()
        }
    }

    private fun pump(input: InputStream) {
        try {
            while (open) {
                val header = readFully(input, 4) ?: break
                val n = ((header[0].toInt() and 0xff) shl 24) or
                    ((header[1].toInt() and 0xff) shl 16) or
                    ((header[2].toInt() and 0xff) shl 8) or
                    (header[3].toInt() and 0xff)
                if (n < 0 || n > MAX_FRAME) break
                val body = readFully(input, n) ?: break
                val text = body.toString(StandardCharsets.UTF_8)
                val parsed = JsonReader.obj(JsonReader.parse(text)) ?: continue
                frames.put(Incoming.Frame(parsed, rawResult(text)))
            }
        } catch (_: Exception) {
            // fall through to Died
        }
        open = false
        frames.offer(Incoming.Died)
    }

    private fun readFully(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) {
                if (off == 0) return null
                throw EOFException("short frame")
            }
            off += r
        }
        return buf
    }

    /** The raw JSON of the `result` value, so the snapshot file is the server's model. */
    private fun rawResult(frame: String): String {
        val key = "\"result\":"
        val at = frame.indexOf(key)
        if (at < 0) return frame
        return frame.substring(at + key.length).trim().removeSuffix("}")
    }

    data class Call(val rawResult: String, val result: Any?)

    class ToolingException(message: String, val stderr: String) : RuntimeException(message)

    private sealed interface Incoming {
        data class Frame(val map: Map<String, Any?>, val rawResult: String) : Incoming
        data object Died : Incoming
    }

    companion object {
        private const val MAX_FRAME = 16 * 1024 * 1024
        private const val STDERR_RING = 40

        fun encode(value: Any?): String = buildString { write(this, value) }

        @Suppress("UNCHECKED_CAST")
        private fun write(out: StringBuilder, value: Any?) {
            when (value) {
                null -> out.append("null")
                is String -> {
                    out.append('"')
                    value.forEach { c ->
                        when (c) {
                            '"' -> out.append("\\\"")
                            '\\' -> out.append("\\\\")
                            '\n' -> out.append("\\n")
                            '\r' -> out.append("\\r")
                            '\t' -> out.append("\\t")
                            else -> if (c.code < 0x20) out.append("\\u%04x".format(c.code)) else out.append(c)
                        }
                    }
                    out.append('"')
                }
                is Boolean, is Int, is Long -> out.append(value.toString())
                is Map<*, *> -> {
                    out.append('{')
                    var first = true
                    for ((k, v) in value) {
                        if (!first) out.append(',')
                        first = false
                        write(out, k.toString())
                        out.append(':')
                        write(out, v)
                    }
                    out.append('}')
                }
                is List<*> -> {
                    out.append('[')
                    value.forEachIndexed { i, item ->
                        if (i > 0) out.append(',')
                        write(out, item)
                    }
                    out.append(']')
                }
                else -> write(out, value.toString())
            }
        }
    }
}
