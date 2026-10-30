@file:OptIn(ExperimentalForeignApi::class)

package dev.ide.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream

/**
 * Pure Kotlin/Native ZIP extractor backed by system `platform.zlib`.
 *
 * Allows `IosBackend.vcs.cloneRepository` to unpack GitHub archive zips directly inside the
 * iOS app sandbox on physical iPhone/iPad hardware (`iosArm64`), where POSIX `popen("unzip")`
 * is forbidden by the Darwin kernel sandbox.
 */
internal object IosZipExtractor {

    private const val LOCAL_SIGNATURE = 0x04034b50
    private const val CENTRAL_SIGNATURE = 0x02014b50
    private const val END_SIGNATURE = 0x06054b50
    private const val LOCAL_HEADER_SIZE = 30
    private const val CENTRAL_HEADER_SIZE = 46
    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATED = 8

    /**
     * Unpacks [zipBytes] into [targetDir], stripping a single shared top-level directory prefix
     * (such as `<repo>-main/` or `<repo>-<sha>/` from GitHub codeload archives).
     */
    fun extractRepositoryZip(zipBytes: ByteArray, targetDir: String): Boolean {
        if (!extractZip(zipBytes, targetDir, stripSinglePrefix = true)) return false
        val gitDir = IosFiles.join(targetDir, ".git")
        IosFiles.mkdirs(gitDir)
        IosFiles.writeText(IosFiles.join(gitDir, "HEAD"), "ref: refs/heads/main\n")
        return true
    }

    /**
     * Unpacks [zipBytes] into [targetDir].
     * If [stripSinglePrefix] is true, strips a single shared top-level directory prefix.
     */
    fun extractZip(zipBytes: ByteArray, targetDir: String, stripSinglePrefix: Boolean = false): Boolean {
        val end = findEndOfCentralDirectory(zipBytes) ?: return false
        val count = readShort(zipBytes, end + 10)
        if (count <= 0) return false
        var offset = readInt(zipBytes, end + 16)

        data class Entry(
            val name: String,
            val isDir: Boolean,
            val compressedSize: Int,
            val uncompressedSize: Int,
            val method: Int,
            val localOffset: Int,
        )

        val entries = ArrayList<Entry>(count)
        repeat(count) {
            if (offset < 0 || offset + CENTRAL_HEADER_SIZE > zipBytes.size) return false
            if (readInt(zipBytes, offset) != CENTRAL_SIGNATURE) return false
            val method = readShort(zipBytes, offset + 10)
            val compressed = readInt(zipBytes, offset + 20)
            val uncompressed = readInt(zipBytes, offset + 24)
            val nameLen = readShort(zipBytes, offset + 28)
            val extraLen = readShort(zipBytes, offset + 30)
            val commentLen = readShort(zipBytes, offset + 32)
            val localOffset = readInt(zipBytes, offset + 42)
            val nameStart = offset + CENTRAL_HEADER_SIZE
            if (nameStart + nameLen > zipBytes.size) return false
            val rawName = zipBytes.decodeToString(nameStart, nameStart + nameLen).replace('\\', '/')
            entries.add(
                Entry(
                    name = rawName,
                    isDir = rawName.endsWith("/"),
                    compressedSize = compressed,
                    uncompressedSize = uncompressed,
                    method = method,
                    localOffset = localOffset,
                ),
            )
            offset = nameStart + nameLen + extraLen + commentLen
        }

        val stripPrefix = if (stripSinglePrefix) {
            val firstSegs = entries.mapNotNull { e ->
                val trimmed = e.name.trimStart('/')
                val slash = trimmed.indexOf('/')
                if (slash > 0) trimmed.substring(0, slash + 1) else null
            }.distinct()
            if (firstSegs.size == 1) firstSegs.first() else ""
        } else ""

        IosFiles.mkdirs(targetDir)
        var extractedFiles = 0

        for (entry in entries) {
            val clean = entry.name.trimStart('/')
            val rel = if (stripPrefix.isNotEmpty() && clean.startsWith(stripPrefix)) {
                clean.removePrefix(stripPrefix)
            } else {
                clean
            }
            if (rel.isEmpty() || rel.contains("..")) continue
            val outPath = IosFiles.join(targetDir, rel.trimEnd('/'))
            if (entry.isDir) {
                IosFiles.mkdirs(outPath)
                continue
            }
            val header = entry.localOffset
            if (header < 0 || header + LOCAL_HEADER_SIZE > zipBytes.size) continue
            if (readInt(zipBytes, header) != LOCAL_SIGNATURE) continue
            val nameLen = readShort(zipBytes, header + 26)
            val extraLen = readShort(zipBytes, header + 28)
            val dataStart = header + LOCAL_HEADER_SIZE + nameLen + extraLen
            val dataEnd = dataStart + entry.compressedSize
            if (dataStart < 0 || dataEnd > zipBytes.size || dataEnd < dataStart) continue
            val raw = zipBytes.copyOfRange(dataStart, dataEnd)
            val content = when (entry.method) {
                METHOD_STORED -> raw
                METHOD_DEFLATED -> inflateRaw(raw, entry.uncompressedSize) ?: continue
                else -> continue
            }
            val parent = outPath.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) IosFiles.mkdirs(parent)
            if (IosFiles.writeBytes(outPath, content)) {
                extractedFiles++
            }
        }

        return extractedFiles > 0
    }

    private fun findEndOfCentralDirectory(bytes: ByteArray): Int? {
        val min = 22
        if (bytes.size < min) return null
        val floor = maxOf(0, bytes.size - min - 0xFFFF)
        for (i in bytes.size - min downTo floor) {
            if (readInt(bytes, i) == END_SIGNATURE) return i
        }
        return null
    }

    private fun readInt(bytes: ByteArray, at: Int): Int {
        if (at < 0 || at + 4 > bytes.size) return -1
        return (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }

    private fun readShort(bytes: ByteArray, at: Int): Int {
        if (at < 0 || at + 2 > bytes.size) return -1
        return (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    }

    private fun inflateRaw(data: ByteArray, expectedSize: Int): ByteArray? = memScoped {
        if (data.isEmpty()) return if (expectedSize == 0) ByteArray(0) else null
        val stream = alloc<z_stream>()
        if (inflateInit2(stream.ptr, -15) != Z_OK) return null
        try {
            var out = ByteArray(maxOf(expectedSize.coerceAtLeast(0), 1024))
            var produced = 0
            data.usePinned { input ->
                stream.next_in = input.addressOf(0).reinterpret()
                stream.avail_in = data.size.toUInt()
                while (true) {
                    if (produced == out.size) out = out.copyOf(out.size * 2)
                    val status = out.usePinned { sink ->
                        stream.next_out = sink.addressOf(produced).reinterpret()
                        stream.avail_out = (out.size - produced).toUInt()
                        val code = inflate(stream.ptr, Z_NO_FLUSH)
                        produced = out.size - stream.avail_out.toInt()
                        code
                    }
                    if (status == Z_STREAM_END) return@usePinned
                    if (status != Z_OK) return null
                }
            }
            out.copyOf(produced)
        } finally {
            inflateEnd(stream.ptr)
        }
    }
}
