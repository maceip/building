// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.android.workbench.appengine

import dev.ide.workbench.appengine.AppEngine
import dev.ide.workbench.appengine.AppEnginePackageRequest
import dev.ide.workbench.appengine.AppEnginePackageResult
import dev.ide.workbench.appengine.AppEnginePackager
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

internal data class EngineInputHash(val path: String, val bytes: Long, val sha256: String)

internal data class EnginePackageReview(
    val request: AppEnginePackageRequest,
    val sourceInputs: List<EngineInputHash>,
    val payloadInputs: List<EngineInputHash>,
    val sourceIdentity: String,
    val payloadIdentity: String,
    val runtimeIdentity: String,
    val version: String,
    val abi: String,
    val minimumApi: String,
    val license: String,
    val support: String,
    val qualification: String,
    val correspondingSource: EngineInputHash,
    val selectedSourceArchive: Path,
    val notice: String,
) {
    val sourceBytes: Long get() = sourceInputs.sumOf { it.bytes }
    val payloadBytes: Long get() = payloadInputs.sumOf { it.bytes }
}

/** File-only preflight/review. Actual project generation belongs exclusively to AppEnginePackager. */
internal object EnginePackageInputs {
    const val PAYLOAD_BUDGET: Long = 512L * 1024 * 1024
    private const val MAX_INDEX_BYTES: Long = 4L * 1024 * 1024
    private const val MAX_METADATA_BYTES: Long = 64L * 1024
    private val digestPattern = Regex("[a-f0-9]{64}")

    /** One literal argument per line, not shell syntax; an entirely empty field means no argv. */
    fun arguments(text: String): List<String> {
        val values = if (text.isEmpty()) emptyList() else text.replace("\r\n", "\n").split('\n')
        validateArguments(values)
        return values
    }

