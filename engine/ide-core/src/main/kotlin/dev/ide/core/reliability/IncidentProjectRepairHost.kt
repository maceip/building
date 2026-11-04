package dev.ide.core.reliability

import dev.ide.agent.IncidentApplyResult
import dev.ide.agent.IncidentDeviceResult
import dev.ide.agent.IncidentKnownBuild
import dev.ide.agent.IncidentPatchReview
import dev.ide.agent.IncidentRecord
import dev.ide.agent.IncidentRepairArtifact
import dev.ide.agent.IncidentRepairHost
import dev.ide.agent.IncidentSourceSnapshot
import dev.ide.agent.IncidentTaskRunner
import dev.ide.agent.IncidentVerificationPlan
import dev.ide.build.ReliabilitySourceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** Application-independent host adapter. Mandatory build/task hooks are real capabilities, not defaults. */
class IncidentProjectRepairHost(
    override val projectId: String,
    override val projectRoot: Path,
    override val privateWorkRoot: Path,
    override val tasks: IncidentTaskRunner,
    private val registeredBuild: suspend (IncidentRecord) -> IncidentKnownBuild?,
    private val plan: suspend (IncidentKnownBuild) -> IncidentVerificationPlan?,
    private val checkedApply: (suspend (IncidentPatchReview, () -> Boolean) -> IncidentApplyResult)? = null,
    private val findArtifact: (suspend (IncidentPatchReview, Set<String>) -> IncidentRepairArtifact?)? = null,
    private val installArtifact: (suspend (IncidentRepairArtifact) -> IncidentDeviceResult)? = null,
    private val launchArtifact: (suspend (IncidentRepairArtifact) -> IncidentDeviceResult)? = null,
) : IncidentRepairHost {
    override suspend fun knownBuild(incident: IncidentRecord): IncidentKnownBuild? = registeredBuild(incident)
    override suspend fun verificationPlan(build: IncidentKnownBuild): IncidentVerificationPlan? = plan(build)

    override suspend fun snapshot(root: Path): IncidentSourceSnapshot = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val result = ReliabilitySourceSnapshot.capture(root) { context.ensureActive() }
        IncidentSourceSnapshot(result.revision, result.privateRelativeFiles.toMap())
    }

    override suspend fun applyAtomically(review: IncidentPatchReview, authorized: () -> Boolean): IncidentApplyResult =
        checkedApply?.invoke(review, authorized) ?: super.applyAtomically(review, authorized)
    override suspend fun artifact(review: IncidentPatchReview, candidates: Set<String>): IncidentRepairArtifact? =
        findArtifact?.invoke(review, candidates)
    override suspend fun install(artifact: IncidentRepairArtifact): IncidentDeviceResult =
        installArtifact?.invoke(artifact) ?: super.install(artifact)
    override suspend fun launch(artifact: IncidentRepairArtifact): IncidentDeviceResult =
        launchArtifact?.invoke(artifact) ?: super.launch(artifact)
}
