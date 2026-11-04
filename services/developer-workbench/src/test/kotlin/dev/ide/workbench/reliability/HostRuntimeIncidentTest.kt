package dev.ide.workbench.reliability

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val runtimeWorkspaceId = "11111111-1111-4111-8111-111111111111"
internal const val runtimeSessionId = "22222222-2222-4222-8222-222222222222"
internal const val runtimeEventId = "33333333-3333-4333-8333-333333333333"
internal const val runtimePackId = "termux-arm64-0123456789abcdef"
internal val runtimeIdentity = HostRuntimeIdentity("dev.ide.test", 106, "3.27.0")
internal fun runtimeIncident(
    id: String = runtimeEventId,
    workspace: String = runtimeWorkspaceId,
    revision: String? = "f".repeat(64),
) = HostRuntimeIncident(workspace, runtimeSessionId, id, testClock.millis(), HostRuntimeContext(
    revision, if (revision == null) HostRuntimeSourceStatus.UNAVAILABLE else HostRuntimeSourceStatus.PRE_RUN_SNAPSHOT,
    runtimeIdentity.hostPackage, runtimeIdentity.versionCode, runtimeIdentity.versionName, HostRuntimeKind.NODE,
    HostRuntimeChannel.RUN, HostRuntimeStage.PROCESS, HostRuntimeOutcome.NONZERO_EXIT, exitCode = 9, durationMs = 123,
    packId = runtimePackId, abi = "arm64-v8a",
))

class HostRuntimeIncidentTest {
    @Test fun `v1 SDK migration preserves original fingerprint and retry after v2 rewrite`() {
        val disk = MemoryReliabilityStorage()
        val event = incident(text = "password=unpersisted-original")
        // Literal old encoder order is load-bearing: never recompute the stored hash from redacted text.
        val oldJson = "{\"eventId\":\"event-1\",\"sessionId\":\"session-1\",\"packageName\":\"sample.app\",\"buildId\":\"build-1\",\"sourceRevision\":\"abc123\",\"timestamp\":${testClock.millis()},\"type\":\"fatal_java\",\"redactedText\":\"password=unpersisted-original\"}"
        assertEquals(oldJson, eventJson(event).toString())
        val hash = digest(oldJson)
        disk.bytes = buildJsonObject {
            put("version", 1)
            put("records", JsonArray(listOf(buildJsonObject {
                put("event", eventJson(event.copy(redactedText = scrubIncidentText(event.redactedText))))
                put("receivedAt", testClock.millis()); put("state", "RESOLVED"); put("hostRedacted", true); put("payloadHash", hash)
            })))
        }.toString().toByteArray()
        val repo = DurableIncidentRepository(disk, clock = testClock)
        assertTrue(repo.accept(listOf(event)).newlyStored.isEmpty())
        assertNull(repo.read(event.key)!!.summary.runtimeContext)
        repo.acceptRuntime(listOf(runtimeIncident()))
        val saved = reliabilityObject(disk.bytes!!.toString(Charsets.UTF_8))
        assertEquals(2L, saved.number("version"))
        assertFalse("unpersisted-original" in disk.bytes!!.toString(Charsets.UTF_8))
        val restarted = DurableIncidentRepository(disk, clock = testClock)
        assertTrue(restarted.accept(listOf(event)).newlyStored.isEmpty())
        assertTrue(restarted.acceptRuntime(listOf(runtimeIncident())).newlyStored.isEmpty())
        assertEquals(IncidentState.RESOLVED, restarted.read(event.key)!!.summary.state)
        val sdkRecord = (saved["records"] as JsonArray).first().objectValue()
        assertEquals(hash, sdkRecord.text("payloadHash"))
        assertEquals(IncidentOrigin.SDK_APPLICATION, event.key.origin)
        assertNull(event.key.workspaceId)
    }

