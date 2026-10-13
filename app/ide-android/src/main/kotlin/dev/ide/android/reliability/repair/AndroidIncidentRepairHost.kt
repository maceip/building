package dev.ide.android.reliability.repair

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import dev.ide.agent.IncidentApplyResult
import dev.ide.agent.IncidentKnownBuild
import dev.ide.agent.IncidentPatchReview
import dev.ide.agent.IncidentTaskRunner
import dev.ide.agent.impl.IncidentDigests
import dev.ide.agent.impl.IncidentRepairPending
import dev.ide.android.reliability.ReliabilityHostProvider
import dev.ide.android.reliability.androidReliabilityFile
import dev.ide.build.ReliabilitySourceSnapshot
import dev.ide.core.reliability.IncidentGradleExecutionBoundary
import dev.ide.core.reliability.IncidentGradleExecutionLease
import dev.ide.core.reliability.IncidentGradleInvocation
import dev.ide.core.reliability.IncidentGradleTaskRunner
import dev.ide.core.reliability.IncidentRepairController
import dev.ide.core.reliability.createIncidentRepairController
import dev.ide.gradle.GradleEnvironment
import dev.ide.gradle.GradleHost
import dev.ide.gradle.JdkSelection
import dev.ide.gradle.PrefixProbe
import dev.ide.gradle.ProjectVersions
import dev.ide.gradle.ToolingClient
import dev.ide.ui.backend.AgentService
import dev.ide.workbench.reliability.IncidentKey
import dev.ide.workbench.reliability.PairingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

class AndroidIncidentRepairProject(val controller: IncidentRepairController, val planStore: ApprovedRepairPlanStore) : AutoCloseable {
    override fun close() = controller.close()
}

/** Real Android binding: SDK build callbacks + signed APK activation + approved offline local Gradle tests. */
class AndroidIncidentRepairHost private constructor(private val context: Context) {
    private val builds = androidReliabilityFile(context.noBackupFilesDir.toPath().toRealPath().resolve("reliability/repair-builds.json"), 2 * 1024 * 1024)
    private val local by lazy { ReliabilityHostProvider.get(context) }

    /** Invoke from BOTH native and Gradle Reliability configuration-provider callbacks, on the build lane. */
    fun registerBuild(projectRoot: Path, packageName: String, buildId: String, variant: String, sourceRevision: String, debuggable: Boolean) {
        if (!debuggable) return
        val root = projectRoot.toRealPath()
        val projectId = IncidentDigests.bytes(root.toString().toByteArray())
        require(Regex("[A-Za-z0-9._:-]{1,128}").matches(buildId) && Regex("[a-f0-9]{64}").matches(sourceRevision))
        require(ReliabilitySourceSnapshot.capture(root).revision == sourceRevision)
        val value = buildJsonObject {
            put("projectId", projectId); put("projectRoot", root.toString()); put("package", packageName)
            put("buildId", buildId); put("variant", variant); put("sourceRevision", sourceRevision)
            // The SDK build UUID already identifies this source-bound build. Never invent another one.
            put("sourceUuid", buildId); put("artifactSha256", ""); put("debuggable", true)
        }
        builds.transaction {
            val registry = readBuilds()
            val key = buildKey(packageName, buildId)
            val previous = registry[key] as? JsonObject
            if (previous != null) {
                require(previous.getValue("projectId").jsonPrimitive.content == projectId &&
                    previous.getValue("sourceRevision").jsonPrimitive.content == sourceRevision &&
                    previous.getValue("variant").jsonPrimitive.content == variant) { "Registered build identity conflict." }
                return@transaction
            }
            require(registry.size < 1024) { "Private build registry is full." }
            builds.write(JsonObject(registry + (key to value)).toString().toByteArray(Charsets.UTF_8))
        }
    }

