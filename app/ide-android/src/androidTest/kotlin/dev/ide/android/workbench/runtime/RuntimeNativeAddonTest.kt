package dev.ide.android.workbench.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.workbench.runtime.RuntimeController
import dev.ide.workbench.runtime.RuntimeFiles
import dev.ide.workbench.runtime.RuntimeRunRequest
import dev.ide.workbench.runtime.RuntimeSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/** Builds from source on the device; no registry packages, downloaded headers or precompiled addon. */
@RunWith(AndroidJUnit4::class)
class RuntimeNativeAddonTest {
    @Test fun localNpmTarballBuildsAndLoadsNapiAddonOffline() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val host = AndroidRuntimeHost(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = RuntimeController(host, scope)
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "runtime-native-addon-test-")
        try {
            val installation = host.prepare()
            val prefix = installation.prefix
            assertNotNull("Bundled Node is required", installation.node)
            assertNotNull("Bundled npm is required", installation.npmCli)
            assertEquals("node-gyp must use bundled headers", prefix.toString(), installation.environment["npm_config_nodedir"])
            for (header in listOf("node_api.h", "common.gypi", "config.gypi")) {
                val path = prefix.resolve("include/node/$header")
                assertTrue("Missing bundled Node header/config: $path", Files.isRegularFile(path))
            }
            for ((variable, binary) in mapOf("CC" to "clang", "CXX" to "clang++", "AR" to "llvm-ar", "PYTHON" to "python3")) {
                val path = prefix.resolve("bin/$binary")
                assertTrue("Missing compiler prerequisite: $path", Files.isRegularFile(path))
                assertEquals("$variable must select the bundled tool", path.toString(), installation.environment[variable])
            }
            assertTrue("Missing bundled make: $prefix/bin/make", Files.isRegularFile(prefix.resolve("bin/make")))

            RuntimeFiles.writeUtf8(root.resolve("header-probe.cc"), "#include <node_api.h>\nnapi_status header_probe = napi_ok;\n")
            RuntimeFiles.writeUtf8(root.resolve("probe.cjs"), """
                const assert = require('node:assert/strict');
                const { spawnSync } = require('node:child_process');
                function run(binary, args) {
                    console.log('> ' + binary + ' ' + args.join(' '));
                    const result = spawnSync(binary, args, { encoding: 'utf8', timeout: 15000 });
                    process.stdout.write(result.stdout || '');
                    process.stderr.write(result.stderr || '');
                    if (result.error) throw result.error;
                    assert.equal(result.status, 0, binary + ' failed, signal=' + result.signal);
                }
                run(process.env.CC, ['--version']);
                run(process.env.CXX, ['--version']);
                run(process.env.AR, ['--version']);
                run('make', ['--version']);
                run(process.env.PYTHON, ['--version']);
                run(process.env.CXX, ['-fsyntax-only', '-I' + process.env.npm_config_nodedir + '/include/node', 'header-probe.cc']);
                console.log('native-toolchain-probe-ok');
            """.trimIndent())
            controller.run(RuntimeRunRequest(root, "node:probe.cjs"))
            val probe = awaitSuccess(controller, "Compiler/header probe", 120_000)
            assertTrue(probe.transcript, probe.stdout.contains("native-toolchain-probe-ok"))

            // Offline npm plus host-provided nodedir prevents registry and node-gyp header downloads.
            // Foreground lifecycle output preserves the actual compiler/linker diagnostics on failure.
            RuntimeFiles.writeUtf8(root.resolve(".npmrc"), "offline=true\nforeground-scripts=true\nfetch-retries=0\n")
            val fixture = mapOf(
                "package/package.json" to """{"name":"building-test-native-addon","version":"1.0.0","main":"index.cjs","gypfile":true,"scripts":{"install":"node-gyp rebuild --verbose"}}""",
                "package/binding.gyp" to """{"targets":[{"target_name":"answer","sources":["answer.cc"]}]}""",
                "package/index.cjs" to "module.exports = require('./build/Release/answer.node');\n",
                "package/answer.cc" to """
                    #include <node_api.h>

                    static napi_value Answer(napi_env env, napi_callback_info info) {
                        napi_value value;
                        if (napi_create_int32(env, 42, &value) != napi_ok) return nullptr;
                        return value;
                    }

                    static napi_value Init(napi_env env, napi_value exports) {
                        napi_property_descriptor property = {
                            "answer", nullptr, Answer, nullptr, nullptr, nullptr, napi_default, nullptr
                        };
                        if (napi_define_properties(env, exports, 1, &property) != napi_ok) return nullptr;
                        return exports;
                    }

                    NAPI_MODULE(NODE_GYP_MODULE_NAME, Init)
                """.trimIndent(),
            )
            TarArchiveOutputStream(GZIPOutputStream(Files.newOutputStream(root.resolve("fixture.tgz")))).use { tar ->
                for ((name, text) in fixture) {
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong(); mode = 420 })
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
            RuntimeFiles.writeUtf8(root.resolve("package.json"), """{"name":"offline-native-addon-test","version":"1.0.0","private":true,"dependencies":{"building-test-native-addon":"file:fixture.tgz"},"scripts":{"test":"node check.cjs"}}""")
            RuntimeFiles.writeUtf8(root.resolve("check.cjs"), """
                const assert = require('node:assert/strict');
                const addon = require('building-test-native-addon');
                assert.equal(typeof addon.answer, 'function');
                assert.equal(addon.answer(), 42);
                console.log('native-addon-answer=42');
            """.trimIndent())
            val addon = root.resolve("node_modules/building-test-native-addon/build/Release/answer.node")
            assertFalse("Fresh fixture must not contain a prebuilt addon", Files.exists(addon))
            controller.installDependencies(root)
            val installed = awaitSuccess(controller, "Offline npm install / node-gyp rebuild", 180_000)
            assertFalse(installed.transcript, installed.workspaceCopy)
            assertTrue(installed.transcript, Files.isRegularFile(root.resolve("package-lock.json")))
            assertTrue("node-gyp did not produce $addon\n${installed.transcript}", Files.isRegularFile(addon))
            assertTrue("Compiled addon is empty: $addon", Files.size(addon) > 0)

            controller.run(RuntimeRunRequest(root, "npm:test"))
            val result = awaitSuccess(controller, "Load compiled N-API addon via npm test", 60_000)
            assertTrue(result.transcript, result.stdout.contains("native-addon-answer=42"))
        } finally {
            controller.stop()
            controller.close()
            scope.cancel()
        }
    }

    private suspend fun awaitSuccess(controller: RuntimeController, stage: String, timeoutMs: Long): RuntimeSessionState {
        val result = withTimeoutOrNull(timeoutMs) { controller.state.first { !it.isRunning } }
            ?: throw AssertionError("$stage timed out after ${timeoutMs}ms\n${diagnostics(controller.state.value)}")
        assertEquals("$stage\n${diagnostics(result)}", 0, result.exitCode)
        return result
    }

    private fun diagnostics(state: RuntimeSessionState): String =
        "Workspace: ${state.workspacePath ?: state.projectRoot}\nPhase: ${state.phase}; exit: ${state.exitCode}; error: ${state.error}\n${state.transcript}"
}
