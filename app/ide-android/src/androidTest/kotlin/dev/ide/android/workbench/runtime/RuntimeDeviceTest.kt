package dev.ide.android.workbench.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.workbench.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.util.zip.GZIPOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

/** Offline real-process acceptance, including local npm tarball installation and lifecycle hooks. */
@RunWith(AndroidJUnit4::class)
class RuntimeDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun bundledRuntimesProbeOffline() = runBlocking {
        val controller = controller()
        try {
            val report = controller.probe()
            assertEquals("arm64-v8a", report.abi)
            assertTrue(report.toString(), report.node?.available == true)
            assertTrue(report.toString(), report.npm?.available == true)
            assertTrue(report.toString(), report.python?.available == true)
            assertTrue(report.toString(), report.shell?.available == true)
            assertTrue(report.toString(), report.xtool?.available == true)
        } finally { controller.close() }
    }

    @Test fun swiftAndXtoolProbeAndExecutionWorkOffline() = runBlocking {
        val controller = controller()
        val parent = Files.createTempDirectory(context.cacheDir.toPath(), "runtime-swift-test-")
        try {
            val project = Files.createDirectories(parent.resolve("SwiftApp"))
            RuntimeFiles.writeUtf8(project.resolve("Package.swift"), """
                // swift-tools-version: 5.9
                import PackageDescription

                let package = Package(
                    name: "SwiftApp",
                    targets: [
                        .executableTarget(name: "SwiftApp", path: "Sources")
                    ]
                )
            """.trimIndent())
            val sources = Files.createDirectories(project.resolve("Sources"))
            RuntimeFiles.writeUtf8(sources.resolve("main.swift"), """
                print("Hello from Swift and XTool on Android!")
            """.trimIndent())

            val targets = controller.targets(project)
            assertTrue(targets.toString(), targets.any { it.kind == RuntimeKind.XTOOL })

            // Test execution through controller
            controller.run(RuntimeRunRequest(project, "xtool:build", listOf("--version")))
            val result = withTimeout(60_000) { controller.state.first { !it.isRunning } }
            assertEquals(result.toString(), 0, result.exitCode)
            assertTrue(result.stdout, result.stdout.contains("xtool") || result.transcript.contains("xtool"))
        } finally { controller.stop(); controller.close() }
    }

    @Test fun nodeHttpNpmAndStopAreRealOfflineProcesses() = runBlocking {
        val controller = controller()
        val parent = Files.createTempDirectory(context.cacheDir.toPath(), "runtime-node-test-")
        try {
            val sample = controller.seedSample(parent, "node-http")
            controller.run(RuntimeRunRequest(sample, "npm:start"))
            val ready = withTimeout(60_000) { controller.state.first { it.previewUrl != null || !it.isRunning } }
            assertNotNull(ready.toString(), ready.previewUrl)
            val address = ready.previewUrl!!
            val response = withContext(Dispatchers.IO) { URL(address).openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }.getInputStream().bufferedReader().use { it.readText() } }
            assertTrue(response, response.contains("Node is running offline"))
            assertFalse(ready.toString(), ready.workspaceCopy)
            assertTrue(ready.toString(), Files.isRegularFile(sample.resolve("last-run.json")))
            withTimeout(10_000) { controller.stop() }
            assertFalse(controller.state.value.isRunning)
            val stillListening = withContext(Dispatchers.IO) { runCatching { URL(address).openConnection().apply { connectTimeout = 500; readTimeout = 500 }.getInputStream().use { it.read() } }.isSuccess }
            assertFalse("Stop must close the child Node listener, not only npm", stillListening)
        } finally { controller.stop(); controller.close() }
    }

    @Test fun npmShebangAndProjectBinScriptsExecuteThroughThePrefix() = runBlocking {
        val controller = controller()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "runtime-shebang-test-")
        try {
            val bin = Files.createDirectories(root.resolve("node_modules/.bin"))
            val projectCommand = root.resolve("project-tool.cjs")
            RuntimeFiles.writeUtf8(projectCommand, "#!/usr/bin/env node\nconst child = require('node:child_process').spawnSync(process.execPath, ['-e', \"process.stdout.write('child-from-shebang')\"], { encoding: 'utf8' }); require('node:assert/strict').equal(child.status, 0, child.stderr); require('node:assert/strict').equal(child.stdout, 'child-from-shebang'); console.log('project-bin-ok');\n")
            projectCommand.toFile().setExecutable(true, true)
            Files.createSymbolicLink(bin.resolve("project-tool"), projectCommand)
            RuntimeFiles.writeUtf8(root.resolve("probe.sh"), "set -e\nnpm --version\nnpx --version\n./node_modules/.bin/project-tool\nnode -e 'console.log(process.platform)'\n")
            controller.run(RuntimeRunRequest(root, "shell:probe.sh"))
            val result = withTimeout(60_000) { controller.state.first { !it.isRunning } }
            assertEquals(result.toString(), 0, result.exitCode)
            assertTrue(result.toString(), result.stdout.split("11.20.0").size >= 3)
            assertTrue(result.toString(), result.stdout.contains("project-bin-ok"))
            assertTrue(result.stdout, result.stdout.contains("android"))
        } finally { controller.stop(); controller.close() }
    }

    @Test fun nodeCliWorkersChildProcessesAndCryptoWorkOffline() = runBlocking {
        val controller = controller()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "runtime-node-core-test-")
        try {
            RuntimeFiles.writeUtf8(root.resolve("core.cjs"), """
                const assert = require('node:assert/strict');
                const crypto = require('node:crypto');
                const { Worker, isMainThread, parentPort } = require('node:worker_threads');
                const { spawnSync } = require('node:child_process');
                assert.equal(crypto.createHash('sha256').update('abc').digest('hex'), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
                assert.equal(crypto.randomBytes(32).length, 32);
                if (!isMainThread) {
                    parentPort.postMessage({ answer: 42, platform: process.platform });
                } else {
                    assert.deepEqual(process.argv.slice(2), ['one two', '--flag']);
                    const child = spawnSync(process.execPath, ['-e', "process.stdout.write('child-ok'); process.stderr.write('child-stderr')"], { encoding: 'utf8', timeout: 10000 });
                    if (child.error) throw child.error;
                    assert.equal(child.status, 0, child.stderr);
                    assert.equal(child.stdout, 'child-ok');
                    assert.equal(child.stderr, 'child-stderr');
                    let received = false;
                    const worker = new Worker(__filename);
                    const timer = setTimeout(() => { console.error('worker timeout'); process.exit(1); }, 10000);
                    worker.on('error', error => { console.error(error); process.exit(1); });
                    worker.on('message', message => {
                        assert.deepEqual(message, { answer: 42, platform: 'android' });
                        received = true;
                    });
                    worker.on('exit', code => {
                        clearTimeout(timer);
                        assert.equal(code, 0);
                        assert.equal(received, true);
                        console.log('node-cli-worker-child-crypto-ok');
                    });
                }
            """.trimIndent())
            controller.run(RuntimeRunRequest(root, "node:core.cjs", listOf("one two", "--flag")))
            val result = withTimeout(60_000) { controller.state.first { !it.isRunning } }
            assertEquals(result.toString(), 0, result.exitCode)
            assertTrue(result.stdout, result.stdout.contains("node-cli-worker-child-crypto-ok"))
        } finally { controller.stop(); controller.close() }
    }

    @Test fun pythonInputSqliteAndNestedExecutionWorkOffline() = runBlocking {
        val controller = controller()
        val parent = Files.createTempDirectory(context.cacheDir.toPath(), "runtime-python-test-")
        try {
            val sample = controller.seedSample(parent, "python")
            RuntimeFiles.writeUtf8(sample.resolve("child.py"), "import subprocess,sys,ssl,sqlite3,ctypes,readline\nr=subprocess.run([sys.executable,'-c','print(42)'],capture_output=True,text=True,check=True)\nprint(r.stdout.strip(),flush=True)\n")
            controller.run(RuntimeRunRequest(sample, "python:child.py"))
            val child = withTimeout(60_000) { controller.state.first { !it.isRunning } }
            assertEquals(child.toString(), 0, child.exitCode)
            assertTrue(child.stdout, child.stdout.contains("42"))
            controller.run(RuntimeRunRequest(sample, "python:main.py"))
            withTimeout(60_000) { controller.state.first { it.stdout.contains("Name (or send EOF)") || !it.isRunning } }
            assertTrue(controller.state.value.toString(), controller.state.value.acceptsInput)
            controller.sendInput("Android")
            val result = withTimeout(60_000) { controller.state.first { !it.isRunning } }
            assertEquals(result.toString(), 0, result.exitCode)
            assertTrue(result.stdout, result.stdout.contains("Hello Android"))
            assertFalse(result.toString(), result.workspaceCopy)
            assertTrue(result.toString(), RuntimeFiles.readUtf8(sample.resolve("result.json")).contains("Hello Android"))
            assertTrue(Files.size(sample.resolve("sample.sqlite")) > 0)
        } finally { controller.stop(); controller.close() }
    }

    @Test fun explicitNpmInstallUsesARealOfflineDependencyAndRunsLifecycleHooks() = runBlocking {
        val controller = controller()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "runtime-npm-install-test-")
        try {
            // Only this fixture opts into offline mode; production npm must not force it.
            RuntimeFiles.writeUtf8(root.resolve(".npmrc"), "offline=true\n")
            val fixture = mapOf(
                "package/package.json" to """{"name":"building-test-dependency","version":"1.0.0","main":"index.js","scripts":{"postinstall":"node -e \"require('node:fs').writeFileSync('HOOK_RAN','postinstall-ok')\""}}""",
                "package/index.js" to "module.exports = name => 'Hello ' + name + ' from installed dependency';\n",
            )
            TarArchiveOutputStream(GZIPOutputStream(Files.newOutputStream(root.resolve("fixture.tgz")))).use { tar ->
                for ((name, text) in fixture) {
                    val bytes = text.toByteArray()
                    val entry = TarArchiveEntry(name).apply { size = bytes.size.toLong(); mode = 420 }
                    tar.putArchiveEntry(entry); tar.write(bytes); tar.closeArchiveEntry()
                }
            }
            RuntimeFiles.writeUtf8(root.resolve("package.json"), """{"name":"offline-install-test","version":"1.0.0","private":true,"dependencies":{"building-test-dependency":"file:fixture.tgz"},"scripts":{"pretest":"node hook.cjs pretest","test":"node check.cjs","posttest":"node hook.cjs posttest"}}""")
            RuntimeFiles.writeUtf8(root.resolve("hook.cjs"), "const assert = require('node:assert/strict'); const phase = process.argv[2]; assert.equal(process.env.npm_lifecycle_event, phase); require('node:fs').appendFileSync('hooks.log', phase + '\\n'); console.log('npm-' + phase + '-ok');\n")
            RuntimeFiles.writeUtf8(root.resolve("check.cjs"), "const fs = require('node:fs'); require('node:assert/strict').equal(fs.readFileSync('hooks.log', 'utf8'), 'pretest\\n'); fs.appendFileSync('hooks.log', 'test\\n'); console.log(require('building-test-dependency')('Android'));\n")
            controller.installDependencies(root)
            val installed = withTimeout(90_000) { controller.state.first { !it.isRunning } }
            assertEquals(installed.toString(), 0, installed.exitCode)
            assertFalse(installed.toString(), installed.workspaceCopy)
            assertTrue(installed.toString(), Files.isRegularFile(root.resolve("package-lock.json")))
            assertEquals("postinstall-ok", RuntimeFiles.readUtf8(root.resolve("node_modules/building-test-dependency/HOOK_RAN")))
            controller.run(RuntimeRunRequest(root, "npm:test"))
            val result = withTimeout(30_000) { controller.state.first { !it.isRunning } }
            assertEquals(result.toString(), 0, result.exitCode)
            assertTrue(result.stdout, result.stdout.contains("Hello Android from installed dependency"))
            assertTrue(result.stdout, result.stdout.contains("npm-pretest-ok"))
            assertTrue(result.stdout, result.stdout.contains("npm-posttest-ok"))
            assertEquals("pretest\ntest\nposttest\n", RuntimeFiles.readUtf8(root.resolve("hooks.log")))
        } finally { controller.stop(); controller.close() }
    }

    @Test fun externalWorkspaceCopyHasExplicitConflictSafeOutputSync() = runBlocking {
        val controller = controller()
        val external = context.getExternalFilesDir(null) ?: error("External files directory unavailable")
        val parent = Files.createTempDirectory(external.toPath(), "runtime-copy-test-")
        try {
            val sample = controller.seedSample(parent, "node-http")
            RuntimeFiles.writeUtf8(sample.resolve("write.cjs"), "require('node:fs').writeFileSync('output.txt','guest'); console.log('done');\n")
            controller.run(RuntimeRunRequest(sample, "node:write.cjs"))
            val result = withTimeout(60_000) { controller.state.first { !it.isRunning } }
            assertEquals(result.toString(), 0, result.exitCode)
            assertTrue(result.workspaceCopy)
            assertFalse(Files.exists(sample.resolve("output.txt")))
            assertEquals(1, controller.syncBack().copied)
            assertEquals("guest", RuntimeFiles.readUtf8(sample.resolve("output.txt")))
        } finally { controller.stop(); controller.close() }
    }

    @Test fun stopDoesNotWaitForAChildThatNeverReadsItsFullInputPipe() = runBlocking {
        val controller = controller()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "runtime-stdin-stop-test-")
        try {
            RuntimeFiles.writeUtf8(root.resolve("wait.cjs"), "console.log('ready'); setInterval(() => {}, 1000);\n")
            controller.run(RuntimeRunRequest(root, "node:wait.cjs"))
            withTimeout(30_000) { controller.state.first { it.stdout.contains("ready") || !it.isRunning } }
            assertTrue(controller.state.value.toString(), controller.state.value.isRunning)
            controller.sendInput("x".repeat(64 * 1024))
            delay(150)
            withTimeout(5_000) { controller.stop() }
            assertFalse(controller.state.value.isRunning)
        } finally { controller.stop(); controller.close() }
    }

    @Test fun staticPreviewRunsWithoutProvisioningAndHasNoPrivilegedBridge() = runBlocking {
        val controller = controller()
        val parent = Files.createTempDirectory(context.cacheDir.toPath(), "runtime-static-test-")
        try {
            val sample = controller.seedSample(parent, "static-web")
            controller.run(RuntimeRunRequest(sample, "static:index.html"))
            val result = withTimeout(10_000) { controller.state.first { it.previewUrl != null || !it.isRunning } }
            assertNotNull(result.toString(), result.previewUrl)
            val connection = URL(result.previewUrl).openConnection() as HttpURLConnection
            assertEquals(200, connection.responseCode)
            assertTrue(connection.inputStream.bufferedReader().use { it.readText() }.contains("Offline web preview"))
            connection.disconnect()
        } finally { controller.stop(); controller.close() }
    }

    private fun controller() = RuntimeController(AndroidRuntimeHost(context), CoroutineScope(SupervisorJob() + Dispatchers.IO))
}
