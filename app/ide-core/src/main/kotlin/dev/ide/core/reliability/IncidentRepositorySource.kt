package dev.ide.core.reliability

import dev.ide.agent.IncidentFrame
import dev.ide.agent.IncidentIdentity
import dev.ide.agent.IncidentKnownBuild
import dev.ide.agent.IncidentRecord
import dev.ide.agent.IncidentSource
import dev.ide.workbench.reliability.Incident
import dev.ide.workbench.reliability.IncidentFilter
import dev.ide.workbench.reliability.IncidentKey
import dev.ide.workbench.reliability.IncidentRepository
import dev.ide.workbench.reliability.IncidentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Joins the authenticated/scrubbed repository to THIS project's registered builds. hostRedacted means text CHANGED, not whether it was checked. */
class IncidentRepositorySource(
    private val repository: IncidentRepository,
    private val projectId: String,
    private val registeredBuild: (IncidentKey) -> IncidentKnownBuild?,
) : IncidentSource {
    override suspend fun list(limit: Int): List<IncidentRecord> = withContext(Dispatchers.IO) {
        repository.list(IncidentFilter(state = IncidentState.OPEN, limit = 2048)).asSequence()
            .filter { it.type in TYPES }
            .mapNotNull { repository.read(it.key)?.let(::record) }.take(limit.coerceIn(1, 50)).toList()
    }

    override suspend fun read(identity: IncidentIdentity): IncidentRecord? = withContext(Dispatchers.IO) {
        repository.read(identity.key())?.let(::record)
    }

    override fun observe(): Flow<List<IncidentRecord>> = repository.observe().map { summaries ->
        summaries.asSequence().filter { it.state == IncidentState.OPEN && it.type in TYPES }
            .mapNotNull { repository.read(it.key)?.let(::record) }.take(2048).toList()
    }.flowOn(Dispatchers.IO)

    private fun record(incident: Incident): IncidentRecord? {
        val summary = incident.summary
        if (summary.state != IncidentState.OPEN || summary.type !in TYPES) return null
        val build = registeredBuild(summary.key) ?: return null
        if (build.projectId != projectId || build.packageName != summary.key.packageName ||
            build.buildId != summary.key.buildId || build.sourceRevision != summary.sourceRevision) return null
        // Drop exception messages, thread names, URLs and all arbitrary payload text. Stack symbols alone
        // are structural evidence useful to diagnosis; they confer no code-execution authority.
        val frames = incident.redactedText.lineSequence().take(512).mapNotNull { line ->
            val match = FRAME.matchEntire(line.trim()) ?: return@mapNotNull null
            val file = match.groupValues[3].takeIf { it.isNotBlank() }
            IncidentFrame(match.groupValues[1], match.groupValues[2], file, match.groupValues[4].toIntOrNull())
        }.take(64).toList()
        return IncidentRecord(summary.key.identity(), summary.sourceRevision, summary.timestamp, summary.type, frames, build)
    }

    companion object {
        private val TYPES = setOf("fatal_java", "nonfatal_java", "previous_exit")
        private val FRAME = Regex("(?:at\\s+)?([A-Za-z_$][A-Za-z0-9_.$]*)\\.([A-Za-z_$<>][A-Za-z0-9_$<>]*)\\((?:([A-Za-z0-9_.$-]+\\.(?:java|kt))(?::([0-9]{1,8}))?|Native Method|Unknown Source)\\)")
    }
}

fun IncidentKey.identity() = IncidentIdentity(packageName, buildId, sessionId, eventId)
fun IncidentIdentity.key() = IncidentKey(packageName, buildId, sessionId, eventId)
