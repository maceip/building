package dev.ide.workbench.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RuntimeControllerTest {
    @TempDir lateinit var temp: Path

    @Test fun detectionAndConfigAreReadOnlyUntilExplicitSave() = runBlocking<Unit> {
        RuntimeFiles.writeUtf8(temp.resolve("package.json"), """{"scripts":{"dev":"node server.js","test":"node tests.js"}}""")
        RuntimeFiles.writeUtf8(temp.resolve("server.js"), "console.log('hello')")
        RuntimeFiles.writeUtf8(temp.resolve("main.py"), "print('hello')")
        val controller = controller()
        try {
            assertEquals(setOf("npm:dev", "npm:test", "node:server.js", "python:main.py"), controller.targets(temp).map { it.id }.toSet())
            assertEquals(RuntimeProjectConfig(), controller.config(temp))
            assertFalse(Files.exists(temp.resolve(".building")))
            val config = RuntimeProjectConfig(targetId = "python:main.py", args = listOf("one two"), environment = mapOf("PORT" to "3210"), previewPort = 3210)
            controller.saveConfig(temp, config)
            assertEquals(config, controller.config(temp))
            assertThrows(IllegalArgumentException::class.java) { RuntimeProjects.save(temp, config.copy(environment = mapOf("LD_PRELOAD" to "/evil"))) }
        } finally { controller.close() }
    }

    @Test fun shellStreamsInputExitAndRawStderr() = runBlocking<Unit> {
        RuntimeFiles.writeUtf8(temp.resolve("main.sh"), "printf 'Name: '; read name; printf 'hello %s\\n' \"\$name\"; printf 'problem\\n' >&2; exit 7\n")
        val controller = controller()
        try {
            controller.run(RuntimeRunRequest(temp, "shell:main.sh"))
            withTimeout(5000) { controller.state.first { it.stdout.contains("Name:") } }
            controller.sendInput("Ada")
            val done = withTimeout(5000) { controller.state.first { !it.isRunning } }
            assertEquals(7, done.exitCode)
            assertTrue(done.stdout.contains("hello Ada"), done.toString())
            assertEquals("problem\n", done.stderr)
            assertNotNull(done.sessionId)
        } finally { controller.stop(); controller.close() }
    }

    @Test fun cancellationStopsLiveProcessAndAllowsAnotherRun() = runBlocking<Unit> {
        RuntimeFiles.writeUtf8(temp.resolve("wait.sh"), "printf 'ready\\n'; while :; do :; done\n")
        RuntimeFiles.writeUtf8(temp.resolve("next.sh"), "printf 'next\\n'\n")
        val controller = controller()
        try {
            controller.run(RuntimeRunRequest(temp, "shell:wait.sh"))
            withTimeout(5000) { controller.state.first { it.stdout.contains("ready") } }
            withTimeout(5000) { controller.stop() }
            assertFalse(controller.state.value.isRunning)
            controller.run(RuntimeRunRequest(temp, "shell:next.sh"))
            val done = withTimeout(5000) { controller.state.first { !it.isRunning } }
            assertEquals(0, done.exitCode)
            assertEquals("next\n", done.stdout)
        } finally { controller.close() }
    }

    @Test fun chattyProgramHasBoundedSeparateStreams() = runBlocking<Unit> {
        RuntimeFiles.writeUtf8(temp.resolve("noise.sh"), "i=0; while [ \"\$i\" -lt 12000 ]; do printf '012345678901234567890123456789\\n'; i=\$((i+1)); done\n")
        val controller = controller()
        try {
            controller.run(RuntimeRunRequest(temp, "shell:noise.sh"))
            val done = withTimeout(10000) { controller.state.first { !it.isRunning } }
            assertEquals(0, done.exitCode)
            assertTrue(done.stdoutTruncated)
            assertEquals(RuntimeController.MAX_TRANSCRIPT, done.stdout.length)
            assertTrue(done.transcript.length <= RuntimeController.MAX_TRANSCRIPT)
        } finally { controller.close() }
    }

    @Test fun staticPreviewNeedsNoNativePackAndRejectsEscapes() = runBlocking<Unit> {
        RuntimeFiles.writeUtf8(temp.resolve("index.html"), "<h1>offline</h1>")
        RuntimeFiles.writeUtf8(temp.resolve(".secret"), "hidden")
        val controller = controller(prepareFails = true)
        var address = ""
        try {
            controller.run(RuntimeRunRequest(temp, "static:index.html"))
            val ready = withTimeout(5000) { controller.state.first { it.previewUrl != null } }
            address = ready.previewUrl!!
            assertEquals("<h1>offline</h1>", URL(address).readText())
            val conn = URL(address.substringBeforeLast('/') + "/.secret").openConnection() as HttpURLConnection
            assertEquals(403, conn.responseCode)
            conn.disconnect()
            controller.stop()
            assertThrows(Exception::class.java) { URL(address).openConnection().apply { connectTimeout = 500; readTimeout = 500 }.getInputStream().close() }
        } finally { controller.close() }
    }

    @Test fun internalCopySyncIsExplicitAndPreservesConflicts() {
        val source = Files.createDirectory(temp.resolve("source"))
        RuntimeFiles.writeUtf8(source.resolve("main.py"), "original")
        val mirror = RuntimeWorkspace(source, temp.resolve("cache"))
        mirror.prepare()
        RuntimeFiles.writeUtf8(mirror.root.resolve("result.json"), "result")
        assertFalse(Files.exists(source.resolve("result.json")))
        assertEquals(1, mirror.syncBack().copied)
        RuntimeFiles.writeUtf8(source.resolve("main.py"), "editor")
        RuntimeFiles.writeUtf8(mirror.root.resolve("main.py"), "runtime")
        val sync = mirror.syncBack()
        assertEquals(listOf("main.py"), sync.conflicts)
        assertEquals("editor", RuntimeFiles.readUtf8(source.resolve("main.py")))
        assertThrows(IllegalArgumentException::class.java) { mirror.prepare() }
    }

    @Test fun syncNeverReplacesExistingSourceEvenWithUnchangedBaseline() {
        val source = Files.createDirectory(temp.resolve("safe-source"))
        RuntimeFiles.writeUtf8(source.resolve("main.py"), "original")
        val mirror = RuntimeWorkspace(source, temp.resolve("safe-cache")) { destination -> RuntimeFiles.writeUtf8(destination, "racing editor save") }
        mirror.prepare()
        RuntimeFiles.writeUtf8(mirror.root.resolve("main.py"), "guest modification")
        RuntimeFiles.writeUtf8(mirror.root.resolve("new.txt"), "guest output")
        val report = mirror.syncBack()
        assertEquals(0, report.copied)
        assertEquals(setOf("main.py", "new.txt"), report.conflicts.toSet())
        assertEquals("original", RuntimeFiles.readUtf8(source.resolve("main.py")))
        assertEquals("racing editor save", RuntimeFiles.readUtf8(source.resolve("new.txt")))
    }

    @Test fun failedCopyPreparationRetainsOnlyTheCurrentWorkspaceForSync() = runBlocking<Unit> {
        val source = Files.createDirectory(temp.resolve("copy-project"))
        RuntimeFiles.writeUtf8(source.resolve("main.sh"), "printf 'first\\n'\n")
        val controller = controller()
        try {
            controller.saveConfig(source, RuntimeProjectConfig(workspaceMode = RuntimeWorkspaceMode.INTERNAL_COPY))
            controller.run(RuntimeRunRequest(source, "shell:main.sh"))
            val first = withTimeout(5000) { controller.state.first { !it.isRunning } }
            assertEquals(0, first.exitCode)
            val copy = first.workspacePath!!
            RuntimeFiles.writeUtf8(copy.resolve("main.sh"), "runtime edit")
            RuntimeFiles.writeUtf8(source.resolve("main.sh"), "editor edit")
            controller.run(RuntimeRunRequest(source, "shell:main.sh"))
            val failed = withTimeout(5000) { controller.state.first { !it.isRunning } }
            assertEquals(RuntimePhase.FAILED, failed.phase)
            assertTrue(failed.workspaceCopy)
            assertEquals(copy, failed.workspacePath)
            assertEquals(listOf("main.sh"), controller.syncBack().conflicts)
            assertEquals("editor edit", RuntimeFiles.readUtf8(source.resolve("main.sh")))
            val unrelated = Files.createDirectory(temp.resolve("unrelated"))
            controller.run(RuntimeRunRequest(unrelated, "missing"))
            withTimeout(5000) { controller.state.first { !it.isRunning } }
            assertEquals(RuntimeSyncReport(0), controller.syncBack())
        } finally { controller.stop(); controller.close() }
    }

    @Test fun npmRunArgvDoesNotDisableLifecycleHooksAndPreservesArguments() = runBlocking<Unit> {
        RuntimeFiles.writeUtf8(temp.resolve("package.json"), """{"scripts":{"pretest":"node before.cjs","test":"node test.cjs","posttest":"node after.cjs"}}""")
        // A shell argument printer tests the controller's argv only; real npm hooks are device-tested.
        val cli = temp.resolve("npm-argv.sh")
        RuntimeFiles.writeUtf8(cli, "printf '%s\\n' \"\$@\"\n")
        val controller = controller(npmCli = cli)
        try {
            controller.run(RuntimeRunRequest(temp, "npm:test", listOf("one two", "--flag")))
            val done = withTimeout(5000) { controller.state.first { !it.isRunning } }
            assertEquals(0, done.exitCode, done.toString())
            assertEquals(listOf("--prefix", temp.toRealPath().toString(), "--no-audit", "--no-fund", "run", "test", "--", "one two", "--flag"), done.stdout.trimEnd().lines())
        } finally { controller.stop(); controller.close() }
    }

    @Test fun npmInstallArgvAllowsDependenciesAndNeverForcesOfflineOrDisablesHooks() = runBlocking<Unit> {
        val cli = temp.resolve("npm-argv.sh")
        RuntimeFiles.writeUtf8(cli, "printf '%s\\n' \"\$@\"\n")
        val controller = controller(npmCli = cli)
        try {
            for (manifest in listOf(
                """{"dependencies":{"esbuild":"0.25.0"},"workspaces":["packages/*"],"bundledDependencies":["esbuild"]}""",
                """{"dependencies":{"fixture":"file:fixture.tgz"}}""",
                """{"name":"no-dependencies"}""",
            )) {
                RuntimeFiles.writeUtf8(temp.resolve("package.json"), manifest)
                controller.installDependencies(temp)
                val done = withTimeout(5000) { controller.state.first { !it.isRunning } }
                assertEquals(0, done.exitCode, done.toString())
                assertEquals(listOf("--prefix", temp.toRealPath().toString(), "install", "--no-audit", "--no-fund"), done.stdout.trimEnd().lines())
            }
        } finally { controller.stop(); controller.close() }
    }

    @Test fun observerReportsActualProcessOutcomesWithoutPrivatePayloads() = runBlocking<Unit> {
        val marker = "PRIVATE_ARGUMENT_ENV_OUTPUT"
        RuntimeFiles.writeUtf8(temp.resolve("main.sh"), "printf '%s\\n' \"\$1\"; printf '%s\\n' \"\$SECRET\" >&2; exit \"\$2\"\n")
        val observer = RecordingObserver()
        val controller = controller(observer = observer)
        try {
            controller.saveConfig(temp, RuntimeProjectConfig(environment = mapOf("SECRET" to marker)))
            for (code in listOf(9, 0)) {
                controller.run(RuntimeRunRequest(temp, "shell:main.sh", listOf(marker, code.toString())))
                val outcome = observer.next()
                val start = observer.starts.last()
                assertEquals(UUID.fromString(controller.state.value.sessionId), outcome.sessionId)
                assertEquals(start.sessionId, outcome.sessionId)
                assertEquals(temp.toRealPath(), start.root)
                assertEquals(RuntimeObservedTarget(RuntimeKind.SHELL, "shell:main.sh"), start.target)
                assertEquals(RuntimeKind.SHELL, outcome.kind)
                assertEquals(RuntimeOutcomeStage.PROCESS, outcome.stage)
                assertEquals(if (code == 0) RuntimeOutcomeClassification.SUCCESS else RuntimeOutcomeClassification.NONZERO_EXIT, outcome.classification)
                assertEquals(code, outcome.exitCode)
                assertNull(outcome.exceptionCategory)
                assertTrue(outcome.durationMillis in 0..RuntimeSessionOutcome.MAX_DURATION_MILLIS)
                assertEquals(outcome.sessionId to RuntimePreparedMetadata("host-test", "host"), observer.prepared.last())
                assertTrue(controller.state.value.stdout.contains(marker))
                assertTrue(controller.state.value.stderr.contains(marker))
                assertFalse(outcome.toString().contains(marker))
                assertFalse(outcome.toString().contains(temp.toString()))
                assertFalse(outcome.toString().contains("main.sh"))
            }
            controller.stop(); controller.stop(); controller.close()
            assertEquals(2, observer.outcomes.size)
            assertEquals(2, observer.starts.map { it.sessionId }.distinct().size)
            assertEquals(2, observer.prepared.size)
            assertEquals(setOf("sessionId", "kind", "stage", "classification", "exitCode", "durationMillis", "exceptionCategory"),
                RuntimeSessionOutcome::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
        } finally { controller.stop(); controller.close() }
    }

    @Test fun observerStartsBeforePreparationAndUsesOnlyTheOriginalRoot() = runBlocking<Unit> {
        RuntimeFiles.writeUtf8(temp.resolve("main.sh"), "printf 'done\\n'\n")
        val events = CopyOnWriteArrayList<String>()
        val base = fixtureHost()
        val observer = RecordingObserver()
        val recording = RuntimeObserver { id, root, kind, target ->
            events += "started"
            assertEquals(temp.toRealPath(), root)
            val handle = observer.onSessionStarted(id, root, kind, target)
            object : RuntimeSessionObserver {
                override fun onRuntimePrepared(metadata: RuntimePreparedMetadata) {
                    events += "prepared"
                    handle.onRuntimePrepared(metadata)
                }
                override fun onSessionFinished(outcome: RuntimeSessionOutcome) {
                    events += "finished"
                    handle.onSessionFinished(outcome)
                }
            }
        }
        val host = object : RuntimeHost by base {
            override val workspaceCache: Path get() { events += "workspace"; return base.workspaceCache }
            override suspend fun prepare(): RuntimeInstallation { events += "prepare"; return base.prepare() }
            override fun start(command: List<String>, workingDirectory: Path, environment: Map<String, String>): RuntimeChild {
                events += "launch"
                return base.start(command, workingDirectory, environment)
            }
        }
        val controller = controller(observer = recording, host = host)
        try {
            controller.saveConfig(temp, RuntimeProjectConfig(workspaceMode = RuntimeWorkspaceMode.INTERNAL_COPY))
            assertTrue(events.isEmpty())
            controller.run(RuntimeRunRequest(temp, "shell:main.sh"))
            assertEquals(RuntimeOutcomeClassification.SUCCESS, observer.next().classification)
            assertEquals(listOf("started", "workspace", "prepare", "prepared", "launch", "finished"), events)
            assertNotEquals(temp.toRealPath(), controller.state.value.workspacePath)
            assertEquals(temp.toRealPath(), observer.starts.single().root)
        } finally { controller.stop(); controller.close() }
    }

    @Test fun observerClassifiesRealLaunchFailureSeparatelyFromProjectExit() = runBlocking<Unit> {
        RuntimeFiles.writeUtf8(temp.resolve("main.sh"), "exit 0\n")
        val missing = temp.resolve("PRIVATE-missing-executable")
        val observer = RecordingObserver()
        val children = CopyOnWriteArrayList<Process>()
        val controller = controller(observer = observer, host = fixtureHost(shell = missing, children = children))
        try {
            controller.run(RuntimeRunRequest(temp, "shell:main.sh"))
            val outcome = observer.next()
            assertEquals(RuntimeOutcomeStage.LAUNCH, outcome.stage)
            assertEquals(RuntimeOutcomeClassification.UNEXPECTED_FAILURE, outcome.classification)
            assertEquals(RuntimeExceptionCategory.IO, outcome.exceptionCategory)
            assertNull(outcome.exitCode)
            assertEquals(RuntimePhase.FAILED, controller.state.value.phase)
            assertTrue(controller.state.value.error!!.contains(missing.toString()))
            assertFalse(outcome.toString().contains("PRIVATE"))
            assertTrue(children.isEmpty())
            controller.stop(); controller.close()
            assertEquals(1, observer.outcomes.size)
        } finally { controller.stop(); controller.close() }
    }

    @Test fun observerReportsPreparationFailureAndUnresolvedKindWithoutGuessing() = runBlocking<Unit> {
        RuntimeFiles.writeUtf8(temp.resolve("main.sh"), "exit 0\n")
        val observer = RecordingObserver()
        val controller = controller(prepareFails = true, observer = observer)
        try {
            controller.run(RuntimeRunRequest(temp, "shell:main.sh"))
            val prepared = observer.next()
            assertEquals(RuntimeOutcomeStage.PREPARE, prepared.stage)
            assertEquals(RuntimeOutcomeClassification.UNEXPECTED_FAILURE, prepared.classification)
            assertEquals(RuntimeExceptionCategory.INVALID_CONFIGURATION, prepared.exceptionCategory)
            assertEquals(RuntimeKind.SHELL, prepared.kind)
            assertTrue(observer.prepared.isEmpty())
            controller.run(RuntimeRunRequest(temp.resolve("missing-root"), "shell:PRIVATE-unresolved"))
            val unresolved = observer.next()
            assertEquals(RuntimeOutcomeStage.PREPARE, unresolved.stage)
            assertEquals(RuntimeOutcomeClassification.UNEXPECTED_FAILURE, unresolved.classification)
            assertEquals(RuntimeExceptionCategory.IO, unresolved.exceptionCategory)
            assertNull(unresolved.kind)
            assertNull(observer.starts.last().kind)
            assertNull(observer.starts.last().target)
            assertFalse(unresolved.toString().contains("PRIVATE"))
            assertNotEquals(prepared.sessionId, unresolved.sessionId)
        } finally { controller.stop(); controller.close() }
    }

    @Test fun observerUsesOnlyFixedExceptionCategoriesAndNeverCallerClassNames() = runBlocking<Unit> {
        RuntimeFiles.writeUtf8(temp.resolve("main.sh"), "exit 0\n")
        class PRIVATEProjectException : Exception("PRIVATE-exception-message")
        val base = fixtureHost()
        for ((failure, category) in listOf(
            IOException("PRIVATE-path") to RuntimeExceptionCategory.IO,
            SecurityException("PRIVATE-permission") to RuntimeExceptionCategory.SECURITY,
            IllegalArgumentException("PRIVATE-config") to RuntimeExceptionCategory.INVALID_CONFIGURATION,
            NoClassDefFoundError("PRIVATE-host-class") to RuntimeExceptionCategory.HOST_API_UNAVAILABLE,
            PRIVATEProjectException() to RuntimeExceptionCategory.OTHER,
        )) {
            val observer = RecordingObserver()
            val controller = controller(observer = observer, host = object : RuntimeHost by base {
                override suspend fun prepare(): RuntimeInstallation = throw failure
            })
            try {
                controller.run(RuntimeRunRequest(temp, "shell:main.sh"))
                val outcome = observer.next()
                assertEquals(RuntimeOutcomeClassification.UNEXPECTED_FAILURE, outcome.classification)
                assertEquals(category, outcome.exceptionCategory)
                assertFalse(outcome.toString().contains("PRIVATE"))
                assertNull(outcome.exitCode)
            } finally { controller.stop(); controller.close() }
        }
    }

    @Test fun observerReportsPreparationTimeoutWithoutChangingCancellationState() = runBlocking<Unit> {
        RuntimeFiles.writeUtf8(temp.resolve("main.sh"), "exit 0\n")
        val base = fixtureHost()
        val observer = RecordingObserver()
        val controller = controller(observer = observer, host = object : RuntimeHost by base {
            override suspend fun prepare(): RuntimeInstallation = withTimeout(50) { awaitCancellation() }
        })
        try {
            controller.run(RuntimeRunRequest(temp, "shell:main.sh"))
            val outcome = observer.next()
            assertEquals(RuntimeOutcomeStage.PREPARE, outcome.stage)
            assertEquals(RuntimeOutcomeClassification.TIMEOUT, outcome.classification)
            assertNull(outcome.exitCode)
            assertNull(outcome.exceptionCategory)
            assertEquals(RuntimePhase.FINISHED, controller.state.value.phase)
            assertTrue(observer.prepared.isEmpty())
        } finally { controller.stop(); controller.close() }
    }

    @Test fun observerReportsRealProcessTimeoutAndTerminatesTheChild() = runBlocking<Unit> {
        RuntimeFiles.writeUtf8(temp.resolve("wait.sh"), "printf 'ready\\n'; while :; do :; done\n")
        val children = CopyOnWriteArrayList<Process>()
        val base = fixtureHost(children = children)
        val observer = RecordingObserver()
        val controller = controller(observer = observer, host = object : RuntimeHost by base {
            override fun start(command: List<String>, workingDirectory: Path, environment: Map<String, String>): RuntimeChild {
                val child = base.start(command, workingDirectory, environment)
                return object : RuntimeChild by child {
                    override suspend fun awaitExit(): Int = withTimeout(100) { child.awaitExit() }
                }
            }
        })
        try {
            controller.run(RuntimeRunRequest(temp, "shell:wait.sh"))
            val outcome = observer.next()
            assertEquals(RuntimeOutcomeStage.PROCESS, outcome.stage)
            assertEquals(RuntimeOutcomeClassification.TIMEOUT, outcome.classification)
            assertNull(outcome.exitCode)
            assertFalse(children.single().isAlive)
            controller.stop(); controller.stop()
            assertEquals(1, observer.outcomes.size)
        } finally { controller.stop(); controller.close() }
    }

    @Test fun observerMarksStopIntentBeforeTerminateCanReturnAnExitCode() = runBlocking<Unit> {
        RuntimeFiles.writeUtf8(temp.resolve("wait.sh"), "printf 'ready\\n'; while :; do :; done\n")
        val children = CopyOnWriteArrayList<Process>()
        val base = fixtureHost(children = children)
        val observer = RecordingObserver()
        val terminal = CompletableDeferred<Unit>()
        val firstTermination = AtomicBoolean(true)
        val controller = controller(observer = RuntimeObserver { id, root, kind, target ->
            val handle = observer.onSessionStarted(id, root, kind, target)
            object : RuntimeSessionObserver {
                override fun onRuntimePrepared(metadata: RuntimePreparedMetadata) = handle.onRuntimePrepared(metadata)
                override fun onSessionFinished(outcome: RuntimeSessionOutcome) {
                    handle.onSessionFinished(outcome)
                    terminal.complete(Unit)
                }
            }
        }, host = object : RuntimeHost by base {
            override fun start(command: List<String>, workingDirectory: Path, environment: Map<String, String>): RuntimeChild {
                val child = base.start(command, workingDirectory, environment)
                return object : RuntimeChild by child {
                    override suspend fun terminate() {
                        child.terminate()
                        // Let awaitExit fully finish before stop can cancel the coroutine.
                        // The fake terminate and the surrounding stop share one load-tolerant
                        // deadline: nested equal 5s caps raced under full-suite rebuild load
                        // and starved the exit-delivery coroutine into a false timeout.
                        if (firstTermination.compareAndSet(true, false)) withTimeout(30_000) { terminal.await() }
                    }
                }
            }
        })
        try {
            controller.run(RuntimeRunRequest(temp, "shell:wait.sh"))
            withTimeout(30_000) { controller.state.first { it.stdout.contains("ready") } }
            withTimeout(30_000) { controller.stop() }
            val outcome = observer.next()
            assertEquals(RuntimeOutcomeClassification.CANCELLED, outcome.classification)
            assertEquals(RuntimeOutcomeStage.PROCESS, outcome.stage)
            assertNotNull(outcome.exitCode)
            assertNull(outcome.exceptionCategory)
            assertFalse(children.single().isAlive)
            controller.stop(); controller.close(); controller.close()
            assertEquals(1, observer.outcomes.size)
            assertEquals(1, observer.starts.size)
        } finally { controller.stop(); controller.close() }
    }

    @Test fun observerHandlesParentCancellationAndCancellationBeforeEntryOnce() = runBlocking<Unit> {
        RuntimeFiles.writeUtf8(temp.resolve("wait.sh"), "printf 'ready\\n'; while :; do :; done\n")
        for (cancelBeforeEntry in listOf(false, true)) {
            val parent = SupervisorJob()
            if (cancelBeforeEntry) parent.cancel()
            val children = CopyOnWriteArrayList<Process>()
            val observer = RecordingObserver()
            val callerThread = Thread.currentThread()
            val callbackThreads = CopyOnWriteArrayList<Thread>()
            val controller = controller(observer = RuntimeObserver { id, root, kind, target ->
                callbackThreads += Thread.currentThread()
                val handle = observer.onSessionStarted(id, root, kind, target)
                RuntimeSessionObserver { outcome ->
                    callbackThreads += Thread.currentThread()
                    handle.onSessionFinished(outcome)
                }
            }, host = fixtureHost(children = children), scope = CoroutineScope(parent + Dispatchers.Default))
            try {
                controller.run(RuntimeRunRequest(temp, "shell:wait.sh"))
                if (!cancelBeforeEntry) {
                    withTimeout(5000) { controller.state.first { it.stdout.contains("ready") } }
                    parent.cancel()
                }
                val outcome = observer.next()
                assertEquals(RuntimeOutcomeClassification.CANCELLED, outcome.classification)
                assertEquals(if (cancelBeforeEntry) RuntimeOutcomeStage.PREPARE else RuntimeOutcomeStage.PROCESS, outcome.stage)
                assertEquals(if (cancelBeforeEntry) null else RuntimeKind.SHELL, outcome.kind)
                assertEquals(2, callbackThreads.size)
                assertTrue(callbackThreads.none { it === callerThread })
                assertTrue(children.none { it.isAlive })
                controller.stop(); controller.close()
                assertEquals(1, observer.starts.size)
                assertEquals(1, observer.outcomes.size)
            } finally { controller.stop(); controller.close(); parent.cancel() }
        }
    }

    @Test fun closeObservesCancellationAndReleasesARealChildOnlyOnce() = runBlocking<Unit> {
        RuntimeFiles.writeUtf8(temp.resolve("wait.sh"), "printf 'ready\\n'; while :; do :; done\n")
        val children = CopyOnWriteArrayList<Process>()
        val observer = RecordingObserver()
        val controller = controller(observer = observer, host = fixtureHost(children = children))
        try {
            controller.run(RuntimeRunRequest(temp, "shell:wait.sh"))
            withTimeout(5000) { controller.state.first { it.stdout.contains("ready") } }
            controller.close(); controller.close()
            assertEquals(RuntimeOutcomeClassification.CANCELLED, observer.next().classification)
            assertFalse(children.single().isAlive)
            controller.stop()
            assertEquals(1, observer.outcomes.size)
        } finally { controller.stop(); controller.close() }
    }

    @Test fun disabledObserverAndThrowingCallbacksCannotMisreportExecution() = runBlocking<Unit> {
        RuntimeFiles.writeUtf8(temp.resolve("main.sh"), "printf 'actual output\\n'; exit \"\$1\"\n")
        val callbacks = AtomicInteger()
        val recorded = CopyOnWriteArrayList<RuntimeSessionOutcome>()
        val startThrows = RuntimeObserver { _, _, _, _ ->
            callbacks.incrementAndGet()
            throw CancellationException("PRIVATE-start-observer")
        }
        val terminalThrows = RuntimeObserver { _, _, _, _ ->
            object : RuntimeSessionObserver {
                override fun onRuntimePrepared(metadata: RuntimePreparedMetadata) {
                    callbacks.incrementAndGet()
                    throw LinkageError("PRIVATE-prepared-observer")
                }
                override fun onSessionFinished(outcome: RuntimeSessionOutcome) {
                    callbacks.incrementAndGet()
                    recorded += outcome
                    throw AssertionError("PRIVATE-terminal-observer")
                }
            }
        }
        for (observer in listOf(RuntimeObserver.NONE, startThrows, terminalThrows)) {
            val controller = controller(observer = observer)
            try {
                for (code in listOf(0, 4)) {
                    controller.run(RuntimeRunRequest(temp, "shell:main.sh", listOf(code.toString())))
                    val done = withTimeout(5000) { controller.state.first { !it.isRunning } }
                    controller.stop()
                    assertEquals(code, done.exitCode)
                    assertEquals(if (code == 0) RuntimePhase.FINISHED else RuntimePhase.FAILED, done.phase)
                    assertEquals("actual output\n", done.stdout)
                    assertFalse(done.toString().contains("PRIVATE"))
                }
            } finally { controller.stop(); controller.close() }
        }
        assertEquals(6, callbacks.get())
        assertEquals(listOf(RuntimeOutcomeClassification.SUCCESS, RuntimeOutcomeClassification.NONZERO_EXIT), recorded.map { it.classification })
        assertEquals(listOf(0, 4), recorded.map { it.exitCode })
        assertTrue(recorded.all { it.exceptionCategory == null })
    }

    @Test fun observerMetadataIsBoundedAndNeverInfersTestsFromNpmCommands() = runBlocking<Unit> {
        val script = "test\u001b\n" + "x".repeat(300)
        val escapedScript = script.replace("\u001b", "\\u001b").replace("\n", "\\n")
        RuntimeFiles.writeUtf8(temp.resolve("package.json"), """{"scripts":{"$escapedScript":"PRIVATE_COMMAND"}}""")
        val cli = temp.resolve("npm-argv.sh")
        RuntimeFiles.writeUtf8(cli, "printf '%s\\n' \"\$@\"\n")
        val observer = RecordingObserver()
        val base = fixtureHost(npmCli = cli)
        val controller = controller(observer = observer, host = object : RuntimeHost by base {
            override suspend fun prepare(): RuntimeInstallation = base.prepare().copy(packId = "PRIVATE/path\n", abi = "a".repeat(33))
        })
        try {
            controller.run(RuntimeRunRequest(temp, "npm:$script"))
            val outcome = observer.next()
            assertEquals(RuntimeKind.NPM, outcome.kind)
            assertEquals(RuntimeOutcomeStage.PROCESS, outcome.stage)
            assertEquals(RuntimeOutcomeClassification.SUCCESS, outcome.classification)
            val target = observer.starts.single().target!!
            assertEquals(RuntimeKind.NPM, target.kind)
            assertEquals(256, target.id.length)
            assertTrue(target.id.all { it in ' '..'~' })
            assertEquals(RuntimePreparedMetadata(null, null), observer.prepared.single().second)
            // Sanitizing the private descriptor must not rewrite the actual npm argv or hooks.
            assertTrue(controller.state.value.stdout.contains(script))
            controller.installDependencies(temp)
            assertEquals(RuntimeOutcomeClassification.SUCCESS, observer.next().classification)
            assertEquals(RuntimeObservedTarget(RuntimeKind.NPM, "dependencies:npm"), observer.starts.last().target)
            assertFalse(controller.state.value.stdout.contains("--ignore-scripts"))
            assertEquals(2, observer.outcomes.size)
        } finally { controller.stop(); controller.close() }
    }

    @Test fun observerIsNotInvokedForReadOnlyQueriesOrNativeProbes() = runBlocking<Unit> {
        RuntimeFiles.writeUtf8(temp.resolve("main.sh"), "exit 0\n")
        val observer = RecordingObserver()
        val controller = controller(observer = observer)
        try {
            controller.targets(temp)
            controller.config(temp)
            controller.probe()
            assertTrue(observer.starts.isEmpty())
            assertTrue(observer.prepared.isEmpty())
            assertTrue(observer.outcomes.isEmpty())
        } finally { controller.stop(); controller.close() }
    }

    @Test fun staticPreviewHasAnObservedLifetimeWithoutPreparedNativeMetadata() = runBlocking<Unit> {
        RuntimeFiles.writeUtf8(temp.resolve("index.html"), "<h1>offline</h1>")
        val observer = RecordingObserver()
        val controller = controller(prepareFails = true, observer = observer)
        try {
            controller.run(RuntimeRunRequest(temp, "static:index.html"))
            withTimeout(5000) { controller.state.first { it.previewUrl != null } }
            assertTrue(observer.outcomes.isEmpty())
            controller.stop()
            val outcome = observer.next()
            assertEquals(RuntimeKind.STATIC, outcome.kind)
            assertEquals(RuntimeOutcomeStage.PROCESS, outcome.stage)
            assertEquals(RuntimeOutcomeClassification.CANCELLED, outcome.classification)
            assertNull(outcome.exitCode)
            assertTrue(observer.prepared.isEmpty())
        } finally { controller.stop(); controller.close() }
    }

    @Test fun prefixLayoutFitsProductionAndDebugButRejectsLongAppPaths() {
        val pack = "termux-arm64-0123456789abcdef"
        for (appId in listOf("ai.building", "com.tyron.code.dev")) {
            val files = java.nio.file.Paths.get("/data/user/0/$appId/files")
            val prefix = RuntimePrefixLayout.prefix(files, pack)
            assertTrue(prefix.toString().toByteArray().size < 90)
            assertEquals(files.resolve("dr/p/0123456789abcdef/usr"), prefix)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimePrefixLayout.prefix(java.nio.file.Paths.get("/data/user/0/" + "long".repeat(25) + "/files"), pack)
        }
    }

    @Test fun samplesNeverOverwriteUserContent() = runBlocking<Unit> {
        val controller = controller()
        try {
            val first = controller.seedSample(temp, "node-http")
            val second = controller.seedSample(temp, "node-http")
            assertNotEquals(first, second)
            assertTrue(controller.targets(first).any { it.id == "npm:start" })
            assertTrue(Files.exists(first.resolve("server.cjs")))
        } finally { controller.close() }
    }

    private fun controller(
        prepareFails: Boolean = false,
        npmCli: Path? = null,
        observer: RuntimeObserver = RuntimeObserver.NONE,
        host: RuntimeHost = fixtureHost(prepareFails, npmCli),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ): RuntimeController = RuntimeController(host, scope, observer)

    private fun fixtureHost(
        prepareFails: Boolean = false,
        npmCli: Path? = null,
        shell: Path = java.nio.file.Paths.get("/bin/sh"),
        children: MutableList<Process> = CopyOnWriteArrayList(),
    ): RuntimeHost = object : RuntimeHost {
        override val workspaceCache = temp.resolve("cache")
        override suspend fun prepare(): RuntimeInstallation {
            check(!prepareFails) { "Static preview must not provision native runtime" }
            return RuntimeInstallation("host-test", "host", temp, shell.takeIf { npmCli != null }, npmCli, null, shell, mapOf("PATH" to "/usr/bin:/bin"))
        }
        override fun start(command: List<String>, workingDirectory: Path, environment: Map<String, String>): RuntimeChild {
            val process = ProcessBuilder(command).directory(workingDirectory.toFile()).apply { environment().putAll(environment) }.start()
            children += process
            return object : RuntimeChild {
                private val stopped = AtomicBoolean(false)
                override val stdin: OutputStream get() = process.outputStream
                override val stdout: InputStream get() = process.inputStream
                override val stderr: InputStream get() = process.errorStream
                override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) { while (!process.waitFor(20, TimeUnit.MILLISECONDS)) ensureActive(); process.exitValue() }
                override suspend fun terminate() { if (stopped.compareAndSet(false, true) && process.isAlive) { process.destroy(); if (!process.waitFor(200, TimeUnit.MILLISECONDS)) process.destroyForcibly() } }
            }
        }
    }

    private data class ObservedStart(val sessionId: UUID, val root: Path, val kind: RuntimeKind?, val target: RuntimeObservedTarget?)

    private class RecordingObserver : RuntimeObserver {
        val starts = CopyOnWriteArrayList<ObservedStart>()
        val prepared = CopyOnWriteArrayList<Pair<UUID, RuntimePreparedMetadata>>()
        val outcomes = CopyOnWriteArrayList<RuntimeSessionOutcome>()
        private val completions = Channel<RuntimeSessionOutcome>(Channel.UNLIMITED)

        override fun onSessionStarted(sessionId: UUID, projectRoot: Path, kind: RuntimeKind?, target: RuntimeObservedTarget?): RuntimeSessionObserver {
            starts += ObservedStart(sessionId, projectRoot, kind, target)
            return object : RuntimeSessionObserver {
                override fun onRuntimePrepared(metadata: RuntimePreparedMetadata) { prepared += sessionId to metadata }
                override fun onSessionFinished(outcome: RuntimeSessionOutcome) {
                    outcomes += outcome
                    completions.trySend(outcome)
                }
            }
        }

        suspend fun next(): RuntimeSessionOutcome = withTimeout(5000) { completions.receive() }
    }
}
