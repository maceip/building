package dev.ide.workbench.scenario

import dev.ide.workbench.runtime.RuntimeChild
import dev.ide.workbench.runtime.RuntimeController
import dev.ide.workbench.runtime.RuntimeHost
import dev.ide.workbench.runtime.RuntimeInstallation
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Real ProcessBuilder execution in disposable test workspaces. No installs, provider calls or user apps. */
class RuntimeControllerScenarioHostTest {
    @TempDir lateinit var temporary: Path

    @Test fun `real offline Node server persists state over process restart and recovers`() = runBlocking {
        val node = executable("node")
        assumeTrue(node != null, "Host Node is unavailable; executable Node integration is SKIPPED, not passed")
        val workspace = RegressionScenarioSamples.seedOfflineNode(temporary)
        val runtimeHost = DesktopFixtureRuntimeHost(temporary.resolve("cache"), node = node)
        val result = runScenario(workspace, RegressionScenarioSamples.STATE_SCENARIO, runtimeHost)
        assertEquals(ScenarioRunStatus.PASSED, result.status, result.steps.toString())
        assertTrue(result.evidenceStored && result.comparisonEligible)
        assertEquals(2, runtimeHost.children.size)
        assertTrue(runtimeHost.children.none { it.process.isAlive })
        assertTrue(Files.readString(workspace.resolve("scenario-state.json")).contains("retained-after-restart"))
        assertEquals(429, result.steps.first { it.id == "structured-provider-error" }.http?.status)
        assertEquals(ScenarioNetworkOutcome.RATE_LIMITED, result.steps.first { it.id == "structured-provider-error" }.http?.outcome)
        assertEquals(200, result.steps.first { it.id == "provider-recovered" }.http?.status)
        assertNotEquals(result.steps.first().process?.sessionId, result.steps.first { it.id == "restart" }.process?.sessionId)
    }

    @Test fun `real Node command asserts exit stdout and stderr independently`() = runBlocking {
        val node = executable("node")
        assumeTrue(node != null, "Host Node is unavailable; command integration is SKIPPED, not passed")
        val workspace = RegressionScenarioSamples.seedOfflineNode(temporary)
        val runtimeHost = DesktopFixtureRuntimeHost(temporary.resolve("cache"), node = node)
        val result = runScenario(workspace, RegressionScenarioSamples.COMMAND_SCENARIO, runtimeHost)
        assertEquals(ScenarioRunStatus.PASSED, result.status, result.steps.toString())
        assertEquals(3, result.evaluatedTestAssertions)
        assertEquals(0, result.steps.single().process?.exitCode)
        assertTrue(result.steps.single().process?.outputComplete == true)
        assertTrue(runtimeHost.children.none { it.process.isAlive })
    }

    @Test fun `real Python command uses selected local entry with bounded streams`() = runBlocking {
        val python = executable("python3")
        assumeTrue(python != null, "Host Python is unavailable; executable Python integration is SKIPPED, not passed")
        val workspace = Files.createDirectory(temporary.resolve("python-workspace"))
        Files.writeString(workspace.resolve("main.py"), "import json,sys\nassert json.loads('{\"enabled\":true}')[\"enabled\"] is True\nprint('python state passed', flush=True)\nprint('fixture diagnostic', file=sys.stderr, flush=True)\n")
        Files.writeString(workspace.resolve("scenario.json"), """{"version":1,"id":"python-smoke","title":"Python state serialization","targetId":"python:main.py","steps":[{"id":"smoke","type":"command","expect":{"exitCode":0,"stdout":{"equals":"python state passed\n"},"stderr":{"contains":["fixture diagnostic"]}}}]}""")
        val runtimeHost = DesktopFixtureRuntimeHost(temporary.resolve("cache"), python = python)
        val result = runScenario(workspace, "scenario.json", runtimeHost)
        assertEquals(ScenarioRunStatus.PASSED, result.status, result.steps.toString())
        assertEquals(3, result.evaluatedTestAssertions)
        assertTrue(runtimeHost.children.none { it.process.isAlive })
    }

