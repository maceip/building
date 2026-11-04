package dev.ide.core.reliability

import dev.ide.agent.IncidentAiProvider
import dev.ide.agent.IncidentDeviceResult
import dev.ide.agent.IncidentIdentity
import dev.ide.agent.IncidentRepairActions
import dev.ide.agent.IncidentRepairArtifact
import dev.ide.agent.IncidentRepairHost
import dev.ide.agent.IncidentRepairPhase
import dev.ide.agent.IncidentRepairPolicy
import dev.ide.agent.IncidentRepairState
import dev.ide.agent.IncidentSource
import dev.ide.agent.impl.IncidentDigests
import dev.ide.agent.impl.IncidentRepairEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** One host-private controller per open project; no global auto-accept or chat-session permission changes. */
class IncidentRepairController(
    private val host: IncidentRepairHost,
    private val source: IncidentSource,
    private val ai: IncidentAiProvider,
    parentScope: CoroutineScope,
    initialPolicy: IncidentRepairPolicy = IncidentRepairPolicy(host.projectId),
    /** Persist only to host-private per-project preferences, NEVER to incident/project-controlled files. */
    private val persistPolicy: (IncidentRepairPolicy) -> Unit = {},
) : IncidentRepairActions, AutoCloseable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)
    private val mutablePolicy = MutableStateFlow(initialPolicy.also { require(it.projectId == host.projectId) })
    override val policy: StateFlow<IncidentRepairPolicy> = mutablePolicy.asStateFlow()
    private val mutableState = MutableStateFlow(IncidentRepairState(if (initialPolicy.enabled) IncidentRepairPhase.IDLE else IncidentRepairPhase.DISABLED))
    override val state: StateFlow<IncidentRepairState> = mutableState.asStateFlow()
    private val engine = IncidentRepairEngine(host, source, ai) { policy.value }
    private val lock = Any()
    private var active: Job? = null
    private val processed = LinkedHashSet<IncidentIdentity>()
    private var latest = emptyList<dev.ide.agent.IncidentRecord>()
    private var closed = false

    init {
        scope.launch {
            combine(source.observe(), policy) { incidents, consent -> incidents to consent }.collect { (incidents, consent) ->
                synchronized(lock) {
                    latest = incidents
                    if (consent.enabled) scheduleNext()
                }
            }
        }
    }

    /** The UI displays this provider label and captures its ID alongside the separate sharing consent. */
    fun aiConfiguration() = ai.configuration()

    override fun setPolicy(policy: IncidentRepairPolicy) {
        require(policy.projectId == host.projectId)
        synchronized(lock) {
            check(!closed)
            persistPolicy(policy)
            val previous = mutablePolicy.value
            mutablePolicy.value = policy
            if (!policy.enabled || !policy.cloudAiDataSharing || previous.approvedAiConfiguration != policy.approvedAiConfiguration) {
                active?.cancel()
            }
            if (active == null && !policy.enabled) mutableState.value = when {
                state.value.phase == IncidentRepairPhase.APPLIED -> state.value.copy(
                    detail = "Automatic repair is disabled. The previously applied verified patch remains in the original workspace.")
                state.value.review == null -> IncidentRepairState()
                else -> state.value.copy(phase = IncidentRepairPhase.VERIFIED_COPY,
                    detail = "Automatic repair is disabled. The verified isolated copy is retained; no original apply or device action is authorized.")
            }
            // A user changing consent may retry a previously blocked incident; normal repository re-emits do not.
            if (previous != policy) processed.clear()
            if (active == null) scheduleNext()
        }
    }

    override fun repair(identity: IncidentIdentity) {
        synchronized(lock) {
            if (closed || active != null || state.value.review != null) return
            if (!policy.value.enabled) {
                mutableState.value = IncidentRepairState()
                return
            }
            processed += identity
            start {
                val incident = source.read(identity)
                if (incident == null) mutableState.value = IncidentRepairState(IncidentRepairPhase.REFUSED,
                    incident = identity, detail = "This incident is not authorized for the current project.")
                else engine.repair(incident) { mutableState.value = it }
            }
        }
    }

    override fun cancel() {
        synchronized(lock) { active?.cancel() }
    }

    override fun approvePatch(runId: String) = action(runId) {
        val before = state.value
        val review = before.review ?: return@action
        mutableState.value = engine.apply(review, before)
    }

    override fun rejectPatch(runId: String) {
        synchronized(lock) {
            if (active != null || state.value.runId != runId || state.value.phase == IncidentRepairPhase.APPLIED) return
            val review = state.value.review ?: return
            deletePrivateCopy(review.isolatedRoot)
            mutableState.value = state.value.copy(phase = IncidentRepairPhase.CANCELLED, review = null, artifact = null,
                detail = "Patch declined; the isolated copy was removed and original source is unchanged.")
            scheduleNext()
        }
    }

    override fun install(runId: String) = action(runId) {
        val artifact = verifiedArtifact() ?: return@action
        // This method is called only by the separate user-facing Install action, never by event subscription.
        val outcome = host.install(artifact)
        mutableState.value = state.value.copy(install = outcome, detail = when (outcome) {
            IncidentDeviceResult.Completed -> "Verified debug artifact installed. Launch still requires its separate action."
            is IncidentDeviceResult.Pending -> "Verified copy retained; Android installation permission or host capability is pending."
        })
    }

    override fun launch(runId: String) = action(runId) {
        val artifact = verifiedArtifact() ?: return@action
        if (state.value.install != IncidentDeviceResult.Completed) {
            mutableState.value = state.value.copy(launch = IncidentDeviceResult.Pending("Installation has not completed."))
            return@action
        }
        val outcome = host.launch(artifact)
        mutableState.value = state.value.copy(launch = outcome, detail = when (outcome) {
            IncidentDeviceResult.Completed -> "Host confirmed launching the verified debug artifact."
            is IncidentDeviceResult.Pending -> "Verified copy retained; launch authorization or host capability is pending."
        })
    }

    private fun verifiedArtifact(): IncidentRepairArtifact? {
        val value = state.value
        val artifact = value.artifact
        if (value.review == null || value.phase !in VERIFIED_PHASES || artifact == null || !artifact.debuggable ||
            !Files.isRegularFile(artifact.path, NOFOLLOW_LINKS) || IncidentDigests.file(artifact.path) != artifact.sha256) {
            mutableState.value = value.copy(detail = "A provenance-checked verified debug artifact is required; nothing was installed or launched.")
            return null
        }
        return artifact
    }

    private fun action(runId: String, block: suspend () -> Unit) {
        synchronized(lock) {
            if (closed || active != null || state.value.runId != runId || state.value.phase !in VERIFIED_PHASES || !policy.value.enabled) return
            start(block)
        }
    }

    /** Called with lock held. Backpressure is one run/review at a time, never a coroutine per crash burst. */
    private fun scheduleNext() {
        if (closed || active != null || !policy.value.enabled || state.value.review != null) return
        val next = latest.firstOrNull { it.identity !in processed } ?: return
        // Repository itself is bounded to 2048. Do not evict identities and silently repeat paid AI runs.
        if (processed.size >= 2048) {
            mutableState.value = state.value.copy(phase = IncidentRepairPhase.PENDING, detail = "Incident session limit reached; review policy before another automatic repair.")
            return
        }
        processed += next.identity
        start {
            val current = source.read(next.identity)
            if (current != null) engine.repair(current) { mutableState.value = it }
        }
    }

    private fun start(block: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (_: CancellationException) {
                if (state.value.review == null) mutableState.value = state.value.copy(phase = IncidentRepairPhase.CANCELLED,
                    detail = "Repair cancelled; no original source was changed.")
            } catch (_: Exception) {
                mutableState.value = state.value.copy(phase = when {
                    state.value.phase == IncidentRepairPhase.APPLIED -> IncidentRepairPhase.APPLIED
                    state.value.review != null -> IncidentRepairPhase.VERIFIED_COPY
                    else -> IncidentRepairPhase.PENDING
                }, detail = "The host operation did not confirm completion; source-apply and device states remain separate.")
            }
        }
        active = job
        job.invokeOnCompletion { synchronized(lock) { if (active === job) active = null; scheduleNext() } }
        job.start()
    }

    private fun deletePrivateCopy(root: java.nio.file.Path) {
        val privateRoot = host.privateWorkRoot.toRealPath()
        require(root.toAbsolutePath().normalize().startsWith(privateRoot) && root != privateRoot && !Files.isSymbolicLink(root))
        if (Files.isDirectory(root, NOFOLLOW_LINKS)) Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    override fun close() {
        synchronized(lock) { closed = true; scope.cancel() }
    }

    companion object {
        private val VERIFIED_PHASES = setOf(IncidentRepairPhase.AWAITING_REVIEW, IncidentRepairPhase.VERIFIED_COPY, IncidentRepairPhase.APPLIED)
    }
}