    private fun validateArguments(values: List<String>) {
        require(values.size <= 128) { "At most 128 script arguments are supported." }
        require(values.none { '\u0000' in it }) { "Script arguments must not contain NUL." }
        require(values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } <= 32768L) { "Script arguments exceed 32,768 UTF-8 bytes." }
    }

    fun sourceDirectory(workspace: String, relativeRoot: String): Path {
        val base = directory(workspace, "Source workspace")
        val relative = if (relativeRoot == ".") "" else relativeName(relativeRoot)
        val source = base.resolve(relative).normalize()
        require(source.startsWith(base)) { "Application source must stay inside the selected workspace." }
        noLinks(source)
        require(Files.isDirectory(source, NOFOLLOW_LINKS)) { "Application source folder does not exist." }
        return source.toRealPath()
    }

    fun destination(parent: String, folder: String): Path {
        require(folder.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))) {
            "Use a new project folder name (letters, digits, dot, dash or underscore; no path separators)."
        }
        val output = directory(parent, "Output parent").resolve(folder)
        require(!Files.exists(output, NOFOLLOW_LINKS)) { "Destination already exists; it will not be overwritten." }
        return output
    }

    fun directory(text: String, label: String): Path {
        require(text.isNotBlank()) { "$label is required; no open project is not a source folder." }
        val path = Paths.get(text)
        require(path.isAbsolute) { "$label must be an absolute local filesystem path." }
        require(path == path.normalize()) { "$label must not contain dot or parent path components." }
        require(!Files.isSymbolicLink(path)) { "$label must not itself be a linked folder." }
        require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "$label is not a readable local directory." }
        // Resolve platform aliases (for example Android /data/user/0) before enforcing containment.
        // Descendant links are still refused when walking this canonical selected root.
        return path.toRealPath()
    }

    fun expectedSourceArchive(payloadRoot: Path, engine: AppEngine): Path {
        val properties = descriptor(payloadRoot, engine)
        val version = properties.getProperty("version").orEmpty()
        require(version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}"))) { "Invalid payload version." }
        return payloadRoot.resolve("${engine.id}-$version-corresponding-source.tar.xz")
    }

    fun review(request: AppEnginePackageRequest, selectedSourceArchive: String): EnginePackageReview {
        require(request.applicationId.matches(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){2,}"))) { "Invalid Android application ID (for example com.example.myapp)." }
        require(request.label.length in 1..100 && request.label.none { it.code < 32 }) { "App label must contain 1–100 printable characters." }
        validateArguments(request.arguments)
        val entry = relativeName(request.entry)
        require(entry.substringAfterLast('.', "") in request.engine.extensions) { "Entry extension must match the selected engine." }
        val source = directory(request.sourceRoot.toString(), "Application source")
        val payload = directory(request.payloadRoot.toString(), "Local payload")
        val destination = request.destination.toAbsolutePath().normalize()
        noLinks(destination)
        require(!Files.exists(destination, NOFOLLOW_LINKS)) { "Destination already exists; it will not be overwritten." }
        val parent = directory(destination.parent?.toString().orEmpty(), "Output parent")
        require(!parent.startsWith(source) && !source.startsWith(destination)) { "Output must be separate from application source." }
        require(!payload.startsWith(source) && !source.startsWith(payload)) { "Payload and application source must be separate folders." }
        require(!parent.startsWith(payload)) { "Output must not be placed inside the selected payload." }
        val props = descriptor(payload, request.engine)
        val archive = expectedSourceArchive(payload, request.engine)
        require(selectedSourceArchive.isNotBlank()) { "Select the exact corresponding-source archive from the local payload." }
        val pickedPath = Paths.get(selectedSourceArchive)
        require(pickedPath.isAbsolute && pickedPath == pickedPath.normalize()) { "Corresponding source must be an absolute, unambiguous local file path." }
        require(!Files.isSymbolicLink(pickedPath)) { "Select a regular source-package file, not a link." }
        require(Files.isRegularFile(pickedPath, NOFOLLOW_LINKS) && Files.size(pickedPath) in 1025..PAYLOAD_BUDGET) { "Selected source package is missing, too small or exceeds 512 MiB." }
        // SAF may return /data/user/0 while the selected payload root has already been canonicalized.
        val chosen = pickedPath.toRealPath()
        require(chosen.fileName == archive.fileName) { "Select the exact ${archive.fileName} corresponding-source package." }
        noLinks(archive)
        require(Files.isRegularFile(archive, NOFOLLOW_LINKS) && Files.size(archive) > 1024L) { "Full corresponding-source archive is missing or empty inside the payload." }
        val sourceInputs = sourceHashes(source)
        require(sourceInputs.any { it.path == entry }) { "The explicit entry file was not found in the selected application source folder." }
        val payloadInputs = payloadHashes(payload)
        val archiveInput = payloadInputs.firstOrNull { it.path == archive.fileName.toString() }
            ?: error("Corresponding-source archive must be covered by payload SHA256SUMS.")
        require(Files.size(chosen) == archiveInput.bytes && (chosen == archive || hash(chosen) == archiveInput.sha256)) {
            "Selected source package does not exactly match this payload's checksummed corresponding-source archive."
        }
        val descriptorName = "assets/runtime-engine/${request.engine.id}/engine.properties"
        require(payloadInputs.any { it.path == descriptorName }) { "Engine descriptor is not covered by payload SHA256SUMS." }
        val noticeName = "assets/runtime-engine/${request.engine.id}/licenses/NOTICE.txt"
        require(payloadInputs.any { it.path == noticeName }) { "The payload's actual licensing NOTICE.txt is missing or unhashed." }
        val noticePath = payload.resolve(noticeName)
        require(Files.size(noticePath) <= MAX_METADATA_BYTES) { "License notice is too large to display safely." }
        val notice = Files.newBufferedReader(noticePath).use { it.readText() }
        require(notice.isNotBlank()) { "Payload licensing notice is empty." }
        validateRuntimeJar(request.runtimeClassesJar)
        return EnginePackageReview(
            request.copy(sourceRoot = source, payloadRoot = payload, destination = destination),
            sourceInputs, payloadInputs, identity(sourceInputs), identity(payloadInputs), hash(request.runtimeClassesJar),
            props.getProperty("version"), props.getProperty("abi"), props.getProperty("api"),
            props.getProperty("license", "Not declared — review actual notices"),
            props.getProperty("support", "Not declared"), props.getProperty("qualified", "Not declared"),
            archiveInput, chosen, notice,
        )
    }

    fun create(review: EnginePackageReview, acknowledgeGpl: Boolean, allowExperimentalNode: Boolean): AppEnginePackageResult {
        require(acknowledgeGpl) { "Acknowledge GPL distribution obligations before creating a project." }
        require(review.request.engine != AppEngine.Node || allowExperimentalNode) { "Node Android requires its own experimental qualification opt-in." }
        // Rehash on the worker thread, including the large source archive; never hold file contents in memory.
        val fresh = EnginePackageInputs.review(review.request, review.selectedSourceArchive.toString())
        require(fresh.sourceIdentity == review.sourceIdentity && fresh.payloadIdentity == review.payloadIdentity && fresh.runtimeIdentity == review.runtimeIdentity) {
            "An input changed after review. Review all inputs again before generating."
        }
        return AppEnginePackager().create(fresh.request.copy(
            acknowledgeGplDistribution = acknowledgeGpl,
            allowUnqualifiedNode = allowExperimentalNode,
        ))
    }

    private fun descriptor(root: Path, engine: AppEngine): Properties {
        val file = root.resolve("assets/runtime-engine/${engine.id}/engine.properties")
        noLinks(file)
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= MAX_METADATA_BYTES) { "Matching engine.properties is missing or too large." }
        return Properties().apply { Files.newInputStream(file).use(::load) }.also {
            require(it.getProperty("schema") == "1" && it.getProperty("engine") == engine.id) { "Payload descriptor does not match the selected engine." }
            require(it.getProperty("abi") == "arm64-v8a" && it.getProperty("api") == "26") { "Only the source-built arm64-v8a / Android API 26 payload is supported." }
        }
    }

    private fun sourceHashes(root: Path): List<EngineInputHash> {
        val result = mutableListOf<EngineInputHash>()
        var bytes = 0L
        var visited = 0
        Files.walk(root).use { paths ->
            paths.forEach { path ->
                checkInterrupted()
                require(++visited <= 20_001) { "Too many source files or directories." }
                if (path != root) {
                    require(!Files.isSymbolicLink(path)) { "Source links are not allowed: $path" }
                    val name = relativeName(root.relativize(path).joinToString("/"))
                    require(name.split('/').none { it.startsWith('.') || it in setOf("build", "__pycache__") }) {
                        "Hidden/build input is not packaged: $name. Select a clean app-source subfolder; nothing is silently excluded."
                    }
                    if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
                        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Only regular application files are allowed: $name" }
                        require(name.substringAfterLast('.', "").lowercase() !in setOf("so", "node", "dll", "pyd", "pyc", "dex", "apk", "jar", "exe", "sh")) { "Native/executable app input is not supported: $name" }
                        require(name != "files.sha256") { "files.sha256 is reserved for the generated input index." }
                        bytes += Files.size(path)
                        require(bytes <= AppEnginePackager.MAX_APP_BYTES && result.size < AppEnginePackager.MAX_FILES) { "Application input exceeds 64 MiB or 10,000 files." }
                        result += EngineInputHash(name, Files.size(path), hash(path))
                    }
                }
            }
        }
        require(result.isNotEmpty()) { "Application source is empty. Select a folder containing the explicit entry file." }
        return result.sortedBy { it.path }
    }

    private fun payloadHashes(root: Path): List<EngineInputHash> {
        val index = root.resolve("SHA256SUMS")
        noLinks(index)
        require(Files.isRegularFile(index, NOFOLLOW_LINKS) && Files.size(index) in 1..MAX_INDEX_BYTES) { "Payload SHA256SUMS is missing, empty or too large." }
        val result = mutableListOf<EngineInputHash>()
        val seen = mutableSetOf<String>()
        var total = 0L
        Files.newBufferedReader(index).use { reader ->
            reader.forEachLine { line ->
                checkInterrupted()
                require(result.size < 20_000) { "Too many payload checksum entries." }
                val parts = line.split("  ", limit = 2)
                require(parts.size == 2 && digestPattern.matches(parts[0])) { "Malformed payload SHA256SUMS." }
                val name = relativeName(parts[1])
                require(seen.add(name)) { "Duplicate payload checksum path: $name" }
                val path = root.resolve(name)
                noLinks(path)
                require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Missing payload file: $name" }
                val size = Files.size(path)
                total += size
                require(total <= PAYLOAD_BUDGET) { "Local payload exceeds the 512 MiB review/import budget." }
                val digest = hash(path)
                require(digest == parts[0]) { "Payload checksum mismatch: $name" }
                result += EngineInputHash(name, size, digest)
            }
        }
        require(result.isNotEmpty()) { "Payload SHA256SUMS has no entries." }
        // Include the index itself in the review identity so editing it also invalidates confirmation.
        result += EngineInputHash("SHA256SUMS", Files.size(index), hash(index))
        return result.sortedBy { it.path }
    }

    internal fun relativeName(name: String): String {
        require(name.length in 1..1024 && !name.startsWith('/') && name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Use an unambiguous relative path without traversal or empty components." }
        require(name.none { it.code < 32 || it.code == 127 || it == '\\' || it == ':' }) { "Invalid relative path." }
        return name
    }

    internal fun noLinks(path: Path) {
        var cursor: Path? = path.toAbsolutePath()
        while (cursor != null) {
            require(!Files.isSymbolicLink(cursor)) { "Linked paths are not accepted: $cursor" }
            cursor = cursor.parent
        }
    }

    internal fun hash(path: Path): String = Files.newInputStream(path).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkInterrupted()
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        hex(digest.digest())
    }

    private fun identity(inputs: List<EngineInputHash>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputs.forEach { digest.update("${it.sha256}\t${it.bytes}\t${it.path}\n".toByteArray(Charsets.UTF_8)) }
        return hex(digest.digest())
    }

    internal fun validateRuntimeJar(jar: Path) {
        noLinks(jar)
        require(Files.isRegularFile(jar, NOFOLLOW_LINKS) && Files.size(jar) in 1..(32L * 1024 * 1024)) { "Runtime SDK JAR is missing or too large." }
        ZipFile(jar.toFile()).use { zip ->
            for (name in listOf("EngineClient", "RuntimeService", "NativeBridge")) {
                val entry = zip.getEntry("dev/ide/runtime/engine/$name.class") ?: error("Runtime SDK JAR is missing $name.")
                zip.getInputStream(entry).use { input ->
                    val header = ByteArray(8)
                    var count = 0
                    while (count < header.size) {
                        val read = input.read(header, count, header.size - count)
                        require(read > 0) { "Truncated runtime SDK class: $name" }
                        count += read
                    }
                    require(header.take(4) == listOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte()) &&
                        (header[6].toInt() and 255) == 0 && (header[7].toInt() and 255) == 52) { "Runtime SDK must contain actual Java-8 library classes." }
                }
            }
            require(zip.entries().asSequence().none { it.name.startsWith("dev/ide/android/") }) { "IDE implementation classes must not be embedded in generated apps." }
        }
    }

    internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Input review interrupted.")
    }
}