    @Test fun `SDK direct and wire entry points cannot forge host origin or workspace`() {
        val disk = MemoryReliabilityStorage()
        val repo = DurableIncidentRepository(disk, clock = testClock)
        for (key in listOf(runtimeIncident().key, incident().key.copy(origin = IncidentOrigin.HOST_RUNTIME, workspaceId = runtimeWorkspaceId),
            incident().key.copy(workspaceId = runtimeWorkspaceId))) {
            assertEquals(ReliabilityError.INVALID, assertFailsWith<ReliabilityException> { repo.accept(listOf(incident().copy(key = key))) }.code)
        }
        for ((name, value) in listOf("origin" to JsonPrimitive("HOST_RUNTIME"), "workspaceId" to JsonPrimitive(runtimeWorkspaceId),
            "runtimeContext" to runtimeEventJson(runtimeIncident()))) {
            val injected = JsonObject(eventJson(incident()) + (name to value))
            assertEquals(ReliabilityError.INVALID, assertFailsWith<ReliabilityException> { parseIncident(injected) }.code)
        }
        assertNull(disk.bytes)
        assertEquals(0, repo.list().size)
    }

    @Test fun `runtime namespace and per workspace quotas are separate from SDK package`() {
        val repo = DurableIncidentRepository(MemoryReliabilityStorage(), IncidentLimits(maxEventsPerApp = 1, maxEventsTotal = 3), testClock)
        val runtime = runtimeIncident()
        val sdk = incident().copy(key = IncidentKey(runtimeIdentity.hostPackage, "build-1", runtime.sessionId, runtime.eventId))
        repo.accept(listOf(sdk))
        repo.acceptRuntime(listOf(runtime))
        val otherWorkspace = runtime.copy(workspaceId = "44444444-4444-4444-8444-444444444444")
        repo.acceptRuntime(listOf(otherWorkspace))
        assertEquals(3, repo.list().size)
        assertEquals("", runtime.key.packageName)
        assertEquals("", runtime.key.buildId)
        assertEquals(IncidentOrigin.HOST_RUNTIME, runtime.key.origin)
        assertEquals(runtime.workspaceId, runtime.key.workspaceId)
        assertTrue(repo.acceptRuntime(listOf(runtime)).newlyStored.isEmpty())
        val overQuota = runtime.copy(eventId = "55555555-5555-4555-8555-555555555555")
        assertEquals(ReliabilityError.QUOTA, assertFailsWith<ReliabilityException> { repo.acceptRuntime(listOf(overQuota)) }.code)
        assertNotNull(repo.read(sdk.key))
        assertNotNull(repo.read(otherWorkspace.key))
    }

    @Test fun `runtime DTO retry deduplicates and changed metadata conflicts atomically`() {
        val disk = MemoryReliabilityStorage()
        val repo = DurableIncidentRepository(disk, clock = testClock)
        val original = runtimeIncident()
        repo.acceptRuntime(listOf(original, original))
        assertEquals(1, repo.list().size)
        assertEquals(listOf(original.key), repo.acceptRuntime(listOf(original)).acceptedKeys)
        val before = disk.bytes!!.clone()
        for (changed in listOf(original.copy(timestamp = 7), original.copy(context = original.context.copy(durationMs = 321)),
            original.copy(context = original.context.copy(sourceRevision = "e".repeat(64))),
            original.copy(context = original.context.copy(runtimeKind = HostRuntimeKind.NPM)))) {
            assertEquals(ReliabilityError.ID_CONFLICT, assertFailsWith<ReliabilityException> {
                repo.acceptRuntime(listOf(original.copy(eventId = "55555555-5555-4555-8555-555555555555"), changed))
            }.code)
            assertTrue(before.contentEquals(disk.bytes))
        }
        val another = original.copy(sessionId = "66666666-6666-4666-8666-666666666666")
        repo.acceptRuntime(listOf(another))
        assertEquals(2, repo.list().size)
    }

