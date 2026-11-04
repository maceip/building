package dev.ide.workbench.reliability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** write() must return only after both the new data and its directory entry are durable. */
interface ReliabilityBlobStorage {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    /** Atomic read-modify-write across all consumers; file implementations also lock across processes. */
    fun <T> transaction(operation: () -> T): T = synchronized(this) { operation() }
}

/** One snapshot = one atomic transaction. An interrupted temporary file is never a committed snapshot. */
class AtomicReliabilityFile(
    private val path: Path,
    private val maxBytes: Int,
    private val syncDirectory: (Path) -> Unit = { directory ->
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    },
) : ReliabilityBlobStorage {
    init { require(maxBytes > 0 && path.isAbsolute) }
    private val transactionLock = transactionLocks.computeIfAbsent(path.toAbsolutePath().normalize().toString()) { ReentrantLock() }

    override fun <T> transaction(operation: () -> T): T = transactionLock.withLock {
        if (transactionLock.holdCount > 1) return@withLock operation()
        guarded {
            ensureDirectory()
            val lockFile = path.resolveSibling(".${path.fileName}.lock")
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                channel.lock().use { operation() }
            }
        }
    }

    override fun read(): ByteArray? = guarded {
        ensureDirectory()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@guarded null
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > maxBytes) storageFailure()
        val bytes = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { stream ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 8_192))
            val buffer = ByteArray(8_192)
            var total = 0
            while (true) {
                val count = stream.read(buffer, 0, minOf(buffer.size, maxBytes - total + 1))
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > maxBytes) storageFailure()
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        // Also closes the durability gap if a prior process died between rename and directory fsync.
        syncDirectory(path.parent)
        bytes
    }

    override fun write(bytes: ByteArray) = guarded {
        if (bytes.size > maxBytes) throw ReliabilityException(ReliabilityError.QUOTA)
        ensureDirectory()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) storageFailure()
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}-", ".pending")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            // No non-atomic fallback: a failed atomic move must never receive an ACK.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(path.parent)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun ensureDirectory() {
        val directory = path.parent ?: storageFailure()
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(directory)
            directory.parent?.let(syncDirectory)
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) storageFailure()
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (failure: ReliabilityException) { throw failure }
    catch (_: Exception) { storageFailure() }

    companion object {
        private val transactionLocks = ConcurrentHashMap<String, ReentrantLock>()
    }
}

internal fun storageFailure(): Nothing = throw ReliabilityException(ReliabilityError.STORAGE_UNAVAILABLE)
internal val reliabilityJson = Json { isLenient = false }

/** Lexical depth/token limits precede recursive parsing, including for damaged private snapshots. */
internal fun reliabilityObject(text: String, maxChars: Int = IncidentWire.MAX_JSON_CHARS, maxDepth: Int = 8): JsonObject {
    if (text.length > maxChars) invalid()
    var depth = 0
    var tokens = 0
    var quoted = false
    var escaped = false
    for (ch in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (ch == '\\') escaped = true
            else if (ch == '"') quoted = false
        } else {
            when (ch) {
                '"' -> { quoted = true; tokens++ }
                '{', '[' -> { depth++; tokens++; if (depth > maxDepth) invalid() }
                '}', ']' -> { depth--; if (depth < 0) invalid() }
                ',', ':' -> tokens++
            }
            if (tokens > maxOf(8_192, maxChars / 2)) invalid()
        }
    }
    if (quoted || depth != 0) invalid()
    return try { reliabilityJson.parseToJsonElement(text) as? JsonObject ?: invalid() }
    catch (_: IllegalArgumentException) { invalid() }
}

internal fun JsonObject.text(name: String): String = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
internal fun JsonObject.number(name: String): Long = (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: invalid()
internal fun JsonObject.bool(name: String): Boolean = (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalid()
internal fun JsonObject.only(vararg names: String) { if (keys.any { it !in names }) invalid() }
internal fun decodeReliabilityUtf8(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: Exception) { storageFailure() }

internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun digest(text: String): String = digest(text.toByteArray(Charsets.UTF_8))
internal fun randomNonce(random: SecureRandom): String = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun constantTimeEqual(left: String, right: String): Boolean = MessageDigest.isEqual(left.toByteArray(Charsets.US_ASCII), right.toByteArray(Charsets.US_ASCII))
internal fun JsonElement.objectValue(): JsonObject = this as? JsonObject ?: invalid()
