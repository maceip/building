package dev.ide.core.reliability

import dev.ide.agent.IncidentTask
import dev.ide.agent.IncidentTaskReceipt
import dev.ide.agent.IncidentTaskRunner
import dev.ide.agent.IncidentTaskKind
import dev.ide.agent.impl.IncidentDigests
import dev.ide.agent.impl.IncidentJUnitEvidence
import dev.ide.agent.impl.IncidentRepairPending
import dev.ide.gradle.GradleHost
import dev.ide.gradle.GradleUserProperties
import dev.ide.gradle.JdkSelection
import dev.ide.gradle.ToolingClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** Fixed Gradle invocation bound to a frozen IncidentTask; never takes arguments from an AI tool. */
data class IncidentGradleInvocation(
    val task: IncidentTask,
    val tasks: List<String>,
    val arguments: List<String> = emptyList(),
    /** connected/device tests implicitly install; require the separate native-test/device authorization. */
    val usesDevice: Boolean = false,
)

/**
 * Android/desktop execution permission, independent of AI/cloud/patch consent. Prefer an OS-confined
 * offline lease. On hosts without an OS sandbox a lease may run only explicitly approved, trusted local
 * project code after a separate warning/consent; it must never call itself sandboxed or infer this from
 * the repair toggle. Always use a PRIVATE credential-free Gradle home and offline installed caches.
 * Closing the lease must terminate owned task/daemon children. Device leases separately authorize the
 * immutable test runner and preserve its APK/DEX hash across before/after runs.
 */
interface IncidentGradleExecutionLease : AutoCloseable {
    val gradleUserHome: Path
    val environment: Map<String, String>
    /** Hash of the actual fixed test APK/DEX installed by a device lease, null for non-device tasks. */
    val immutableHarnessSha256: String? get() = null
    fun openClient(host: GradleHost, javaHome: Path): ToolingClient
    override fun close()
}

fun interface IncidentGradleExecutionBoundary {
    fun acquire(copyRoot: Path, invocation: IncidentGradleInvocation): IncidentGradleExecutionLease?
}

