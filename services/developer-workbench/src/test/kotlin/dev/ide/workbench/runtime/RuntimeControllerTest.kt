package dev.ide.workbench.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    @Test fun dependencyPolicyDoesNotMistakeIgnoreScriptsForNativeSafety() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeDependencyPolicy.check(Json.parseToJsonElement("""{"dependencies":{"esbuild":"0.25.0"}}""").jsonObject, emptyMap())
        }
        RuntimeDependencyPolicy.check(Json.parseToJsonElement("""{"name":"no-dependencies"}""").jsonObject, emptyMap())
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

    private fun controller(prepareFails: Boolean = false): RuntimeController = RuntimeController(object : RuntimeHost {
        override val workspaceCache = temp.resolve("cache")
        override suspend fun prepare(): RuntimeInstallation {
            check(!prepareFails) { "Static preview must not provision native runtime" }
            return RuntimeInstallation("host-test", "host", temp, null, null, null, java.nio.file.Paths.get("/bin/sh"), mapOf("PATH" to "/usr/bin:/bin"))
        }
        override fun start(command: List<String>, workingDirectory: Path, environment: Map<String, String>): RuntimeChild {
            val process = ProcessBuilder(command).directory(workingDirectory.toFile()).apply { environment().putAll(environment) }.start()
            return object : RuntimeChild {
                private val stopped = AtomicBoolean(false)
                override val stdin: OutputStream get() = process.outputStream
                override val stdout: InputStream get() = process.inputStream
                override val stderr: InputStream get() = process.errorStream
                override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) { while (!process.waitFor(20, TimeUnit.MILLISECONDS)) ensureActive(); process.exitValue() }
                override suspend fun terminate() { if (stopped.compareAndSet(false, true) && process.isAlive) { process.destroy(); if (!process.waitFor(200, TimeUnit.MILLISECONDS)) process.destroyForcibly() } }
            }
        }
    }, CoroutineScope(SupervisorJob() + Dispatchers.Default))
}
