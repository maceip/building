package dev.ide.workbench.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeProjectBridgeTest {
    @TempDir lateinit var temp: Path

    @Test fun aTypeScriptHonoPackageHasScriptsAndAnEditableEntryWithoutEvaluation() {
        write("package.json", """{
          "name":"@example/hono-server", "type":"module", "main":"src/index.ts", "packageManager":"pnpm@10.15.0",
          "scripts":{"preinstall":"node untrusted.cjs", "build":"tsc", "dev":"tsx watch src/index.ts", "start":"node dist/index.js"},
          "dependencies":{"hono":"^4.0.0","@hono/node-server":"^2.1.5"}
        }""")
        write("src/index.ts", "import { Hono } from 'hono'; export default new Hono();")
        write("untrusted.cjs", "throw new Error('IMPORT MUST NOT RUN THIS');")
        val info = assertNotNull(NodeProjectBridge.inspect(temp))
        assertEquals("@example/hono-server", info.name)
        assertEquals("pnpm@10.15.0", info.manifest.packageManager)
        assertEquals("tsx watch src/index.ts", info.manifest.scripts["dev"])
        assertEquals("npm:start", info.selectedTargetId)
        assertEquals("src/index.ts", info.entryFile)
        assertTrue(info.targets.any { it.id == "npm:build" })
        assertTrue(info.targets.none { it.id == "node:src/index.ts" }, "TypeScript requires a configured package script, not a pretend JS run")
        assertFalse(Files.exists(temp.resolve("node_modules")))
        assertFalse(Files.exists(temp.resolve(".building")))
    }

    @Test fun manifestRecognitionRejectsMalformedOversizedAndEscapingInputs() {
        for (text in listOf("not JSON", "[]", "null", "[{}]")) {
            write("package.json", text)
            assertNull(NodePackageJson.read(temp), text)
        }
        write("package.json", "{\"name\":\"" + "x".repeat(1024 * 1024) + "\"}")
        assertNull(NodePackageJson.read(temp))
        Files.delete(temp.resolve("package.json"))
        val outside = Files.createTempFile(temp.parent, "outside-node-", ".json")
        try {
            Files.write(outside, "{}".toByteArray())
            Files.createSymbolicLink(temp.resolve("package.json"), outside)
            assertNull(NodePackageJson.read(temp), "A package.json symlink outside the project is not imported")
        } finally {
            Files.deleteIfExists(temp.resolve("package.json"))
            Files.deleteIfExists(outside)
        }
    }

    @Test fun namelessPackagesAndNonStringFieldsAreHandledWithoutInventingCommands() {
        write("package.json", """{"name":42,"main":false,"scripts":{"dev":"tsc --watch","bad":1,"--prefix":"/outside","":"node unsafe"}}""")
        val manifest = assertNotNull(NodePackageJson.read(temp))
        assertNull(manifest.name)
        assertNull(manifest.main)
        assertEquals(mapOf("dev" to "tsc --watch"), manifest.scripts)
        assertEquals("npm:dev", NodeProjectBridge.inspect(temp)?.selectedTargetId)
    }

    @Test fun savedScriptSelectionPersistsAndRescanReadsPackageEdits() {
        write("package.json", """{"scripts":{"build":"tsc","dev":"node dev.cjs","start":"node server.cjs"}}""")
        write("server.cjs", "console.log('server')")
        val config = RuntimeProjectConfig("npm:build", args = listOf("--pretty", "false"), workspaceMode = RuntimeWorkspaceMode.DIRECT)
        RuntimeProjects.save(temp, config)
        assertEquals("npm:build", NodeProjectBridge.inspect(temp)?.selectedTargetId)
        write("package.json", """{"scripts":{"dev":"node dev.cjs","test":"node --test"}}""")
        val refreshed = assertNotNull(NodeProjectBridge.inspect(temp))
        assertEquals("npm:dev", refreshed.selectedTargetId)
        assertTrue(refreshed.targets.any { it.id == "npm:test" })
        assertTrue(refreshed.targets.none { it.id == "npm:build" })
        assertEquals(config, RuntimeProjects.load(temp), "Discovery must not rewrite even a stale saved configuration")
    }

    @Test fun lifecycleHooksAreNeverTheFirstOpenDefault() {
        write("package.json", """{"scripts":{"preinstall":"node unsafe.cjs","prepare":"node prepare.cjs","posttest":"node cleanup.cjs","build":"tsc","test":"node --test"}}""")
        assertEquals("npm:build", NodeProjectBridge.inspect(temp)?.selectedTargetId)
        write("package.json", """{"scripts":{"preinstall":"node unsafe.cjs","prepare":"node prepare.cjs","posttest":"node cleanup.cjs"}}""")
        val hooksOnly = assertNotNull(NodeProjectBridge.inspect(temp))
        assertEquals(3, hooksOnly.targets.size, "The user may still choose a hook explicitly")
        assertNull(hooksOnly.selectedTargetId)
        write("package.json", """{"scripts":{"prebuild":"node setup.cjs","build":"tsc","preview":"vite preview"}}""")
        write("vite.config.js", "export default {}")
        assertEquals("npm:build", NodeProjectBridge.inspect(temp)?.selectedTargetId, "Ordinary scripts beat arbitrary JavaScript config files")
        write("package.json", """{"scripts":{"prepreview":"node setup.cjs","preview":"vite preview"}}""")
        assertEquals("npm:preview", NodeProjectBridge.inspect(temp)?.selectedTargetId, "preview itself is not a pre hook")
    }

    @Test fun configuredScriptsAndNodeFilesDispatchWithSameRootAndSavedArguments() = runBlocking {
        write("package.json", """{"name":"dispatch-fixture","scripts":{"test":"node server.cjs"}}""")
        write("server.cjs", "console.log('edited in the IDE')")
        val host = RecordingHost(temp)
        val controller = RuntimeController(host, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        try {
            assertNotNull(NodeProjectBridge.inspect(temp))
            assertEquals(0, host.starts.size, "Inspecting the project must not start a process")
            for (id in listOf("npm:test", "node:server.cjs")) {
                controller.saveConfig(temp, RuntimeProjectConfig(id, args = listOf("one two", "--flag"), workspaceMode = RuntimeWorkspaceMode.DIRECT))
                controller.run(RuntimeRunRequest(temp, id))
                val done = withTimeout(5000) { controller.state.first { !it.isRunning } }
                assertEquals(0, done.exitCode, done.error)
                assertEquals(temp.toRealPath(), done.workspacePath)
                assertFalse(done.workspaceCopy)
                assertEquals(temp.toRealPath(), host.starts.last().second)
                assertEquals(listOf("one two", "--flag"), host.starts.last().first.takeLast(2))
                assertEquals("console.log('edited in the IDE')", host.savedSource)
            }
            assertEquals(
                listOf("node", "npm-cli.js", "--prefix", temp.toRealPath().toString(), "--no-audit", "--no-fund", "run", "test", "--", "one two", "--flag"),
                host.starts.first().first,
            )
            assertEquals(listOf("node", temp.toRealPath().resolve("server.cjs").toString(), "one two", "--flag"), host.starts.last().first)
        } finally { controller.stop(); controller.close() }
    }

    private fun write(relative: String, text: String) {
        val file = temp.resolve(relative)
        Files.createDirectories(file.parent)
        Files.write(file, text.toByteArray())
    }

    /** Dispatch evidence only: the Android suite separately proves the bundled Node/npm binaries execute. */
    private class RecordingHost(private val root: Path) : RuntimeHost {
        val starts = mutableListOf<Pair<List<String>, Path>>()
        var savedSource: String? = null
        override val workspaceCache: Path = root.resolve("unused-cache")
        override suspend fun prepare() = RuntimeInstallation(
            "dispatch-test", "host", root, Path.of("node"), Path.of("npm-cli.js"), null, null, emptyMap(),
        )
        override fun start(command: List<String>, workingDirectory: Path, environment: Map<String, String>): RuntimeChild {
            starts += command to workingDirectory
            savedSource = RuntimeFiles.readUtf8(workingDirectory.resolve("server.cjs"))
            return object : RuntimeChild {
                override val stdin = ByteArrayOutputStream()
                override val stdout = ByteArrayInputStream(ByteArray(0))
                override val stderr = ByteArrayInputStream(ByteArray(0))
                override suspend fun awaitExit() = 0
                override suspend fun terminate() = Unit
            }
        }
    }
}
