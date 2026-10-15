package dev.ide.android.workbench.terminal

import android.content.ClipData
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.workbench.runtime.AndroidRuntimeHost
import dev.ide.workbench.runtime.RuntimeFiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** App-UID, offline PTY acceptance. No root/adb helper capability in the implementation. */
@RunWith(AndroidJUnit4::class)
class RuntimeTerminalDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun until(child: RuntimePty, marker: String): String = withTimeout(20_000) {
        val output = StringBuilder()
        val buffer = ByteArray(16384)
        while (!output.contains(marker)) {
            val count = withContext(Dispatchers.IO) { child.read(buffer) }
            if (count < 0) error("Terminal EOF before $marker: $output")
            if (count > 0) output.append(String(buffer, 0, count, Charsets.UTF_8))
            else delay(5)
        }
        output.toString()
    }
    private suspend fun send(child: RuntimePty, text: String) = withTimeout(10_000) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var offset = 0
        while (offset < bytes.size) {
            offset += withContext(Dispatchers.IO) { child.write(bytes, offset, bytes.size - offset) }
            if (offset < bytes.size) delay(5)
        }
    }
    private suspend fun exited(child: RuntimePty): Int = withTimeout(10_000) {
        val buffer = ByteArray(16384)
        var result: Int
        while (true) {
            result = withContext(Dispatchers.IO) { child.peekExit() }
            if (result != RuntimePty.RUNNING) break
            withContext(Dispatchers.IO) { child.read(buffer) }
            delay(5)
        }
        result
    }

    @Test fun nodeSeesAllThreeTtysAndResizeEmitsSigwinch() = runBlocking {
        val pack = AndroidRuntimeHost(context).prepare()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "terminal-tty-")
        val probe = root.resolve("tty.cjs")
        RuntimeFiles.writeUtf8(probe, """
            const assert = require('node:assert/strict');
            assert.equal(process.stdin.isTTY, true);
            assert.equal(process.stdout.isTTY, true);
            assert.equal(process.stderr.isTTY, true);
            assert.equal(process.stdout.columns, 80);
            assert.equal(process.stdout.rows, 24);
            console.log('ALL-TTYS-READY');
            process.stdout.on('resize', () => {
                console.log('SIZE-' + process.stdout.columns + 'x' + process.stdout.rows);
                if (process.stdout.columns === 101 && process.stdout.rows === 33) process.exit(0);
            });
            setTimeout(() => process.exit(92), 10000);
        """.trimIndent())
        RuntimePty.start(listOf(requireNotNull(pack.node).toString(), probe.toString()), root.toString(), terminalEnvironment(pack, emptyMap()), 80, 24).use { child ->
            until(child, "ALL-TTYS-READY")
            child.resize(101, 33)
            until(child, "SIZE-101x33")
            assertEquals(0, exited(child))
        }
    }

    @Test fun interactiveNodeReplAcceptsEditingUtf8CtrlCAndCtrlD() = runBlocking {
        val pack = AndroidRuntimeHost(context).prepare()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "terminal-repl-")
        RuntimePty.start(listOf(requireNotNull(pack.node).toString(), "--interactive"), root.toString(), terminalEnvironment(pack, emptyMap()), 80, 24).use { child ->
            until(child, "> ")
            // Left + backspace edits 6*8 to 6*7 in Node readline. The computed marker is
            // absent from the echoed input, so a prompt redraw alone cannot pass this test.
            send(child, "'EDIT-' + (6*8)\u001b[D\u007f7\r")
            until(child, "EDIT-42")
            send(child, "console.log('repl-' + '界')\r")
            until(child, "repl-界")
            send(child, "while (true) {}\r")
            delay(300)
            send(child, "\u0003")
            until(child, "interrupted")
            send(child, "console.log('after-' + 'interrupt')\r")
            until(child, "after-interrupt")
            send(child, "\u0004")
            assertEquals(0, exited(child))
        }
    }

    @Test fun interactiveShellRunsBundledNpmAndForegroundInterruptPreservesShell() = runBlocking {
        val pack = AndroidRuntimeHost(context).prepare()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "terminal-shell-")
        RuntimePty.start(listOf(requireNotNull(pack.shell).toRealPath().toString(), "-i"), root.toString(), terminalEnvironment(pack, emptyMap()), 80, 24).use { child ->
            until(child, "runtime $ ")
            send(child, "npm --version; printf 'NPM-%s\\n' DONE\r")
            val npm = until(child, "NPM-DONE")
            assertTrue(npm, npm.contains("11.20.0"))
            send(child, "node -e \"console.log('FOREGROUND-'+'READY'); setInterval(()=>{},1000)\"\r")
            until(child, "FOREGROUND-READY")
            send(child, "\u0003")
            until(child, "runtime $ ")
            send(child, "printf 'SHELL-%s\\n' ALIVE\r")
            until(child, "SHELL-ALIVE")
            send(child, "exit\r")
            assertEquals(0, exited(child))
        }
    }

    @Test fun stopKillsBackgroundProcessGroupsAndDoesNotWaitOnFullInput() = runBlocking {
        val pack = AndroidRuntimeHost(context).prepare()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "terminal-stop-")
        val marker = root.resolve("child.pid")
        RuntimeFiles.writeUtf8(root.resolve("child.cjs"), "require('fs').writeFileSync('child.pid', String(process.pid));process.on('SIGTERM',()=>{});setInterval(()=>{},1000);")
        assertTrue("Job-control cleanup requires supported pidfd signaling on this test device", RuntimePty.supportsSessionCleanup())
        RuntimePty.start(listOf(requireNotNull(pack.shell).toRealPath().toString(), "-i"), root.toString(), terminalEnvironment(pack, emptyMap()), 80, 24).use { child ->
            until(child, "runtime $ ")
            send(child, "node child.cjs &\r")
            withTimeout(20_000) { while (!Files.isRegularFile(marker)) delay(20) }
            val pid = RuntimeFiles.readUtf8(marker).toInt()
            child.signalSession(RuntimePty.SIGTERM)
            delay(350)
            child.signalSession(RuntimePty.SIGKILL)
            exited(child)
            withTimeout(5_000) { while (isLive(pid)) delay(20) }
            assertFalse("Stop left live job-control descendant", isLive(pid))
        }
    }

    @Test fun imeHardwareKeysCanvasResizeAndSessionCloseUseTheSamePty() = runBlocking {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "terminal-view-")
        val observation = RecordingTerminalObserver()
        val session = withContext(Dispatchers.Main) { RuntimeTerminalSession(context, observation.factory) }
        try {
            session.start(root, RuntimeTerminalSession.Program.NODE)
            withTimeout(60_000) { session.status.first { it.phase != RuntimeTerminalSession.Phase.STARTING } }
            assertEquals(session.status.value.toString(), RuntimeTerminalSession.Phase.RUNNING, session.status.value.phase)
            assertTrue("Fork owner thread must remain alive while terminal runs", session.forkThreadIsAlive())
            val view = withContext(Dispatchers.Main) {
                RuntimeTerminalView(context).apply {
                    this.session = session
                    layout(0, 0, 720, 640)
                }
            }
            suspend fun screen(): String = withContext(Dispatchers.Main) { session.withEmulator { it.screen.transcriptText } }
            suspend fun awaitStage(stage: String, predicate: suspend () -> Boolean) {
                try {
                    withTimeout(20_000) {
                        while (!predicate()) {
                            check(session.status.value.phase == RuntimeTerminalSession.Phase.RUNNING) { "$stage: ${session.status.value}; screen=${screen()}" }
                            delay(20)
                        }
                    }
                } catch (failure: TimeoutCancellationException) {
                    throw AssertionError("Terminal stage '$stage' timed out: ${session.status.value}; screen=${screen()}", failure)
                }
            }
            awaitStage("initial Node VT prompt") {
                withContext(Dispatchers.Main) {
                    session.withEmulator { emulator ->
                        // Transcript extraction deliberately trims trailing spaces. Inspect cells
                        // and cursor instead of expecting a trailing space in the transcript.
                        emulator.cursorCol == 2 && emulator.getSelectedText(0, emulator.cursorRow, 1, emulator.cursorRow) == ">"
                    }
                }
            }
            withContext(Dispatchers.Main) {
                val input = requireNotNull(view.onCreateInputConnection(EditorInfo()))
                assertTrue("IME commitText was rejected", input.commitText("console.log('ime-'+'ok')", 1))
                assertTrue("Hardware Enter was rejected", input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
            }
            awaitStage("IME command evaluated by Node") { screen().contains("ime-ok") }
            assertTrue("Main/IME suspension must not retire the fork owner thread", session.forkThreadIsAlive())
            withContext(Dispatchers.Main) {
                val bitmap = Bitmap.createBitmap(720, 640, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                assertTrue("Renderer drew only a blank canvas", (0 until bitmap.height step 3).any { y -> (0 until bitmap.width step 3).any { x -> bitmap.getPixel(x, y) != android.graphics.Color.BLACK } })
                bitmap.recycle()
                val originalColumns = session.withEmulator { it.mColumns }
                val originalRows = session.withEmulator { it.mRows }
                view.layout(0, 0, 950, 400)
                assertTrue("Wider view must increase PTY columns", session.withEmulator { it.mColumns } > originalColumns)
                assertTrue("Shorter view must reduce PTY rows", session.withEmulator { it.mRows } < originalRows)
            }
        } finally {
            stopAndAwaitWorker(session)
        }
        val outcome = observation.awaitOutcome()
        assertEquals(TerminalOutcomeClassification.USER_CANCELLED, outcome.classification)
        assertEquals(TerminalProgramKind.NODE, outcome.programKind)
        assertEquals(1, observation.starts.size)
        assertEquals(1, observation.outcomes.size)
        assertEquals(1, observation.factoryCalls.get())
        assertSame(root, observation.privateContext!!.workingDirectory)
        observation.assertWorkerCallbacks()
    }

    @Test fun observerReportsOnlyOwnedShellExitNotCommandsTitleOutputOrCtrlC() = runBlocking {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "terminal-observer-private-")
        val observation = RecordingTerminalObserver()
        val session = withContext(Dispatchers.Main) { RuntimeTerminalSession(context, observation.factory) }
        try {
            session.start(root)
            awaitRunning(session)
            awaitScreen(session, "runtime $")
            withContext(Dispatchers.Main) {
                session.send("printf '\\033]0;PRIVATE-TITLE\\007'; printf 'PRIVATE-%s\\n' TRANSCRIPT; false; printf 'READY-%s\\n' OBSERVER\r")
            }
            awaitScreen(session, "READY-OBSERVER")
            assertEquals("Interactive command failure must not finish its shell", RuntimeTerminalSession.Phase.RUNNING, session.status.value.phase)
            assertTrue(observation.outcomes.isEmpty())
            withContext(Dispatchers.Main) {
                session.send("node -e \"console.log('INTERRUPT-'+'READY');setInterval(()=>{},1000)\"\r")
            }
            awaitScreen(session, "INTERRUPT-READY")
            withContext(Dispatchers.Main) { session.send("\u0003") }
            withTimeout(20_000) {
                while (!withContext(Dispatchers.Main) {
                    session.withEmulator { it.getSelectedText(0, it.cursorRow, it.cursorCol, it.cursorRow).startsWith("runtime $") }
                }) delay(20)
            }
            withContext(Dispatchers.Main) { session.send("printf 'AFTER-%s\\n' INTERRUPT\r") }
            awaitScreen(session, "AFTER-INTERRUPT")
            assertTrue("Ctrl-C of foreground job is not a terminal outcome", observation.outcomes.isEmpty())
            withContext(Dispatchers.Main) { session.send("exit 7\r") }
            val outcome = observation.awaitOutcome()
            assertEquals(TerminalOutcomeClassification.NONZERO_EXIT, outcome.classification)
            assertEquals(7, outcome.exitCode)
            assertEquals(RuntimeTerminalSession.Phase.FINISHED, session.status.value.phase)
            assertEquals(7, session.status.value.exitCode)
            assertNull(session.status.value.error)
            assertEquals(TerminalProgramKind.SHELL, outcome.programKind)
            assertEquals(observation.starts.single().sessionId, outcome.sessionId)
            assertEquals(observation.privateContext!!.sessionId, outcome.sessionId)
            assertSame(root, observation.privateContext!!.workingDirectory)
            assertTrue(outcome.durationMs >= 0L)
            val payloads = observation.starts.toString() + outcome.toString()
            for (privateValue in listOf(root.toString(), "PRIVATE-TITLE", "PRIVATE-TRANSCRIPT", "false", "exit 7", "setInterval")) {
                assertFalse("Payload disclosed terminal data", payloads.contains(privateValue))
            }
            observation.assertWorkerCallbacks()
        } finally {
            stopAndAwaitWorker(session)
        }
        assertEquals(1, observation.outcomes.size)
    }

    @Test fun observerExceptionsDoNotChangeRealNodeReplExitOrForkOwnerLifecycle() = runBlocking {
        val pack = AndroidRuntimeHost(context).prepare()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "terminal-observer-throwing-")
        val observation = RecordingTerminalObserver(throwOnCallbacks = true)
        val session = withContext(Dispatchers.Main) { RuntimeTerminalSession(context, observation.factory) }
        try {
            session.start(root, RuntimeTerminalSession.Program.NODE)
            awaitRunning(session)
            withContext(Dispatchers.Main) { session.send("console.log('OBSERVER-'+'ALIVE')\r") }
            awaitScreen(session, "OBSERVER-ALIVE")
            assertTrue(session.forkThreadIsAlive())
            withContext(Dispatchers.Main) { session.send(".exit\r") }
            val outcome = observation.awaitOutcome()
            assertEquals(TerminalOutcomeClassification.SUCCESS, outcome.classification)
            assertEquals(0, outcome.exitCode)
            assertEquals(RuntimeTerminalSession.Phase.FINISHED, session.status.value.phase)
            assertNull(session.status.value.error)
            assertEquals(listOf(TerminalRuntimeIdentity(pack.packId, pack.abi)), observation.identities.toList())
            observation.assertWorkerCallbacks()
        } finally {
            stopAndAwaitWorker(session)
        }
        assertEquals(1, observation.starts.size)
        assertEquals(1, observation.outcomes.size)
    }

    @Test fun throwingObserverFactoryDoesNotPreventRealShellProcess() = runBlocking {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "terminal-observer-factory-")
        val factoryCalls = AtomicInteger()
        val session = withContext(Dispatchers.Main) {
            RuntimeTerminalSession(context, TerminalOutcomeObserverFactory {
                factoryCalls.incrementAndGet()
                throw IllegalStateException("private factory failure ${root.resolve("secret")}")
            })
        }
        try {
            session.start(root)
            awaitRunning(session)
            withContext(Dispatchers.Main) { session.send("exit 0\r") }
            withTimeout(20_000) { session.status.first { it.phase == RuntimeTerminalSession.Phase.FINISHED || it.phase == RuntimeTerminalSession.Phase.FAILED } }
            assertEquals(RuntimeTerminalSession.Phase.FINISHED, session.status.value.phase)
            assertEquals(0, session.status.value.exitCode)
            assertNull(session.status.value.error)
            assertEquals(1, factoryCalls.get())
        } finally {
            stopAndAwaitWorker(session)
        }
    }

    @Test fun missingPrivateDirectoryReportsPreparationFailureWithoutStartOrFailureMessage() = runBlocking {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "terminal-observer-failure-")
        val missing = root.resolve("private-project-secret")
        val observation = RecordingTerminalObserver()
        val session = withContext(Dispatchers.Main) { RuntimeTerminalSession(context, observation.factory) }
        try {
            session.start(missing)
            val outcome = observation.awaitOutcome()
            assertEquals(TerminalOutcomeClassification.PREPARE_FAILURE, outcome.classification)
            assertNull(outcome.exitCode)
            assertEquals(RuntimeTerminalSession.Phase.FAILED, session.status.value.phase)
            assertTrue(observation.starts.isEmpty())
            assertEquals(1, observation.identities.size)
            assertFalse(outcome.toString().contains(root.toString()))
            assertFalse(outcome.toString().contains("private-project-secret"))
            assertFalse(outcome.toString().contains("NoSuchFileException"))
            assertSame(missing, observation.privateContext!!.workingDirectory)
            observation.assertWorkerCallbacks()
        } finally {
            stopAndAwaitWorker(session)
        }
        assertEquals(1, observation.outcomes.size)
    }

    @Test fun stopBeforeStartDoesNotPrepareOrLaunchAndDeliversOneCancelledOutcome() = runBlocking {
        val observation = RecordingTerminalObserver()
        val session = withContext(Dispatchers.Main) { RuntimeTerminalSession(context, observation.factory) }
        try {
            session.stop()
            session.stop()
            session.start(null)
            val outcome = observation.awaitOutcome()
            assertEquals(TerminalOutcomeClassification.USER_CANCELLED, outcome.classification)
            assertNull(outcome.exitCode)
            assertTrue(observation.starts.isEmpty())
            assertTrue(observation.identities.isEmpty())
            assertEquals(1, observation.factoryCalls.get())
            assertNull(observation.privateContext!!.workingDirectory)
            assertEquals(RuntimeTerminalSession.Phase.FINISHED, session.status.value.phase)
            assertFalse(session.forkThreadIsAlive())
            observation.assertWorkerCallbacks()
        } finally {
            stopAndAwaitWorker(session)
        }
        assertEquals(1, observation.outcomes.size)
    }

    @Test fun coroutineCancelledBeforeWorkerStartsStillDeliversAndClosesWorker() = runBlocking {
        val observation = RecordingTerminalObserver()
        val session = withContext(Dispatchers.Main) { RuntimeTerminalSession(context, observation.factory) }
        try {
            // Reach only the existing private owner scope; do not inject a fake host/PTY or expose
            // production cancellation machinery solely for tests. ATOMIC must enter try/finally.
            val scope = RuntimeTerminalSession::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(session) as CoroutineScope
            scope.cancel()
            session.start(null)
            val outcome = observation.awaitOutcome()
            assertEquals(TerminalOutcomeClassification.USER_CANCELLED, outcome.classification)
            assertNull(outcome.exitCode)
            assertTrue(observation.starts.isEmpty())
            assertTrue(observation.identities.isEmpty())
            assertEquals(1, observation.factoryCalls.get())
            assertEquals(RuntimeTerminalSession.Phase.FINISHED, session.status.value.phase)
            observation.assertWorkerCallbacks()
        } finally {
            stopAndAwaitWorker(session)
        }
        assertEquals(1, observation.outcomes.size)
    }

    @Test fun clipboardPasteIsBoundedPlainTextAndNeverOpensUriItems() {
        assertEquals("printf hello", terminalClipboardText(ClipData.Item("printf hello")))
        assertThrows(IllegalArgumentException::class.java) { terminalClipboardText(ClipData.Item(Uri.parse("content://unavailable-provider/never-open"))) }
        assertThrows(IllegalArgumentException::class.java) { terminalClipboardText(ClipData.Item("a".repeat(16385))) }
        assertThrows(IllegalArgumentException::class.java) { terminalClipboardText(ClipData.Item("界".repeat(6000))) }
        assertThrows(IllegalArgumentException::class.java) { terminalClipboardText(null) }
    }

    @Test fun environmentPreservesLiveRuntimeAndCompilerPathsWithoutAppSecrets() = runBlocking {
        val pack = AndroidRuntimeHost(context).prepare()
        val env = terminalEnvironment(pack, mapOf("ANDROID_ROOT" to "/system", "PROVIDER_TOKEN" to "must-not-inherit"))
        assertEquals("xterm-256color", env["TERM"])
        assertEquals("truecolor", env["COLORTERM"])
        assertFalse(env.containsKey("NO_COLOR"))
        assertFalse(env.containsKey("PIP_NO_INPUT"))
        assertFalse(env.containsKey("PROVIDER_TOKEN"))
        for (key in listOf("PREFIX", "LD_PRELOAD", "LD_LIBRARY_PATH", "CC", "CXX", "npm_config_nodedir", "NODE_EXTRA_CA_CERTS", "npm_config_cache")) {
            assertEquals(key, pack.environment[key], env[key])
        }
    }

    private class RecordingTerminalObserver(private val throwOnCallbacks: Boolean = false) : TerminalOutcomeObserver {
        val factoryCalls = AtomicInteger()
        val identities = Collections.synchronizedList(mutableListOf<TerminalRuntimeIdentity>())
        val starts = Collections.synchronizedList(mutableListOf<TerminalSessionStart>())
        val outcomes = Collections.synchronizedList(mutableListOf<TerminalOutcome>())
        private val callbackThreads = Collections.synchronizedList(mutableListOf<Thread>())
        private val terminal = CompletableDeferred<TerminalOutcome>()
        @Volatile var privateContext: TerminalSessionContext? = null
        val factory = TerminalOutcomeObserverFactory {
            factoryCalls.incrementAndGet()
            privateContext = it
            callbackThreads += Thread.currentThread()
            this
        }
        override fun onRuntimePrepared(identity: TerminalRuntimeIdentity) {
            callbackThreads += Thread.currentThread()
            identities += identity
            if (throwOnCallbacks) throw IllegalStateException("private runtime identity observer failure")
        }
        override fun onStart(start: TerminalSessionStart) {
            callbackThreads += Thread.currentThread()
            starts += start
            if (throwOnCallbacks) throw java.util.concurrent.CancellationException("private observer cancellation, not user cancellation")
        }
        override fun onTerminal(outcome: TerminalOutcome) {
            callbackThreads += Thread.currentThread()
            outcomes += outcome
            terminal.complete(outcome)
            if (throwOnCallbacks) throw IllegalStateException("private terminal observer failure")
        }
        suspend fun awaitOutcome(): TerminalOutcome = withTimeout(60_000) { terminal.await() }
        fun assertWorkerCallbacks() {
            val threads = callbackThreads.toList()
            assertTrue(threads.isNotEmpty())
            assertTrue("Factory and all callbacks must stay on the dedicated PTY worker", threads.all { it === threads.first() && it.name == "runtime-terminal-pty" })
        }
    }

    private suspend fun awaitRunning(session: RuntimeTerminalSession) {
        withTimeout(60_000) { session.status.first { it.phase != RuntimeTerminalSession.Phase.STARTING } }
        assertEquals(session.status.value.toString(), RuntimeTerminalSession.Phase.RUNNING, session.status.value.phase)
    }

    private suspend fun awaitScreen(session: RuntimeTerminalSession, marker: String) = withTimeout(20_000) {
        while (true) {
            if (withContext(Dispatchers.Main) { session.withEmulator { it.screen.transcriptText.contains(marker) } }) break
            check(session.status.value.phase == RuntimeTerminalSession.Phase.RUNNING) { "Terminal exited before test marker: ${session.status.value}" }
            delay(20)
        }
    }

    private suspend fun stopAndAwaitWorker(session: RuntimeTerminalSession) {
        session.stop()
        withTimeout(10_000) { session.status.first { it.phase == RuntimeTerminalSession.Phase.FINISHED || it.phase == RuntimeTerminalSession.Phase.FAILED } }
        // forkThread is null for failed/cancelled startup, so check the owner executor as well.
        val worker = RuntimeTerminalSession::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(session) as kotlinx.coroutines.ExecutorCoroutineDispatcher
        withContext(Dispatchers.IO) { assertTrue("Closed terminal leaked its dedicated worker", (worker.executor as ExecutorService).awaitTermination(5, TimeUnit.SECONDS)) }
        assertFalse("Fork owner survived terminal cleanup", session.forkThreadIsAlive())
    }

    private fun isLive(pid: Int): Boolean {
        val stat = runCatching { RuntimeFiles.readUtf8(java.nio.file.Paths.get("/proc/$pid/stat")) }.getOrNull() ?: return false
        return stat.substringAfterLast(") ").firstOrNull() !in listOf('Z', 'X')
    }
}
