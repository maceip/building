package dev.ide.agent.impl

import dev.ide.agent.IncidentFrame
import dev.ide.agent.IncidentIdentity
import dev.ide.agent.IncidentKnownBuild
import dev.ide.agent.IncidentRecord
import dev.ide.agent.IncidentSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncidentToolsTest {
    private val key = IncidentIdentity("dev.fixture.app", "build-1", "session-1", "event-1")
    private val build = IncidentKnownBuild("project", key.packageName, key.buildId, "brokenDebug", "a".repeat(64), "source-uuid", "b".repeat(64), true)
    private val record = IncidentRecord(key, build.sourceRevision, 123, "fatal_java",
        listOf(IncidentFrame("dev.fixture.Logic", "checkpoint", "Logic.java", 42)), build)
    private var reads = 0
    private val source = object : IncidentSource {
        override suspend fun list(limit: Int) = listOf(record).also { reads++ }
        override suspend fun read(identity: IncidentIdentity) = record.takeIf { it.identity == identity }.also { reads++ }
        override fun observe() = MutableStateFlow(listOf(record))
    }

    @Test fun readToolsReturnActualStructuredMetadataAndNeverMutateOrExposeRawPayloads() = runBlocking {
        val tools = incidentTools(source) { true }
        assertTrue(tools.none { it.mutating })
        val listed = tools.first().execute(JsonToolArgs(parseArgsObject("{}")))
        val item = AgentJson.parseToJsonElement(listed.content).jsonArray.single().jsonObject
        assertEquals("build-1", item.getValue("build_id").jsonPrimitive.content)
        assertEquals("brokenDebug", item.getValue("variant").jsonPrimitive.content)
        assertEquals("source-uuid", item.getValue("source_uuid").jsonPrimitive.content)
        val read = tools.last().execute(JsonToolArgs(parseArgsObject("""{"package_name":"dev.fixture.app","build_id":"build-1","session_id":"session-1","event_id":"event-1"}""")))
        val content = AgentJson.parseToJsonElement(read.content).jsonObject
        assertEquals("checkpoint", content.getValue("frames").jsonArray.single().jsonObject.getValue("method").jsonPrimitive.content)
        assertTrue(content.keys.none { it in setOf("raw", "redactedText", "message", "stackTrace", "nonce", "authorization", "apiKey", "source") })
        assertFalse(read.isError)
    }

    @Test fun disabledReadToolsNeverEvenAccessRepository() = runBlocking {
        val tools = incidentTools(source) { false }
        for (tool in tools) assertTrue(tool.execute(JsonToolArgs(parseArgsObject("{}"))).isError)
        assertEquals(0, reads)
    }

    @Test fun foreignIdentityIsNotReadableAndArbitraryStringsAreWithheld() = runBlocking {
        val tool = incidentTools(source) { true }.last()
        assertTrue(tool.execute(JsonToolArgs(parseArgsObject("""{"package_name":"other.app","build_id":"build-1","session_id":"session-1","event_id":"event-1"}"""))).isError)
        val malicious = record.copy(frames = listOf(IncidentFrame("https://example.test/?token=PRIVATE", "Bearer SECRET", "../../private.env", 4)))
        val payload = incidentJson(malicious).toString()
        assertTrue("[withheld]" in payload)
        assertFalse("PRIVATE" in payload || "SECRET" in payload || "private.env" in payload)
    }
}