/** Compare the installed asset on every extraction; never trust an unrelated pre-existing cache file. */
internal object EngineSdkCache {
    const val ASSET_NAME = "runtime-engine-sdk.jar"

    fun extract(cacheRoot: Path, apkStamp: String, openAsset: () -> InputStream): Path {
        require(apkStamp.matches(Regex("[A-Za-z0-9._-]{1,160}"))) { "Invalid installed-APK cache stamp." }
        EnginePackageInputs.noLinks(cacheRoot)
        val directory = cacheRoot.resolve("runtime-sdk").resolve(apkStamp)
        Files.createDirectories(directory)
        EnginePackageInputs.noLinks(directory)
        val destination = directory.resolve("runtime-engine.jar")
        val temporary = Files.createTempFile(directory, ".sdk-", ".tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            openAsset().use { input ->
                Files.newOutputStream(temporary, WRITE).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("SDK extraction interrupted.")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        size += count
                        require(size <= 32L * 1024 * 1024) { "Runtime SDK asset exceeds 32 MiB." }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            }
            val expected = EnginePackageInputs.hex(digest.digest())
            require(EnginePackageInputs.hash(temporary) == expected) { "Runtime SDK copy checksum mismatch." }
            EnginePackageInputs.validateRuntimeJar(temporary)
            if (Files.exists(destination, NOFOLLOW_LINKS)) {
                EnginePackageInputs.noLinks(destination)
                require(Files.isRegularFile(destination, NOFOLLOW_LINKS) && EnginePackageInputs.hash(destination) == expected) {
                    "Cached runtime SDK differs from this installed APK. Refusing to overwrite it; clear this app's cache and retry."
                }
            } else {
                // CREATE_NEW fails even if another writer creates the destination after our existence check.
                var created = false
                try {
                    Files.newOutputStream(destination, CREATE_NEW, WRITE).use { output ->
                        created = true
                        Files.newInputStream(temporary).use { input -> input.copyTo(output, 64 * 1024) }
                    }
                    require(EnginePackageInputs.hash(destination) == expected) { "Runtime SDK destination checksum mismatch." }
                } catch (error: Exception) {
                    if (created) Files.deleteIfExists(destination)
                    throw error
                }
            }
            return destination
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
