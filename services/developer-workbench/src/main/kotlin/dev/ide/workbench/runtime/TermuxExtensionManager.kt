package dev.ide.workbench.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Pure-Kotlin on-demand Termux `.deb` dependency-closure installer (ported from
 * `Soodok/Deepseek-Harness-Local-Android`'s `ExtensionManager.kt`).
 *
 * Capabilities:
 * - Catalog of 19 curated developer toolchains (`python`, `golang`, `rust`, `clang`, `openjdk-17`,
 *   `git`, `ruby`, `php`, `perl`, `lua54`, `sqlite`, `ffmpeg`, `imagemagick`, `openssh`,
 *   `android-tools`, `vim`, `cmake`, `ninja`, `jq`).
 * - Parses Debian `Packages` / `Packages.gz` repository indices and resolves full transitive
 *   `Depends` closures across virtual/versioned package constraints.
 * - Verifies SHA-256 digests of every downloaded `.deb` before unpacking.
 * - Extracts `.deb` (`ar` archive -> `data.tar.xz` / `data.tar.gz` / `data.tar`) in pure JVM/Kotlin
 *   without requiring external `dpkg` or `tar` binaries, preserving symlinks, hardlinks, and
 *   executable file modes.
 * - Strips the `data/data/com.termux/files/usr/` prefix and automatically rewrites hardcoded
 *   `/data/data/com.termux/files/usr` shebangs to the target runtime prefix.
 */
class TermuxExtensionManager(
    private val extensionsRoot: Path,
    private val runtimePrefix: Path,
    private val repositoryBaseUrl: String = DEFAULT_TERMUX_REPO,
    private val architecture: String = "aarch64",
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    data class ExtensionSpec(
        val id: String,
        val title: String,
        val description: String,
        val rootPackages: List<String>,
        val verifyBinary: String,
    )

    data class DebPackageRecord(
        val name: String,
        val version: String,
        val filename: String,
        val sha256: String,
        val size: Long = 0L,
        val depends: List<String> = emptyList(),
        val provides: List<String> = emptyList(),
    )

    data class InstalledExtension(
        val id: String,
        val title: String,
        val installDir: Path,
        val installedPackages: List<String>,
        val binDir: Path,
        val libDir: Path,
    )

    private val installMutex = Mutex()
    @Volatile private var cachedIndex: Map<String, DebPackageRecord>? = null

    fun catalog(): List<ExtensionSpec> = BUILTIN_CATALOG

    fun findSpec(id: String): ExtensionSpec? =
        BUILTIN_CATALOG.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }

    fun listInstalled(): List<InstalledExtension> {
        if (!Files.isDirectory(extensionsRoot)) return emptyList()
        return BUILTIN_CATALOG.mapNotNull { spec ->
            val dir = extensionsRoot.resolve(spec.id)
            val stamp = dir.resolve(".installed")
            if (Files.isRegularFile(stamp)) {
                val pkgs = runCatching {
                    RuntimeFiles.readUtf8(stamp).lines().map { it.trim() }.filter { it.isNotEmpty() }
                }.getOrDefault(emptyList())
                InstalledExtension(
                    id = spec.id,
                    title = spec.title,
                    installDir = dir,
                    installedPackages = pkgs,
                    binDir = dir.resolve("bin"),
                    libDir = dir.resolve("lib"),
                )
            } else {
                null
            }
        }
    }

    /**
     * Computes additional `PATH` and `LD_LIBRARY_PATH` directories for all installed extensions.
     */
    fun activeEnvironmentPaths(): Pair<List<Path>, List<Path>> {
        val installed = listInstalled()
        val binDirs = installed.map { it.binDir }.filter { Files.isDirectory(it) }
        val libDirs = installed.map { it.libDir }.filter { Files.isDirectory(it) }
        return binDirs to libDirs
    }

    /**
     * Installs an extension from [BUILTIN_CATALOG] (or a custom package list) by resolving its full
     * transitive `.deb` dependency closure, verifying SHA-256 checksums, extracting, and rewriting shebangs.
     */
    suspend fun installExtension(
        extensionId: String,
        onProgress: (String) -> Unit = {},
    ): InstalledExtension = withContext(Dispatchers.IO) {
        installMutex.withLock {
            val spec = findSpec(extensionId)
                ?: ExtensionSpec(
                    id = extensionId.lowercase().replace(Regex("[^a-z0-9._-]"), "-"),
                    title = extensionId,
                    description = "Custom Termux package extension ($extensionId)",
                    rootPackages = listOf(extensionId),
                    verifyBinary = extensionId,
                )

            onProgress("Loading Termux package index ($architecture)...")
            val index = loadOrFetchIndex()
            val closure = resolveDependencyClosure(spec.rootPackages, index)
            require(closure.isNotEmpty()) {
                "No matching packages found in Termux index for '${spec.id}' (${spec.rootPackages.joinToString()})"
            }

            Files.createDirectories(extensionsRoot)
            val stagingDir = extensionsRoot.resolve(".staging-${spec.id}-${System.nanoTime()}")
            val targetDir = extensionsRoot.resolve(spec.id)
            val cacheDebDir = extensionsRoot.resolve(".deb-cache")
            Files.createDirectories(stagingDir)
            Files.createDirectories(cacheDebDir)

            try {
                for ((idx, pkg) in closure.withIndex()) {
                    onProgress("[${idx + 1}/${closure.size}] Installing ${pkg.name} (${pkg.version})...")
                    val debFile = downloadAndVerifyDeb(pkg, cacheDebDir)
                    extractDebArchive(debFile, stagingDir, runtimePrefix)
                }

                RuntimeFiles.writeUtf8(
                    stagingDir.resolve(".installed"),
                    closure.joinToString("\n") { "${it.name}=${it.version}" } + "\n",
                )

                if (Files.exists(targetDir)) {
                    deleteRecursively(targetDir)
                }
                Files.move(stagingDir, targetDir, StandardCopyOption.REPLACE_EXISTING)

                InstalledExtension(
                    id = spec.id,
                    title = spec.title,
                    installDir = targetDir,
                    installedPackages = closure.map { it.name },
                    binDir = targetDir.resolve("bin"),
                    libDir = targetDir.resolve("lib"),
                )
            } finally {
                if (Files.exists(stagingDir)) {
                    runCatching { deleteRecursively(stagingDir) }
                }
            }
        }
    }

    private fun loadOrFetchIndex(): Map<String, DebPackageRecord> {
        cachedIndex?.let { return it }
        val gzUrl = "${repositoryBaseUrl.trimEnd('/')}/dists/stable/main/binary-$architecture/Packages.gz"
        val plainUrl = "${repositoryBaseUrl.trimEnd('/')}/dists/stable/main/binary-$architecture/Packages"

        val text = runCatching {
            fetchBytes(gzUrl).let { bytes ->
                GzipCompressorInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).readText()
            }
        }.getOrElse {
            String(fetchBytes(plainUrl), Charsets.UTF_8)
        }

        val parsed = parsePackagesIndex(text)
        cachedIndex = parsed
        return parsed
    }

    private fun downloadAndVerifyDeb(pkg: DebPackageRecord, cacheDir: Path): Path {
        val safeFile = pkg.filename.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._+-]"), "_")
        val target = cacheDir.resolve(safeFile)
        if (Files.isRegularFile(target) && sha256Hex(target).equals(pkg.sha256, ignoreCase = true)) {
            return target
        }
        val url = "${repositoryBaseUrl.trimEnd('/')}/${pkg.filename.trimStart('/')}"
        val bytes = fetchBytes(url)
        val actualHash = sha256Hex(bytes)
        require(actualHash.equals(pkg.sha256, ignoreCase = true)) {
            "SHA-256 mismatch for ${pkg.name}: expected ${pkg.sha256}, got $actualHash"
        }
        Files.write(target, bytes)
        return target
    }

    private fun fetchBytes(url: String): ByteArray {
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} fetching $url")
            }
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    companion object {
        const val DEFAULT_TERMUX_REPO = "https://packages.termux.dev/apt/termux-main"
        private const val TERMUX_USR_PREFIX = "data/data/com.termux/files/usr/"
        private const val TERMUX_ABS_PREFIX = "/data/data/com.termux/files/usr"

        val BUILTIN_CATALOG: List<ExtensionSpec> = listOf(
            ExtensionSpec("python", "Python 3", "CPython interpreter, pip, and standard library", listOf("python"), "python3"),
            ExtensionSpec("golang", "Go Toolchain", "Go compiler and standard library", listOf("golang"), "go"),
            ExtensionSpec("rust", "Rust & Cargo", "Rust compiler and Cargo package manager", listOf("rust"), "rustc"),
            ExtensionSpec("clang", "LLVM / Clang", "Clang C/C++ compiler, lld, make, pkg-config", listOf("clang", "make", "pkg-config"), "clang"),
            ExtensionSpec("openjdk-17", "OpenJDK 17", "Java 17 JDK and runtime", listOf("openjdk-17"), "java"),
            ExtensionSpec("git", "Git", "Distributed version control system with HTTPS/SSH support", listOf("git"), "git"),
            ExtensionSpec("ruby", "Ruby", "Ruby interpreter and Rubygems", listOf("ruby"), "ruby"),
            ExtensionSpec("php", "PHP", "PHP CLI and runtime", listOf("php"), "php"),
            ExtensionSpec("perl", "Perl", "Perl 5 interpreter", listOf("perl"), "perl"),
            ExtensionSpec("lua54", "Lua 5.4", "Lua 5.4 scripting engine", listOf("lua54"), "lua5.4"),
            ExtensionSpec("sqlite", "SQLite 3", "SQLite command-line shell and library", listOf("sqlite"), "sqlite3"),
            ExtensionSpec("ffmpeg", "FFmpeg", "Audio/video transcoding and stream inspection tools", listOf("ffmpeg"), "ffmpeg"),
            ExtensionSpec("imagemagick", "ImageMagick", "Image manipulation and conversion suite", listOf("imagemagick"), "magick"),
            ExtensionSpec("openssh", "OpenSSH", "SSH client, scp, sftp, and keygen", listOf("openssh"), "ssh"),
            ExtensionSpec("android-tools", "Android Platform Tools", "ADB and fastboot CLI utilities", listOf("android-tools"), "adb"),
            ExtensionSpec("vim", "Vim", "Terminal text editor", listOf("vim"), "vim"),
            ExtensionSpec("cmake", "CMake", "Cross-platform native build system generator", listOf("cmake"), "cmake"),
            ExtensionSpec("ninja", "Ninja", "Fast low-level build system", listOf("ninja"), "ninja"),
            ExtensionSpec("jq", "jq", "Command-line JSON processor", listOf("jq"), "jq"),
        )

        /**
         * Parses a Debian `Packages` control file into a map keyed by package name (and virtual `Provides` aliases).
         */
        fun parsePackagesIndex(indexText: String): Map<String, DebPackageRecord> {
            val map = LinkedHashMap<String, DebPackageRecord>()
            val providesMap = LinkedHashMap<String, DebPackageRecord>()

            var pkgName: String? = null
            var version: String? = null
            var filename: String? = null
            var sha256: String? = null
            var size = 0L
            var dependsRaw: String? = null
            var providesRaw: String? = null

            fun flushRecord() {
                val n = pkgName
                val v = version
                val f = filename
                val s = sha256
                if (!n.isNullOrBlank() && !v.isNullOrBlank() && !f.isNullOrBlank() && !s.isNullOrBlank()) {
                    val record = DebPackageRecord(
                        name = n,
                        version = v,
                        filename = f,
                        sha256 = s,
                        size = size,
                        depends = parseDependsList(dependsRaw.orEmpty()),
                        provides = parseDependsList(providesRaw.orEmpty()),
                    )
                    map[n] = record
                    for (provided in record.provides) {
                        providesMap.putIfAbsent(provided, record)
                    }
                }
                pkgName = null
                version = null
                filename = null
                sha256 = null
                size = 0L
                dependsRaw = null
                providesRaw = null
            }

            for (rawLine in indexText.lineSequence()) {
                val line = rawLine.trimEnd('\r')
                if (line.isBlank()) {
                    flushRecord()
                    continue
                }
                if (line.startsWith(" ") || line.startsWith("\t")) continue
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                when (key) {
                    "Package" -> pkgName = value
                    "Version" -> version = value
                    "Filename" -> filename = value
                    "SHA256" -> sha256 = value
                    "Size" -> size = value.toLongOrNull() ?: 0L
                    "Depends" -> dependsRaw = value
                    "Provides" -> providesRaw = value
                }
            }
            flushRecord()

            for ((alias, record) in providesMap) {
                map.putIfAbsent(alias, record)
            }
            return map
        }

        /**
         * Parses a Debian `Depends:` header (e.g. `libandroid-support, libc++, zlib (>= 1.2.11) | libz`)
         * into canonical package names, stripping version constraints and selecting the primary alternative.
         */
        fun parseDependsList(raw: String): List<String> {
            if (raw.isBlank()) return emptyList()
            return raw.split(',')
                .mapNotNull { clause ->
                    val primary = clause.substringBefore('|').trim()
                    val name = primary.substringBefore('(').trim().substringBefore(':').trim()
                    name.takeIf { it.isNotEmpty() }
                }
                .distinct()
        }

        /**
         * Resolves the topological transitive dependency closure for [rootPackages] against [index].
         */
        fun resolveDependencyClosure(
            rootPackages: List<String>,
            index: Map<String, DebPackageRecord>,
        ): List<DebPackageRecord> {
            val visited = LinkedHashSet<String>()
            val ordered = mutableListOf<DebPackageRecord>()

            fun visit(pkgName: String) {
                val record = index[pkgName] ?: return
                if (!visited.add(record.name)) return
                for (dep in record.depends) {
                    visit(dep)
                }
                ordered += record
            }

            for (root in rootPackages) {
                visit(root)
            }
            return ordered
        }

        /**
         * Extracts a `.deb` archive (`ar` container with `data.tar.xz` / `data.tar.gz` / `data.tar`)
         * into [destRoot], stripping `data/data/com.termux/files/usr/` and rewriting hardcoded Termux
         * shebangs to [runtimePrefix].
         */
        fun extractDebArchive(debFile: Path, destRoot: Path, runtimePrefix: Path) {
            Files.newInputStream(debFile).use { rawIn ->
                ArArchiveInputStream(BufferedInputStream(rawIn)).use { arIn ->
                    while (true) {
                        val arEntry = arIn.nextEntry ?: break
                        val name = arEntry.name.removeSuffix("/")
                        if (name.startsWith("data.tar")) {
                            val dataStream: InputStream = when {
                                name.endsWith(".xz") -> XZCompressorInputStream(arIn)
                                name.endsWith(".gz") -> GzipCompressorInputStream(arIn)
                                else -> arIn
                            }
                            extractDataTar(dataStream, destRoot, runtimePrefix)
                            break
                        }
                    }
                }
            }
        }

        fun extractDataTar(dataStream: InputStream, destRoot: Path, runtimePrefix: Path) {
            val normalizedRoot = destRoot.toAbsolutePath().normalize()
            val pendingLinks = mutableListOf<Pair<Path, Path>>()

            TarArchiveInputStream(BufferedInputStream(dataStream)).use { tar ->
                while (true) {
                    val entry: TarArchiveEntry = tar.nextEntry ?: break
                    val stripped = stripTermuxPrefix(entry.name)
                    if (stripped.isEmpty()) continue
                    val target = normalizedRoot.resolve(stripped).normalize()
                    require(target.startsWith(normalizedRoot)) {
                        "Tar entry escapes destination root: ${entry.name}"
                    }

                    when {
                        entry.isDirectory -> {
                            Files.createDirectories(target)
                        }
                        entry.isSymbolicLink -> {
                            Files.createDirectories(target.parent)
                            Files.deleteIfExists(target)
                            val linkTarget = rewriteTermuxLinkTarget(entry.linkName, runtimePrefix)
                            Files.createSymbolicLink(target, Path.of(linkTarget))
                        }
                        entry.isLink -> {
                            val linkedRel = stripTermuxPrefix(entry.linkName)
                            if (linkedRel.isNotEmpty()) {
                                val linkSource = normalizedRoot.resolve(linkedRel).normalize()
                                if (linkSource.startsWith(normalizedRoot)) {
                                    pendingLinks += target to linkSource
                                }
                            }
                        }
                        entry.isFile -> {
                            Files.createDirectories(target.parent)
                            if (Files.isSymbolicLink(target)) {
                                Files.deleteIfExists(target)
                            }
                            Files.newOutputStream(target).use { out ->
                                tar.copyTo(out)
                            }
                            rewriteShebangIfNeeded(target, runtimePrefix)
                            val mode = entry.mode
                            if ((mode and 0b001_001_001) != 0 || target.parent?.fileName?.toString() == "bin") {
                                target.toFile().setExecutable(true, false)
                            }
                        }
                    }
                }
            }

            for ((target, source) in pendingLinks) {
                if (Files.exists(source)) {
                    Files.createDirectories(target.parent)
                    Files.deleteIfExists(target)
                    runCatching {
                        Files.createLink(target, source)
                    }.onFailure {
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }

        fun stripTermuxPrefix(rawPath: String): String {
            val clean = rawPath.trimStart('.', '/').trim()
            val stripped = when {
                clean.startsWith(TERMUX_USR_PREFIX) -> clean.removePrefix(TERMUX_USR_PREFIX)
                clean == TERMUX_USR_PREFIX.trimEnd('/') -> ""
                else -> clean
            }
            return stripped.trimStart('/')
        }

        fun rewriteTermuxLinkTarget(rawLink: String, runtimePrefix: Path): String {
            return if (rawLink.startsWith(TERMUX_ABS_PREFIX)) {
                runtimePrefix.toString() + rawLink.removePrefix(TERMUX_ABS_PREFIX)
            } else {
                rawLink
            }
        }

        /**
         * Rewrites `#! /data/data/com.termux/files/usr/...` shebangs in text scripts to [runtimePrefix].
         */
        fun rewriteShebangIfNeeded(file: Path, runtimePrefix: Path): Boolean {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false
            val size = runCatching { Files.size(file) }.getOrDefault(0L)
            if (size < 4L || size > 2L * 1024 * 1024) return false

            val head = ByteArray(minOf(size.toInt(), 256))
            Files.newInputStream(file).use { it.read(head) }
            if (head[0] != '#'.code.toByte() || head[1] != '!'.code.toByte()) return false

            val firstLineEnd = head.indexOf('\n'.code.toByte()).let { if (it < 0) head.size else it }
            val firstLine = String(head, 0, firstLineEnd, Charsets.UTF_8)
            if (!firstLine.contains(TERMUX_ABS_PREFIX)) return false

            val fullBytes = Files.readAllBytes(file)
            val fullText = String(fullBytes, Charsets.UTF_8)
            val rewritten = fullText.replace(TERMUX_ABS_PREFIX, runtimePrefix.toString())
            if (rewritten != fullText) {
                Files.write(file, rewritten.toByteArray(Charsets.UTF_8))
                return true
            }
            return false
        }

        private fun sha256Hex(file: Path): String = Files.newInputStream(file).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(32768)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun deleteRecursively(path: Path) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.list(path).use { stream ->
                    stream.toList().forEach { deleteRecursively(it) }
                }
            }
            Files.deleteIfExists(path)
        }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