    /** Invoke only AFTER ReliabilityHost.activateDebugPairing has verified the actual APK signer and grant. */
    fun recordArtifact(packageName: String, buildId: String, apkPath: Path) {
        builds.transaction {
            val registry = readBuilds()
            val key = buildKey(packageName, buildId)
            val stored = registry[key] as? JsonObject ?: return@transaction
            val pairing = local.pairings.list().singleOrNull { it.packageName == packageName && it.buildId == buildId && it.status == PairingStatus.ACTIVE }
                ?: throw IllegalStateException("Pairing activation is required before repair artifact registration.")
            require(Files.isRegularFile(apkPath, NOFOLLOW_LINKS) && Files.size(apkPath) in 1..512L * 1024 * 1024)
            val originalHash = IncidentDigests.file(apkPath)
            val archive = if (Build.VERSION.SDK_INT >= 28) context.packageManager.getPackageArchiveInfo(apkPath.toString(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                else @Suppress("DEPRECATION") context.packageManager.getPackageArchiveInfo(apkPath.toString(), android.content.pm.PackageManager.GET_SIGNATURES)
            require(archive?.packageName == packageName && ((archive?.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            val signers = if (Build.VERSION.SDK_INT >= 28) archive?.signingInfo?.apkContentsSigners.orEmpty().toList()
                else @Suppress("DEPRECATION") archive?.signatures.orEmpty().toList()
            require(signers.isNotEmpty() && signers.map { IncidentDigests.bytes(it.toByteArray()) }.toSet() == pairing.expectedSignerSha256)
            val asset = ZipFile(apkPath.toFile()).use { zip ->
                val entry = zip.getEntry("assets/ide-reliability.json") ?: error("SDK build identity is missing.")
                require(entry.size in 1..16384)
                val text = zip.getInputStream(entry).use { boundedBytes(it, 16384).toString(Charsets.UTF_8) }
                Json.parseToJsonElement(text) as JsonObject
            }
            require(asset["buildId"]?.jsonPrimitive?.content == buildId &&
                asset["sourceRevision"]?.jsonPrimitive?.content == stored.getValue("sourceRevision").jsonPrimitive.content &&
                pairing.sourceRevision == stored.getValue("sourceRevision").jsonPrimitive.content)
            require(IncidentDigests.file(apkPath) == originalHash) { "APK changed during provenance inspection." }
            val next = JsonObject(stored + ("artifactSha256" to JsonPrimitive(originalHash)))
            builds.write(JsonObject(registry + (key to next)).toString().toByteArray(Charsets.UTF_8))
        }
    }

    fun project(
        projectRoot: Path,
        agent: AgentService,
        scope: CoroutineScope,
        applyFactory: ((Path, Path) -> (suspend (IncidentPatchReview, () -> Boolean) -> IncidentApplyResult)?)? = null,
    ): AndroidIncidentRepairProject {
        val root = projectRoot.toRealPath()
        val store = ApprovedRepairPlanStore(context, root)
        val privateRoot = context.noBackupFilesDir.toPath().toRealPath().resolve("incident-repair-copies").resolve(store.projectId)
        val runner = IncidentTaskRunner { copy, task ->
            val approved = store.read() ?: throw IncidentRepairPending("Approve this project's immutable local test plan first.")
            if (!approved.input.trustedLocalExecution) throw IncidentRepairPending("Trusted local project execution requires separate consent.")
            val gradle = GradleEnvironment.host?.takeIf { GradleEnvironment.enabled && it.available() }
                ?: throw IncidentRepairPending("The installed on-device Gradle toolchain is not ready.")
            val plan = store.approvedLocalPlan() ?: throw IncidentRepairPending("No approved local verification plan matches this project.")
            val scan = ProjectVersions.scan(copy)
            val decision = JdkSelection.select(scan.gradleVersion, scan.toolchainMajor, scan.toolchainVendor)
            val image = decision.image?.takeIf { decision.supported } ?: throw IncidentRepairPending("The project's JDK/Gradle version is unsupported.")
            val distribution = installedDistribution(gradle, scan.gradleVersion)
                ?: throw IncidentRepairPending("Run a normal approved build to install this Gradle distribution and dependencies first; repair does not download them.")
            val boundary = IncidentGradleExecutionBoundary { copyRoot, invocation ->
                // Re-read consent and exact plan on every task. No other project/AI policy authorizes code execution.
                val current = store.read()
                if (current != approved || !current.input.trustedLocalExecution || invocation.usesDevice) null
                else ApprovedLocalLease(copyRoot, gradle, image)
            }
            IncidentGradleTaskRunner(gradle, distribution, image, store.invocations(plan), boundary).run(copy, task)
        }
        val editorApply = applyFactory?.invoke(root, privateRoot)
        val controller = createIncidentRepairController(store.projectId, root, privateRoot, agent, local.repository, scope,
            registeredBuild = { key -> registeredBuild(store.projectId, key) }, tasks = runner,
            verificationPlan = { store.plan(it) }, initialPolicy = store.policy(), persistPolicy = store::savePolicy,
            checkedApply = editorApply?.let { apply ->
                { review, authorized ->
                    val key = IncidentKey(review.incident.packageName, review.incident.buildId, review.incident.sessionId, review.incident.eventId)
                    val approved = review.verificationPlan
                    if (approved == null || store.plan(review.knownBuild) != approved) {
                        IncidentApplyResult.Pending("The approved verification plan changed after verification.")
                    } else apply(review) {
                        authorized() && store.plan(review.knownBuild) == approved &&
                            registeredBuild(store.projectId, key) == review.knownBuild
                    }
                }
            })
        return AndroidIncidentRepairProject(controller, store)
    }

    private fun registeredBuild(projectId: String, key: IncidentKey): IncidentKnownBuild? {
        val value = builds.transaction { readBuilds()[buildKey(key.packageName, key.buildId)] as? JsonObject } ?: return null
        fun field(name: String) = value[name]?.jsonPrimitive?.content.orEmpty()
        val hash = field("artifactSha256")
        if (field("projectId") != projectId || !Regex("[a-f0-9]{64}").matches(hash)) return null
        if (local.pairings.list().none { it.packageName == key.packageName && it.buildId == key.buildId && it.status == PairingStatus.ACTIVE }) return null
        return IncidentKnownBuild(projectId, field("package"), field("buildId"), field("variant"), field("sourceRevision"),
            field("sourceUuid"), hash, value["debuggable"]?.jsonPrimitive?.booleanOrNull == true)
    }

    /** Called only under builds.transaction(): reloads after every cross-process lock acquisition. */
    private fun readBuilds(): JsonObject = builds.read()?.let { bytes ->
        val value = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
        require(value.size <= 1024 && value.values.all { it is JsonObject })
        value
    } ?: JsonObject(emptyMap())
    private fun buildKey(packageName: String, buildId: String) = IncidentDigests.bytes("$packageName\u0000$buildId".toByteArray())

    private fun installedDistribution(host: GradleHost, version: String?): Path? {
        if (version == null || !Regex("[0-9]+(?:\\.[0-9]+){1,2}(?:-[A-Za-z0-9.-]+)?").matches(version)) return null
        val wrappers = host.gradleUserHome().resolve("wrapper/dists")
        if (!Files.isDirectory(wrappers, NOFOLLOW_LINKS)) return null
        return Files.walk(wrappers, 4).use { paths ->
            paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) && it.fileName.toString() == "gradle-$version" && Files.isDirectory(it.resolve("lib")) }
                .findFirst().orElse(null)
        }
    }

    companion object {
        @Volatile private var instance: AndroidIncidentRepairHost? = null
        fun get(context: Context): AndroidIncidentRepairHost = instance ?: synchronized(this) {
            instance ?: AndroidIncidentRepairHost(context.applicationContext).also { instance = it }
        }
    }
}

private fun boundedBytes(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(4096)
    var total = 0
    while (true) {
        val read = input.read(buffer, 0, minOf(buffer.size, limit - total + 1))
        if (read < 0) break
        if (read == 0) continue
        total += read
        require(total <= limit) { "Private metadata exceeds its input limit." }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

/**
 * Explicitly trusted local-code mode, NOT an OS sandbox. Gradle dependency resolution is offline and
 * receives no credentials. Android's same UID still permits project build code to access files/network;
 * the dedicated settings warning grants this execution only for trusted project code. No device tasks.
 */
private class ApprovedLocalLease(private val copyRoot: Path, host: GradleHost, image: JdkSelection.Image) : IncidentGradleExecutionLease {
    override val gradleUserHome: Path = copyRoot.resolve(".incident-gradle")
    private val tempRoot = copyRoot.resolve(".incident-tmp")
    private val home = copyRoot.resolve(".incident-home")
    private val closed = AtomicBoolean(false)
    @Volatile private var process: Process? = null
    override val environment: Map<String, String>

    init {
        Files.createDirectories(gradleUserHome); Files.createDirectories(tempRoot); Files.createDirectories(home)
        val java = requireNotNull(host.javaHome(image))
        val prefix = requireNotNull(host.prefix())
        environment = PrefixProbe.environment(prefix, java, host.libraryPath()).toMutableMap().apply {
            put("HOME", home.toString()); put("TMPDIR", tempRoot.toString())
            put("JAVA_TOOL_OPTIONS", "-Djava.io.tmpdir=$tempRoot -Duser.home=$home")
            put("GRADLE_USER_HOME", gradleUserHome.toString())
            put("GRADLE_RO_DEP_CACHE", host.gradleUserHome().resolve("caches/modules-2").toString())
            put("ANDROID_HOME", host.androidHome().toString()); put("ANDROID_SDK_ROOT", host.androidHome().toString())
        }.toMap()
    }

    override fun openClient(host: GradleHost, javaHome: Path): ToolingClient {
        check(!closed.get())
        // Do not inherit the IDE's environment (provider keys, proxy credentials, global Gradle init scripts).
        val child = ProcessBuilder(host.linker() ?: PrefixProbe.linkerFor(Build.SUPPORTED_ABIS.firstOrNull()),
            javaHome.resolve("bin/java").toString(), "-jar", host.toolingJar().toString())
            .directory(copyRoot.toFile()).apply { environment().clear(); environment().putAll(this@ApprovedLocalLease.environment) }.start()
        process = child
        if (closed.get()) { child.destroyForcibly(); error("Execution consent was cancelled.") }
        return ToolingClient(child)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Private Gradle user home prevents sharing daemon identities with ordinary user builds. Tooling
        // cancellation is sent first by the core runner; stop only this lease's private daemon PIDs.
        val daemonRoot = gradleUserHome.resolve("daemon")
        if (Files.isDirectory(daemonRoot, NOFOLLOW_LINKS)) runCatching {
            Files.walk(daemonRoot, 3).use { paths -> paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.forEach { path ->
                val pid = Regex("daemon-([0-9]+)\\.out\\.log").matchEntire(path.fileName.toString())?.groupValues?.get(1)?.toIntOrNull()
                if (pid != null && pid != android.os.Process.myPid()) {
                    val command = runCatching { Files.newInputStream(Paths.get("/proc/$pid/cmdline")).use { boundedBytes(it, 8192).toString(Charsets.UTF_8) } }.getOrDefault("")
                    if (command.contains("GradleDaemon") && command.contains(copyRoot.toString())) android.os.Process.killProcess(pid)
                }
            } }
        }
        process?.let { child ->
            runCatching { child.destroy(); if (!child.waitFor(1, TimeUnit.SECONDS)) child.destroyForcibly() }
        }
    }
}
