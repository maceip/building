package dev.ide.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real LOCAL Gradle + Exec + installed Node/npm; no downloaded distribution/headers/dependencies.
 * Requires host Node (with include/node), npm, clang, clang++, ar and Python. Override explicit
 * locations with dev.ide.node, dev.ide.npm.cli, dev.ide.node.headers and dev.ide.native.<tool>.
 * This is host integration coverage, not an Android/ABI compatibility claim.
 */
@Timeout(value = 4, unit = TimeUnit.MINUTES)
class GradleNodeExecTest {
    @TempDir lateinit var root: Path

    @Test
    fun realGradleExecFindsNodeNpmAndBuildsALocalNativeDependencyWithoutChangingJavaEnvironment() {
        val javaHome = Path.of(System.getProperty("java.home"))
        val node = executable("node", "dev.ide.node")
        val npmCli = System.getProperty("dev.ide.npm.cli")?.let(Path::of) ?: executable("npm").toRealPath()
        val headers = System.getProperty("dev.ide.node.headers")?.let(Path::of) ?: node.toRealPath().parent.parent
        assertTrue(Files.isRegularFile(headers.resolve("include/node/node_api.h")), "Install Node's matching headers or set dev.ide.node.headers")
        val interpreter = Path.of("/bin/sh")
        val runtimePrefix = root.resolve("bundled-runtime")
        val runtimeBin = runtimePrefix.resolve("bin")
        Files.createDirectories(runtimeBin)
        Files.createSymbolicLink(runtimeBin.resolve("node"), node.toRealPath())
        // npm lifecycle scripts resolve node-gyp via this real npm installation; no registry involved.
        val nativeTools = mapOf(
            "CC" to executable("clang", "dev.ide.native.cc"),
            "CXX" to executable("clang++", "dev.ide.native.cxx"),
            "AR" to executable("ar", "dev.ide.native.ar"),
            "PYTHON" to executable("python3", "dev.ide.native.python"),
        )
        val runtimeEnvironment = linkedMapOf(
            "PATH" to "$runtimeBin:/usr/bin:/bin",
            "PREFIX" to runtimePrefix.toString(),
            "TERMUX__PREFIX" to runtimePrefix.toString(),
            "npm_config_nodedir" to headers.toString(),
            "npm_config_python" to nativeTools.getValue("PYTHON").toString(),
            "npm_config_script_shell" to interpreter.toString(),
            "npm_config_cache" to root.resolve("runtime-cache").toString(),
            "npm_config_prefix" to root.resolve("runtime-global").toString(),
            "npm_config_offline" to "true", "npm_config_audit" to "false", "npm_config_fund" to "false",
            "npm_config_update_notifier" to "false", "npm_config_foreground_scripts" to "true",
        ).apply { nativeTools.forEach { (key, path) -> put(key, path.toString()) } }
        val scripts = GradleRuntimeCommandBridge.install(root.resolve("commands"), interpreter, node, npmCli, runtimeEnvironment)
        val javaPrefix = root.resolve("java-prefix")
        val home = root.resolve("private-home")
        val temp = root.resolve("tmp")
        val gradleHome = root.resolve("gradle-home")
        listOf(home, temp, gradleHome).forEach(Files::createDirectories)
        val npmConfig = write("private-home/empty.npmrc", "")
        val npmGlobalConfig = write("private-home/empty-global.npmrc", "")
        val base = linkedMapOf(
            "PATH" to "${javaHome.resolve("bin")}:/usr/bin:/bin",
            "JAVA_HOME" to javaHome.toString(), "HOME" to home.toString(), "TMPDIR" to temp.toString(),
            "GRADLE_USER_HOME" to gradleHome.toString(), "PREFIX" to javaPrefix.toString(), "TERMUX__PREFIX" to javaPrefix.toString(),
            "LANG" to "C.UTF-8", "npm_config_userconfig" to npmConfig.toString(), "npm_config_globalconfig" to npmGlobalConfig.toString(),
        )
        val environment = scripts.applyTo(base)
        assertEquals(base.filterKeys { it != "PATH" }, environment.filterKeys { it != "PATH" })
        write("settings.gradle", "rootProject.name = 'gradle-node-native-exec'\n")
        write("build.gradle", buildScript())
        write("package.json", """{"name":"gradle-node-native-exec","version":"1.0.0","private":true,"dependencies":{"gradle-native-fixture":"file:./native-dependency"},"scripts":{"probe":"node check.cjs"}}""")
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
        write("node-probe.cjs", """
            const assert = require('node:assert/strict');
            const fs = require('node:fs');
            const cp = require('node:child_process');
            assert.equal(process.env.PREFIX, ${js(runtimePrefix.toString())});
            assert.equal(process.env.TERMUX__PREFIX, ${js(runtimePrefix.toString())});
            for (const key of ['OPENAI_API_KEY', 'ANTHROPIC_API_KEY', 'NPM_TOKEN']) assert.equal(process.env[key], undefined, key);
            const child = cp.spawnSync(process.execPath, ['-p', 'process.version'], {encoding: 'utf8'});
            assert.equal(child.status, 0, child.stderr);
            fs.writeFileSync('node-probe.json', JSON.stringify({version: process.version, execPath: process.execPath, forkVersion: child.stdout.trim()}));
        """.trimIndent())
        val expectedTools = ToolingClient.encode(runtimeEnvironment.filterKeys { it in nativeTools || it in setOf("npm_config_nodedir", "npm_config_python", "npm_config_script_shell") })
        write("native-dependency/install-probe.cjs", """
            const assert = require('node:assert/strict');
            const fs = require('node:fs');
            const cp = require('node:child_process');
            const expected = $expectedTools;
            for (const [key, value] of Object.entries(expected)) assert.equal(process.env[key], value, key);
            for (const key of ['OPENAI_API_KEY', 'ANTHROPIC_API_KEY', 'NPM_TOKEN']) assert.equal(process.env[key], undefined, key);
            for (const key of ['CC', 'CXX', 'PYTHON']) {
                const child = cp.spawnSync(process.env[key], ['--version'], {encoding: 'utf8'});
                assert.equal(child.status, 0, key + ': ' + child.stderr);
            }
            assert.ok(fs.existsSync(process.env.npm_config_nodedir + '/include/node/node_api.h'));
            fs.writeFileSync('install-environment.json', JSON.stringify(expected));
        """.trimIndent())
        write("check.cjs", """
            const assert = require('node:assert/strict');
            const fs = require('node:fs');
            const addon = require('gradle-native-fixture');
            assert.equal(addon.answer(), 42);
            assert.ok(fs.existsSync('node_modules/gradle-native-fixture/install-environment.json'));
            fs.writeFileSync('npm-script-ok.txt', 'native-addon-answer=42');
        """.trimIndent())
        val events = StringBuilder()
        val jar = requireProperty("dev.ide.tooling.jar")
        val installedGradle = requireProperty("dev.ide.gradle.home")
        val process = ProcessBuilder(javaHome.resolve("bin/java").toString(), "-jar", jar)
            .directory(root.toFile()).redirectError(ProcessBuilder.Redirect.PIPE)
            .apply { environment().clear(); environment().putAll(environment) }.start()
        val client = ToolingClient(process)
        try {
            client.initialize(root.toString(), "LOCAL", installedGradle, javaHome.toString(), null, gradleHome.toString(),
                listOf("--offline", "--no-build-cache", "--no-configuration-cache", "-Dorg.gradle.workers.max=1", "-Dorg.gradle.daemon.idletimeout=1000"),
                listOf("-Xmx384m"), environment)
            client.execute(listOf(":verifyBridge")) { events.append(it).append('\n') }
            assertTrue(Files.isRegularFile(root.resolve("node-probe.json")), events.toString())
            assertTrue(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").containsMatchIn(Files.readString(root.resolve("npm-version.txt"))), events.toString())
            assertEquals("native-addon-answer=42", Files.readString(root.resolve("npm-script-ok.txt")), events.toString())
            assertTrue(Files.size(root.resolve("node_modules/gradle-native-fixture/build/Release/answer.node")) > 0, events.toString())
            assertTrue(Files.readString(root.resolve("java-version.txt")).contains("version"), events.toString())
            assertEquals(javaPrefix.toString(), Files.readString(root.resolve("gradle-prefix.txt")))
            assertFalse(environment.containsKey("CC"), "Runtime compiler env must never replace the Gradle native toolchain")
        } catch (error: ToolingClient.ToolingException) {
            throw AssertionError("${error.message}\n${error.stderr}\n$events", error)
        } finally {
            client.close()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun buildScript(): String = """
        def outputFor = { String name -> new FileOutputStream(file(name)) }
        tasks.register('nodeProbe', Exec) { commandLine 'node', 'node-probe.cjs' }
        tasks.register('npmVersion', Exec) {
            commandLine 'npm', '--version'
            standardOutput = outputFor('npm-version.txt')
        }
        tasks.register('npmInstall', Exec) {
            dependsOn 'nodeProbe', 'npmVersion'
            commandLine 'npm', 'install', '--offline', '--no-audit', '--no-fund', '--foreground-scripts', '--install-links'
        }
        tasks.register('npmScript', Exec) { dependsOn 'npmInstall'; commandLine 'npm', 'run', 'probe' }
        tasks.register('javaProbe', Exec) {
            dependsOn 'npmScript'
            commandLine new File(System.getenv('JAVA_HOME'), 'bin/java').absolutePath, '-version'
            standardOutput = outputFor('java-version.txt'); errorOutput = standardOutput
        }
        tasks.register('verifyBridge') {
            dependsOn 'javaProbe'
            doLast {
                assert System.getenv('PREFIX') == System.getenv('TERMUX__PREFIX')
                assert System.getenv('OPENAI_API_KEY') == null
                assert System.getenv('ANTHROPIC_API_KEY') == null
                assert System.getenv('NPM_TOKEN') == null
                file('gradle-prefix.txt').text = System.getenv('PREFIX')
            }
        }
    """.trimIndent()

    private fun executable(name: String, property: String? = null): Path {
        val explicit = property?.let(System::getProperty)?.let(Path::of)
        if (explicit != null) {
            require(explicit.isAbsolute && Files.isExecutable(explicit)) { "$property is not an executable absolute path" }
            return explicit
        }
        // Prefer real system compiler binaries to HOME-dependent rustup/swiftly-style shims. npm
        // and Node still come from PATH so the installed Node distribution supplies matching headers.
        if (name in setOf("clang", "clang++", "ar") && Files.isExecutable(Path.of("/usr/bin", name))) return Path.of("/usr/bin", name)
        return System.getenv("PATH").orEmpty().split(java.io.File.pathSeparatorChar).filter { it.isNotEmpty() }
            .map { Path.of(it).resolve(name).toAbsolutePath() }.firstOrNull(Files::isExecutable)
            ?: error("Required real test executable '$name' is unavailable; install it${property?.let { " or set $it" }.orEmpty()}")
    }

    private fun requireProperty(name: String): String = System.getProperty(name) ?: error("$name is not set")
    private fun js(text: String): String = ToolingClient.encode(text)
    private fun write(name: String, content: String): Path = root.resolve(name).also {
        Files.createDirectories(it.parent); Files.write(it, content.toByteArray(Charsets.UTF_8))
    }
}