    @Test fun `real process timeout closes owned server and cannot claim a completed command`() = runBlocking {
        val node = executable("node")
        assumeTrue(node != null, "Host Node is unavailable")
        val workspace = Files.createDirectory(temporary.resolve("timeout-workspace"))
        Files.writeString(workspace.resolve("wait.cjs"), "setInterval(() => {}, 1000);\n")
        Files.writeString(workspace.resolve("scenario.json"), """{"version":1,"id":"timeout","title":"Bounded process timeout","targetId":"node:wait.cjs","steps":[{"id":"wait","type":"command","timeoutMs":300,"expect":{"exitCode":0}}]}""")
        val runtimeHost = DesktopFixtureRuntimeHost(temporary.resolve("cache"), node = node)
        val result = runScenario(workspace, "scenario.json", runtimeHost)
        assertEquals(ScenarioRunStatus.FAILED, result.status)
        assertFalse(result.testActuallyRan)
        assertTrue(runtimeHost.children.none { it.process.isAlive })
        assertTrue(result.steps.single().assertions.any { it.observed == "STEP_TIMEOUT" })
    }

    @Test fun `live output flood is interrupted rather than saved or treated as a pass`() = runBlocking {
        val node = executable("node")
        assumeTrue(node != null, "Host Node is unavailable")
        val workspace = Files.createDirectory(temporary.resolve("output-workspace"))
        Files.writeString(workspace.resolve("noisy.cjs"), "process.stdout.write('x'.repeat(32768)); setInterval(() => {}, 1000);\n")
        Files.writeString(workspace.resolve("scenario.json"), """{"version":1,"id":"output-limit","title":"Bounded output","targetId":"node:noisy.cjs","limits":{"maxOutputBytes":256},"steps":[{"id":"noisy","type":"command","expect":{"exitCode":0}}]}""")
        val runtimeHost = DesktopFixtureRuntimeHost(temporary.resolve("cache"), node = node)
        val result = runScenario(workspace, "scenario.json", runtimeHost)
        assertEquals(ScenarioRunStatus.FAILED, result.status)
        assertTrue(result.steps.single().assertions.any { it.observed == "PROCESS_OUTPUT_LIMIT" })
        assertTrue(runtimeHost.children.none { it.process.isAlive })
        assertFalse(Files.readString(result.evidencePath).contains("x".repeat(100)))
    }

    private suspend fun runScenario(workspace: Path, relativePath: String, host: DesktopFixtureRuntimeHost): ScenarioRunReport {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = RuntimeController(host, scope)
        return try {
            val reviewed = RegressionScenarioParser.load(workspace, relativePath)
            RegressionScenarioRunner(RuntimeControllerScenarioHost(controller)).run(reviewed,
                ScenarioRunApproval(reviewed.sha256, reviewed.scenario.targetId, true))
        } finally { controller.close(); scope.cancel() }
    }

    private fun executable(name: String): Path? = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator)
        .asSequence().filter { it.isNotBlank() }.map { Path.of(it).resolve(name) }.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
}

internal class DesktopFixtureRuntimeHost(
    override val workspaceCache: Path,
    private val node: Path? = null,
    private val python: Path? = null,
) : RuntimeHost {
    val children = mutableListOf<DesktopFixtureChild>()
    override suspend fun prepare(): RuntimeInstallation {
        Files.createDirectories(workspaceCache)
        val prefix = workspaceCache.resolve("runtime")
        Files.createDirectories(prefix)
        return RuntimeInstallation("host-fixture-only", System.getProperty("os.arch"), prefix, node, null, python, null,
            mapOf("PATH" to listOfNotNull(node?.parent, python?.parent, Path.of("/usr/bin"), Path.of("/bin")).joinToString(":"), "HOME" to workspaceCache.toString(), "NO_COLOR" to "1"))
    }
    override fun start(command: List<String>, workingDirectory: Path, environment: Map<String, String>): RuntimeChild {
        val process = ProcessBuilder(command).directory(workingDirectory.toFile()).apply {
            environment().clear(); environment().putAll(environment)
        }.start()
        return DesktopFixtureChild(process).also { children += it }
    }
}
internal class DesktopFixtureChild(val process: Process) : RuntimeChild {
    private val terminated = AtomicBoolean(false)
    override val stdin: OutputStream get() = process.outputStream
    override val stdout: InputStream get() = process.inputStream
    override val stderr: InputStream get() = process.errorStream
    override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) {
        while (!process.waitFor(20, TimeUnit.MILLISECONDS)) currentCoroutineContext().ensureActive()
        process.exitValue()
    }
    override suspend fun terminate(): Unit = withContext(Dispatchers.IO) {
        if (!terminated.compareAndSet(false, true)) return@withContext
        process.descendants().use { children -> children.forEach { it.destroy() } }
        process.destroy()
        if (!process.waitFor(300, TimeUnit.MILLISECONDS)) {
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
            process.waitFor(300, TimeUnit.MILLISECONDS)
        }
        Unit
    }
}
