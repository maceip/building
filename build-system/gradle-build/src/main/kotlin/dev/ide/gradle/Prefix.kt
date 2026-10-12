package dev.ide.gradle

import dev.ide.platform.JsonReader
import org.apache.commons.compress.utils.BoundedInputStream
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The bionic prefix (`context.filesDir/usr` on device). Packages are the pinned Termux
 * aarch64 debs in `bootstrap-catalog.json`. A checksum mismatch fails setup and is not
 * retried. A network failure may be retried a few times.
 *
 * `sh` is dash. The deb's `bin/sh` symlink is preserved; no other shell is installed.
 */
object Prefix {

    const val STRIP = "data/data/com.termux/files/usr/"
    private const val ATTEMPTS = 3

    data class Package(val name: String, val version: String, val filename: String, val sha256: String, val size: Long)

    data class Catalog(val baseUrl: String, val packages: List<Package>) {
        fun url(pkg: Package): String = baseUrl.trimEnd('/') + "/" + pkg.filename.trimStart('/')
    }

    class ChecksumMismatch(val pkg: String, val expected: String, val actual: String) :
        Exception("checksum mismatch for $pkg: expected $expected, got $actual")

    fun catalog(): Catalog {
        val text = Prefix::class.java.classLoader.getResourceAsStream("dev/ide/gradle/bootstrap-catalog.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("bootstrap-catalog.json is missing")
        val root = JsonReader.obj(JsonReader.parse(text)) ?: error("bootstrap catalog is not an object")
        val base = JsonReader.str(root, "baseUrl") ?: error("bootstrap catalog has no baseUrl")
        val packages = JsonReader.arr(root["packages"]).mapNotNull { item ->
            val obj = JsonReader.obj(item) ?: return@mapNotNull null
            Package(
                name = JsonReader.str(obj, "name") ?: return@mapNotNull null,
                version = JsonReader.str(obj, "version").orEmpty(),
                filename = JsonReader.str(obj, "filename") ?: return@mapNotNull null,
                sha256 = JsonReader.str(obj, "sha256") ?: return@mapNotNull null,
                size = JsonReader.long(obj, "size"),
            )
        }
        return Catalog(base, packages)
    }

    fun install(catalog: Catalog, prefix: Path, fetch: (String) -> ByteArray) {
        Files.createDirectories(prefix)
        for (pkg in catalog.packages) {
            val bytes = fetchRetrying(catalog.url(pkg), fetch)
            val actual = sha256(bytes)
            if (!actual.equals(pkg.sha256, ignoreCase = true)) {
                throw ChecksumMismatch(pkg.name, pkg.sha256, actual)
            }
            DebUnpacker.unpack(bytes, prefix)
        }
    }

    fun linkJava(prefix: Path, javaHome: Path) {
        val bin = prefix.resolve("bin")
        Files.createDirectories(bin)
        val link = bin.resolve("java")
        val target = javaHome.resolve("bin/java")
        Files.deleteIfExists(link)
        Files.createSymbolicLink(link, target)
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fetchRetrying(url: String, fetch: (String) -> ByteArray): ByteArray {
        var last: Exception? = null
        repeat(ATTEMPTS) {
            try {
                return fetch(url)
            } catch (mismatch: ChecksumMismatch) {
                throw mismatch
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("download failed: $url")
    }
}

object DebUnpacker {

    fun unpack(deb: ByteArray, dest: Path, strip: String = Prefix.STRIP) {
        ArArchiveInputStream(ByteArrayInputStream(deb)).use { ar ->
            var entry: ArArchiveEntry? = ar.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name.startsWith("data.tar")) {
                    unpackTar(BoundedInputStream(ar, entry.size), name, dest, strip)
                    return
                }
                entry = ar.nextEntry
            }
        }
    }

    private fun unpackTar(raw: InputStream, name: String, dest: Path, strip: String) {
        val uncompressed: InputStream = when {
            name.endsWith(".xz") -> XZCompressorInputStream(raw)
            name.endsWith(".gz") || name.endsWith(".tgz") -> GzipCompressorInputStream(raw)
            else -> raw
        }
        TarArchiveInputStream(uncompressed).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val rel = stripName(entry.name, strip)
                if (rel.isNotEmpty()) {
                    val out = dest.resolve(rel).normalize()
                    if (!out.startsWith(dest.normalize())) {
                        entry = tar.nextEntry
                        continue
                    }
                    when {
                        entry.isSymbolicLink -> {
                            Files.createDirectories(out.parent)
                            Files.deleteIfExists(out)
                            val original = entry.linkName
                            val packageRoot = "/" + strip.trim('/') + "/"
                            val target = if (original.startsWith(packageRoot)) {
                                dest.toAbsolutePath().resolve(original.removePrefix(packageRoot))
                            } else Path.of(original)
                            Files.createSymbolicLink(out, target)
                        }
                        entry.isDirectory -> Files.createDirectories(out)
                        else -> {
                            Files.createDirectories(out.parent)
                            Files.newOutputStream(out).use { tar.copyTo(it) }
                            if (entry.mode and 0b001_001_001 != 0) out.toFile().setExecutable(true)
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
    }

    private fun stripName(name: String, strip: String): String {
        var n = name.removePrefix("./")
        val prefix = strip.removePrefix("./")
        if (n.startsWith(prefix)) n = n.removePrefix(prefix)
        return n.trimStart('/')
    }
}

/**
 * The first process of an installed image. Both commands must print before any Gradle work.
 * The IDE process itself does not set `LD_PRELOAD`.
 */
object PrefixProbe {

    data class Command(val argv: List<String>, val env: Map<String, String>)

    fun linkerFor(abi: String?): String =
        if (abi == "armeabi-v7a" || abi == "x86") "/system/bin/linker" else "/system/bin/linker64"

    fun environment(prefix: Path, javaHome: Path, libraryPath: List<Path> = emptyList()): Map<String, String> {
        val libs = (listOf(prefix.resolve("lib")) + libraryPath).joinToString(":") { it.toString() }
        val relocator = libraryPath.map { it.resolve("libsdk-exec-prefix.so") }.firstOrNull { Files.isRegularFile(it) }
        val preload = listOfNotNull(relocator, prefix.resolve("lib/libtermux-exec.so")).joinToString(":")
        val path = listOf(prefix.resolve("bin"), javaHome.resolve("bin")).joinToString(":") + ":/system/bin:/system/xbin"
        val env = linkedMapOf(
            "JAVA_HOME" to javaHome.toString(),
            "LD_PRELOAD" to preload,
            "LD_LIBRARY_PATH" to libs,
            "PATH" to path,
            "HOME" to prefix.parent.toString(),
            // Android-host CMake also uses PREFIX to locate native-host headers in sub-builds.
            "PREFIX" to prefix.toString(),
            "TMPDIR" to prefix.parent.resolve("tmp").toString(),
            // The packaged JDK otherwise uses its compiled-in Termux home and temp paths.
            "JAVA_TOOL_OPTIONS" to "-Djava.io.tmpdir=${prefix.parent.resolve("tmp")} -Duser.home=${prefix.parent}",
            "LANG" to "C.UTF-8",
            // Child execs also need the linker on apps targeting Android 10 or newer.
            "TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE" to "force",
            "TERMUX__PREFIX" to prefix.toString(),
            "TERMUX_APP__DATA_DIR" to prefix.parent.parent.toString(),
            "TERMUX_APP__LEGACY_DATA_DIR" to prefix.parent.parent.toString()
                .replaceFirst(Regex("^/data/user/0/"), "/data/data/"),
        )
        val cert = prefix.resolve("etc/tls/cert.pem")
        if (Files.isRegularFile(cert)) env["SSL_CERT_FILE"] = cert.toString()
        return env
    }

    fun javaVersion(linker: String, javaHome: Path, prefix: Path): Command = Command(
        argv = listOf(linker, javaHome.resolve("bin/java").toString(), "-version"),
        env = environment(prefix, javaHome),
    )

    fun shellVersion(linker: String, javaHome: Path, prefix: Path): Command = Command(
        argv = listOf(linker, prefix.resolve("bin/sh").toString(), "-c", "java -version"),
        env = environment(prefix, javaHome),
    )
}
