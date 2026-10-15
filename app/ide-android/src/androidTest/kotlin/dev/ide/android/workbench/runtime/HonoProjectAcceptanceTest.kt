package dev.ide.android.workbench.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.workbench.runtime.RuntimeController
import dev.ide.workbench.runtime.RuntimeFiles
import dev.ide.workbench.runtime.RuntimeRunRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class HonoProjectAcceptanceTest {
    @Test fun actualUpstreamProjectInstallsBuildsAndPassesItsUnchangedSuite() = runBlocking<Unit> {
        assumeTrue("Explicit registry-download authorization is required", InstrumentationRegistry.getArguments().getString("allowHonoRegistryInstall") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = HonoProjectInstaller.create(context)
        val host = AndroidRuntimeHost(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = RuntimeController(host, scope)
        val results = Files.createDirectories(context.filesDir.toPath().resolve("hono-acceptance").resolve(UUID.randomUUID().toString()))
        val sources = inputHashes(root)
        try {
            assertFalse(Files.exists(root.resolve("node_modules")))
            assertFalse(Files.exists(root.resolve("dist")))
            println("HONO_UPSTREAM_REVISION=${HonoProjectInstaller.REVISION}")
            println("HONO_WORKSPACE=$root")
            println("HONO_RESULTS=$results")
            val installation = host.prepare()
            runCommand(host, root, installation.environment, listOf(requireNotNull(installation.node).toString(), requireNotNull(installation.npmCli).toString(),
                "ci", "--foreground-scripts", "--cache=${results.resolve("npm-cache")}", "--fetch-retries=1", "--fetch-timeout=45000"), results.resolve("npm-ci.log"), 480_000)
            assertTrue(Files.isDirectory(root.resolve("node_modules/hono")))
            for (script in listOf("build", "typecheck")) {
                controller.run(RuntimeRunRequest(root, "npm:$script"))
                val outcome = withTimeout(300_000) { controller.state.first { !it.isRunning } }
                RuntimeFiles.writeUtf8(results.resolve("$script.log"), outcome.transcript)
                assertEquals("$script failed: ${outcome.transcript}", 0, outcome.exitCode)
            }
            assertTrue(Files.isRegularFile(root.resolve("dist/index.mjs")))
            assertTrue(Files.isRegularFile(root.resolve("dist/index.cjs")))
            controller.run(RuntimeRunRequest(root, "npm:test", listOf("--run", "--maxWorkers=2", "--reporter=default", "--reporter=junit", "--outputFile.junit=upstream-junit.xml")))
            val tested = withTimeout(480_000) { controller.state.first { !it.isRunning } }
            RuntimeFiles.writeUtf8(results.resolve("test.log"), tested.transcript)
            assertEquals("Upstream tests failed: ${tested.transcript}", 0, tested.exitCode)
            val report = root.resolve("upstream-junit.xml")
            assertTrue("Upstream JUnit report missing", Files.isRegularFile(report))
            Files.copy(report, results.resolve("upstream-junit.xml"))
            assertJunit(report, 503)
            assertEquals("Upstream source/test inputs changed", sources, inputHashes(root))
            println("HONO_UPSTREAM_TESTS_PASSED=503")
        } finally {
            controller.stop()
            controller.close()
            scope.cancel()
        }
    }

    @Test fun persistentHonoApplicationPassesRealHttpTestsOnAndroid() = runBlocking<Unit> {
        assumeTrue("Explicit registry-download authorization is required", InstrumentationRegistry.getArguments().getString("allowHonoRegistryInstall") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = HonoProjectInstaller.create(context, HonoProjectInstaller.Project.WORKBENCH)
        val host = AndroidRuntimeHost(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = RuntimeController(host, scope)
        val results = Files.createDirectories(context.filesDir.toPath().resolve("hono-acceptance").resolve(UUID.randomUUID().toString()))
        val sources = inputHashes(root)
        try {
            println("HONO_APPLICATION_WORKSPACE=$root")
            println("HONO_APPLICATION_RESULTS=$results")
            val installed = host.prepare()
            runCommand(host, root, installed.environment, listOf(requireNotNull(installed.node).toString(), requireNotNull(installed.npmCli).toString(),
                "ci", "--foreground-scripts", "--cache=${results.resolve("npm-cache")}", "--fetch-retries=1", "--fetch-timeout=45000"), results.resolve("npm-ci.log"), 300_000)
            controller.run(RuntimeRunRequest(root, "npm:typecheck"))
            val types = withTimeout(180_000) { controller.state.first { !it.isRunning } }
            RuntimeFiles.writeUtf8(results.resolve("typecheck.log"), types.transcript)
            assertEquals(types.transcript, 0, types.exitCode)
            controller.run(RuntimeRunRequest(root, "npm:build"))
            val built = withTimeout(180_000) { controller.state.first { !it.isRunning } }
            RuntimeFiles.writeUtf8(results.resolve("build.log"), built.transcript)
            assertEquals(built.transcript, 0, built.exitCode)
            val report = results.resolve("application-junit.xml")
            runCommand(host, root, installed.environment, listOf(requireNotNull(installed.node).toString(), "--test", "--test-concurrency=1",
                "--test-reporter=junit", "test/http.test.mjs"), results.resolve("test.log"), 300_000, report)
            assertJunit(report, 34)
            assertEquals("Hono application/test inputs changed", sources, inputHashes(root))
            println("HONO_APPLICATION_TESTS_PASSED=34")
        } finally {
            controller.stop()
            controller.close()
            scope.cancel()
        }
    }

    private suspend fun runCommand(host: AndroidRuntimeHost, root: Path, env: Map<String, String>, command: List<String>, log: Path, timeout: Long, stdoutFile: Path? = null) {
        val child = host.start(command, root, env)
        val outputs = listOf(java.io.ByteArrayOutputStream(), java.io.ByteArrayOutputStream())
        val pumps = listOf(child.stdout, child.stderr).mapIndexed { index, input ->
            Thread {
                input.use {
                    val bytes = ByteArray(8192)
                    while (true) {
                        val count = it.read(bytes)
                        if (count < 0) break
                        synchronized(outputs[index]) { if (outputs[index].size() < 4 * 1024 * 1024) outputs[index].write(bytes, 0, count) }
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        try {
            child.stdin.close()
            val exit = withTimeout(timeout) { child.awaitExit() }
            pumps.forEach { it.join(5000) }
            val texts = outputs.map { output -> synchronized(output) { output.toString(Charsets.UTF_8.name()) } }
            val text = texts.joinToString("\n[stderr]\n")
            RuntimeFiles.writeUtf8(log, text)
            stdoutFile?.let { RuntimeFiles.writeUtf8(it, texts[0]) }
            assertEquals("${command.take(3)} failed:\n$text", 0, exit)
        } finally {
            child.terminate()
            pumps.forEach { it.join(1000) }
        }
    }

    private fun assertJunit(report: Path, expected: Int) {
        assertTrue(Files.size(report) in 1..4L * 1024 * 1024)
        val text = RuntimeFiles.readUtf8(report)
        assertFalse("Entity declarations are not accepted", Regex("(?i)<!\\s*(?:DOCTYPE|ENTITY)").containsMatchIn(text))
        val parser = Xml.newPullParser()
        parser.setInput(text.reader())
        var cases = 0
        var failures = 0
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                if (parser.name == "testcase") cases++
                if (parser.name in setOf("failure", "error", "skipped")) failures++
            }
        }
        assertEquals("Executed test identities", expected, cases)
        assertEquals("Failed, errored or skipped test cases", 0, failures)
    }

    private fun inputHashes(root: Path): Map<String, String> = Files.walk(root).use { paths ->
        val result = linkedMapOf<String, String>()
        paths.filter { Files.isRegularFile(it) }.forEach { path ->
            val name = root.relativize(path).toString().replace('\\', '/')
            if (name.startsWith("src/") || name.startsWith("test/") || name in setOf("package.json", "package-lock.json", "vitest.config.ts", "tsdown.config.ts", "tsconfig.json")) {
                result[name] = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
            }
        }
        result
    }
}
