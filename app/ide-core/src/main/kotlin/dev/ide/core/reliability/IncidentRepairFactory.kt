package dev.ide.core.reliability

import dev.ide.agent.IncidentApplyResult
import dev.ide.agent.IncidentDeviceResult
import dev.ide.agent.IncidentKnownBuild
import dev.ide.agent.IncidentPatchReview
import dev.ide.agent.IncidentRepairArtifact
import dev.ide.agent.IncidentRepairPolicy
import dev.ide.agent.IncidentTaskRunner
import dev.ide.agent.IncidentVerificationPlan
import dev.ide.ui.backend.AgentService
import dev.ide.workbench.reliability.IncidentKey
import dev.ide.workbench.reliability.IncidentRepository
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/** Minimal launcher wiring. No Activity, BuildService, editor-global permission, or module circle. */
fun createIncidentRepairController(
    projectId: String,
    projectRoot: Path,
    privateWorkRoot: Path,
    agent: AgentService,
    repository: IncidentRepository,
    scope: CoroutineScope,
    /** Lookup host-private finalized build records, not SDK payload fields or pairing-only summaries. */
    registeredBuild: (IncidentKey) -> IncidentKnownBuild?,
    /** Real offline execution capability with separate native-device consent where applicable. */
    tasks: IncidentTaskRunner,
    /** Host-approved fixture/invariant plan, never supplied by the LLM or incident text. */
    verificationPlan: suspend (IncidentKnownBuild) -> IncidentVerificationPlan?,
    initialPolicy: IncidentRepairPolicy = IncidentRepairPolicy(projectId),
    persistPolicy: (IncidentRepairPolicy) -> Unit = {},
    checkedApply: (suspend (IncidentPatchReview) -> IncidentApplyResult)? = null,
    artifact: (suspend (IncidentPatchReview, Set<String>) -> IncidentRepairArtifact?)? = null,
    install: (suspend (IncidentRepairArtifact) -> IncidentDeviceResult)? = null,
    launch: (suspend (IncidentRepairArtifact) -> IncidentDeviceResult)? = null,
): IncidentRepairController {
    val source = IncidentRepositorySource(repository, projectId, registeredBuild)
    val host = IncidentProjectRepairHost(projectId, projectRoot, privateWorkRoot, tasks,
        registeredBuild = { record -> registeredBuild(record.identity.key()) }, plan = verificationPlan,
        checkedApply = checkedApply, findArtifact = artifact, installArtifact = install, launchArtifact = launch)
    return IncidentRepairController(host, source, IncidentAgentProvider(agent), scope, initialPolicy, persistPolicy)
}
