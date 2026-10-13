package dev.ide.workbench.scenario

import kotlinx.serialization.json.JsonElement
import java.nio.file.Path

/** Scenario JSON is declarative: it cannot supply a shell command, environment, or arbitrary URL. */
data class RegressionScenario(
    val id: String,
    val title: String,
    val targetId: String,
    val limits: ScenarioLimits,
    val steps: List<ScenarioStep>,
    val artifacts: List<String>,
)

data class ScenarioLimits(
    val runTimeoutMs: Long = 120_000,
    val stepTimeoutMs: Long = 10_000,
    val maxOutputBytes: Int = 32 * 1024,
) {
    companion object {
        const val MAX_FILE_BYTES = 64 * 1024
        const val MAX_STEPS = 32
        const val MAX_RUN_MS = 180_000L
        const val MAX_STEP_MS = 60_000L
        const val MAX_OUTPUT_BYTES = 64 * 1024
        const val CLEANUP_MS = 10_000L
    }
}

sealed interface ScenarioStep {
    val id: String
    val timeoutMs: Long

    /** Readiness polling is only this explicitly reviewed, idempotent GET. */
    data class Start(override val id: String, override val timeoutMs: Long, val readyPath: String, val readyStatus: Int) : ScenarioStep
    data class Stop(override val id: String, override val timeoutMs: Long) : ScenarioStep
    data class Command(override val id: String, override val timeoutMs: Long, val expect: CommandExpectation) : ScenarioStep
    data class Http(
        override val id: String,
        override val timeoutMs: Long,
        val method: String,
        val path: String,
        val jsonBody: JsonElement?,
        val expect: HttpExpectation,
    ) : ScenarioStep
}

data class TextExpectation(val equals: String? = null, val contains: List<String> = emptyList())
data class JsonExpectation(val pointer: String, val equals: JsonElement)
data class CommandExpectation(val exitCode: Int, val stdout: TextExpectation?, val stderr: TextExpectation?)
data class HttpExpectation(
    val status: Int,
    val body: TextExpectation?,
    val json: List<JsonExpectation>,
    val maxResponseTimeMs: Long?,
)

data class ScenarioArtifact(val relativePath: String, val sha256: String, val bytes: Long)

/** Obtain through [RegressionScenarioParser.load]. Run re-reads both JSON and reviewed entry artifacts. */
class ReviewedRegressionScenario internal constructor(
    val projectRoot: Path,
    val relativePath: String,
    val sha256: String,
    val scenario: RegressionScenario,
    val sourceArtifacts: List<ScenarioArtifact>,
    val sourceArtifactHash: String,
    /** Safe to display: request bodies, environment values, expected output and credentials are omitted. */
    val reviewSummary: String,
)

data class ScenarioRunApproval(
    val reviewedSha256: String,
    val selectedTargetId: String,
    val trustedProjectCodeAcknowledged: Boolean,
)

/** An injected host MUST exclusively own its sessions; close must stop children and preview servers.
 * This is a lifecycle/executor seam, NOT an operating-system sandbox. */
interface RuntimeScenarioHost {
    suspend fun start(projectRoot: Path, targetId: String): RuntimeScenarioSession
    suspend fun close()
}

interface RuntimeScenarioSession {
    val sessionId: String
    suspend fun awaitReady(): ScenarioProcessSnapshot
    suspend fun awaitExit(): ScenarioProcessSnapshot
    fun snapshot(): ScenarioProcessSnapshot
    suspend fun stop()
}

data class ScenarioProcessSnapshot(
    val running: Boolean,
    val exitCode: Int? = null,
    /** Null means the host cannot distinguish this stream; its assertions must be SKIPPED, not passed. */
    val stdout: String? = null,
    val stderr: String? = null,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    /** Only a URL discovered from the owned runtime session, never one supplied by scenario JSON. */
    val previewUrl: String? = null,
    val failed: Boolean = false,
)

enum class ScenarioVerdict { PASS, FAIL, SKIPPED }
enum class ScenarioRunStatus { PASSED, FAILED, SKIPPED, CANCELLED }
enum class ScenarioNetworkOutcome {
    SUCCESS, AUTHENTICATION, RATE_LIMITED, CLIENT_ERROR, SERVER_ERROR,
    TRANSPORT_ERROR, TIMEOUT, BLOCKED_REDIRECT, RESPONSE_TOO_LARGE, BLOCKED_URL,
}

data class ScenarioAssertionEvidence(
    val key: String,
    val verdict: ScenarioVerdict,
    val expected: String,
    val observed: String,
    val timestamp: String,
    /** Lifecycle/readiness checks are not counted as executed command/HTTP regression assertions. */
    val testAssertion: Boolean = true,
)

data class ScenarioHttpEvidence(
    val status: Int?,
    val durationMs: Long,
    val responseBytes: Int,
    val outcome: ScenarioNetworkOutcome,
    val structuredError: Boolean = false,
    val attempts: Int = 1,
)

data class ScenarioProcessEvidence(
    val sessionId: String,
    val exitCode: Int?,
    val stdoutBytes: Int?,
    val stderrBytes: Int?,
    val outputComplete: Boolean,
)

data class ScenarioStepEvidence(
    val id: String,
    val kind: String,
    val verdict: ScenarioVerdict,
    val startedAt: String,
    val finishedAt: String,
    val durationMs: Long,
    val executed: Boolean,
    val assertions: List<ScenarioAssertionEvidence>,
    val http: ScenarioHttpEvidence? = null,
    val process: ScenarioProcessEvidence? = null,
)

data class ScenarioRunReport(
    val runId: String,
    val scenarioId: String,
    val scenarioSha256: String,
    val scenarioPath: String,
    val targetId: String,
    val sourceArtifacts: List<ScenarioArtifact>,
    val sourceArtifactHash: String,
    val startedAt: String,
    val finishedAt: String,
    val status: ScenarioRunStatus,
    val steps: List<ScenarioStepEvidence>,
    val evidencePath: Path,
    val evidenceStored: Boolean,
    val notes: List<String>,
    /** False for checkpoints, incomplete execution or unconfirmed cleanup/storage. */
    val executionComplete: Boolean = true,
) {
    val evaluatedTestAssertions: Int get() = steps.sumOf { step -> step.assertions.count { it.testAssertion && it.verdict != ScenarioVerdict.SKIPPED } }
    val testActuallyRan: Boolean get() = evaluatedTestAssertions > 0
    val comparisonEligible: Boolean get() = executionComplete && evidenceStored && testActuallyRan &&
        status in setOf(ScenarioRunStatus.PASSED, ScenarioRunStatus.FAILED) &&
        steps.none { it.verdict == ScenarioVerdict.SKIPPED || it.assertions.any { a -> a.verdict == ScenarioVerdict.SKIPPED } }
}

class ScenarioRunCancelledException(val report: ScenarioRunReport) : kotlinx.coroutines.CancellationException("Scenario cancelled; partial evidence was retained")

class ScenarioValidationException(message: String) : IllegalArgumentException(message)
internal class ScenarioExecutionException(val code: String) : IllegalStateException(code)

internal fun ScenarioStep.kind(): String = when (this) {
    is ScenarioStep.Start -> "start"
    is ScenarioStep.Stop -> "stop"
    is ScenarioStep.Command -> "command"
    is ScenarioStep.Http -> "http"
}
