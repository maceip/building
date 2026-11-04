package dev.ide.gradle

import dev.ide.platform.JsonReader
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The arm64 link libraries for every API level in NDK r27c. The editor's `toolchain.zip` ships
 * one level (API 26). A project with another `minSdk` links `--target=aarch64-linux-android<api>`,
 * so the missing level has to be in the sysroot before CMake runs.
 *
 * The pinned file is Google's linux NDK zip. Only
 * `linux-x86_64/sysroot/usr/lib/aarch64-linux-android/` is kept. The x86_64 clang in that zip is
 * discarded. The checksum is the SHA-1 Google publishes for that zip in `repository2-1.xml`.
 */
object Arm64Sysroot {

    const val TRIPLE = "aarch64-linux-android"
    private const val MARKER = "linux-x86_64/sysroot/usr/lib/$TRIPLE/"
    private const val ATTEMPTS = 3

    data class Catalog(val url: String, val size: Long, val algorithm: String, val checksum: String)

    class ChecksumMismatch(val expected: String, val actual: String) :
        Exception("arm64 sysroot checksum mismatch: expected $expected, got $actual")

    fun catalog(): Catalog {
        val text = Arm64Sysroot::class.java.classLoader
            .getResourceAsStream("dev/ide/gradle/arm64-sysroot-catalog.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("arm64-sysroot-catalog.json is missing")
        val root = JsonReader.obj(JsonReader.parse(text)) ?: error("arm64 sysroot catalog is not an object")
        return Catalog(
            url = JsonReader.str(root, "url") ?: error("arm64 sysroot catalog has no url"),
            size = JsonReader.long(root, "size"),
            algorithm = JsonReader.str(root, "algorithm") ?: "sha1",
            checksum = JsonReader.str(root, "checksum") ?: error("arm64 sysroot catalog has no checksum"),
        )
    }

    /**
     * Download the pinned zip when [cache] has no matching stamp, verify it, and keep only the
     * arm64 libraries. A checksum mismatch is not retried. A network failure is retried three times.
     */
    fun ensure(cache: Path, catalog: Catalog, download: (String, Path) -> Unit) {
        val libs = cache.resolve("usr/lib").resolve(TRIPLE)
        val stamp = cache.resolve(".stamp")
        if (Files.isRegularFile(stamp) &&
            stamp.readText().trim().equals(catalog.checksum, ignoreCase = true) &&
            Files.isDirectory(libs)
        ) {
            return
        }
        val zip = Files.createTempFile("arm64-sysroot-", ".zip")
        try {
            downloadRetrying(catalog.url, zip, download)
            val actual = digest(zip, catalog.algorithm)
            if (!actual.equals(catalog.checksum, ignoreCase = true)) {
                throw ChecksumMismatch(catalog.checksum, actual)
            }
            if (Files.exists(cache)) cache.toFile().deleteRecursively()
            extract(zip, cache)
            Files.createDirectories(cache)
            stamp.writeText(catalog.checksum.lowercase())
        } finally {
            Files.deleteIfExists(zip)
        }
    }

    /** Copy library files from [cache] that the NDK sysroot does not already have. */
    fun overlay(ndkSysroot: Path, cache: Path) {
        val from = cache.resolve("usr/lib").resolve(TRIPLE)
        if (!Files.isDirectory(from)) return
        val to = ndkSysroot.resolve("usr/lib").resolve(TRIPLE)
        Files.createDirectories(to)
        Files.walk(from).use { stream ->
            stream.forEach { src ->
                val dest = to.resolve(from.relativize(src).toString())
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest)
                } else if (!Files.exists(dest)) {
                    Files.createDirectories(dest.parent)
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    fun apiDirPresent(ndkRoot: Path, minSdk: Int): Boolean {
        val api = ndkRoot.resolve("toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib")
            .resolve(TRIPLE).resolve(minSdk.toString())
        if (!Files.isDirectory(api)) return false
        return Files.list(api).use { it.findAny().isPresent }
    }

    private fun downloadRetrying(url: String, dest: Path, download: (String, Path) -> Unit) {
        var last: Exception? = null
        repeat(ATTEMPTS) {
            try {
                Files.deleteIfExists(dest)
                download(url, dest)
                return
            } catch (mismatch: ChecksumMismatch) {
                throw mismatch
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("download failed: $url")
    }

    private fun extract(zip: Path, cache: Path) {
        ZipFile(zip.toFile()).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                val at = name.indexOf(MARKER)
                if (at < 0) continue
                val rel = name.substring(at + MARKER.length)
                if (rel.isEmpty() || rel.split('/').any { it == ".." }) continue
                val dest = cache.resolve("usr/lib").resolve(TRIPLE).resolve(rel).normalize()
                val root = cache.resolve("usr/lib").resolve(TRIPLE).normalize()
                if (!dest.startsWith(root)) continue
                Files.createDirectories(dest.parent)
                archive.getInputStream(entry).use { input ->
                    Files.copy(input, dest, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun digest(file: Path, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        Files.newInputStream(file).use { input -> update(md, input) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun update(md: MessageDigest, input: InputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
}
