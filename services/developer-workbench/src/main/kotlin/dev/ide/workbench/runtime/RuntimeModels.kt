package dev.ide.workbench.runtime

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

/** These are app-private, user-trusted processes, NOT a security sandbox. */
enum class RuntimeKind { NODE, NPM, PYTHON, SHELL, STATIC }
enum class RuntimePhase { IDLE, PREPARING, RUNNING, STOPPING, FINISHED, FAILED }
enum class RuntimeWorkspaceMode { AUTO, DIRECT, INTERNAL_COPY }

data class RuntimeTarget(
    val id: String,
    val label: String,
    val kind: RuntimeKind,
    val relativePath: String,
    val script: String? = null,
)

data class RuntimeProjectConfig(
    val targetId: String? = null,
    val args: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    /** A relative directory within the project, never an arbitrary host path. */
    val workingDirectory: String = ".",
    val workspaceMode: RuntimeWorkspaceMode = RuntimeWorkspaceMode.AUTO,
    /** Explicit loopback preview port for a server; null leaves discovery to its output. */
    val previewPort: Int? = null,
)

data class RuntimeRunRequest(
    val projectRoot: Path,
    val targetId: String,
    val args: List<String> = emptyList(),
)

data class RuntimeSessionState(
    val sessionId: String? = null,
    val stdout: String = "",
    val stderr: String = "",
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val phase: RuntimePhase = RuntimePhase.IDLE,
    val projectRoot: Path? = null,
    val target: RuntimeTarget? = null,
    val transcript: String = "",
    val exitCode: Int? = null,
    val previewUrl: String? = null,
    val error: String? = null,
    val workspacePath: Path? = null,
    val workspaceCopy: Boolean = false,
    val acceptsInput: Boolean = false,
) {
    val isRunning: Boolean get() = phase in setOf(RuntimePhase.PREPARING, RuntimePhase.RUNNING, RuntimePhase.STOPPING)
}

data class RuntimeProbe(
    val kind: RuntimeKind,
    val available: Boolean,
    val version: String = "",
    val error: String? = null,
)

data class RuntimeProbeReport(
    val packId: String,
    val abi: String,
    val runtimes: List<RuntimeProbe>,
    val notes: List<String> = emptyList(),
) {
    val node: RuntimeProbe? get() = runtimes.firstOrNull { it.kind == RuntimeKind.NODE }
    val npm: RuntimeProbe? get() = runtimes.firstOrNull { it.kind == RuntimeKind.NPM }
    val python: RuntimeProbe? get() = runtimes.firstOrNull { it.kind == RuntimeKind.PYTHON }
    val shell: RuntimeProbe? get() = runtimes.firstOrNull { it.kind == RuntimeKind.SHELL }
}

data class RuntimeSyncReport(val copied: Int, val conflicts: List<String> = emptyList())
data class RuntimeSample(val id: String, val label: String, val description: String)

/** Provisioning is OFFLINE. All native bytes must have arrived in the installed APK. */
data class RuntimeInstallation(
    val packId: String,
    val abi: String,
    val prefix: Path,
    val node: Path?,
    val npmCli: Path?,
    val python: Path?,
    val shell: Path?,
    val environment: Map<String, String>,
    /** Only these exact, build-time-audited registry artifacts may be installed by our npm action. */
    val approvedNpmPackages: Map<String, String> = emptyMap(),
    /** Developer/sideload distribution only. This is NOT a Play-policy exemption. */
    val allowNativeDependencyDownloads: Boolean = false,
)

/** The Android implementation owns installer paths and process-group termination. */
interface RuntimeHost {
    val workspaceCache: Path
    suspend fun prepare(): RuntimeInstallation
    fun requiresWorkspaceCopy(projectRoot: Path): Boolean = false
    fun start(command: List<String>, workingDirectory: Path, environment: Map<String, String>): RuntimeChild
}

interface RuntimeChild {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream
    suspend fun awaitExit(): Int
    /** Terminate the session's process group, not just its first shell. Must be idempotent. */
    suspend fun terminate()
}
