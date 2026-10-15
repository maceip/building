package dev.ide.workbench.runtime

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.UUID

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

/** The operation actually reached; script names and command text never imply a test run. */
enum class RuntimeOutcomeStage { PREPARE, LAUNCH, PROCESS }

/** UNEXPECTED_FAILURE is a runner failure; NONZERO_EXIT is a command outcome, not proof of a crash. */
enum class RuntimeOutcomeClassification { UNEXPECTED_FAILURE, NONZERO_EXIT, TIMEOUT, CANCELLED, SUCCESS }

/** Closed categories only: never an exception object, message, stack or caller-defined class name. */
enum class RuntimeExceptionCategory { IO, SECURITY, INVALID_CONFIGURATION, HOST_API_UNAVAILABLE, OTHER }

/** Host-private, not SDK-ready. The controller emits only kind and a bounded printable target ID. */
data class RuntimeObservedTarget(val kind: RuntimeKind, val id: String)

/**
 * Host-private attribution from the installation, not evidence of a probed runtime version.
 * Invalid token syntax is omitted. The host must additionally allowlist these values before export.
 * This intentionally excludes installation paths and environment variables.
 */
data class RuntimePreparedMetadata(val packId: String?, val abi: String?)

/** Immutable terminal facts with no project paths, target IDs, command, output or exception text. */
data class RuntimeSessionOutcome(
    val sessionId: UUID,
    val kind: RuntimeKind?,
    val stage: RuntimeOutcomeStage,
    val classification: RuntimeOutcomeClassification,
    val exitCode: Int?,
    /** Monotonic elapsed time, including preparation, clamped to [0, MAX_DURATION_MILLIS]. */
    val durationMillis: Long,
    val exceptionCategory: RuntimeExceptionCategory? = null,
) {
    companion object {
        const val MAX_DURATION_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}

/**
 * Optional host boundary, called once before workspace/native preparation and user execution.
 * The root and target are HOST-PRIVATE: never forward them directly to an SDK or remote sink.
 * Consent and immutable source/build provenance must be captured here, not at completion.
 * A target-resolution failure (or cancellation before resolution) has null kind and target.
 * Return [RuntimeSessionObserver.NONE] when observation is disabled; no persistence is provided here.
 * Callbacks run on the worker dispatcher (never inline on the run/stop caller), sequentially per
 * session. Keep them bounded; stop waits for completion. Failures are swallowed without logging.
 */
fun interface RuntimeObserver {
    fun onSessionStarted(sessionId: UUID, projectRoot: Path, kind: RuntimeKind?, target: RuntimeObservedTarget?): RuntimeSessionObserver

    companion object {
        val NONE = RuntimeObserver { _, _, _, _ -> RuntimeSessionObserver.NONE }
    }
}

/** Per-session handle: bind start-time consent/provenance here instead of consulting active UI state. */
fun interface RuntimeSessionObserver {
    /** Called at most once, including preparation failure and cancellation; failures never escape. */
    fun onSessionFinished(outcome: RuntimeSessionOutcome)

    /** Called after successful native preparation, before launch; not called for static previews. */
    fun onRuntimePrepared(metadata: RuntimePreparedMetadata) {}

    companion object {
        val NONE = RuntimeSessionObserver { }
    }
}

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

/** Runtime provisioning is offline: the bundled runtime binaries arrive in the installed APK. */
data class RuntimeInstallation(
    val packId: String,
    val abi: String,
    val prefix: Path,
    val node: Path?,
    val npmCli: Path?,
    val python: Path?,
    val shell: Path?,
    val environment: Map<String, String>,
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