/** Actual existing Gradle tooling protocol, LOCAL installed distribution only, offline, no implicit Run. */
class IncidentGradleTaskRunner(
    private val host: GradleHost,
    private val installedDistribution: Path,
    private val javaImage: JdkSelection.Image,
    invocations: List<IncidentGradleInvocation>,
    private val boundary: IncidentGradleExecutionBoundary,
) : IncidentTaskRunner {
    private val approved = invocations.associateBy { IncidentDigests.task(it.task) }

    init {
        require(approved.size == invocations.size)
        invocations.forEach { invocation ->
            require(invocation.tasks.isNotEmpty() && invocation.tasks.all { Regex(":[A-Za-z0-9_:-]+").matches(it) })
            require(invocation.tasks.none { Regex("(?i)(publish|upload|deploy|release|install|uninstall)").containsMatchIn(it) })
            require(invocation.usesDevice || invocation.tasks.none { it.contains("connected", true) || it.contains("device", true) })
            val unitFilter = invocation.arguments.size == 2 && invocation.arguments[0] == "--tests" &&
                Regex("[A-Za-z_$][A-Za-z0-9_.$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)?").matches(invocation.arguments[1])
            require(unitFilter || invocation.arguments.all { arg ->
                // Exact approved test class filter only. No init scripts, credentials, remote URL, or -x skips.
                Regex("-Pandroid\\.testInstrumentationRunnerArguments\\.class=[A-Za-z0-9_.$#]+(?:,[A-Za-z0-9_.$#]+)*").matches(arg)
            })
        }
    }

    override suspend fun run(copyRoot: Path, task: IncidentTask): IncidentTaskReceipt = withContext(Dispatchers.IO) {
        val invocation = approved[IncidentDigests.task(task)] ?: throw IncidentRepairPending("The requested Gradle task is not approved.")
        if (!host.available() || !Files.isDirectory(installedDistribution.resolve("lib"), NOFOLLOW_LINKS)) {
            throw IncidentRepairPending("An already installed offline Gradle distribution and native toolchain are required.")
        }
        val javaHome = host.javaHome(javaImage) ?: throw IncidentRepairPending("The approved JDK is not installed.")
        val lease = boundary.acquire(copyRoot, invocation)
            ?: throw IncidentRepairPending("An isolated offline execution boundary or separate native-device test permission is required.")
        lease.use {
            require(lease.gradleUserHome.toAbsolutePath().normalize().startsWith(copyRoot.toAbsolutePath().normalize()))
            val context = currentCoroutineContext()
            context.ensureActive()
            val deadline = System.nanoTime() + task.timeoutMillis * 1_000_000
            val clientReference = AtomicReference<ToolingClient?>()
            val watcher = Thread({
                while (!Thread.currentThread().isInterrupted) {
                    if (!context.isActive || System.nanoTime() >= deadline) {
                        runCatching { clientReference.get()?.cancel() }
                        // Give the tooling cancellation token a bounded opportunity to stop its build.
                        try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                        runCatching { lease.close() }
                        break
                    }
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                }
            }, "incident-gradle-cancel").apply { isDaemon = true; start() }
            try {
                IncidentJUnitEvidence.clear(copyRoot, task)
                val client = lease.openClient(host, javaHome).also(clientReference::set)
                val homes = listOfNotNull(host.javaHome(JdkSelection.Image.JDK_17), host.javaHome(JdkSelection.Image.JDK_21)).distinct()
                val args = GradleUserProperties.launchArgs(host.ramBytes(), homes, host.aapt2(), host.initScript(), null) +
                    listOf("--offline", "--rerun-tasks", "--no-build-cache", "--no-configuration-cache", "-Dorg.gradle.workers.max=2",
                        "-Dorg.gradle.daemon.idletimeout=1000", "-Pandroid.useAndroidX=true")
                client.initialize(copyRoot.toAbsolutePath().toString(), "LOCAL", installedDistribution.toAbsolutePath().toString(),
                    javaHome.toString(), host.androidHome().toString(), lease.gradleUserHome.toString(), args,
                    GradleUserProperties.jvmArgs(host.ramBytes()).split(' '), lease.environment)
                val observed = HashMap<String, String>()
                val successful = try {
                    val result = client.execute(invocation.tasks, invocation.arguments,
                        isCanceled = { !context.isActive || System.nanoTime() >= deadline }, onEvent = { event ->
                            if (event["event"] == "progress") {
                                val id = event["task"] as? String
                                val status = event["state"] as? String
                                if (id in invocation.tasks && status in setOf("succeeded", "failed", "skipped", "up-to-date")) {
                                    observed[requireNotNull(id)] = requireNotNull(status)
                                }
                            }
                        })
                    (result.result as? Map<*, *>)?.get("success") == true && invocation.tasks.all {
                        observed[it] == "succeeded" || (task.kind == IncidentTaskKind.BUILD && observed[it] in setOf("skipped", "up-to-date"))
                    }
                } catch (_: ToolingClient.ToolingException) {
                    // A real failed terminal task is proof of task failure, not a fabricated process exit.
                    if (observed.values.none { it == "failed" }) throw IncidentRepairPending("Gradle did not produce a terminal task result.")
                    false
                }
                context.ensureActive()
                if (System.nanoTime() >= deadline) throw IncidentRepairPending("Native task timed out; verification is incomplete.")
                IncidentJUnitEvidence.receipt(copyRoot, task, exitCode = null, terminalTaskSuccess = successful)
                    .copy(immutableHarnessSha256 = lease.immutableHarnessSha256)
            } finally {
                watcher.interrupt()
                // lease owns process-group teardown; calling ToolingClient.close alone may leave a Gradle daemon.
                runCatching { lease.close() }
            }
        }
    }
}
