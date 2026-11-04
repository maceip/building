package dev.ide.android.support.tasks

import dev.ide.android.support.ReliabilityBuildConfiguration
import dev.ide.android.support.ReliabilityBuildRequest
import dev.ide.android.support.ReliabilityProjectOptions
import dev.ide.android.support.gms.MiniJson
import dev.ide.build.AlwaysRun
import dev.ide.build.ReliabilitySourceSnapshot
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import dev.ide.build.engine.debug
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.ZipFile

internal class VerifyReliabilitySourcesTask(
    override val name: TaskName,
    private val projectRoot: Path,
    private val sourceManifest: Path,
    private val options: ReliabilityProjectOptions,
) : Task, AlwaysRun {
    override val inputs: TaskInputs get() = TaskInputsImpl().apply { filePaths("manifest", listOf(sourceManifest)) }
    override val outputs: TaskOutputs get() = TaskOutputsImpl()
    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        return try {
            val metadata = MiniJson.parse(String(Files.readAllBytes(sourceManifest), Charsets.UTF_8)) as? Map<*, *>
                ?: error("Reliability: generated source metadata is missing.")
            check(ReliabilitySourceSnapshot.capture(projectRoot) { ctx.checkCanceled() }.revision == metadata["sourceRevision"]) {
                "Reliability: sources changed during compilation; rebuild before packaging or pairing the app."
            }
            check(ReliabilityProjectOptions.read(projectRoot) == options) {
                "Reliability: consent changed during compilation; start a new build."
            }
            TaskResult.Success
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            TaskResult.Failed(e.message ?: "Reliability: source verification failed.", e)
        }
    }
}

internal object ReliabilityAssets {
    fun privateDirectory(root: Path, project: Path): Path {
        Files.createDirectories(root)
        val real = root.toRealPath()
        require(!real.startsWith(project.toRealPath())) {
            "Reliability: generated configuration must use a host-private cache outside the project."
        }
        // This is a capability-bearing directory. Unsupported POSIX permissions on a host are fine;
        // Android's app-private files directory is additionally isolated by the application sandbox.
        runCatching { Files.setPosixFilePermissions(real, PosixFilePermissions.fromString("rwx------")) }
        return real
    }

    fun config(options: ReliabilityProjectOptions, request: ReliabilityBuildRequest, hostJson: String?): Map<String, Any> {
        require(options.appliesTo(request.debuggable)) { "Reliability: project consent is required." }
        val fields = linkedMapOf<String, Any>(
            "version" to 1, "enabled" to true, "consentGranted" to true,
            "idePackage" to "", "ideService" to "", "pairingNonce" to "",
            "buildId" to request.buildId, "sourceRevision" to request.sourceRevision,
            "captureLifecycle" to options.captureLifecycle, "captureCrashes" to options.captureCrashes,
            "capturePreviousExitInfo" to options.capturePreviousExitInfo, "captureNetwork" to options.captureNetwork,
            "teeStdout" to options.teeStdout, "maxJournalBytes" to 262144, "maxEvents" to 256,
        )
        if (!request.debuggable || hostJson == null) return fields
        require(hostJson.length <= 16_384) { "Reliability: host pairing configuration is too large." }
        val host = try {
            MiniJson.parse(hostJson) as? Map<*, *> ?: error("not an object")
        } catch (_: Exception) {
            throw IllegalArgumentException("Reliability: invalid host pairing configuration.") // Never echo nonce-bearing JSON.
        }
        for ((key, expected) in mapOf("buildId" to request.buildId, "sourceRevision" to request.sourceRevision, "packageName" to request.packageName)) {
            require(key !in host || host[key] == expected) { "Reliability: host pairing belongs to a different build/source snapshot." }
        }
        fun string(key: String): String = (host[key] as? String).orEmpty()
        val recipient = string("idePackage")
        val service = string("ideService")
        val nonce = string("pairingNonce")
        val signer = string("ideSignerSha256")
        if (recipient.isEmpty() && service.isEmpty() && nonce.isEmpty() && signer.isEmpty()) return fields
        require(recipient.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) &&
            service.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) &&
            nonce.matches(Regex("[0-9a-f]{64}")) && (signer.isEmpty() || signer.matches(Regex("[0-9a-fA-F]{64}")))) {
            "Reliability: debug pairing requires an explicit IDE package/service, a 256-bit nonce and a valid optional signer SHA-256."
        }
        fields["idePackage"] = recipient
        fields["ideService"] = service
        fields["pairingNonce"] = nonce
        if (signer.isNotEmpty()) fields["ideSignerSha256"] = signer.lowercase()
        return fields
    }

    /** Keep production inputs honest: adding a runtime flag cannot hide a packaged fault-control class. */
    fun validateRuntime(jar: Path) {
        require(Files.isRegularFile(jar, NOFOLLOW_LINKS)) { "Reliability: production runtime JAR is missing." }
        ZipFile(jar.toFile()).use { zip ->
            require(zip.getEntry("dev/ide/applog/IdeLogBridgeProvider.class") != null) { "Reliability: runtime JAR is missing its provider." }
            require(zip.getEntry("dev/ide/applog/Reliability.class") != null) { "Reliability: a consent-aware production SDK JAR is required (legacy app-log JAR is not supported)." }
            require(zip.getEntry("assets/ide-reliability.json") == null) { "Reliability: runtime JAR must not embed an app-specific configuration asset." }
            for (entry in zip.entries()) {
                val name = entry.name
                require(!name.startsWith("dev/ide/reliability/test/") && !name.startsWith("dev/ide/applog/test/") &&
                    !name.startsWith("dev/ide/reliability/fault/")) { "Reliability: debug fault controls must be supplied as a separate debug/test artifact." }
                if (name.endsWith(".class")) zip.getInputStream(entry).use { input ->
                    val header = ByteArray(8)
                    var read = 0
                    while (read < header.size) {
                        val count = input.read(header, read, header.size - read)
                        require(count > 0) { "Reliability: invalid class in runtime JAR." }
                        read += count
                    }
                    require(header.take(4) == listOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte())) {
                        "Reliability: invalid class in runtime JAR."
                    }
                    val major = ((header[6].toInt() and 255) shl 8) or (header[7].toInt() and 255)
                    require(major in 45..52) { "Reliability: runtime must use Java 8-compatible bytecode." }
                }
            }
        }
    }

    fun json(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
        else -> buildString {
            append('"')
            for (c in value.toString()) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
            }
            append('"')
        }
    }
}

