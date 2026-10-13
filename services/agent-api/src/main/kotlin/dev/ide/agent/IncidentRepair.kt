package dev.ide.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/** Host-verified identity. Never use an SDK event's package/build claims to grant permissions. */
data class IncidentIdentity(val packageName: String, val buildId: String, val sessionId: String, val eventId: String)

data class IncidentFrame(val className: String, val methodName: String, val fileName: String?, val line: Int?)

/** Deliberately no raw payload, exception message, log, URL, device identifier, or pairing capability. */
data class IncidentRecord(
    val identity: IncidentIdentity,
    val sourceRevision: String,
    val timestamp: Long,
    val type: String,
    val frames: List<IncidentFrame> = emptyList(),
    /** Joined only from the host's registered build, never trusted from the SDK payload. */
    val build: IncidentKnownBuild? = null,
)

/** Read-only, project-scoped adapter over the authenticated local incident repository. */
interface IncidentSource {
    suspend fun list(limit: Int = 50): List<IncidentRecord>
    suspend fun read(identity: IncidentIdentity): IncidentRecord?
    fun observe(): Flow<List<IncidentRecord>>
}

/** Consent is host-private and per project, never inferred from repository files or incoming events. */
data class IncidentRepairPolicy(
    val projectId: String,
    val enabled: Boolean = false,
    val cloudAiDataSharing: Boolean = false,
    /** Binds consent to the selected provider/endpoint/model, not just to a generic AI toggle. */
    val approvedAiConfiguration: String? = null,
    val autoApplyVerifiedPatch: Boolean = false,
    val maxAttempts: Int = 2,
) {
    init { require(projectId.isNotBlank()); require(maxAttempts in 1..2) }
}

data class IncidentAiConfiguration(val id: String, val label: String)

/** Uses the application's already configured providers. Null means an external gate, never a mock. */
interface IncidentAiProvider {
    fun configuration(): IncidentAiConfiguration?
    fun client(expectedConfigurationId: String): LlmClient?
    fun model(expectedConfigurationId: String): String?
}

/** Registered at build time, from SDK BuildConfig/source assets, not reconstructed from an APK name. */
data class IncidentKnownBuild(
    val projectId: String,
    val packageName: String,
    val buildId: String,
    val variant: String,
    val sourceRevision: String,
    val sourceUuid: String,
    val artifactSha256: String,
    val debuggable: Boolean,
)

/** Use the SAME canonical source hasher as SDK build injection; keys retain relative filenames. */
data class IncidentSourceSnapshot(val revision: String, val files: Map<String, String>)

enum class IncidentTaskKind { BUILD, REPRODUCER, FUNCTIONAL }

/** A host-approved task, frozen BEFORE AI. The model cannot add commands, arguments, tests, or reports. */
data class IncidentTask(
    val id: String,
    val kind: IncidentTaskKind,
    val command: List<String>,
    val reportFiles: Set<String> = emptySet(),
    /** Exact classname#method identities, not a test count or an AI's assertion of success. */
    val expectedTests: Set<String> = emptySet(),
    val timeoutMillis: Long = 180_000,
) {
    init {
        require(id.isNotBlank() && command.isNotEmpty())
        require(timeoutMillis in 1..300_000)
        require(kind == IncidentTaskKind.BUILD || (reportFiles.isNotEmpty() && expectedTests.isNotEmpty()))
    }
}

/** Trusted host/fixture configuration. Never load this authorization from an incident or model output. */
data class IncidentVerificationPlan(
    val build: IncidentTask,
    val reproducer: IncidentTask,
    val functional: IncidentTask,
    /** Explicit existing source files the AI may edit. No build files, tests, creates, renames, or deletes. */
    val editableFiles: Set<String>,
    /** Frozen test/reproducer/build/runner inputs, including scripts and all test invariant files. */
    val immutableFiles: Map<String, String>,
    val reproducerFiles: Set<String>,
    val functionalFiles: Set<String>,
    /** Extra non-secret build inputs absent from the SDK source snapshot (e.g. wrapper JAR/resources). */
    val additionalCopyFiles: Set<String> = emptySet(),
    val artifactFiles: Set<String> = emptySet(),
    /** A native verifier must reuse this exact external test APK/DEX; source hashes alone are insufficient. */
    val immutableHarnessSha256: String? = null,
) {
    init {
        require(immutableHarnessSha256 == null || Regex("[a-f0-9]{64}").matches(immutableHarnessSha256))
        require(build.kind == IncidentTaskKind.BUILD)
        require(reproducer.kind == IncidentTaskKind.REPRODUCER && functional.kind == IncidentTaskKind.FUNCTIONAL)
        require(setOf(build.id, reproducer.id, functional.id).size == 3)
        require(editableFiles.isNotEmpty() && reproducerFiles.isNotEmpty() && functionalFiles.isNotEmpty())
        require(reproducerFiles.intersect(functionalFiles).isEmpty())
        require(immutableFiles.keys.containsAll(reproducerFiles + functionalFiles))
        require(editableFiles.intersect(immutableFiles.keys).isEmpty())
        require(reproducer.expectedTests.intersect(functional.expectedTests).isEmpty())
    }
}

enum class IncidentTestOutcome { PASSED, FAILED, ERROR, SKIPPED }
data class IncidentTestCase(val id: String, val outcome: IncidentTestOutcome)

