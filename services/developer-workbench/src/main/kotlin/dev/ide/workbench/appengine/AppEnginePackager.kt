// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.workbench.appengine

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

/** Build-time assembly only. It copies a selected source-built library, not the IDE or a runtime prefix.
 * Output is an ordinary native-model Android project and can be built/installed independently.
 */
class AppEnginePackager {
    fun create(request: AppEnginePackageRequest): AppEnginePackageResult {
        require(request.acknowledgeGplDistribution) { "Runtime linking exception not granted: acknowledge GPL distribution obligations before generating" }
        require(request.engine != AppEngine.Node || request.allowUnqualifiedNode) { "Node Android is unqualified upstream; explicit internal qualification opt-in required" }
        require(request.applicationId.matches(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){2,}"))) { "Invalid application id" }
        require(request.label.length in 1..100 && request.label.none { it.code < 32 }) { "Invalid app label" }
        require(request.arguments.size <= 128 && request.arguments.none { '\u0000' in it }) { "Invalid script arguments" }
        require(request.arguments.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } <= 32768L) { "Script arguments exceed 32768 UTF-8 bytes" }
        val relativeEntry = relative(request.entry)
        require(request.entry.substringAfterLast('.', "") in request.engine.extensions) { "Entry extension must match engine" }
        val source = request.sourceRoot.toRealPath()
        val destination = request.destination.toAbsolutePath().normalize()
        require(!Files.exists(destination)) { "Destination already exists; refusing to overwrite" }
        val parent = requireNotNull(destination.parent).toRealPath()
        require(!parent.startsWith(source)) { "Output cannot be inside source input" }
        val payload = request.payloadRoot.toRealPath()
        require(!payload.startsWith(source)) { "Payload cannot be part of application source input" }
        val props = Properties().apply {
            Files.newInputStream(payload.resolve("assets/runtime-engine/${request.engine.id}/engine.properties")).use(::load)
        }
        require(props.getProperty("schema") == "1" && props.getProperty("engine") == request.engine.id) { "Payload descriptor mismatch" }
        require(props.getProperty("abi") == "arm64-v8a" && props.getProperty("api") == "26") { "Unqualified payload ABI/API" }
        val sourceBundle = payload.resolve("${request.engine.id}-${props.getProperty("version")}-corresponding-source.tar.xz")
        require(Files.isRegularFile(sourceBundle, NOFOLLOW_LINKS) && Files.size(sourceBundle) > 1024) { "Actual corresponding-source bundle is missing" }
        val payloadIndex = checksumIndex(payload)
        require(sourceBundle in payloadIndex) { "Corresponding source archive is not covered by payload hashes" }
        for ((file, sha) in payloadIndex) require(hash(file) == sha) { "Payload checksum mismatch: $file" }
        val runtimeJar = request.runtimeClassesJar.toRealPath()
        ZipFile(runtimeJar.toFile()).use { zip ->
            for (name in listOf("EngineClient", "RuntimeService", "NativeBridge"))
                require(zip.getEntry("dev/ide/runtime/engine/$name.class") != null) { "Incomplete runtime library: $name" }
            require(zip.entries().asSequence().none { it.name.startsWith("dev/ide/android/") }) { "IDE classes must not leak into generated apps" }
            require(zip.entries().asSequence().none { it.name.startsWith("dev/ide/applog/") || it.name.startsWith("dev/ide/runtime/engine/reliability/") }) {
                "SDK and listener must be supplied separately as debug-only dependencies"
            }
        }
        val reliabilityJars = request.reliability?.let { dependencies ->
            listOf(
                "libs/debug/applog-runtime.jar" to checkedClasses(dependencies.sdkClassesJar, "dev/ide/applog/", listOf("Reliability", "Config")),
                "libs/debug/runtime-engine-reliability.jar" to checkedClasses(dependencies.listenerClassesJar,
                    "dev/ide/runtime/engine/reliability/", listOf("ReliabilityEngineListener", "ReliabilityEngineListener\$Client")),
            )
        }.orEmpty()
        val libraries = nativeLibraries(payload, request.engine)
        require(libraries.all { it in payloadIndex }) { "Native libraries must be covered by payload hashes" }
        val scripts = sourceFiles(source)
        require(scripts.any { it.first == relativeEntry }) { "Entry not included in reviewed source files" }
        AppEngineSourceCheck.check(request.engine, scripts)
        val staging = Files.createTempDirectory(parent, ".engine-app-")
        var total = 0L
        try {
            AppEngineProject.files(request).forEach { (name, text) ->
                val file = staging.resolve(name); Files.createDirectories(file.parent); Files.writeString(file, text)
            }
            val jar = staging.resolve("libs/runtime-engine.jar"); Files.createDirectories(jar.parent); Files.copy(runtimeJar, jar)
            total += Files.size(jar)
            reliabilityJars.forEach { (name, file) ->
                val target = staging.resolve(name); Files.createDirectories(target.parent); Files.copy(file, target)
                total += Files.size(target)
            }
            libraries.forEach { file ->
                val target = staging.resolve("app/src/main/jniLibs/arm64-v8a/${file.fileName}")
                Files.createDirectories(target.parent); Files.copy(file, target); total += Files.size(target)
            }
            val engineAssets = payload.resolve("assets/runtime-engine/${request.engine.id}")
            Files.walk(engineAssets).use { files ->
                files.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.sorted().forEach { file ->
                    require(file in payloadIndex) { "Unhashed payload asset: $file" }
                    val target = staging.resolve("app/src/main/assets/runtime-engine/${request.engine.id}").resolve(engineAssets.relativize(file))
                    Files.createDirectories(target.parent); Files.copy(file, target); total += Files.size(target)
                }
            }
            val assets = staging.resolve("app/src/main/assets/runtime-engine/app")
            Files.createDirectories(assets)
            val index = StringBuilder()
            scripts.forEach { (name, original) ->
                val target = assets.resolve(name); Files.createDirectories(target.parent); Files.copy(original, target)
                val size = Files.size(target); total += size
                require(total <= MAX_BUNDLED_BYTES) { "Generated-app byte budget exceeded" }
                index.append(hash(target)).append('\t').append(size).append('\t').append(name).append('\n')
            }
            Files.writeString(assets.resolve("files.sha256"), index.toString())
            val licenseBundle = staging.resolve("runtime-sources/${sourceBundle.fileName}")
            Files.createDirectories(licenseBundle.parent); Files.copy(sourceBundle, licenseBundle)
            Files.copy(payload.resolve("SHA256SUMS"), staging.resolve("runtime-sources/PAYLOAD-SHA256SUMS"))
            // Include the application's actual scripts and generated Java/model alongside the engine archive.
            // The caller still owns GPL source-offer/distribution and all user-supplied package licensing.
            Files.move(staging, destination, ATOMIC_MOVE)
            return AppEnginePackageResult(destination, request.engine, total,
                destination.resolve("runtime-sources/${sourceBundle.fileName}"), AppEngineCapabilities.limitations(request.engine))
        } finally {
            if (Files.exists(staging)) Files.walk(staging).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun checkedClasses(input: Path, prefix: String, required: List<String>): Path {
        val file = input.toRealPath()
        require(Files.isRegularFile(file) && Files.size(file) in 1..16_777_216L) { "Expected a bounded classes jar" }
        ZipFile(file.toFile()).use { zip ->
            required.forEach { name -> require(zip.getEntry("$prefix$name.class") != null) { "Incomplete optional SDK classes: $name" } }
            require(zip.entries().asSequence().all { entry ->
                entry.isDirectory || entry.name.startsWith("META-INF/") ||
                    (entry.name.startsWith(prefix) && entry.name.endsWith(".class") && ".." !in entry.name)
            }) { "Optional SDK jars must contain only their own classes, not IDE, core, configuration or native payloads" }
        }
        return file
    }

    private fun checksumIndex(root: Path): Map<Path, String> {
        val file = root.resolve("SHA256SUMS")
        require(Files.size(file) <= 4 * 1024 * 1024) { "Payload index too large" }
        val entries = linkedMapOf<Path, String>()
        Files.readAllLines(file).forEach { line ->
            val parts = line.split("  ", limit = 2)
            require(parts.size == 2 && parts[0].matches(Regex("[a-f0-9]{64}"))) { "Invalid payload hash index" }
            val path = root.resolve(relative(parts[1]))
            require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) { "Missing/linked payload file" }
            require(entries.put(path, parts[0]) == null) { "Duplicate payload index path" }
        }
        require(entries.isNotEmpty()) { "Empty payload index" }
        return entries
    }
    private fun nativeLibraries(payload: Path, engine: AppEngine): List<Path> {
        val directory = payload.resolve("jniLibs/arm64-v8a")
        val expected = setOf("libengine_host.so", "libengine_${engine.id}.so", if (engine == AppEngine.Python) "libpython3.14.so" else "libnode.so")
        val result = Files.list(directory).use { it.toList() }
        require(result.map { it.fileName.toString() }.toSet() == expected) { "Payload contains missing/unselected native libraries" }
        result.forEach { file ->
            require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) in 4096..200_000_000L) { "Invalid native library" }
            Files.newInputStream(file).use { input ->
                val bytes = ByteArray(20); require(input.read(bytes) == bytes.size) { "Truncated ELF" }
                require(bytes[0] == 127.toByte() && bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()
                    && bytes[4] == 2.toByte() && bytes[5] == 1.toByte() && bytes[18] == 0xb7.toByte() && bytes[19] == 0.toByte()) { "Expected aarch64 ELF library" }
            }
        }
        return result
    }
    private fun sourceFiles(root: Path): List<Pair<String, Path>> {
        val result = mutableListOf<Pair<String, Path>>(); var bytes = 0L
        Files.walk(root).use { paths ->
            paths.sorted().forEach { path ->
                if (path == root) return@forEach
                require(!Files.isSymbolicLink(path)) { "Source symlinks are not packaged: $path" }
                val name = root.relativize(path).joinToString("/")
                // This is a reviewed app asset root, never silently skip credential files.
                require(name.split('/').none { it.startsWith('.') || it in setOf("build", "__pycache__") }) { "Hidden/build input must be removed from app asset root: $name" }
                relative(name)
                if (Files.isDirectory(path, NOFOLLOW_LINKS)) return@forEach
                require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Only regular application assets are allowed" }
                require(name.substringAfterLast('.', "").lowercase() !in setOf("so", "node", "dll", "pyd", "pyc", "dex", "apk", "jar", "exe", "sh")) { "Native/executable input not supported: $name" }
                bytes += Files.size(path)
                require(bytes <= MAX_APP_BYTES && result.size < MAX_FILES) { "Application asset budget exceeded" }
                require(name != "files.sha256") { "files.sha256 is reserved for the generated asset index" }
                result += name to path
            }
        }
        return result
    }
    private fun relative(name: String): String {
        require(name.length in 1..1024 && !name.startsWith('/') && name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Relative, unambiguous path required" }
        require(name.none { it.code < 32 || it.code == 127 || it == '\\' || it == ':' }) { "Invalid asset path" }
        return name
    }
    private fun hash(file: Path): String {
        val hash = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    companion object {
        const val MAX_FILES = 10000
        const val MAX_APP_BYTES = 64L * 1024 * 1024
        const val MAX_BUNDLED_BYTES = 256L * 1024 * 1024
    }
}
