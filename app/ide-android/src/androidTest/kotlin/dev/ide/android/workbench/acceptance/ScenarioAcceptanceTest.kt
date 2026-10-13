package dev.ide.android.workbench.acceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.workbench.runtime.AndroidRuntimeHost
import dev.ide.workbench.runtime.RuntimeController
import dev.ide.workbench.scenario.RegressionScenarioComparison
import dev.ide.workbench.scenario.RegressionScenarioParser
import dev.ide.workbench.scenario.RegressionScenarioRunner
import dev.ide.workbench.scenario.RegressionScenarioSamples
import dev.ide.workbench.scenario.RuntimeControllerScenarioHost
import dev.ide.workbench.scenario.ScenarioNetworkOutcome
import dev.ide.workbench.scenario.ScenarioRunApproval
import dev.ide.workbench.scenario.ScenarioRunReport
import dev.ide.workbench.scenario.ScenarioRunStatus
import dev.ide.workbench.scenario.ScenarioValidationException
import dev.ide.workbench.scenario.ScenarioVerdict
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the APK's native Node via AndroidRuntimeHost; no simulated execution and no settings changes. */
@RunWith(AndroidJUnit4::class)
class ScenarioAcceptanceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 210_000)
    fun realNodePersistsStateAcrossRestartAndRecoversAfterStructured429() = runBlocking<Unit> {
        withTimeout(190_000) {
            AcceptanceWorkspace("scenario-state").use { workspace ->
                val project = RegressionScenarioSamples.seedOfflineNode(workspace.root)
                assertTrue(project.startsWith(workspace.root) && project != workspace.root)
                val report = runRealScenario(project, RegressionScenarioSamples.STATE_SCENARIO)
                assertPassed(report)
                assertEquals(10, report.steps.size)
                assertEquals(listOf("start", "update", "retained", "stop-before-restart", "restart", "persisted", "arm-provider-error", "structured-provider-error", "provider-recovered", "stop"), report.steps.map { it.id })
                assertTrue(report.steps.all { it.executed && it.verdict == ScenarioVerdict.PASS })
                val start = report.steps.single { it.id == "start" }
                val restart = report.steps.single { it.id == "restart" }
                assertNotEquals("A restart must own a new native process session", start.process!!.sessionId, restart.process!!.sessionId)
                assertEquals(200, report.steps.single { it.id == "persisted" }.http!!.status)
                val error = report.steps.single { it.id == "structured-provider-error" }
                assertEquals(429, error.http!!.status)
                assertEquals(ScenarioNetworkOutcome.RATE_LIMITED, error.http!!.outcome)
                assertTrue(error.http!!.structuredError)
                assertEquals("No blind retry of the reviewed GET", 1, error.http!!.attempts)
                assertTrue("Nested error.code and retryable assertions must really execute", error.assertions.filter { it.key.startsWith("json[") }.let { it.size == 2 && it.all { assertion -> assertion.verdict == ScenarioVerdict.PASS && assertion.testAssertion } })
                val recovery = report.steps.single { it.id == "provider-recovered" }
                assertEquals(200, recovery.http!!.status)
                assertEquals(ScenarioNetworkOutcome.SUCCESS, recovery.http!!.outcome)
                assertEquals(1, recovery.http!!.attempts)
                val state = JSONObject(Files.readAllBytes(project.resolve("scenario-state.json")).toString(Charsets.UTF_8))
                assertEquals("retained-after-restart", state.getString("value"))
                assertEquals(7, state.getInt("revision"))
                val evidence = readEvidence(report, workspace.root)
                assertFalse("Persisted evidence must omit raw response values", evidence.contains("retained-after-restart"))
                assertFalse("Persisted evidence must omit provider response bodies", evidence.contains("Local simulated provider limit"))
            }
        }
    }

    @Test(timeout = 180_000)
    fun realNodeCommandSeparatesStreamsAndRepeatRunsHaveComparableStoredEvidence() = runBlocking<Unit> {
        withTimeout(160_000) {
            AcceptanceWorkspace("scenario-command").use { workspace ->
                val project = RegressionScenarioSamples.seedOfflineNode(workspace.root)
                val first = runRealScenario(project, RegressionScenarioSamples.COMMAND_SCENARIO)
                val second = runRealScenario(project, RegressionScenarioSamples.COMMAND_SCENARIO)
                for (report in listOf(first, second)) {
                    assertPassed(report)
                    val command = report.steps.single()
                    assertEquals("command", command.kind)
                    assertTrue(command.executed)
                    assertEquals(0, command.process!!.exitCode)
                    assertTrue("Separate complete stdout/stderr are required, never inferred", command.process!!.outputComplete)
                    assertTrue(command.process!!.stdoutBytes!! > 0 && command.process!!.stderrBytes!! > 0)
                    assertEquals(setOf("exitCode", "stdout.equals", "stderr.equals"), command.assertions.map { it.key }.toSet())
                    assertTrue(command.assertions.all { it.testAssertion && it.verdict == ScenarioVerdict.PASS })
                    val evidence = readEvidence(report, workspace.root)
                    assertFalse(evidence.contains("offline state serialization passed"))
                    assertFalse(evidence.contains("fixture diagnostic only"))
                }
                assertNotEquals(first.runId, second.runId)
                assertNotEquals(first.evidencePath, second.evidencePath)
                val comparison = RegressionScenarioComparison.compare(first, second, first.scenarioSha256)
                assertFalse(comparison.sourceChanged)
                assertTrue("Identical successful runs should show no changed assertions", comparison.changes.isEmpty())
            }
        }
    }

    @Test(timeout = 30_000)
    fun changedReviewedSourceIsRejectedBeforeAnyRuntimeOrEvidenceStarts() = runBlocking<Unit> {
        withTimeout(20_000) {
            AcceptanceWorkspace("scenario-stale").use { workspace ->
                val project = RegressionScenarioSamples.seedOfflineNode(workspace.root)
                val reviewed = RegressionScenarioParser.load(project, RegressionScenarioSamples.COMMAND_SCENARIO)
                Files.write(project.resolve("smoke.cjs"), "throw new Error('must never execute changed code');\n".toByteArray(Charsets.UTF_8))
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val controller = RuntimeController(AndroidRuntimeHost(context), scope)
                try {
                    var rejected = false
                    try {
                        RegressionScenarioRunner(RuntimeControllerScenarioHost(controller)).run(reviewed,
                            ScenarioRunApproval(reviewed.sha256, reviewed.scenario.targetId, true))
                    } catch (_: ScenarioValidationException) {
                        rejected = true
                    }
                    assertTrue("Changed source requires renewed explicit review", rejected)
                    assertFalse(controller.state.value.isRunning)
                    assertEquals(null, controller.state.value.sessionId)
                    assertFalse("Stale approval must not create a misleading run checkpoint", Files.exists(project.resolve(".building/scenario-evidence")))
                } finally {
                    try {
                        withContext(NonCancellable) { withTimeout(12_000) { controller.stop() } }
                    } finally {
                        controller.close()
                        scope.cancel()
                    }
                }
            }
        }
    }

    private suspend fun runRealScenario(project: Path, relative: String): ScenarioRunReport {
        val reviewed = RegressionScenarioParser.load(project, relative)
        assertTrue(reviewed.sourceArtifacts.isNotEmpty())
        assertTrue(reviewed.sha256.matches(Regex("[0-9a-f]{64}")))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = RuntimeController(AndroidRuntimeHost(context), scope)
        try {
            val approval = ScenarioRunApproval(reviewed.sha256, reviewed.scenario.targetId, true)
            val report = RegressionScenarioRunner(RuntimeControllerScenarioHost(controller)).run(reviewed, approval)
            assertFalse("The one-shot runner must stop its owned native sessions", controller.state.value.isRunning)
            assertEquals(reviewed.sha256, report.scenarioSha256)
            assertEquals(reviewed.sourceArtifactHash, report.sourceArtifactHash)
            assertEquals(reviewed.scenario.targetId, report.targetId)
            return report
        } finally {
            try {
                withContext(NonCancellable) { withTimeout(12_000) { controller.stop() } }
            } finally {
                controller.close()
                scope.cancel()
            }
        }
    }

    private fun assertPassed(report: ScenarioRunReport) {
        // Report contains only sanitized assertion evidence, not raw child output or request bodies.
        assertEquals(report.toString(), ScenarioRunStatus.PASSED, report.status)
        assertTrue("PASSED must be backed by evaluated HTTP/command assertions", report.testActuallyRan)
        assertTrue(report.evaluatedTestAssertions > 0)
        assertTrue("Unstored evidence must never count as an acceptance pass", report.evidenceStored)
        assertTrue(report.comparisonEligible)
    }

    private fun readEvidence(report: ScenarioRunReport, ownedRoot: Path): String {
        assertTrue("Scenario evidence must remain in the disposable workspace", report.evidencePath.toRealPath().startsWith(ownedRoot.toRealPath()))
        assertTrue(Files.size(report.evidencePath) in 1..(512L * 1024))
        val text = Files.readAllBytes(report.evidencePath).toString(Charsets.UTF_8)
        val json = JSONObject(text)
        assertEquals(report.runId, json.getString("runId"))
        assertEquals("PASSED", json.getString("status"))
        assertTrue(json.getBoolean("testActuallyRan"))
        assertTrue(json.getBoolean("evidenceStored"))
        assertEquals(report.evaluatedTestAssertions, json.getInt("evaluatedTestAssertions"))
        assertEquals(report.steps.size, json.getJSONArray("steps").length())
        return text
    }
}
