package dev.ide.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GradleScriptEnvironmentTest {
    @TempDir lateinit var root: Path

    @Test
    fun commandSearchOnlyChangesPathAndIsIdempotent() {
        val prefix = root.resolve("usr")
        val java = prefix.resolve("lib/jvm/java-17-openjdk")
        val libraries = root.resolve("apk-libraries")
        Files.createDirectories(libraries)
        Files.write(libraries.resolve("libsdk-exec-prefix.so"), byteArrayOf(1))
        val original = PrefixProbe.environment(prefix, java, listOf(libraries))
        val before = original.toMap()
        val scripts = GradleScriptEnvironment(listOf(root.resolve("bridge/bin"), root.resolve("bridge/bin")))
        val result = scripts.applyTo(original)
        assertEquals(before, original, "Never mutate the Gradle prefix environment")
        assertEquals(before.filterKeys { it != "PATH" }, result.filterKeys { it != "PATH" })
        assertEquals("${root.resolve("bridge/bin")}${File.pathSeparator}${before.getValue("PATH")}", result["PATH"])
        assertEquals(result, scripts.applyTo(result))
        assertEquals(prefix.toString(), result["TERMUX__PREFIX"])
        assertEquals("$libraries/libsdk-exec-prefix.so:$prefix/lib/libtermux-exec.so", result["LD_PRELOAD"])
        assertEquals("$prefix/lib:$libraries", result["LD_LIBRARY_PATH"])
        assertFailsWith<IllegalArgumentException> { GradleScriptEnvironment(listOf(Path.of("relative"))) }
        assertFailsWith<IllegalArgumentException> { GradleScriptEnvironment(listOf(root.resolve("bad:entry"))) }
    }

    @Test
    fun androidEnvironmentCopiesOnlySystemRootsNotHostCredentialsOrRuntimeLinkerState() {
        val prefix = root.resolve("usr")
        val java = root.resolve("jdk")
        val host = object : GradleHost by DesktopGradleHost(root.resolve("host")) {
            override val android = true
            override fun prefix(): Path = prefix
        }
        val scripts = GradleScriptEnvironment(listOf(root.resolve("bridge/bin")))
        val result = GradleProcessEnvironment.create(host, java, scripts)
        assertEquals(prefix.toString(), result["PREFIX"])
        assertEquals(prefix.toString(), result["TERMUX__PREFIX"])
        assertEquals(java.toString(), result["JAVA_HOME"])
        assertFalse(result.containsKey("OPENAI_API_KEY"))
        assertFalse(result.containsKey("ANTHROPIC_API_KEY"))
        assertFalse(result.containsKey("NPM_TOKEN"))
        assertFalse(result.containsKey("HTTP_PROXY"))
        for (name in listOf("ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT")) {
            assertEquals(System.getenv(name), result[name])
        }
    }

    @Test
    fun childWrapperChangesRuntimeOnlyAndPreservesArgumentsTaskVariablesAndPrivateNpmState() {
        val executable = writeExecutable("runtime/node", """
            #!/bin/sh
            printf '%s\n' "${'$'}PREFIX" "${'$'}TERMUX__PREFIX" "${'$'}CC" "${'$'}CXX" "${'$'}AR" "${'$'}PYTHON" "${'$'}npm_config_nodedir" "${'$'}npm_config_python" "${'$'}npm_config_script_shell" "${'$'}npm_config_cache" "${'$'}HOME" "${'$'}TMPDIR" "${'$'}TASK_VALUE" "${'$'}ANDROID_ROOT" "${'$'}PATH"
            printf '%s\n' "${'$'}@"
        """.trimIndent() + "\n")
        val npm = write("runtime/npm-cli.js", "fixture\n")
        write("runtime/npx-cli.js", "fixture\n")
        val runtime = linkedMapOf(
            "PATH" to "${root.resolve("runtime/npm-global/bin")}:${root.resolve("runtime/bin")}",
            "PREFIX" to root.resolve("runtime").toString(),
            "TERMUX__PREFIX" to root.resolve("runtime").toString(),
            "CC" to "/runtime/clang", "CXX" to "/runtime/clang++", "AR" to "/runtime/llvm-ar", "PYTHON" to "/runtime/python3",
            "npm_config_nodedir" to "/runtime/headers", "npm_config_python" to "/runtime/python3",
            "npm_config_script_shell" to "/runtime/sh", "npm_config_cache" to "/runtime/default-cache",
            "HOME" to "/runtime/default-home", "TMPDIR" to "/runtime/default-tmp",
        )
        val scripts = GradleRuntimeCommandBridge.install(root.resolve("commands with 'quote"), Path.of("/bin/sh"), executable, npm, runtime)
        val original = mapOf(
            "PATH" to "/usr/bin:/bin", "PREFIX" to "/gradle/prefix", "TERMUX__PREFIX" to "/gradle/prefix",
            "CC" to "/gradle/compiler", "HOME" to "/repair/private-home", "TMPDIR" to "/repair/private-tmp",
            "npm_config_cache" to "/repair/private-cache", "TASK_VALUE" to "explicit project value", "ANDROID_ROOT" to "/system",
        )
        val prepared = scripts.applyTo(original)
        val arguments = listOf("", "space and 'quote", "\$(touch should-not-exist)", "line\nbreak")
        val output = run(listOf(scripts.pathEntries.single().resolve("npm").toString()) + arguments, prepared)
        val expected = listOf(runtime.getValue("PREFIX"), runtime.getValue("TERMUX__PREFIX"), "/runtime/clang", "/runtime/clang++",
            "/runtime/llvm-ar", "/runtime/python3", "/runtime/headers", "/runtime/python3", "/runtime/sh",
            "/repair/private-cache", "/repair/private-home", "/repair/private-tmp", "explicit project value", "/system",
            "${runtime.getValue("PATH")}:${prepared.getValue("PATH")}", npm.toString()) + arguments
        assertEquals(expected.joinToString("\n", postfix = "\n"), output)
        assertEquals("/gradle/prefix", prepared["PREFIX"])
        assertEquals("/gradle/compiler", prepared["CC"])
        assertFalse(Files.exists(root.resolve("should-not-exist")))
        assertTrue(Files.isExecutable(scripts.pathEntries.single().resolve("node")))
        assertTrue(Files.isExecutable(scripts.pathEntries.single().resolve("npm")))
        assertTrue(Files.isExecutable(scripts.pathEntries.single().resolve("npx")))
        assertEquals(scripts.pathEntries,
            GradleRuntimeCommandBridge.install(root.resolve("commands with 'quote"), Path.of("/bin/sh"), executable, npm, runtime).pathEntries)
    }

    @Test
    fun wrapperValuesAreQuotedAndRuntimeFailureCannotSilentlyOmitNode() {
        val executable = writeExecutable("node", "#!/bin/sh\nprintf '%s\\n' \"\$CC\" \"\$npm_config_cache\"\n")
        val npm = write("npm-cli.js", "fixture")
        val value = "compiler 'quoted' \$(touch ${root.resolve("injected")})\nsecond line"
        val scripts = GradleRuntimeCommandBridge.install(root.resolve("bridge"), Path.of("/bin/sh"), executable, npm,
            mapOf("PATH" to "/bin", "CC" to value, "npm_config_cache" to value))
        assertEquals("$value\n$value\n", run(listOf(scripts.pathEntries.single().resolve("node").toString()), mapOf("PATH" to "/bin")))
        assertFalse(Files.exists(root.resolve("injected")))
        assertFailsWith<IllegalArgumentException> {
            GradleRuntimeCommandBridge.install(root.resolve("bridge"), Path.of("/bin/sh"), root.resolve("missing-node"), npm, mapOf("PATH" to "/bin"))
        }
        assertFailsWith<IllegalArgumentException> {
            GradleRuntimeCommandBridge.install(root.resolve("bridge"), Path.of("/bin/sh"), executable, npm, mapOf("PATH" to "/bin", "BAD;KEY" to "oops"))
        }
    }

    @Test
    fun bundledExtraNodeCommandsUseTheSameRuntimeEnvironmentAndAbsoluteEntryPoints() {
        val node = writeExecutable("node", "#!/bin/sh\nprintf '%s\\n' \"\$PREFIX\" \"\$@\"\n")
        val npm = write("npm-cli.js", "fixture")
        val pnpm = write("pnpm.cjs", "fixture")
        val scripts = GradleRuntimeCommandBridge.install(root.resolve("bridge"), Path.of("/bin/sh"), node, npm,
            mapOf("PATH" to "/bin", "PREFIX" to "/runtime"), mapOf("pnpm" to pnpm))
        assertEquals("/runtime\n$pnpm\n--version\n", run(listOf(scripts.pathEntries.single().resolve("pnpm").toString(), "--version"), mapOf("PATH" to "/bin")))
        assertFailsWith<IllegalArgumentException> {
            GradleRuntimeCommandBridge.install(root.resolve("bridge"), Path.of("/bin/sh"), node, npm,
                mapOf("PATH" to "/bin"), mapOf("../escape" to pnpm))
        }
    }

    private fun write(name: String, content: String): Path = root.resolve(name).also {
        Files.createDirectories(it.parent)
        Files.write(it, content.toByteArray(Charsets.UTF_8))
    }

    private fun writeExecutable(name: String, content: String): Path = write(name, content).also {
        check(it.toFile().setExecutable(true, true))
    }

    private fun run(command: List<String>, environment: Map<String, String>): String {
        val output = Files.createTempFile(root, "bridge-output", ".txt")
        val process = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile())
            .apply { environment().clear(); environment().putAll(environment) }.start()
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Bridge process timed out")
            val text = Files.readString(output)
            assertEquals(0, process.exitValue(), text)
            return text
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