    @Test fun `typed canonical storage keeps exact structured hash and accepts explicit unavailable snapshot`() {
        val disk = MemoryReliabilityStorage()
        val original = runtimeIncident()
        val repo = DurableIncidentRepository(disk, clock = testClock)
        repo.acceptRuntime(listOf(original))
        val stored = repo.read(original.key)!!
        assertEquals(original.context, stored.summary.runtimeContext)
        assertEquals(original.context.sourceRevision, stored.summary.sourceRevision)
        assertFalse(original.context.sourceRevision!! in stored.redactedText)
        assertTrue("Pre-run snapshot only" in stored.redactedText)
        assertTrue("runtime-source-v1" in stored.redactedText)
        assertTrue(stored.redactedText.length < 2_048)
        val serialized = disk.bytes!!.toString(Charsets.UTF_8)
        assertTrue(original.context.sourceRevision!! in serialized)
        assertFalse("redactedText" in serialized) // runtime private format stores the DTO, regenerates text on reload.
        val noSnapshot = runtimeIncident("55555555-5555-4555-8555-555555555555", revision = null)
        repo.acceptRuntime(listOf(noSnapshot))
        val restored = DurableIncidentRepository(disk, clock = testClock).read(noSnapshot.key)!!
        assertNull(restored.summary.runtimeContext!!.sourceRevision)
        assertEquals(HostRuntimeSourceStatus.UNAVAILABLE, restored.summary.runtimeContext!!.sourceStatus)
        assertEquals("", restored.summary.sourceRevision)
    }

    @Test fun `runtime input has no arbitrary log command environment path or error extension`() {
        val forbidden = setOf("redactedText", "text", "logs", "stdout", "stderr", "error", "args", "arguments", "command", "root", "environment")
        val fields = (HostRuntimeIncident::class.java.declaredFields + HostRuntimeContext::class.java.declaredFields + HostRuntimeFinish::class.java.declaredFields)
        assertTrue(fields.none { it.name in forbidden || Throwable::class.java.isAssignableFrom(it.type) || Map::class.java.isAssignableFrom(it.type) })
        for (field in forbidden) {
            assertEquals(ReliabilityError.INVALID, assertFailsWith<ReliabilityException> {
                parseRuntimeIncident(JsonObject(runtimeEventJson(runtimeIncident()) + (field to JsonPrimitive("raw-secret"))))
            }.code)
        }
    }

    @Test fun `runtime validation is strict on identifiers source metadata pack and bounds`() {
        val repo = DurableIncidentRepository(MemoryReliabilityStorage(), clock = testClock)
        val original = runtimeIncident()
        val invalidContexts = listOf(
            original.context.copy(sourceRevision = "exact-ish"), original.context.copy(sourceRevision = ""),
            original.context.copy(sourceRevision = null), original.context.copy(sourceStatus = HostRuntimeSourceStatus.UNAVAILABLE),
            original.context.copy(sourcePolicy = "/private/source"), original.context.copy(hostPackage = "pseudoapp"),
            original.context.copy(versionCode = 0), original.context.copy(versionName = "token=raw-secret"),
            original.context.copy(packId = "/private/pack"), original.context.copy(abi = "unknown-abi"),
            original.context.copy(packId = null, abi = null), original.context.copy(durationMs = -1),
            original.context.copy(durationMs = MAX_RUNTIME_DURATION_MS + 1), original.context.copy(exitCode = 0),
            original.context.copy(outcome = HostRuntimeOutcome.CONFIGURATION_FAILURE, exception = HostRuntimeException.IO),
        )
        val invalidEvents = invalidContexts.map { original.copy(context = it) } + listOf(original.copy(workspaceId = "../project"),
            original.copy(sessionId = "short"), original.copy(eventId = "event"), original.copy(timestamp = -1))
        for (event in invalidEvents) assertEquals(ReliabilityError.INVALID,
            assertFailsWith<ReliabilityException> { repo.acceptRuntime(listOf(event)) }.code)
        repo.acceptRuntime(listOf(original.copy(context = original.context.copy(durationMs = MAX_RUNTIME_DURATION_MS))))
        val configuration = original.copy(eventId = "55555555-5555-4555-8555-555555555555", context = original.context.copy(
            stage = HostRuntimeStage.PREPARE, outcome = HostRuntimeOutcome.CONFIGURATION_FAILURE,
            exception = HostRuntimeException.INVALID_CONFIGURATION, packId = null, abi = null, exitCode = null))
        repo.acceptRuntime(listOf(configuration))
        assertEquals(2, repo.list().size)
        for (key in listOf(original.key.copy(packageName = "fake.app"), original.key.copy(buildId = "fake-build"))) {
            assertEquals(ReliabilityError.INVALID, assertFailsWith<ReliabilityException> { validateKey(key) }.code)
        }
    }

