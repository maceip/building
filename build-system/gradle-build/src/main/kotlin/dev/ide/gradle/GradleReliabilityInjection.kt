package dev.ide.gradle

import dev.ide.build.ReliabilitySourceSnapshot
import dev.ide.platform.JsonReader
import dev.ide.platform.JsonWriter
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Same host-neutral fields as native ReliabilityBuildRequest, without an android-support dependency. */
data class GradleReliabilityRequest(
    val projectPath: Path,
    val moduleId: String,
    val packageName: String,
    val variant: String,
    val debuggable: Boolean,
    val sourceRevision: String,
    val buildId: String,
)

/** Host-private pending pairing only. The host still has to explicitly approve the installed signer. */
fun interface GradleReliabilityConfigurationProvider {
    fun configurationFor(request: GradleReliabilityRequest): String?
}

/**
 * Prepares per-variant config in the host cache, after a real model is available and before execute.
 * No project build scripts are modified; sync/test/lint/clean do not opt an application into injection.
 * The init script checks the actual AGP variant's package/debuggability and source hash again.
 */
object GradleReliabilityInjection {
    const val PROPERTY = "dev.ide.reliability.input"
    private const val PROJECT_FILE = ".building/reliability.json"
    private val defaults = linkedMapOf<String, Any>(
        "version" to 1, "enabled" to false, "consentGranted" to false,
        "captureCrashes" to true, "captureLifecycle" to true, "capturePreviousExitInfo" to true,
        "captureNetwork" to false, "teeStdout" to false, "productionEnabled" to false,
    )

