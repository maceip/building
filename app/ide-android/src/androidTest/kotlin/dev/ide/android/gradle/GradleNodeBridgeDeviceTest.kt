package dev.ide.android.gradle

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.workbench.runtime.AndroidRuntimeHost
import dev.ide.gradle.GradleProcessEnvironment
import dev.ide.gradle.JdkSelection
import dev.ide.gradle.ToolingClient
import dev.ide.workbench.runtime.RuntimeFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real two-prefix Android Gradle Exec acceptance, not a fake Node or native ABI compatibility claim.
 * Requires an ALREADY installed Gradle prefix and instrumentation argument installedGradle pointing
 * to an extracted local distribution. Nothing here provisions Gradle or downloads runtime/packages.
 */
@RunWith(AndroidJUnit4::class)
class GradleNodeBridgeDeviceTest {
    @Test(timeout = 240_000)
    fun installedGradleExecUsesBundledNodeNpmAndNativeDependencyTools() {
        runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installed = InstrumentationRegistry.getArguments().getString("installedGradle")
            ?: error("Pass installedGradle for an already installed local Gradle distribution; this test never downloads one")
        val distribution = Path.of(installed)
        assertTrue("Missing installed Gradle distribution", distribution.isAbsolute && Files.isDirectory(distribution.resolve("lib")))
        val runtimeHost = AndroidRuntimeHost(context)
        val runtime = runtimeHost.prepare()
        val host = AndroidGradleHost(context, AndroidGradleRuntimeBridge(context, runtimeHost)::prepare)
        assertTrue("The Gradle prefix must already be installed", host.available())
        val javaHome = requireNotNull(host.javaHome(JdkSelection.Image.JDK_17))
        val scripts = host.prepareScriptEnvironment()
        val environment = GradleProcessEnvironment.create(host, javaHome, scripts)
        assertNotEquals("This test exercises distinct Gradle and Node prefixes", host.prefix(), runtime.prefix)
        assertEquals(host.prefix().toString(), environment["PREFIX"])
        assertEquals(host.prefix().toString(), environment["TERMUX__PREFIX"])
        assertFalse("Do not put the runtime linker into the JVM", environment.getValue("LD_PRELOAD").contains(runtime.prefix.toString()))
        assertFalse("Do not replace the Gradle library search path", environment.getValue("LD_LIBRARY_PATH").contains(runtime.prefix.toString()))
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "gradle-node-bridge-")
        val report = context.filesDir.toPath().resolve("gradle-acceptance/node-bridge.log")
        Files.createDirectories(report.parent)
        fun write(name: String, content: String) {
            val path = root.resolve(name)
            Files.createDirectories(path.parent)
            RuntimeFiles.writeUtf8(path, content)
        }
        RuntimeFiles.writeUtf8(report, "Project: $root\nDistribution: $distribution\nGradle prefix: ${host.prefix()}\nRuntime prefix: ${runtime.prefix}\n")
        write("settings.gradle", "rootProject.name = 'android-gradle-node-bridge'\n")
        val pnpmTask = if (Files.isRegularFile(scripts.pathEntries.single().resolve("pnpm"))) ", 'pnpmVersion'" else ""
        write("build.gradle", """
            def outputFor = { String name -> new FileOutputStream(file(name)) }
            tasks.register('nodeProbe', Exec) { commandLine 'node', 'probe.cjs' }
            tasks.register('npmVersion', Exec) { commandLine 'npm', '--version'; standardOutput = outputFor('npm-version.txt') }
            tasks.register('pnpmVersion', Exec) { commandLine 'pnpm', '--version'; standardOutput = outputFor('pnpm-version.txt') }
            tasks.register('npmInstall', Exec) {
                dependsOn 'nodeProbe', 'npmVersion'$pnpmTask
                commandLine 'npm', 'install', '--offline', '--no-audit', '--no-fund', '--foreground-scripts', '--install-links'
            }
            tasks.register('npmScript', Exec) { dependsOn 'npmInstall'; commandLine 'npm', 'run', 'check' }
            tasks.register('javaProbe', Exec) {
                dependsOn 'npmScript'
                commandLine new File(System.getenv('JAVA_HOME'), 'bin/java').absolutePath, '-version'
                standardOutput = outputFor('java-version.txt'); errorOutput = standardOutput
            }
            tasks.register('shellProbe', Exec) {
                dependsOn 'javaProbe'
                commandLine 'sh', '-c', 'java -version && node --version && npm --version'
                standardOutput = outputFor('shell-version.txt'); errorOutput = standardOutput
            }
            tasks.register('verifyBridge') {
                dependsOn 'shellProbe'
                doLast {
                    assert System.getenv('PREFIX') == System.getenv('TERMUX__PREFIX')
                    assert System.getenv('OPENAI_API_KEY') == null
                    assert System.getenv('ANTHROPIC_API_KEY') == null
                    assert System.getenv('NPM_TOKEN') == null
                    file('gradle-prefix.txt').text = System.getenv('PREFIX')
                }
            }
        """.trimIndent())
        write("package.json", """{"name":"android-gradle-node-bridge","version":"1.0.0","private":true,"dependencies":{"gradle-native-fixture":"file:./native-dependency"},"scripts":{"check":"node check.cjs"}}""")
        write(".npmrc", "offline=true\nfetch-retries=0\nforeground-scripts=true\n")
        write("native-dependency/package.json", """{"name":"gradle-native-fixture","version":"1.0.0","main":"index.cjs","gypfile":true,"scripts":{"install":"node install-probe.cjs && node-gyp rebuild --verbose"}}""")
        write("native-dependency/binding.gyp", """{"targets":[{"target_name":"answer","sources":["answer.cc"]}]}""")
        write("native-dependency/index.cjs", "module.exports = require('./build/Release/answer.node');\n")
        write("native-dependency/answer.cc", """
            #include <node_api.h>
            static napi_value Answer(napi_env env, napi_callback_info info) {
                napi_value value; if (napi_create_int32(env, 42, &value) != napi_ok) return nullptr; return value;
            }
            static napi_value Init(napi_env env, napi_value exports) {
                napi_property_descriptor property = {"answer", nullptr, Answer, nullptr, nullptr, nullptr, napi_default, nullptr};
                if (napi_define_properties(env, exports, 1, &property) != napi_ok) return nullptr; return exports;
            }
            NAPI_MODULE(NODE_GYP_MODULE_NAME, Init)
        """.trimIndent())
        val tools = listOf("CC", "CXX", "AR", "PYTHON", "npm_config_nodedir", "npm_config_python", "npm_config_script_shell")
        tools.forEach { assertTrue("Required bundled native development environment: $it", runtime.environment.containsKey(it)) }
        val expected = ToolingClient.encode(runtime.environment.filterKeys { it in tools || it in setOf("PREFIX", "TERMUX__PREFIX", "LD_LIBRARY_PATH", "LD_PRELOAD") })
        val probe = """
            const assert = require('node:assert/strict');
            const fs = require('node:fs');
            const cp = require('node:child_process');
            const expected = $expected;
            for (const [key, value] of Object.entries(expected)) assert.equal(process.env[key], value, key);
            for (const key of ['OPENAI_API_KEY', 'ANTHROPIC_API_KEY', 'NPM_TOKEN']) assert.equal(process.env[key], undefined, key);
            for (const binary of [process.execPath, process.env.CC, process.env.CXX, process.env.AR, process.env.PYTHON, 'make']) {
                const result = cp.spawnSync(binary, ['--version'], {encoding: 'utf8', timeout: 15000});
                if (result.error) throw result.error;
                assert.equal(result.status, 0, binary + ': ' + result.stderr);
            }
            assert.ok(fs.existsSync(process.env.npm_config_nodedir + '/include/node/node_api.h'));
            fs.writeFileSync('native-environment-ok.json', JSON.stringify(expected));
        """.trimIndent()
        write("probe.cjs", probe)
        write("native-dependency/install-probe.cjs", probe)
        write("check.cjs", """
            const assert = require('node:assert/strict');
            const fs = require('node:fs');
            assert.equal(require('gradle-native-fixture').answer(), 42);
            assert.ok(fs.existsSync('node_modules/gradle-native-fixture/native-environment-ok.json'));
            fs.writeFileSync('native-addon-ok.txt', 'answer=42');
        """.trimIndent())
        val timedOut = AtomicBoolean(false)
        val executor = Executors.newSingleThreadScheduledExecutor { action -> Thread(action, "gradle-node-bridge-timeout").apply { isDaemon = true } }
        val client = host.openClient(javaHome, environment)
        val watchdog = executor.schedule({
            timedOut.set(true)
            runCatching { client.cancel() }
        }, 180, TimeUnit.SECONDS)
        try {
            client.initialize(root.toString(), "LOCAL", distribution.toString(), javaHome.toString(), host.androidHome().toString(),
                root.resolve(".gradle-home").toString(),
                listOf("--offline", "--no-build-cache", "--no-configuration-cache", "-Dorg.gradle.workers.max=1", "-Dorg.gradle.daemon.idletimeout=1000"),
                listOf("-Xmx384m", "-Djava.io.tmpdir=${environment.getValue("TMPDIR")}", "-Duser.home=${environment.getValue("HOME")}"), environment)
            client.execute(listOf(":verifyBridge"), isCanceled = { timedOut.get() }) { event ->
                Files.newBufferedWriter(report, Charsets.UTF_8, java.nio.file.StandardOpenOption.APPEND).use { it.appendLine(event.toString()) }
            }
            assertFalse("Timed out; see $report", timedOut.get())
            assertEquals("answer=42", RuntimeFiles.readUtf8(root.resolve("native-addon-ok.txt")))
            assertTrue(Files.size(root.resolve("node_modules/gradle-native-fixture/build/Release/answer.node")) > 0)
            assertTrue(RuntimeFiles.readUtf8(root.resolve("java-version.txt")).contains("version"))
            assertTrue(RuntimeFiles.readUtf8(root.resolve("shell-version.txt")).contains("version"))
            assertTrue(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").containsMatchIn(RuntimeFiles.readUtf8(root.resolve("npm-version.txt"))))
            assertEquals(host.prefix().toString(), RuntimeFiles.readUtf8(root.resolve("gradle-prefix.txt")))
            Files.newBufferedWriter(report, Charsets.UTF_8, java.nio.file.StandardOpenOption.APPEND).use { it.appendLine("PASS") }
        } catch (error: Throwable) {
            Files.newBufferedWriter(report, Charsets.UTF_8, java.nio.file.StandardOpenOption.APPEND).use { it.appendLine(error.stackTraceToString()) }
            throw error
        } finally {
            watchdog.cancel(true)
            executor.shutdownNow()
            client.close()
        }
        }
    }
}
