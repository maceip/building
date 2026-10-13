package dev.ide.workbench.scenario

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class RegressionScenarioRunnerTest {
    @TempDir lateinit var root: Path

    private fun review(steps: String, limits: String = "", target: String = "node:main.cjs"): ReviewedRegressionScenario {
        Files.writeString(root.resolve("main.cjs"), "// Reviewed inert fixture; the injected session owns execution")
        Files.writeString(root.resolve("scenario.json"), """{"version":1,"id":"local-test","title":"Local tests","targetId":"$target"$limits,"steps":[$steps]}""")
        return RegressionScenarioParser.load(root, "scenario.json")
    }
    private fun approve(review: ReviewedRegressionScenario) = ScenarioRunApproval(review.sha256, review.scenario.targetId, true)
    private val command = """{"id":"command","type":"command","expect":{"exitCode":0,"stdout":{"equals":"passed\n"},"stderr":{"equals":""}}}"""
    private val start = """{"id":"start","type":"start","ready":{"path":"/health","status":200}}"""
    private val get = """{"id":"get","type":"http","method":"GET","path":"/state","expect":{"status":200,"json":[{"pointer":"/value","equals":"retained"}]}}"""

    @Test fun `actual localhost request assertions and persisted fake sessions sequence error recovery`() = runBlocking {
        val sample = RegressionScenarioSamples.seedOfflineNode(root)
        val reviewed = RegressionScenarioParser.load(sample, RegressionScenarioSamples.STATE_SCENARIO)
        val persisted = sample.resolve("fake-persisted-state.json")
        var value = "{\"value\":\"initial\",\"revision\":0}"
        var armed = false
        val host = ServerHost { request ->
            when (request.path) {
                "/health" -> json(200, "{\"ready\":true}")
                "/state" -> {
                    if (request.method == "POST") {
                        value = request.body.readUtf8()
                        Files.writeString(persisted, value)
                    } else if (Files.exists(persisted)) value = Files.readString(persisted)
                    json(200, value)
                }
                "/fixtures/provider-error" -> { armed = true; json(202, "{\"armed\":true}") }
                "/provider" -> if (armed) { armed = false; json(429, "{\"error\":{\"code\":\"RATE_LIMITED\",\"retryable\":true}}") } else json(200, "{\"recovered\":true}")
                else -> json(404, "{}")
            }
        }
        val result = RegressionScenarioRunner(host).run(reviewed, approve(reviewed))
        assertEquals(ScenarioRunStatus.PASSED, result.status, result.steps.toString())
        assertEquals(2, host.starts)
        assertTrue(host.closed && host.sessions.all { it.stopped })
        assertEquals(10, result.steps.size)
        assertTrue(result.testActuallyRan && result.comparisonEligible && result.evidenceStored)
        assertEquals(ScenarioNetworkOutcome.RATE_LIMITED, result.steps.first { it.id == "structured-provider-error" }.http?.outcome)
        assertTrue(result.steps.first { it.id == "structured-provider-error" }.http?.structuredError == true)
        assertEquals(ScenarioNetworkOutcome.SUCCESS, result.steps.first { it.id == "provider-recovered" }.http?.outcome)
        assertNotEquals(result.steps.first().process?.sessionId, result.steps.first { it.id == "restart" }.process?.sessionId)
        val evidence = Json.parseToJsonElement(Files.readString(result.evidencePath)).jsonObject
        assertEquals(reviewed.sha256, evidence["scenarioSha256"]?.jsonPrimitive?.content)
        assertFalse(Files.readString(result.evidencePath).contains("retained-after-restart"))
        assertTrue(result.steps.all { it.startedAt.isNotBlank() && it.finishedAt.isNotBlank() && it.durationMs >= 0 })
    }

    @Test fun `exit streams are genuine independent assertions and all payloads stay redacted`() = runBlocking {
        val secret = "sk-live-a-very-private-provider-key"
        val reviewed = review(command.replace("passed\\n", "$secret\\n").replace("\"stderr\":{\"equals\":\"\"}", "\"stderr\":{\"contains\":[\"diagnostic\"]}"))
        val host = CommandHost(ScenarioProcessSnapshot(false, 0, "$secret\n", "diagnostic Authorization: Bearer $secret"))
        val result = RegressionScenarioRunner(host).run(reviewed, approve(reviewed))
        assertEquals(ScenarioRunStatus.PASSED, result.status)
        assertEquals(3, result.evaluatedTestAssertions)
        assertTrue(host.closed)
        val exported = Files.readString(result.evidencePath)
        assertFalse(exported.contains(secret))
        assertFalse(exported.contains("Authorization"))
        assertFalse(exported.contains("diagnostic"))
        assertTrue(exported.contains("redacted"))
    }

    @Test fun `missing distinct streams cannot silently pass and truncated output aborts`() = runBlocking {
        val reviewed = review(command)
        val unavailable = RegressionScenarioRunner(CommandHost(ScenarioProcessSnapshot(false, 0))).run(reviewed, approve(reviewed))
        assertEquals(ScenarioRunStatus.SKIPPED, unavailable.status)
        assertEquals(2, unavailable.steps.single().assertions.count { it.verdict == ScenarioVerdict.SKIPPED })
        assertFalse(unavailable.comparisonEligible)
        val truncated = RegressionScenarioRunner(CommandHost(ScenarioProcessSnapshot(false, 0, "passed\n", "", stdoutTruncated = true))).run(reviewed, approve(reviewed))
        assertEquals(ScenarioRunStatus.FAILED, truncated.status)
        assertFalse(truncated.testActuallyRan)
        assertTrue(truncated.steps.single().assertions.any { it.observed == "PROCESS_OUTPUT_LIMIT" })
    }

    @Test fun `approval hash artifacts and evidence symlinks are checked before any execution`() = runBlocking {
        val reviewed = review(command)
        val wrongHash = CommandHost(ScenarioProcessSnapshot(false, 0, "passed\n", ""))
        assertFailsWith<ScenarioValidationException> { RegressionScenarioRunner(wrongHash).run(reviewed, approve(reviewed).copy(reviewedSha256 = "0".repeat(64))) }
        assertEquals(0, wrongHash.starts); assertTrue(wrongHash.closed)
        Files.writeString(root.resolve("main.cjs"), "changed after review")
        val changed = CommandHost(ScenarioProcessSnapshot(false, 0))
        assertFailsWith<ScenarioValidationException> { RegressionScenarioRunner(changed).run(reviewed, approve(reviewed)) }
        assertEquals(0, changed.starts)
        val rereviewed = RegressionScenarioParser.load(root, "scenario.json")
        val outside = Files.createDirectory(root.resolve("other"))
        Files.createSymbolicLink(root.resolve(".building"), outside)
        val symlink = CommandHost(ScenarioProcessSnapshot(false, 0))
        assertFailsWith<ScenarioValidationException> { RegressionScenarioRunner(symlink).run(rereviewed, approve(rereviewed)) }
        assertEquals(0, symlink.starts)
        assertTrue(symlink.closed)
    }

    @Test fun `external private mapped and redirected URLs are never contacted`() = runBlocking {
        val reviewed = review("$start,$get")
        listOf("http://10.0.0.1:8080/", "http://192.168.1.2:8080/", "http://169.254.169.254:8080/", "http://example.com:8080/", "http://127.1:8080/", "http://2130706433:8080/", "http://[::ffff:127.0.0.1]:8080/", "http://user:secret@127.0.0.1:8080/", "https://127.0.0.1:8080/").forEach { url ->
            val host = CommandHost(ScenarioProcessSnapshot(true, stdout = "", stderr = "", previewUrl = url))
            val result = RegressionScenarioRunner(host).run(reviewed, approve(reviewed))
            assertEquals(ScenarioRunStatus.FAILED, result.status, url)
            assertEquals(ScenarioNetworkOutcome.BLOCKED_URL, result.steps.first().http?.outcome)
            assertTrue(host.closed)
            assertFalse(result.testActuallyRan)
        }
        val external = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            val redirectHost = ServerHost { request -> if (request.path == "/health") json(200, "{}") else MockResponse().setResponseCode(302).setHeader("Location", external.url("/private")) }
            val result = RegressionScenarioRunner(redirectHost).run(reviewed, approve(reviewed))
            assertEquals(ScenarioRunStatus.FAILED, result.status)
            assertEquals(ScenarioNetworkOutcome.BLOCKED_REDIRECT, result.steps[1].http?.outcome)
            assertEquals(0, external.requestCount)
            assertTrue(redirectHost.closed)
        } finally { external.shutdown() }
    }

    @Test fun `response time and oversized bodies fail bounded assertions without logs`() = runBlocking {
        val timing = get.replace("\"status\":200", "\"status\":200,\"maxResponseTimeMs\":20")
        val reviewed = review("$start,$timing")
        val slow = ServerHost { request -> if (request.path == "/health") json(200, "{}") else json(200, "{\"value\":\"retained\"}").setBodyDelay(100, TimeUnit.MILLISECONDS) }
        val delayed = RegressionScenarioRunner(slow).run(reviewed, approve(reviewed))
        assertEquals(ScenarioRunStatus.FAILED, delayed.status)
        assertEquals(ScenarioVerdict.FAIL, delayed.steps[1].assertions.first { it.key == "responseTimeMs" }.verdict)
        assertTrue(delayed.comparisonEligible)
        val bounded = review("$start,$get", ",\"limits\":{\"maxOutputBytes\":256}")
        val large = ServerHost { request -> if (request.path == "/health") json(200, "{}") else json(200, "x".repeat(257)) }
        val result = RegressionScenarioRunner(large).run(bounded, approve(bounded))
        assertEquals(ScenarioNetworkOutcome.RESPONSE_TOO_LARGE, result.steps[1].http?.outcome)
        assertFalse(result.testActuallyRan)
    }

    @Test fun `step timeout cancels hanging command and records unexecuted assertions`() = runBlocking {
        val reviewed = review(command.replace("\"type\":\"command\"", "\"type\":\"command\",\"timeoutMs\":100"))
        val host = CommandHost(ScenarioProcessSnapshot(true, stdout = "", stderr = ""), hang = true)
        val began = System.nanoTime()
        val report = RegressionScenarioRunner(host).run(reviewed, approve(reviewed))
        assertEquals(ScenarioRunStatus.FAILED, report.status)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 3000)
        assertTrue(host.closed && host.stopped)
        assertFalse(report.testActuallyRan)
        assertTrue(report.steps.single().assertions.any { it.observed == "STEP_TIMEOUT" })
    }

    @Test fun `cancellation interrupts HTTP IO closes owned server and retains cancelled evidence`() = runBlocking {
        val requested = CompletableDeferred<Unit>()
        val reviewed = review("$start,$get")
        val host = ServerHost { request ->
            if (request.path == "/health") json(200, "{}")
            else { requested.complete(Unit); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        }
        val runner = launch { RegressionScenarioRunner(host).run(reviewed, approve(reviewed)) }
        withTimeout(5000) { requested.await() }
        val cancelledAt = System.nanoTime()
        withTimeout(2000) { runner.cancelAndJoin() }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelledAt) < 2000)
        assertTrue(host.closed && host.sessions.all { it.stopped })
        val paths = Files.list(root.resolve(".building/scenario-evidence")).use { it.toList() }
        val saved = Json.parseToJsonElement(Files.readString(paths.single())).jsonObject
        assertEquals("CANCELLED", saved["status"]?.jsonPrimitive?.content)
        assertEquals("false", saved["comparisonEligible"]?.jsonPrimitive?.content)
    }

    @Test fun `unconfirmed cleanup never makes an otherwise completed run comparable`() = runBlocking {
        val reviewed = review(command)
        val delegate = CommandHost(ScenarioProcessSnapshot(false, 0, "passed\n", ""))
        val host = object : RuntimeScenarioHost by delegate {
            override suspend fun close() { delegate.close(); error("fixture cleanup could not confirm") }
        }
        val result = RegressionScenarioRunner(host).run(reviewed, approve(reviewed))
        assertEquals(ScenarioRunStatus.FAILED, result.status)
        assertTrue(result.testActuallyRan)
        assertFalse(result.executionComplete)
        assertFalse(result.comparisonEligible)
    }

    @Test fun `comparison requires explicit matching hash two executed complete runs`() = runBlocking {
        val reviewed = review(command)
        val before = RegressionScenarioRunner(CommandHost(ScenarioProcessSnapshot(false, 7, "wrong", ""))).run(reviewed, approve(reviewed))
        Files.writeString(root.resolve("main.cjs"), "// deliberate source change")
        val rereviewed = RegressionScenarioParser.load(root, "scenario.json")
        val after = RegressionScenarioRunner(CommandHost(ScenarioProcessSnapshot(false, 0, "passed\n", ""))).run(rereviewed, approve(rereviewed))
        val comparison = RegressionScenarioComparison.compare(before, after, reviewed.sha256)
        assertEquals(2, comparison.changes.size)
        assertTrue(comparison.sourceChanged)
        assertFailsWith<ScenarioValidationException> { RegressionScenarioComparison.compare(before, after, "0".repeat(64)) }
        assertFailsWith<ScenarioValidationException> { RegressionScenarioComparison.compare(after, after, after.scenarioSha256) }
        val skipped = RegressionScenarioRunner(CommandHost(ScenarioProcessSnapshot(false, 0))).run(rereviewed, approve(rereviewed))
        assertFailsWith<ScenarioValidationException> { RegressionScenarioComparison.compare(before, skipped, reviewed.sha256) }
        Unit
    }

    private class CommandHost(private val value: ScenarioProcessSnapshot, private val hang: Boolean = false) : RuntimeScenarioHost {
        var starts = 0
        var closed = false
        var stopped = false
        override suspend fun start(projectRoot: Path, targetId: String): RuntimeScenarioSession {
            starts++
            return object : RuntimeScenarioSession {
                override val sessionId: String = UUID.randomUUID().toString()
                override suspend fun awaitReady(): ScenarioProcessSnapshot = value
                override suspend fun awaitExit(): ScenarioProcessSnapshot { if (hang) awaitCancellation(); return value }
                override fun snapshot(): ScenarioProcessSnapshot = if (stopped) value.copy(running = false) else value
                override suspend fun stop() { stopped = true }
            }
        }
        override suspend fun close() { closed = true; stopped = true }
    }

    private class ServerHost(private val respond: (RecordedRequest) -> MockResponse) : RuntimeScenarioHost {
        var starts = 0
        var closed = false
        val sessions = mutableListOf<ServerSession>()
        override suspend fun start(projectRoot: Path, targetId: String): RuntimeScenarioSession {
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse = respond(request) }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            starts++
            return ServerSession(server).also { sessions += it }
        }
        override suspend fun close() { closed = true; sessions.forEach { it.stop() } }
    }
    private class ServerSession(private val server: MockWebServer) : RuntimeScenarioSession {
        override val sessionId: String = UUID.randomUUID().toString()
        var stopped = false
        override suspend fun awaitReady(): ScenarioProcessSnapshot = snapshot()
        override suspend fun awaitExit(): ScenarioProcessSnapshot = snapshot()
        override fun snapshot() = ScenarioProcessSnapshot(!stopped, stdout = "", stderr = "", previewUrl = server.url("/").toString())
        override suspend fun stop() { if (!stopped) { stopped = true; server.shutdown() } }
    }
    private fun json(status: Int, body: String) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
}
