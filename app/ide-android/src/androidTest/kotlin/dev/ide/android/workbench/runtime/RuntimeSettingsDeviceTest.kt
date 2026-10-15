package dev.ide.android.workbench.runtime

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.AndroidIde
import dev.ide.android.gradle.AndroidGradleRuntimeBridge
import dev.ide.android.workbench.terminal.RuntimeTerminalSession
import dev.ide.workbench.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Properties

/** Isolated preference roots: never change the installed app's user choices or workspace data. */
class RuntimeSettingsDeviceTest {
    private fun fixture(block: (Context, File) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(target.cacheDir.toPath(), "runtime-settings-").toFile()
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getExternalFilesDir(type: String?): File? = null
        }
        try { block(context, root) } finally { root.deleteRecursively() }
    }

    private fun save(context: Context, enabled: Boolean) {
        val file = AndroidIde.appHomeDir(context).resolve("prefs.properties")
        file.parentFile!!.mkdirs()
        val values = Properties().apply {
            RuntimeFeature.entries.forEach { setProperty(it.preferenceKey, enabled.toString()) }
        }
        file.outputStream().use { values.store(it, "test fixture") }
    }

    @Test fun choicesPersistAndExistingHostsReadChanges() = fixture { context, _ ->
        val host = AndroidRuntimeHost(context)
        RuntimeKind.entries.forEach { assertNull(host.disabledReason(it)) }
        save(context, false)
        RuntimeKind.entries.forEach {
            assertTrue(host.disabledReason(it).orEmpty().contains("Settings > SDK Manager"))
            assertEquals(host.disabledReason(it), AndroidRuntimeHost(context).disabledReason(it))
        }
        save(context, true)
        RuntimeKind.entries.forEach { assertNull(host.disabledReason(it)) }
    }

    @Test fun disabledRunAndProbeDoNotExtractRuntime() = fixture { context, root -> runBlocking {
        save(context, false)
        val project = root.resolve("project").apply { mkdirs() }
        project.resolve("main.py").writeText("print('must not run')")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val runner = RuntimeController(AndroidRuntimeHost(context), scope)
        try {
            runner.run(RuntimeRunRequest(project.toPath(), "python:main.py"))
            val done = withTimeout(5000) { runner.state.first { !it.isRunning } }
            assertTrue(done.toString(), done.error.orEmpty().contains("turned off"))
            assertTrue(runner.probe().runtimes.all { !it.available })
            assertFalse(root.resolve("dr").exists())
        } finally { runner.stop(); runner.close(); scope.cancel() }
    } }

    @Test fun disabledNodeLeavesGradleJavaEnvironmentAlone() = fixture { context, root ->
        save(context, false)
        val result = AndroidGradleRuntimeBridge(context).prepare()
        val javaEnvironment = mapOf("PATH" to "/jdk/bin", "JAVA_HOME" to "/jdk")
        assertTrue(result.pathEntries.isEmpty())
        assertEquals(javaEnvironment, result.applyTo(javaEnvironment))
        assertFalse(root.resolve("dr").exists())
    }

    @Test fun disabledTerminalProgramsFailBeforeProvisioning() = fixture { context, root -> runBlocking {
        save(context, false)
        for (program in RuntimeTerminalSession.Program.entries) {
            val session = RuntimeTerminalSession(context)
            try {
                session.start(null, program)
                val status = withTimeout(5000) { session.status.first { it.phase == RuntimeTerminalSession.Phase.FAILED } }
                assertTrue(status.toString(), status.error.orEmpty().contains("turned off"))
                assertFalse(root.resolve("dr").exists())
            } finally { session.stop() }
        }
    } }
}