/** Receipts are produced by an actual task runner, never populated from assistant text. */
data class IncidentTaskReceipt(
    val executionId: String,
    val taskId: String,
    val taskFingerprint: String,
    val exitCode: Int?,
    val completed: Boolean,
    val tests: List<IncidentTestCase> = emptyList(),
    val reportHashes: Map<String, String> = emptyMap(),
    /** Tooling API terminal task result, when there is no OS process exit code. Never an AI claim. */
    val terminalTaskSuccess: Boolean? = null,
    /** Native runner receipts bind the exact same installed immutable test APK/DEX across every run. */
    val immutableHarnessSha256: String? = null,
)

fun interface IncidentTaskRunner {
    /** Must terminate child work on cancellation; no install, deploy, arbitrary network, or original writes. */
    suspend fun run(copyRoot: Path, task: IncidentTask): IncidentTaskReceipt
}

data class IncidentTaskProof(
    val receipt: IncidentTaskReceipt,
    val sourceRevision: String,
    val reproducerHash: String,
    val immutableInputsHash: String,
)

data class IncidentPatchReview(
    val runId: String,
    val incident: IncidentIdentity,
    val knownBuild: IncidentKnownBuild,
    val originalSnapshot: IncidentSourceSnapshot,
    val repairedSnapshot: IncidentSourceSnapshot,
    val isolatedRoot: Path,
    val changes: List<FileChange>,
    val evidence: List<IncidentTaskProof>,
    /** Includes approved non-source build/test inputs; host must compare these in the same transaction. */
    val originalInputHashes: Map<String, String> = originalSnapshot.files,
    /** The exact host-approved plan used for this proof; applying cannot substitute a newer plan. */
    val verificationPlan: IncidentVerificationPlan? = null,
)

sealed interface IncidentApplyResult {
    data object Applied : IncidentApplyResult
    data class Pending(val reason: String) : IncidentApplyResult
    data object StaleSource : IncidentApplyResult
}

data class IncidentRepairArtifact(
    val path: Path,
    val sha256: String,
    val packageName: String,
    val variant: String,
    val sourceRevision: String,
    val debuggable: Boolean,
)

sealed interface IncidentDeviceResult {
    data object Completed : IncidentDeviceResult
    data class Pending(val reason: String) : IncidentDeviceResult
}

/** No Android/app-module types cross this facade. Unavailable permissions/capabilities remain pending. */
interface IncidentRepairHost {
    val projectId: String
    val projectRoot: Path
    val privateWorkRoot: Path
    val tasks: IncidentTaskRunner
    suspend fun knownBuild(incident: IncidentRecord): IncidentKnownBuild?
    suspend fun snapshot(root: Path): IncidentSourceSnapshot
    suspend fun verificationPlan(build: IncidentKnownBuild): IncidentVerificationPlan?

    /**
     * Only an atomic host/editor transaction may return Applied: compare ALL originalSnapshot filenames,
     * originalInputHashes, bytes, and live editor buffers while holding the write lock; all-or-nothing commit the reviewed diff.
     * A host lacking this primitive must return Pending. Never implement this with reset/stash or a series
     * of unguarded writes. The engine never writes the original project itself. Recheck [authorized] and
     * coroutine cancellation immediately before committing; once committed, report Applied even if the
     * caller cancels afterward. Do not suspend between the commit and returning its outcome.
     */
    suspend fun applyAtomically(review: IncidentPatchReview, authorized: () -> Boolean): IncidentApplyResult =
        IncidentApplyResult.Pending("Verified isolated copy is ready; atomic editor apply is unavailable.")

    /** Bind an actual output's embedded package/variant/source provenance, not merely its filename. */
    suspend fun artifact(review: IncidentPatchReview, candidates: Set<String>): IncidentRepairArtifact? = null

    /** Separate explicit UI actions; Android installer/launch permissions are not AI or auto-patch consent. */
    suspend fun install(artifact: IncidentRepairArtifact): IncidentDeviceResult =
        IncidentDeviceResult.Pending("Installation needs the host's Android permission flow.")
    suspend fun launch(artifact: IncidentRepairArtifact): IncidentDeviceResult =
        IncidentDeviceResult.Pending("Launch needs separate host authorization.")
}

enum class IncidentRepairPhase {
    DISABLED, IDLE, CHECKING, REPRODUCING, DIAGNOSING, VERIFYING,
    AWAITING_REVIEW, VERIFIED_COPY, APPLIED, PENDING, REFUSED, FAILED, CANCELLED,
}

data class IncidentRepairState(
    val phase: IncidentRepairPhase = IncidentRepairPhase.DISABLED,
    val runId: String? = null,
    val incident: IncidentIdentity? = null,
    val attempt: Int = 0,
    val detail: String = "Automatic incident repair is disabled for this project.",
    val review: IncidentPatchReview? = null,
    val artifact: IncidentRepairArtifact? = null,
    val install: IncidentDeviceResult? = null,
    val launch: IncidentDeviceResult? = null,
)

/** Small UI port: opt-in + separate cloud consent, incident action, diff approval, cancel, install, launch. */
interface IncidentRepairActions {
    val policy: StateFlow<IncidentRepairPolicy>
    val state: StateFlow<IncidentRepairState>
    fun setPolicy(policy: IncidentRepairPolicy)
    fun repair(identity: IncidentIdentity)
    fun cancel()
    fun approvePatch(runId: String)
    fun rejectPatch(runId: String)
    fun install(runId: String)
    fun launch(runId: String)
}
