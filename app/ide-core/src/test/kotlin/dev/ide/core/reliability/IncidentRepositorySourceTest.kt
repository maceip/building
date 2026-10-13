package dev.ide.core.reliability

import dev.ide.agent.IncidentKnownBuild
import dev.ide.workbench.reliability.Incident
import dev.ide.workbench.reliability.IncidentFilter
import dev.ide.workbench.reliability.IncidentKey
import dev.ide.workbench.reliability.IncidentRepository
import dev.ide.workbench.reliability.IncidentState
import dev.ide.workbench.reliability.IncidentSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncidentRepositorySourceTest {
    @Test fun cleanSdkTextIsAdmittedWhenHostRedactedFlagMeansNoChangeWasNeeded() = runBlocking {
        val key = IncidentKey("fixture.app", "build", "session", "event")
        val build = IncidentKnownBuild("project", key.packageName, key.buildId, "debug", "a".repeat(64), "build", "b".repeat(64), true)
        val incident = Incident(IncidentSummary(key, build.sourceRevision, 1, 2, "fatal_java", "local preview", IncidentState.OPEN, hostRedacted = false),
            "Exception: do not transmit token=PRIVATE\n  at fixture.Editor.checkpoint(Editor.java:42)\nhttps://secret.example/private")
        val repo = object : IncidentRepository {
            override fun list(filter: IncidentFilter) = listOf(incident.summary)
            override fun read(key: IncidentKey) = incident.takeIf { it.summary.key == key }
            override fun observe() = MutableStateFlow(listOf(incident.summary))
            override fun setState(key: IncidentKey, state: IncidentState) = Unit
        }
        val source = IncidentRepositorySource(repo, "project") { build }
        val value = source.list().single()
        assertEquals("checkpoint", value.frames.single().methodName)
        assertEquals("Editor.java", value.frames.single().fileName)
        assertTrue(!value.toString().contains("PRIVATE") && !value.toString().contains("secret.example"))
        assertEquals(value, source.observe().first().single())
        assertEquals(value, source.read(key.identity()))
        assertNull(IncidentRepositorySource(repo, "other-project") { build }.read(key.identity()))
        assertNull(IncidentRepositorySource(repo, "project") { null }.read(key.identity()))
        assertTrue(IncidentRepositorySource(repo, "project") { build.copy(sourceRevision = "c".repeat(64)) }.observe().first().isEmpty())
        assertNull(IncidentRepositorySource(repo, "project") { build.copy(packageName = "forged.app") }.read(key.identity()))
    }
}
