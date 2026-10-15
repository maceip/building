package dev.ide.android.reliability

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.BuildConfig
import dev.ide.android.workbench.runtime.AndroidRuntimeHost
import dev.ide.android.workbench.terminal.RuntimeTerminalSession
import dev.ide.workbench.reliability.*
import dev.ide.workbench.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real APK-owned runtimes and the same consent/outbox/inbox binding used by the UI. */
@RunWith(AndroidJUnit4::class)
class RuntimeIncidentDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val identity get() = HostRuntimeIdentity(context.packageName, BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME)
    private val secret = "capture-fixture-private-" + "not-a-credential"

    @Test fun disabledNonzeroCapturedNonzeroSuccessAndStopHaveDistinctOutcomes() = runBlocking {
        val root = sourceRoot()
        val bridge = AndroidRuntimeReliability(context)
        val repository = ReliabilityHostProvider.get(context).repository
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = RuntimeController(AndroidRuntimeHost(context), scope, observer = bridge.runtimeObserver)
        try {
            assertFalse(bridge.capture.status(root).enabled)
            val disabled = run(controller, root, "node:failure.cjs")
            assertEquals(17, disabled.exitCode)
            assertTrue(repository.list(IncidentFilter(limit = 2048)).none { it.key.sessionId == disabled.sessionId })
            val enabled = bridge.capture.setEnabled(root, true)
            assertTrue(enabled.toString(), enabled.enabled)
            val revision = RuntimeSourceSnapshot.capture(root).revision
            val failed = run(controller, root, "node:failure.cjs")
            assertEquals(17, failed.exitCode)
            val incident = awaitIncident(repository, requireNotNull(failed.sessionId))
            assertEquals(IncidentOrigin.HOST_RUNTIME, incident.key.origin)
            assertEquals("", incident.key.packageName)
            assertEquals("", incident.key.buildId)
            assertEquals(enabled.workspaceId, incident.key.workspaceId)
            assertEquals(revision, incident.sourceRevision)
            val metadata = requireNotNull(incident.runtimeContext)
            assertEquals(HostRuntimeKind.NODE, metadata.runtimeKind)
            assertEquals(HostRuntimeOutcome.NONZERO_EXIT, metadata.outcome)
            assertEquals(HostRuntimeStage.PROCESS, metadata.stage)
            assertEquals(HostRuntimeSourceStatus.PRE_RUN_SNAPSHOT, metadata.sourceStatus)
            assertEquals(BuildConfig.VERSION_CODE.toLong(), metadata.versionCode)
            assertEquals(context.packageName, metadata.hostPackage)
            assertTrue(metadata.packId.orEmpty().matches(Regex("termux-arm64-[a-f0-9]{16}")))
            assertEquals("arm64-v8a", metadata.abi)
            assertEquals(17, metadata.exitCode)
            val persisted = requireNotNull(repository.read(incident.key))
            for (privateValue in listOf(secret, root.toString(), "failure.cjs", "process.exit", "PRIVATE_ENV")) {
                assertFalse("Incident retained a private runtime value", persisted.toString().contains(privateValue))
            }
            val success = run(controller, root, "node:success.cjs")
            assertEquals(0, success.exitCode)
            controller.run(RuntimeRunRequest(root, "node:wait.cjs"))
            withTimeout(30_000) { controller.state.first { it.stdout.contains("CAPTURE-READY") || !it.isRunning } }
            assertTrue(controller.state.value.toString(), controller.state.value.isRunning)
            val stoppedId = controller.state.value.sessionId
            controller.stop()
            bridge.capture.replay()
            assertTrue(repository.list(IncidentFilter(limit = 2048)).none { it.key.sessionId == success.sessionId || it.key.sessionId == stoppedId })
            assertEquals(1, repository.list(IncidentFilter(limit = 2048)).count { it.key.workspaceId == enabled.workspaceId })
            val reread = DurableIncidentRepository(androidReliabilityFile(context.noBackupFilesDir.toPath().resolve("reliability/incidents.json"), DurableIncidentRepository.MAX_SNAPSHOT_BYTES))
            assertEquals(persisted, reread.read(incident.key))
            println("RUNTIME_INCIDENT_DURABLE=${incident.key.eventId}")
        } finally {
            controller.stop(); controller.close(); scope.cancel()
            bridge.capture.setEnabled(root, false)
        }
    }

    @Test fun genuineMissingExecutableIsLaunchInfrastructureFailureNotProjectCrash() = runBlocking {
        val root = sourceRoot()
        val bridge = AndroidRuntimeReliability(context)
        val repository = ReliabilityHostProvider.get(context).repository
        val actual = AndroidRuntimeHost(context)
        val host = object : RuntimeHost by actual {
            override suspend fun prepare(): RuntimeInstallation = actual.prepare().copy(node = root.resolve("missing-$secret"))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = RuntimeController(host, scope, observer = bridge.runtimeObserver)
        try {
            assertTrue(bridge.capture.setEnabled(root, true).enabled)
            val state = run(controller, root, "node:failure.cjs")
            assertEquals(RuntimePhase.FAILED, state.phase)
            assertNull(state.exitCode)
            val incident = awaitIncident(repository, requireNotNull(state.sessionId))
            val metadata = requireNotNull(incident.runtimeContext)
            assertEquals(HostRuntimeStage.LAUNCH, metadata.stage)
            assertEquals(HostRuntimeOutcome.INFRASTRUCTURE_FAILURE, metadata.outcome)
            assertEquals(HostRuntimeException.IO, metadata.exception)
            assertFalse(requireNotNull(repository.read(incident.key)).toString().contains(secret))
        } finally {
            controller.stop(); controller.close(); scope.cancel()
            bridge.capture.setEnabled(root, false)
        }
    }

    @Test fun projectExit127IsNotReclassifiedAsAMissingExecutable() = runBlocking {
        val root = sourceRoot()
        RuntimeFiles.writeUtf8(root.resolve("exit127.cjs"), "process.exit(127);")
        val bridge = AndroidRuntimeReliability(context)
        val repository = ReliabilityHostProvider.get(context).repository
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = RuntimeController(AndroidRuntimeHost(context), scope, observer = bridge.runtimeObserver)
        try {
            assertTrue(bridge.capture.setEnabled(root, true).enabled)
            val outcome = run(controller, root, "node:exit127.cjs")
            assertEquals(127, outcome.exitCode)
            val incident = awaitIncident(repository, requireNotNull(outcome.sessionId))
            assertEquals(HostRuntimeOutcome.NONZERO_EXIT, incident.runtimeContext?.outcome)
            assertEquals(HostRuntimeStage.PROCESS, incident.runtimeContext?.stage)
            assertNull(incident.runtimeContext?.exception)
        } finally {
            controller.stop(); controller.close(); scope.cancel(); bridge.capture.setEnabled(root, false)
        }
    }

    @Test fun terminalReportsOnlyOwnedShellExitAndNeverTypedCommands() = runBlocking {
        val root = sourceRoot()
        val bridge = AndroidRuntimeReliability(context)
        val repository = ReliabilityHostProvider.get(context).repository
        val workspace = bridge.capture.setEnabled(root, true)
        assertTrue(workspace.enabled)
        val terminal = RuntimeTerminalSession(context, bridge.terminalObserver)
        try {
            terminal.start(root, RuntimeTerminalSession.Program.SHELL)
            withTimeout(60_000) { terminal.status.first { it.phase == RuntimeTerminalSession.Phase.RUNNING || it.phase == RuntimeTerminalSession.Phase.FAILED } }
            assertEquals(terminal.status.value.toString(), RuntimeTerminalSession.Phase.RUNNING, terminal.status.value.phase)
            terminal.send("printf '%s\\n' '$secret'; false; exit 23\r")
            withTimeout(30_000) { terminal.status.first { it.phase == RuntimeTerminalSession.Phase.FINISHED || it.phase == RuntimeTerminalSession.Phase.FAILED } }
            val saved = withTimeout(15_000) { repository.observe().first { entries -> entries.any { it.key.workspaceId == workspace.workspaceId } } }
                .single { it.key.workspaceId == workspace.workspaceId }
            val metadata = requireNotNull(saved.runtimeContext)
            assertEquals(HostRuntimeChannel.TERMINAL, metadata.channel)
            assertEquals(HostRuntimeKind.SHELL, metadata.runtimeKind)
            assertEquals(HostRuntimeOutcome.NONZERO_EXIT, metadata.outcome)
            assertEquals(23, metadata.exitCode)
            val text = requireNotNull(repository.read(saved.key)).toString()
            assertFalse(text.contains(secret)); assertFalse(text.contains("printf")); assertFalse(text.contains(root.toString()))
        } finally { terminal.stop(); bridge.capture.setEnabled(root, false) }
    }

    @Test fun outageAfterInboxCommitReplaysAfterReconstructionWithoutDuplicateReceipt() {
        val root = sourceRoot()
        val storageRoot = Files.createTempDirectory(context.noBackupFilesDir.toPath(), "runtime-replay-test-")
        val registry = androidReliabilityFile(storageRoot.resolve("consent.json"), 512 * 1024)
        val outbox = androidReliabilityFile(storageRoot.resolve("outbox.json"), 512 * 1024)
        val inbox = androidReliabilityFile(storageRoot.resolve("inbox.json"), DurableIncidentRepository.MAX_SNAPSHOT_BYTES)
        val repository = DurableIncidentRepository(inbox)
        val committed = AtomicInteger()
        val capture = HostRuntimeCapture(registry, outbox, { events ->
            repository.acceptRuntime(events).also { committed.addAndGet(it.newlyStored.size) }
            throw IOException("$secret unavailable receipt")
        }, { RuntimeSourceSnapshot.capture(it).revision }, identity)
        val grant = capture.setEnabled(root, true)
        val session = UUID.randomUUID().toString()
        val handle = requireNotNull(capture.begin(root, session, HostRuntimeKind.NODE, HostRuntimeChannel.RUN))
        handle.prepared("termux-arm64-0123456789abcdef", "arm64-v8a")
        handle.finish(HostRuntimeFinish(HostRuntimeOutcome.NONZERO_EXIT, HostRuntimeStage.PROCESS, exitCode = 7, durationMs = 8))
        assertEquals(1, repository.list().size)
        assertEquals(1, committed.get())
        assertEquals(1, capture.updates.value.queuedEvents)
        val restartedRepository = DurableIncidentRepository(inbox)
        val restarted = HostRuntimeCapture(registry, outbox, { restartedRepository.acceptRuntime(it) }, { RuntimeSourceSnapshot.capture(it).revision }, identity)
        restarted.replay()
        assertEquals(0, restarted.updates.value.queuedEvents)
        assertEquals(1, restartedRepository.list().size)
        val incident = restartedRepository.list().single()
        assertEquals(session, incident.key.sessionId)
        assertEquals(grant.workspaceId, incident.key.workspaceId)
        assertFalse(String(requireNotNull(inbox.read()), Charsets.UTF_8).contains(secret))
        assertFalse(String(requireNotNull(outbox.read()), Charsets.UTF_8).contains(secret))
    }

    @Test fun revocationDuringRealProcessPreventsCaptureAndRegrantDoesNotResurrectIt() = runBlocking {
        val root = sourceRoot()
        val bridge = AndroidRuntimeReliability(context)
        val repository = ReliabilityHostProvider.get(context).repository
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = RuntimeController(AndroidRuntimeHost(context), scope, observer = bridge.runtimeObserver)
        RuntimeFiles.writeUtf8(root.resolve("revoke.cjs"), "console.log('REVOKE-READY');process.stdin.once('data',()=>process.exit(19));")
        try {
            assertTrue(bridge.capture.setEnabled(root, true).enabled)
            controller.run(RuntimeRunRequest(root, "node:revoke.cjs"))
            withTimeout(30_000) { controller.state.first { it.stdout.contains("REVOKE-READY") || !it.isRunning } }
            assertTrue(controller.state.value.isRunning)
            assertFalse(bridge.capture.setEnabled(root, false).enabled)
            assertTrue(bridge.capture.setEnabled(root, true).enabled)
            controller.sendInput("finish")
            val finished = withTimeout(30_000) { controller.state.first { !it.isRunning } }
            controller.stop()
            assertEquals(19, finished.exitCode)
            bridge.capture.replay()
            assertTrue(repository.list(IncidentFilter(limit = 2048)).none { it.key.sessionId == finished.sessionId })
        } finally { controller.stop(); controller.close(); scope.cancel(); bridge.capture.setEnabled(root, false) }
    }

    private fun sourceRoot(): Path = Files.createTempDirectory(context.cacheDir.toPath(), "runtime-capture-test-").also { root ->
        RuntimeFiles.writeUtf8(root.resolve("failure.cjs"), "console.log('$secret');console.error('$secret');process.exit(17);")
        RuntimeFiles.writeUtf8(root.resolve("success.cjs"), "console.log('CAPTURE-SUCCESS');")
        RuntimeFiles.writeUtf8(root.resolve("wait.cjs"), "console.log('CAPTURE-READY');setInterval(()=>{},1000);")
    }

    private suspend fun run(controller: RuntimeController, root: Path, target: String): RuntimeSessionState {
        controller.run(RuntimeRunRequest(root, target))
        val outcome = withTimeout(60_000) { controller.state.first { !it.isRunning } }
        controller.stop()
        return outcome
    }

    private suspend fun awaitIncident(repository: IncidentRepository, sessionId: String) = withTimeout(15_000) {
        repository.observe().first { events -> events.any { it.key.sessionId == sessionId } }.single { it.key.sessionId == sessionId }
    }
}
