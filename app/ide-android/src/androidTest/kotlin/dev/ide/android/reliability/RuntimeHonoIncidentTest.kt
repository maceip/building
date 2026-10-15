package dev.ide.android.reliability

import android.os.Process
import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.BuildConfig
import dev.ide.android.workbench.runtime.AndroidRuntimeHost
import dev.ide.android.workbench.runtime.HonoProjectInstaller
import dev.ide.workbench.reliability.DurableIncidentRepository
import dev.ide.workbench.reliability.HostRuntimeChannel
import dev.ide.workbench.reliability.HostRuntimeKind
import dev.ide.workbench.reliability.HostRuntimeOutcome
import dev.ide.workbench.reliability.HostRuntimeSourceStatus
import dev.ide.workbench.reliability.HostRuntimeStage
import dev.ide.workbench.reliability.IncidentFilter
import dev.ide.workbench.reliability.IncidentOrigin
import dev.ide.workbench.reliability.IncidentRepository
import dev.ide.workbench.reliability.IncidentState
import dev.ide.workbench.runtime.RuntimeController
import dev.ide.workbench.runtime.RuntimeFiles
import dev.ide.workbench.runtime.RuntimePhase
import dev.ide.workbench.runtime.RuntimeProjectConfig
import dev.ide.workbench.runtime.RuntimeRunRequest
import dev.ide.workbench.runtime.RuntimeSessionState
import dev.ide.workbench.runtime.RuntimeSourceSnapshot
import dev.ide.workbench.runtime.RuntimeWorkspaceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.UUID