    @Test fun `uncertain runtime inbox commit never ACKs but durable restart deduplicates`() {
        val disk = MemoryReliabilityStorage()
        val repo = DurableIncidentRepository(disk, clock = testClock)
        disk.failAfterWrite = true
        assertEquals(ReliabilityError.STORAGE_UNAVAILABLE, assertFailsWith<ReliabilityException> { repo.acceptRuntime(listOf(runtimeIncident())) }.code)
        assertTrue(repo.observe().value.isEmpty())
        assertEquals(ReliabilityError.STORAGE_UNAVAILABLE, assertFailsWith<ReliabilityException> { repo.acceptRuntime(listOf(runtimeIncident())) }.code)
        disk.failAfterWrite = false
        val restarted = DurableIncidentRepository(disk, clock = testClock)
        assertEquals(listOf(runtimeIncident().key), restarted.acceptRuntime(listOf(runtimeIncident())).acceptedKeys)
        assertTrue(restarted.acceptRuntime(listOf(runtimeIncident())).newlyStored.isEmpty())
    }

    @Test fun `explicit refresh recovers uncertain committed runtime inbox without double insertion`() {
        val disk = MemoryReliabilityStorage()
        val repo = DurableIncidentRepository(disk, clock = testClock)
        disk.failAfterWrite = true
        assertFailsWith<ReliabilityException> { repo.acceptRuntime(listOf(runtimeIncident())) }
        assertTrue(repo.observe().value.isEmpty())
        disk.failAfterWrite = false
        assertFailsWith<ReliabilityException> { repo.acceptRuntime(listOf(runtimeIncident())) }
        repo.refresh()
        assertEquals(1, repo.observe().value.size)
        assertTrue(repo.acceptRuntime(listOf(runtimeIncident())).newlyStored.isEmpty())
        assertEquals(1, repo.list().size)
    }

    @Test fun `refresh on damaged primary stays blocked and never replaces corruption`() {
        val disk = MemoryReliabilityStorage()
        val repo = DurableIncidentRepository(disk, clock = testClock)
        disk.failBeforeWrite = true
        assertFailsWith<ReliabilityException> { repo.acceptRuntime(listOf(runtimeIncident())) }
        disk.failBeforeWrite = false
        disk.bytes = "{truncated".toByteArray()
        val damaged = disk.bytes!!.clone()
        assertEquals(ReliabilityError.STORAGE_UNAVAILABLE, assertFailsWith<ReliabilityException> { repo.refresh() }.code)
        assertEquals(ReliabilityError.STORAGE_UNAVAILABLE, assertFailsWith<ReliabilityException> { repo.acceptRuntime(listOf(runtimeIncident())) }.code)
        assertTrue(damaged.contentEquals(disk.bytes))
        assertTrue(repo.observe().value.isEmpty())
        // A genuinely absent primary after a before-write failure is safe to reload, unlike corrupted bytes.
        disk.bytes = null
        repo.refresh()
        assertEquals(1, repo.acceptRuntime(listOf(runtimeIncident())).newlyStored.size)
    }

    @Test fun `multiple inbox instances refresh inside transaction and retain each others data`() {
        val disk = MemoryReliabilityStorage()
        val one = DurableIncidentRepository(disk, clock = testClock)
        val two = DurableIncidentRepository(disk, clock = testClock)
        one.accept(listOf(incident()))
        two.acceptRuntime(listOf(runtimeIncident()))
        assertTrue(one.acceptRuntime(listOf(runtimeIncident())).newlyStored.isEmpty())
        two.setState(incident().key, IncidentState.RESOLVED)
        assertEquals(IncidentState.RESOLVED, one.read(incident().key)!!.summary.state)
        assertEquals(2, one.list().size)
    }

    @Test fun `damaged runtime private snapshot fails closed without rewriting`() {
        val disk = MemoryReliabilityStorage()
        DurableIncidentRepository(disk, clock = testClock).acceptRuntime(listOf(runtimeIncident()))
        val before = disk.bytes!!.toString(Charsets.UTF_8)
        disk.bytes = before.replace("\"durationMs\":123", "\"durationMs\":124").toByteArray()
        val damaged = disk.bytes!!.clone()
        assertEquals(ReliabilityError.STORAGE_UNAVAILABLE, assertFailsWith<ReliabilityException> { DurableIncidentRepository(disk) }.code)
        assertTrue(damaged.contentEquals(disk.bytes))
    }
}