    fun launchArgs(
        projectDir: Path,
        privateCacheRoot: Path,
        snapshot: GradleProjectSnapshot,
        tasks: List<String>,
        runtimeArtifact: Path? = GradleEnvironment.reliabilityRuntimeJar,
        provider: GradleReliabilityConfigurationProvider? = GradleEnvironment.reliabilityProvider,
    ): List<String> {
        val options = readOptions(projectDir)
        if (options["enabled"] != true || options["consentGranted"] != true) return emptyList()
        val selected = snapshot.variants().filter { variant ->
            variant.application && (variant.debuggable || options["productionEnabled"] == true) &&
                tasks.any { task -> selects(task, variant) }
        }
        if (selected.isEmpty()) return emptyList()
        val runtime = runtimeArtifact ?: error("Reliability: project opted in, but no production SDK JAR/AAR is installed.")
        require(Files.isRegularFile(runtime, NOFOLLOW_LINKS)) { "Reliability: production SDK JAR/AAR is missing." }
        require(runtime.fileName.toString().substringAfterLast('.') in setOf("jar", "aar")) {
            "Reliability: production SDK must be a Java-8 JAR or an AAR."
        }
        validateRuntime(runtime)
        val root = projectDir.toRealPath()
        Files.createDirectories(privateCacheRoot)
        val cache = privateCacheRoot.toRealPath()
        require(!cache.startsWith(root)) { "Reliability: config/nonce must be generated in a host-private cache outside the project." }
        val buildId = UUID.randomUUID().toString()
        val output = cache.resolve("reliability/gradle").resolve(buildId)
        Files.createDirectories(output)
        runCatching { Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rwx------")) }
        val source = ReliabilitySourceSnapshot.capture(root)
        val runtimeHash = ReliabilitySourceSnapshot.sha256(runtime)
        val copiedRuntime = output.resolve("runtime.${runtime.fileName.toString().substringAfterLast('.')}")
        Files.copy(runtime, copiedRuntime, StandardCopyOption.REPLACE_EXISTING)
        val requests = selected.mapIndexed { index, variant ->
            val applicationId = variant.applicationId?.takeIf { it.isNotBlank() }
                ?: error("Reliability: sync this project again to obtain ${variant.modulePath}:${variant.name}'s applicationId before pairing.")
            // ':assemble' can build multiple variants with the SAME applicationId. Pairing registry keys
            // must still identify one artifact; otherwise the last nonce would invalidate earlier APKs.
            val artifactBuildId = if (selected.size == 1) buildId else UUID.randomUUID().toString()
            val request = GradleReliabilityRequest(root, variant.modulePath, applicationId, variant.name,
                variant.debuggable, source.revision, artifactBuildId)
            val hostJson = if (variant.debuggable) try {
                provider?.configurationFor(request)
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (_: Exception) {
                throw IllegalStateException("Reliability: host could not prepare a private debug pairing.")
            } else null
            val config = config(options, request, hostJson)
            val variantDir = output.resolve(index.toString())
            Files.createDirectories(variantDir)
            val configFile = variantDir.resolve("config.json")
            Files.write(configFile, (JsonWriter.value(config) + "\n").toByteArray(Charsets.UTF_8))
            linkedMapOf(
                "modulePath" to variant.modulePath, "variant" to variant.name, "applicationId" to applicationId,
                "debuggable" to variant.debuggable, "buildId" to artifactBuildId, "configFile" to configFile.toString(),
                "outputDir" to variantDir.resolve("generated").toString(),
            )
        }
        val descriptor = linkedMapOf<String, Any>(
            "version" to 1, "projectRoot" to root.toString(), "buildId" to buildId,
            "sourceRevision" to source.revision, "sourceFiles" to source.privateRelativeFiles,
            "publicSources" to source.files, "runtimeArtifact" to copiedRuntime.toString(),
            "runtimeSha256" to runtimeHash, "options" to options, "variants" to requests,
        )
        val input = output.resolve("injection.json")
        Files.write(input, (JsonWriter.value(descriptor) + "\n").toByteArray(Charsets.UTF_8))
        // Only a private filename travels on the command line; nonce/recipient config never does.
        return listOf("-P$PROPERTY=$input", "--no-configuration-cache", "--no-build-cache")
    }

    /** Do not install an artifact attributed to a source tree that changed while Gradle was building. */
    fun verifySources(projectDir: Path, args: List<String>) {
        val input = args.firstOrNull { it.startsWith("-P$PROPERTY=") }?.substringAfter('=') ?: return
        val descriptor = JsonReader.obj(JsonReader.parse(String(Files.readAllBytes(Paths.get(input)), Charsets.UTF_8)))
            ?: error("Reliability: generated descriptor is missing.")
        check(ReliabilitySourceSnapshot.capture(projectDir).revision == descriptor["sourceRevision"]) {
            "Reliability: sources changed during this build; rebuild before installing or pairing the artifact."
        }
        val original = JsonReader.obj(descriptor["options"])
        check(original != null && readOptions(projectDir).all { (key, value) -> original[key]?.toString() == value.toString() }) {
            "Reliability: project consent changed during this build; rebuild before installing or pairing."
        }
    }

    internal fun readOptions(root: Path): Map<String, Any> {
        val file = root.resolve(PROJECT_FILE)
        if (!Files.exists(file, NOFOLLOW_LINKS)) return defaults.toMap()
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.parent) && Files.size(file) <= 16_384) {
            "Reliability: $PROJECT_FILE must be a small, non-symlinked file of non-secret flags."
        }
        val obj = try {
            JsonReader.obj(JsonReader.parse(String(Files.readAllBytes(file), Charsets.UTF_8))) ?: error("expected an object")
        } catch (e: Exception) {
            throw IllegalArgumentException("Reliability: invalid $PROJECT_FILE; expected non-secret version-1 flags.", e)
        }
        require(obj["version"] == 1L && obj.keys.all { it in defaults }) {
            "Reliability: $PROJECT_FILE accepts version-1 collection flags only; keep pairing credentials in host-private storage."
        }
        return defaults.mapValues { (key, default) ->
            if (key == "version") 1 else if (key !in obj) default else obj[key] as? Boolean
                ?: error("Reliability: '$key' must be a boolean in $PROJECT_FILE.")
        }
    }