/** Runs before compilation, not when the project was imported. No capability is persisted in the repo. */
internal class GenerateReliabilityAssetsTask(
    override val name: TaskName,
    private val projectRoot: Path,
    private val moduleId: String,
    private val applicationId: String,
    private val variant: String,
    private val debuggable: Boolean,
    private val buildId: String,
    private val options: ReliabilityProjectOptions,
    private val configuration: ReliabilityBuildConfiguration,
    val assetsDir: Path,
    val recipientFile: Path,
    private val conflictingAssets: List<Path>,
) : Task, AlwaysRun {
    override val inputs: TaskInputs get() = TaskInputsImpl().apply {
        property("buildId", buildId)
        property("options", options.publicFields())
        filePaths("runtime", listOf(configuration.runtimeJar))
    }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply {
        dirPath("assets", assetsDir)
        filePath("recipient", recipientFile)
    }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        return try {
            check(ReliabilityProjectOptions.read(projectRoot) == options) { "Reliability: project consent changed; start a new build." }
            ReliabilityAssets.privateDirectory(assetsDir, projectRoot)
            for (dir in conflictingAssets) for (reserved in listOf(ReliabilityProjectOptions.ASSET_NAME, ReliabilityProjectOptions.SOURCE_ASSET_NAME)) {
                require(!Files.exists(dir.resolve(reserved), NOFOLLOW_LINKS)) { "Reliability: reserved generated asset '$reserved' already exists in app/dependency assets." }
            }
            ReliabilityAssets.validateRuntime(configuration.runtimeJar)
            val snapshot = ReliabilitySourceSnapshot.capture(projectRoot) { ctx.checkCanceled() }
            val request = ReliabilityBuildRequest(projectRoot, moduleId, applicationId, variant, debuggable, snapshot.revision, buildId)
            val hostJson = if (debuggable) try {
                configuration.configurationProvider?.configurationFor(request)
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (_: Exception) {
                throw IllegalStateException("Reliability: host could not prepare a private debug pairing.")
            } else null
            val config = ReliabilityAssets.config(options, request, hostJson)
            Files.write(assetsDir.resolve(ReliabilityProjectOptions.ASSET_NAME), (ReliabilityAssets.json(config) + "\n").toByteArray(Charsets.UTF_8))
            Files.write(assetsDir.resolve(ReliabilityProjectOptions.SOURCE_ASSET_NAME), (ReliabilityAssets.json(linkedMapOf(
                "version" to 1, "buildId" to buildId, "sourceRevision" to snapshot.revision, "files" to snapshot.files,
            )) + "\n").toByteArray(Charsets.UTF_8))
            Files.createDirectories(recipientFile.parent)
            Files.write(recipientFile, config.getValue("idePackage").toString().toByteArray(Charsets.UTF_8))
            ctx.debug("Reliability: consented ${if (debuggable) "debug" else "local-only production"} SDK; build $buildId, source ${snapshot.revision}")
            TaskResult.Success
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            TaskResult.Failed(e.message ?: "Reliability: failed to generate build configuration.", e)
        }
    }
}