/** Real app-UID/public-registry acceptance. The correction below is manual fixture editing, not AI repair. */
@RunWith(AndroidJUnit4::class)
class RuntimeHonoIncidentTest {
    @Test(timeout = 480_000)
    fun honoFailureIsPrivateDurableAndRetainedAfterManualSourceCorrection() = runBlocking<Unit>(Dispatchers.IO) {
        assertEquals(
            "Explicit execution requires allowHonoRegistryInstall=true; this test must not silently skip",
            "true", InstrumentationRegistry.getArguments().getString("allowHonoRegistryInstall"),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = HonoProjectInstaller.create(context, HonoProjectInstaller.Project.WORKBENCH)
        val otherRoot = Files.createDirectory(root.parent.resolve("unselected-workspace"))
        val evidence = Files.createDirectories(root.resolve(".building/capture-evidence"))
        val bridge = AndroidRuntimeReliability(context)
        val capture = bridge.capture
        val repository = ReliabilityHostProvider.get(context).repository
        val host = AndroidRuntimeHost(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = RuntimeController(host, scope, observer = bridge.runtimeObserver)
        try {
            withTimeout(480_000) {
                assertFalse("A fresh workspace must default to capture off", capture.status(root).enabled)
                assertFalse("Unselected workspace must remain disabled", capture.status(otherRoot).enabled)
                assertEquals("Instrumentation must run as the target app", context.applicationInfo.uid, Process.myUid())
                assertFalse("Fixture must not contain installed dependencies", Files.exists(root.resolve("node_modules")))
                assertFalse("Fixture must not contain build outputs", Files.exists(root.resolve("dist")))
                val testsBefore = treeHashes(root.resolve("test"))
                val testSha = sha256(root.resolve(HonoProjectInstaller.Project.WORKBENCH.testFile))
                val lockSha = sha256(root.resolve("package-lock.json"))
                assertPublicRegistryLock(root)

                // npm_config_cache in AndroidRuntimeHost overrides .npmrc. The explicit ci below
                // therefore pins a genuinely empty cache with command-line precedence as well.
                Files.write(root.resolve(".npmrc"), (
                    "registry=https://registry.npmjs.org/\ncache=.building/npm-cache\n" +
                        "offline=false\nprefer-offline=false\nfetch-retries=0\nfetch-timeout=45000\n"
                    ).toByteArray(Charsets.UTF_8), CREATE_NEW)
                val secret = "hono-synthetic-secret-${UUID.randomUUID()}"
                controller.saveConfig(root, RuntimeProjectConfig(
                    environment = mapOf(SECRET_ENV to secret), workspaceMode = RuntimeWorkspaceMode.DIRECT,
                ))
                Files.write(root.resolve(CI_HARNESS), ciHarness.toByteArray(Charsets.UTF_8), CREATE_NEW)
                addScript(root, "capture:ci", "node $CI_HARNESS")
                val installation = withTimeout(60_000) { host.prepare() }
                val npmSha = sha256(requireNotNull(installation.npmCli))

                // One real lockfile-only npm ci, through the production controller, with capture off.
                // installDependencies currently runs npm install; that action has separate coverage.
                val ciReceipt = withTimeout(120_000) {
                    assertFalse("npm ci cache must start absent", Files.exists(root.resolve(".building/npm-cache")))
                    controller.run(RuntimeRunRequest(root, "npm:capture:ci"))
                    val ci = awaitStopped(controller, evidence, "ci", 120_000)
                    assertEquals("Real public-registry npm ci must succeed", 0, ci.exitCode)
                    assertNoSessionIncident(repository, ci)
                    assertFalse("npm ci must not enable capture", capture.status(root).enabled)
                    readReceipt(evidence.resolve("ci-receipt.json")).also {
                        assertProcessReceipt(it, context.applicationInfo.uid, npmSha)
                        assertEquals("Receipt must record the actual npm ci exit", 0, it.getInt("exitCode"))
                    }
                }
                assertTrue("Fresh npm cache must contain downloaded packages", Files.isDirectory(root.resolve(".building/npm-cache/_cacache")))
                assertTrue("Real Hono must be installed", Files.isRegularFile(root.resolve("node_modules/hono/package.json")))
                assertTrue("Actual TypeScript compiler must be installed", Files.isRegularFile(root.resolve("node_modules/typescript/bin/tsc")))
                assertEquals("Dependency lockfile must remain unchanged", lockSha, sha256(root.resolve("package-lock.json")))
                assertTrue("Installation must not modify the original tests", testsBefore == treeHashes(root.resolve("test")))

                // This one immutable command builds via the installed npm and runs every unchanged
                // HTTP test. No success/failure mode, name filter, retries or alternate test bodies.
                val harness = root.resolve(VERIFY_HARNESS)
                Files.write(harness, verifyHarness.toByteArray(Charsets.UTF_8), CREATE_NEW)
                addScript(root, "capture:verify", "node $VERIFY_HARNESS")
                assertTrue("Freeze the fixture-only verification harness", harness.toFile().setReadOnly())
                val harnessSha = sha256(harness)
                val frozenInputs = inputHashes(root)
                val cleanRevision = RuntimeSourceSnapshot.capture(root).revision
                val app = root.resolve("src/app.ts")
                val originalApp = Files.readAllBytes(app)
                val originalText = originalApp.toString(Charsets.UTF_8)
                assertEquals("The known task-creation response must occur exactly once", 1, Regex(Regex.escape(GOOD_RESPONSE)).findAll(originalText).count())
                assertFalse("Fixture must not already contain the planted bug", originalText.contains(BAD_RESPONSE))
                val buggedApp = originalText.replace(GOOD_RESPONSE, BAD_RESPONSE).toByteArray(Charsets.UTF_8)
                Files.write(app, buggedApp)
                val buggedInputs = inputHashes(root)
                assertTrue("Only the disposable app source may be changed", buggedInputs == frozenInputs + ("src/app.ts" to sha256(buggedApp)))

                val enabled = capture.setEnabled(root, true)
                assertTrue("Explicit workspace consent must succeed", enabled.enabled)
                val workspaceId = requireNotNull(enabled.workspaceId) { "Enabled capture must expose a workspace ID" }
                assertEquals(workspaceId, capture.status(root).workspaceId)
                assertFalse("Enabling one workspace must not enable another", capture.status(otherRoot).enabled)
                assertTrue("Fresh workspace must not have prior incidents", workspaceIncidents(repository, workspaceId).isEmpty())
                val forbidden = listOf(
                    secret, SECRET_ENV, "capture-stdout-marker", "capture-stderr-marker",
                    root.toString(), root.parent.toString(), context.filesDir.absolutePath,
                    context.noBackupFilesDir.absolutePath, installation.prefix.toString(),
                    requireNotNull(installation.npmCli).toString(), requireNotNull(installation.node).toString(),
                    VERIFY_HARNESS, "capture:verify", "npm run build", "test/http.test.mjs", "src/app.ts",
                    CRUD_TEST, HEALTH_TEST, "200 !== 201",
                )

                // The five-minute budget covers both suites, not a series of unbounded retries.
                withTimeout(300_000) {
                    val beforeRevision = RuntimeSourceSnapshot.capture(root).revision
                    assertFalse("The planted source change must affect the pre-run hash", cleanRevision == beforeRevision)
                    controller.run(RuntimeRunRequest(root, "npm:capture:verify"))
                    val failed = awaitStopped(controller, evidence, "before", 300_000)
                    assertEquals("A failing suite must be a completed nonzero process", RuntimePhase.FAILED, failed.phase)
                    assertTrue("Failure must have an actual nonzero exit", failed.exitCode != null && failed.exitCode != 0)
                    assertMarkers(failed, secret)
                    val beforeReceipt = readReceipt(evidence.resolve("verify-receipt.json"))
                    assertVerifyReceipt(beforeReceipt, context.applicationInfo.uid, npmSha, testSha, harnessSha, sha256(buggedApp), failed)
                    val beforeReport = readJunit(evidence.resolve("junit.xml"))
                    assertTrue("Unchanged CRUD case must fail on the planted 200 instead of 201", beforeReport.cases.single { it.name == CRUD_TEST }.statusMismatch)
                    assertTrue("Separate health case must still pass", beforeReport.cases.single { it.name == HEALTH_TEST }.passed)
                    assertTrue("The real suite must contain failed cases", beforeReport.cases.any { it.failed })
                    Files.copy(evidence.resolve("junit.xml"), evidence.resolve("before-junit.xml"))
                    Files.copy(evidence.resolve("verify-receipt.json"), evidence.resolve("before-receipt.json"))
                    assertTrue("Failing run must not alter source, tests or harness", buggedInputs == inputHashes(root))
                    assertEquals("Snapshot excludes generated evidence and build outputs", beforeRevision, RuntimeSourceSnapshot.capture(root).revision)

                    withTimeout(10_000) {
                        repository.observe().first { events -> events.any { it.key.workspaceId == workspaceId && it.key.sessionId == failed.sessionId } }
                    }
                    val incidents = workspaceIncidents(repository, workspaceId)
                    assertEquals("Exactly one incident for the failed workspace session", 1, incidents.size)
                    val summary = incidents.single()
                    assertEquals(IncidentOrigin.HOST_RUNTIME, summary.key.origin)
                    assertEquals(failed.sessionId, summary.key.sessionId)
                    assertEquals("host_runtime", summary.type) // Not fatal_java, previous_exit or an app crash.
                    assertEquals("", summary.key.packageName)
                    assertEquals("", summary.key.buildId)
                    assertEquals(IncidentState.OPEN, summary.state)
                    val runtime = requireNotNull(summary.runtimeContext) { "Typed host-runtime context is required" }
                    assertEquals(HostRuntimeKind.NPM, runtime.runtimeKind)
                    assertEquals(HostRuntimeChannel.RUN, runtime.channel)
                    assertEquals(HostRuntimeStage.PROCESS, runtime.stage)
                    assertEquals(HostRuntimeOutcome.NONZERO_EXIT, runtime.outcome)
                    assertNull("Test assertion failure is not a host exception", runtime.exception)
                    assertEquals(failed.exitCode, runtime.exitCode)
                    assertEquals(HostRuntimeSourceStatus.PRE_RUN_SNAPSHOT, runtime.sourceStatus)
                    assertEquals(RuntimeSourceSnapshot.POLICY, runtime.sourcePolicy)
                    assertEquals(beforeRevision, runtime.sourceRevision)
                    assertEquals(beforeRevision, summary.sourceRevision)
                    assertEquals(installation.packId, runtime.packId)
                    assertEquals(installation.abi, runtime.abi)
                    assertEquals(context.packageName, runtime.hostPackage)
                    assertEquals(BuildConfig.VERSION_CODE.toLong(), runtime.versionCode)
                    assertEquals(BuildConfig.VERSION_NAME, runtime.versionName)
                    assertTrue("Duration must be a bounded actual measurement", runtime.durationMs in 1..480_000L)
                    val incident = requireNotNull(repository.read(summary.key)) { "Committed incident must be readable" }
                    assertTrue("Incident must have readable canonical evidence", incident.redactedText.isNotBlank())
                    assertPrivateText(incident.redactedText, forbidden)
                    assertPrivateText(summary.preview, forbidden)
                    val reliabilityDirectory = context.noBackupFilesDir.toPath().resolve("reliability")
                    assertPrivatePersistence(reliabilityDirectory, workspaceId, forbidden)

                    // Manual source correction in the disposable fixture only. Do not modify tests,
                    // package scripts, configuration, harness or dependencies to make the run pass.
                    Files.write(app, originalApp)
                    assertTrue("Restoration must reproduce the original bytes", originalApp.contentEquals(Files.readAllBytes(app)))
                    assertTrue("Only the source correction is allowed", frozenInputs == inputHashes(root))
                    val afterRevision = RuntimeSourceSnapshot.capture(root).revision
                    assertEquals(cleanRevision, afterRevision)
                    controller.run(RuntimeRunRequest(root, "npm:capture:verify"))
                    val passed = awaitStopped(controller, evidence, "after", 300_000)
                    assertEquals("Corrected suite must really exit zero", 0, passed.exitCode)
                    assertEquals(RuntimePhase.FINISHED, passed.phase)
                    assertMarkers(passed, secret)
                    val afterReceipt = readReceipt(evidence.resolve("verify-receipt.json"))
                    assertVerifyReceipt(afterReceipt, context.applicationInfo.uid, npmSha, testSha, harnessSha, sha256(originalApp), passed)
                    assertFalse("Two actual harness executions are required", beforeReceipt.getString("runId") == afterReceipt.getString("runId"))
                    val afterReport = readJunit(evidence.resolve("junit.xml"))
                    assertTrue("Every unchanged case must pass after correction", afterReport.cases.all { it.passed })
                    assertTrue("Both runs must execute the same identities", beforeReport.cases.map { it.name }.toSet() == afterReport.cases.map { it.name }.toSet())
                    assertTrue("Original tests remain byte-identical", testsBefore == treeHashes(root.resolve("test")))
                    assertEquals("Verification harness remains byte-identical", harnessSha, sha256(harness))
                    assertTrue("All frozen source/test/configuration inputs remain unchanged", frozenInputs == inputHashes(root))
                    assertEquals(afterRevision, RuntimeSourceSnapshot.capture(root).revision)
                    assertNoSessionIncident(repository, passed)
                    assertEquals("Success must not add or replace the original incident", listOf(summary.key), workspaceIncidents(repository, workspaceId).map { it.key })
                    assertFalse("Other workspaces must still be disabled", capture.status(otherRoot).enabled)
                    assertPrivatePersistence(reliabilityDirectory, workspaceId, forbidden)

                    // Both jobs and observer callbacks have joined. Open production storage only
                    // for read/list: never construct a second accepting writer over the live inbox.
                    val reopened = DurableIncidentRepository(androidReliabilityFile(
                        reliabilityDirectory.resolve("incidents.json"), DurableIncidentRepository.MAX_SNAPSHOT_BYTES,
                    ))
                    assertEquals(1, workspaceIncidents(reopened, workspaceId).size)
                    assertTrue("A fresh durable reader must retain the original readable incident", incident == reopened.read(summary.key))
                    assertPrivateText(requireNotNull(reopened.read(summary.key)).redactedText, forbidden)
                    println(
                        "HonoBeforeFailAfterPass workspaceId=$workspaceId eventId=${summary.key.eventId}" +
                            " beforeSessionId=${failed.sessionId} afterSessionId=${passed.sessionId}" +
                            " ciReceiptId=${ciReceipt.getString("runId")}" +
                            " beforeReceiptId=${beforeReceipt.getString("runId")} afterReceiptId=${afterReceipt.getString("runId")}" +
                            " beforeSourceSha256=$beforeRevision afterSourceSha256=$afterRevision" +
                            " testSha256=$testSha harnessSha256=$harnessSha" +
                            " beforeJunitSha256=${beforeReport.sha256} afterJunitSha256=${afterReport.sha256}",
                    )
                }
            }
        } finally {
            withContext(NonCancellable) {
                try { withTimeout(10_000) { controller.stop() } }
                finally {
                    try { capture.setEnabled(root, false) }
                    finally { controller.close(); scope.cancel() }
                }
            }
            // Retain only this test's disposable fixture/evidence; never delete user workspaces or inbox entries.
        }
    }

    private suspend fun awaitStopped(controller: RuntimeController, evidence: Path, label: String, timeout: Long): RuntimeSessionState {
        val sessionId = requireNotNull(controller.state.value.sessionId) { "A real controller session is required" }
        val finished = withTimeout(timeout) { controller.state.first { it.sessionId == sessionId && !it.isRunning } }
        // State publication precedes Job completion/onSessionFinished. Join it before any receipt assertions.
        withTimeout(10_000) { controller.stop() }
        assertFalse("Acceptance must execute the disposable workspace directly", finished.workspaceCopy)
        RuntimeFiles.writeUtf8(evidence.resolve("$label.stdout.log"), finished.stdout)
        RuntimeFiles.writeUtf8(evidence.resolve("$label.stderr.log"), finished.stderr)
        return finished
    }

    private fun workspaceIncidents(repository: IncidentRepository, workspaceId: String) =
        repository.list(IncidentFilter(limit = 2_048)).filter { it.key.workspaceId == workspaceId }

    private fun assertNoSessionIncident(repository: IncidentRepository, state: RuntimeSessionState) {
        assertTrue("This completed session must not create an incident", repository.list(IncidentFilter(limit = 2_048)).none { it.key.sessionId == state.sessionId })
    }

    private fun assertMarkers(state: RuntimeSessionState, secret: String) {
        assertTrue("The actual stdout stream must contain the synthetic marker", state.stdout.contains("capture-stdout-marker=$secret"))
        assertTrue("The actual stderr stream must contain the synthetic marker", state.stderr.contains("capture-stderr-marker=$secret"))
    }

    private fun addScript(root: Path, name: String, command: String) {
        val file = root.resolve("package.json")
        val manifest = JSONObject(RuntimeFiles.readUtf8(file))
        val scripts = manifest.getJSONObject("scripts")
        assertFalse("Fixture-only script must not replace an existing script", scripts.has(name))
        scripts.put(name, command)
        RuntimeFiles.writeUtf8(file, manifest.toString(2) + "\n")
    }

    private fun assertPublicRegistryLock(root: Path) {
        val packages = JSONObject(RuntimeFiles.readUtf8(root.resolve("package-lock.json"))).getJSONObject("packages")
        var resolved = 0
        packages.keys().forEach { name ->
            if (name.isNotEmpty()) {
                assertTrue("All locked dependencies must use the public registry", packages.getJSONObject(name).getString("resolved").startsWith("https://registry.npmjs.org/"))
                resolved++
            }
        }
        assertTrue("The real application must have locked dependencies", resolved > 0)
    }

    private fun readReceipt(path: Path) = JSONObject(boundedBytes(path, 64 * 1024).toString(Charsets.UTF_8))

    private fun assertProcessReceipt(receipt: JSONObject, uid: Int, npmSha: String) {
        assertTrue("Receipt must identify an actual harness execution", Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}").matches(receipt.getString("runId")))
        assertEquals("Child must execute under the Android app UID", uid, receipt.getInt("uid"))
        assertEquals("Child must be the Android Node runtime", "android", receipt.getString("platform"))
        assertEquals("Child must use the prepared npm CLI", npmSha, receipt.getString("npmSha256"))
    }

    private fun assertVerifyReceipt(receipt: JSONObject, uid: Int, npmSha: String, testSha: String, harnessSha: String, appSha: String, state: RuntimeSessionState) {
        assertProcessReceipt(receipt, uid, npmSha)
        assertEquals("Actual npm build must succeed even with the HTTP bug", 0, receipt.getInt("buildExitCode"))
        assertEquals("Controller must propagate the actual suite exit", state.exitCode, receipt.getInt("suiteExitCode"))
        assertEquals(testSha, receipt.getString("testSha256"))
        assertEquals(harnessSha, receipt.getString("harnessSha256"))
        assertEquals(appSha, receipt.getString("appSha256"))
    }

    private fun assertPrivateText(text: String, forbidden: List<String>) {
        // Do not interpolate text, environment values, markers or paths into assertion messages.
        assertFalse("Reliability persistence must exclude output, paths, secrets and command text", forbidden.any { text.contains(it) })
    }

    private fun assertPrivatePersistence(directory: Path, workspaceId: String, forbidden: List<String>) {
        fun read(name: String, limit: Int): String {
            val storage = androidReliabilityFile(directory.resolve(name), limit)
            val bytes = storage.transaction { storage.read() }
            assertTrue("Production reliability snapshot must exist", bytes != null)
            return requireNotNull(bytes).toString(Charsets.UTF_8).also { assertPrivateText(it, forbidden) }
        }
        val inbox = JSONObject(read("incidents.json", DurableIncidentRepository.MAX_SNAPSHOT_BYTES)).getJSONArray("records")
        val own = (0 until inbox.length()).map { inbox.getJSONObject(it) }
            .filter { it.getJSONObject("event").optString("workspaceId") == workspaceId }
        assertEquals("Persist exactly one structured workspace record", 1, own.size)
        assertTrue("Runtime record must contain metadata only", own.single().keys().asSequence().toSet() == setOf("origin", "event", "receivedAt", "state", "hostRedacted", "payloadHash"))
        assertTrue("Runtime event must have no arbitrary text fields", own.single().getJSONObject("event").keys().asSequence().toSet() == setOf(
            "workspaceId", "sessionId", "eventId", "timestamp", "sourceRevision", "sourceStatus", "sourcePolicy", "hostPackage",
            "versionCode", "versionName", "runtimeKind", "channel", "stage", "outcome", "exception", "exitCode", "durationMs", "packId", "abi",
        ))
        assertEquals("runtime-source-v1", own.single().getJSONObject("event").getString("sourcePolicy"))
        read("runtime-outbox.json", 512 * 1024)
    }

    private data class JunitCase(val name: String, var failed: Boolean = false, var statusMismatch: Boolean = false) {
        val passed: Boolean get() = !failed
    }
    private data class JunitReport(val cases: List<JunitCase>, val sha256: String)

    private fun readJunit(path: Path): JunitReport {
        val bytes = boundedBytes(path, 4 * 1024 * 1024)
        val text = bytes.toString(Charsets.UTF_8)
        assertFalse("JUnit must not contain entity declarations", Regex("(?i)<!\\s*(?:DOCTYPE|ENTITY)").containsMatchIn(text))
        val parser = Xml.newPullParser()
        val cases = mutableListOf<JunitCase>()
        var current: JunitCase? = null
        var failureDepth = -1
        val failureText = StringBuilder()
        try {
            parser.setInput(text.reader())
            var events = 0
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                assertTrue("JUnit parser event/depth bounds", ++events <= 100_000 && parser.depth <= 24)
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "testcase" -> {
                            assertNull("JUnit cases must not nest", current)
                            val name = parser.getAttributeValue(null, "name")
                            assertTrue("Bounded JUnit identity is required", name != null && name.length in 1..512)
                            current = JunitCase(requireNotNull(name))
                            failureText.setLength(0)
                        }
                        "failure" -> {
                            requireNotNull(current) { "JUnit failure must belong to a case" }.failed = true
                            failureDepth = parser.depth
                            failureText.append(parser.getAttributeValue(null, "message").orEmpty().take(32_768))
                        }
                        "error", "skipped" -> throw AssertionError("All 34 cases must execute without infrastructure errors or skips")
                    }
                    XmlPullParser.TEXT -> if (failureDepth >= 0 && failureText.length < 65_536) {
                        failureText.append(parser.text.take(65_536 - failureText.length))
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "failure") failureDepth = -1
                        if (parser.name == "testcase") {
                            val item = requireNotNull(current) { "JUnit case start is required" }
                            item.statusMismatch = item.failed && Regex("\\b200\\s*!==\\s*201\\b").containsMatchIn(failureText)
                            cases += item
                            assertTrue("Bounded JUnit case count", cases.size <= 128)
                            current = null
                        }
                    }
                }
            }
        } catch (_: XmlPullParserException) {
            throw AssertionError("Invalid bounded Hono JUnit report")
        } catch (_: IOException) {
            throw AssertionError("Unreadable bounded Hono JUnit report")
        }
        assertNull("JUnit case must be complete", current)
        assertEquals("Execute all unchanged Hono HTTP cases", 34, cases.size)
        assertEquals("JUnit identities must be unique", 34, cases.map { it.name }.toSet().size)
        assertEquals("Original health case must be present", 1, cases.count { it.name == HEALTH_TEST })
        assertEquals("Original CRUD case must be present", 1, cases.count { it.name == CRUD_TEST })
        return JunitReport(cases, sha256(bytes))
    }

    private fun boundedBytes(path: Path, maxBytes: Int): ByteArray {
        assertTrue("Expected bounded fixture evidence file", Files.isRegularFile(path) && Files.size(path) in 1..maxBytes.toLong())
        return Files.newInputStream(path).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                assertTrue("Fixture evidence must remain bounded while reading", output.size() + count <= maxBytes)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun treeHashes(directory: Path): Map<String, String> = Files.walk(directory).use { paths ->
        val result = sortedMapOf<String, String>()
        paths.filter { Files.isRegularFile(it) }.forEach { result[directory.relativize(it).toString()] = sha256(it) }
        result
    }

    private fun inputHashes(root: Path): Map<String, String> = buildMap {
        for (directory in listOf("src", "test", "public")) {
            treeHashes(root.resolve(directory)).forEach { (name, hash) -> put("$directory/$name", hash) }
        }
        for (file in listOf("package.json", "package-lock.json", "tsconfig.json", ".npmrc", ".building/runtime.json", CI_HARNESS, VERIFY_HARNESS)) {
            put(file, sha256(root.resolve(file)))
        }
    }

    private fun sha256(path: Path): String = sha256(Files.readAllBytes(path))
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private companion object {
        const val SECRET_ENV = "HONO_CAPTURE_SYNTHETIC_SECRET"
        const val CI_HARNESS = ".building/capture-ci.mjs"
        const val VERIFY_HARNESS = ".building/capture-verify.mjs"
        const val GOOD_RESPONSE = "return c.json({ task }, 201)"
        const val BAD_RESPONSE = "return c.json({ task }, 200)"
        const val HEALTH_TEST = "actual loopback server serves health, dashboard assets and security headers"
        const val CRUD_TEST = "persists full authenticated create/read/update/delete with optimistic ETags"

        val ciHarness = """
            import { spawnSync } from 'node:child_process';
            import { readFileSync, writeFileSync } from 'node:fs';
            import { createHash, randomUUID } from 'node:crypto';
            if (process.env.npm_lifecycle_event !== 'capture:ci' || !process.env.npm_execpath) throw new Error('npm lifecycle required');
            const npm = process.env.npm_execpath;
            const receipt = { runId: randomUUID(), uid: process.getuid(), platform: process.platform,
              npmSha256: createHash('sha256').update(readFileSync(npm)).digest('hex') };
            const result = spawnSync(process.execPath, [npm, 'ci', '--cache=.building/npm-cache',
              '--registry=https://registry.npmjs.org/', '--offline=false', '--prefer-offline=false',
              '--fetch-retries=0', '--fetch-timeout=45000', '--include=dev', '--ignore-scripts=false',
              '--foreground-scripts', '--no-audit', '--no-fund'], { stdio: 'inherit', timeout: 110000 });
            if (result.error || result.signal || !Number.isInteger(result.status)) throw new Error('npm ci did not exit normally');
            writeFileSync('.building/capture-evidence/ci-receipt.json', JSON.stringify({ ...receipt, exitCode: result.status }));
            process.exitCode = result.status;
        """.trimIndent() + "\n"

        val verifyHarness = """
            import { spawnSync } from 'node:child_process';
            import { closeSync, openSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
            import { createHash, randomUUID } from 'node:crypto';
            if (process.env.npm_lifecycle_event !== 'capture:verify' || !process.env.npm_execpath) throw new Error('npm lifecycle required');
            const npm = process.env.npm_execpath;
            const hash = path => createHash('sha256').update(readFileSync(path)).digest('hex');
            const report = '.building/capture-evidence/junit.xml';
            const receiptPath = '.building/capture-evidence/verify-receipt.json';
            rmSync(report, { force: true });
            rmSync(receiptPath, { force: true });
            const receipt = { runId: randomUUID(), uid: process.getuid(), platform: process.platform,
              npmSha256: hash(npm), testSha256: hash('test/http.test.mjs'),
              harnessSha256: hash('.building/capture-verify.mjs'), appSha256: hash('src/app.ts') };
            const secret = process.env.HONO_CAPTURE_SYNTHETIC_SECRET;
            if (!secret) throw new Error('Synthetic environment marker required');
            process.stdout.write('capture-stdout-marker=' + secret + '\n');
            process.stderr.write('capture-stderr-marker=' + secret + '\n');
            const run = (args, stdio) => {
              const child = spawnSync(process.execPath, args, { stdio, timeout: 280000 });
              if (child.error || child.signal || !Number.isInteger(child.status)) throw new Error('Verification child did not exit normally');
              return child.status;
            };
            const buildExitCode = run([npm, '--no-audit', '--no-fund', 'run', 'build'], ['ignore', 'inherit', 'inherit']);
            let suiteExitCode = null;
            if (buildExitCode === 0) {
              const fd = openSync(report, 'wx', 0o600);
              try {
                suiteExitCode = run(['--test', '--test-concurrency=1', '--test-reporter=junit', 'test/http.test.mjs'], ['ignore', fd, 'inherit']);
              } finally { closeSync(fd); }
            }
            writeFileSync(receiptPath, JSON.stringify({ ...receipt, buildExitCode, suiteExitCode }));
            process.exitCode = suiteExitCode ?? buildExitCode;
        """.trimIndent() + "\n"
    }
}