    private fun selects(task: String, variant: VariantChoice.Variant): Boolean {
        val qualified = if (task.startsWith(':')) task else ":$task"
        val owner = qualified.substringBeforeLast(':').ifEmpty { ":" }
        if (owner != ":" && owner != variant.modulePath) return false
        val leaf = qualified.substringAfterLast(':')
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        return leaf in setOf("assemble", "bundle", "package", "assemble$cap", "bundle$cap", "package$cap")
    }

    internal fun config(options: Map<String, Any>, request: GradleReliabilityRequest, hostJson: String?): Map<String, Any> {
        val fields = linkedMapOf<String, Any>(
            "version" to 1, "enabled" to true, "consentGranted" to true,
            "idePackage" to "", "ideService" to "", "pairingNonce" to "",
            "buildId" to request.buildId, "sourceRevision" to request.sourceRevision,
            "maxJournalBytes" to 262144, "maxEvents" to 256,
        )
        for (key in listOf("captureLifecycle", "captureCrashes", "capturePreviousExitInfo", "captureNetwork", "teeStdout"))
            fields[key] = options.getValue(key)
        if (!request.debuggable || hostJson == null) return fields
        require(hostJson.length <= 16_384) { "Reliability: host pairing configuration is too large." }
        val host = try {
            JsonReader.obj(JsonReader.parse(hostJson)) ?: error("not an object")
        } catch (_: Exception) {
            throw IllegalArgumentException("Reliability: invalid host pairing configuration.") // Never echo capability-bearing JSON.
        }
        for ((key, expected) in mapOf("buildId" to request.buildId, "sourceRevision" to request.sourceRevision, "packageName" to request.packageName)) {
            require(key !in host || host[key] == expected) { "Reliability: host pairing belongs to a different build/source snapshot." }
        }
        val recipient = (host["idePackage"] as? String).orEmpty()
        val service = (host["ideService"] as? String).orEmpty()
        val nonce = (host["pairingNonce"] as? String).orEmpty()
        val signer = (host["ideSignerSha256"] as? String).orEmpty()
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

    private fun validateRuntime(artifact: Path) {
        var provider = false
        var reliability = false
        fun inspect(name: String, input: java.io.InputStream) {
            require(name != "assets/ide-reliability.json" && name != "assets/ide-reliability-sources.json") {
                "Reliability: production runtime must not embed an app-specific generated config/source asset."
            }
            require(!name.startsWith("dev/ide/reliability/test/") && !name.startsWith("dev/ide/applog/test/") &&
                !name.startsWith("dev/ide/reliability/fault/")) { "Reliability: debug fault controls must be a separate debug/test artifact." }
            if (name == "dev/ide/applog/IdeLogBridgeProvider.class") provider = true
            if (name == "dev/ide/applog/Reliability.class") reliability = true
            if (name.endsWith(".class")) {
                val header = java.io.DataInputStream(input)
                require(header.readInt() == 0xcafebabe.toInt()) { "Reliability: invalid runtime class." }
                header.readUnsignedShort()
                require(header.readUnsignedShort() in 45..52) { "Reliability: production runtime must use Java 8-compatible bytecode." }
            }
        }
        ZipFile(artifact.toFile()).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                zip.getInputStream(entry).use { input ->
                    if (artifact.fileName.toString().endsWith(".aar") &&
                        (entry.name == "classes.jar" || entry.name.startsWith("libs/") && entry.name.endsWith(".jar"))) {
                        ZipInputStream(input).use { classes ->
                            while (true) {
                                val klass = classes.nextEntry ?: break
                                if (!klass.isDirectory) inspect(klass.name, classes)
                            }
                        }
                    } else inspect(entry.name, input)
                }
            }
        }
        require(provider && reliability) { "Reliability: consent-aware production SDK missing (legacy app-log artifacts are unsupported)." }
    }
}
