// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.android.workbench.appengine

import dev.ide.ui.backend.BuildService
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.ProjectService
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.RunTaskOption
import dev.ide.workbench.appengine.AppEngine
import dev.ide.workbench.appengine.AppEnginePackageResult
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Production actions against backend fakes; no test-only panel flags or synthetic runtime availability. */
class AppEnginePackageActionsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun noProjectHasNoAccidentalCwdAndUnexpectedFailuresStayVisible() {
        val fixture = BackendFixture()
        assertEquals("", engineWorkspaceOrEmpty(fixture.backend))
        fixture.projectFailure = IllegalStateException("No project is open")
        assertEquals("", engineWorkspaceOrEmpty(fixture.backend))
        fixture.projectFailure = IllegalStateException("Real engine failure")
        rejects { engineWorkspaceOrEmpty(fixture.backend) }
        assertTrue(fixture.started.isEmpty())
    }

    @Test fun explicitOpenChangesWorkspaceButNeverBuildsOrInstalls() = runBlocking {
        val fixture = BackendFixture()
        val generated = generated()
        openEngineGeneratedProject(fixture.backend, generated)
        assertEquals(listOf(generated.projectRoot.toString()), fixture.opened)
        assertEquals(generated.projectRoot.toString(), fixture.active)
        assertTrue(fixture.started.isEmpty())
    }

    @Test fun debugRequiresGeneratedWorkspaceAndExactNativeTask() = runBlocking {
        val fixture = BackendFixture()
        val generated = generated()
        rejects { buildEngineGeneratedDebug(fixture.backend, generated) }
        openEngineGeneratedProject(fixture.backend, generated)
        fixture.tasks = listOf(RunTaskOption("gradle:assembleDebug", "Gradle debug", "Gradle"))
        rejects { buildEngineGeneratedDebug(fixture.backend, generated) }
        fixture.tasks = listOf(RunTaskOption("assemble:app:debug", "Assemble app debug", "Native Android"))
        buildEngineGeneratedDebug(fixture.backend, generated)
        assertEquals(listOf("assemble:app:debug"), fixture.started)
        assertEquals(1, fixture.opened.size)
    }

    @Test fun runningBuildOrUnsupportedBackendNeverDispatchesWork() = runBlocking {
        val fixture = BackendFixture()
        val generated = generated()
        openEngineGeneratedProject(fixture.backend, generated)
        fixture.supported = false
        rejects { buildEngineGeneratedDebug(fixture.backend, generated) }
        fixture.supported = true
        fixture.buildState.value = BuildState(status = RunStatus.Running)
        rejects { buildEngineGeneratedDebug(fixture.backend, generated) }
        try {
            openEngineGeneratedProject(fixture.backend, generated)
            throw AssertionError("Open should be refused during a build")
        } catch (_: IllegalStateException) { }
        assertTrue(fixture.started.isEmpty())
        assertEquals(1, fixture.opened.size)
    }

    @Test fun taskDiscoveryCannotSwitchProjectAndBuildTheWrongWorkspace() = runBlocking {
        val fixture = BackendFixture()
        val generated = generated()
        openEngineGeneratedProject(fixture.backend, generated)
        fixture.switchWhileDiscovering = true
        rejects { buildEngineGeneratedDebug(fixture.backend, generated) }
        assertTrue(fixture.started.isEmpty())
    }

    @Test fun artifactsRequireRealContainedFilesAndApkStructure() {
        val generated = generated()
        assertEquals(null to null, engineGeneratedArtifacts(generated))
        val apk = generated.projectRoot.resolve("app/build/outputs/apk/debug/app-debug.apk")
        Files.createDirectories(apk.parent)
        Files.write(apk, "not an APK".toByteArray())
        assertNull(engineGeneratedArtifacts(generated).first)
        ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            for (name in listOf("AndroidManifest.xml", "classes.dex")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(1); zip.closeEntry()
            }
        }
        Files.createDirectories(generated.sourceBundle.parent)
        Files.write(generated.sourceBundle, ByteArray(2048))
        assertEquals(apk to generated.sourceBundle, engineGeneratedArtifacts(generated))
        val outside = temporary.root.toPath().toRealPath().resolve("outside.tar.xz")
        Files.write(outside, ByteArray(2048))
        assertNull(engineGeneratedArtifacts(generated.copy(sourceBundle = outside)).second)
        Files.delete(apk)
        Files.createSymbolicLink(apk, outside)
        assertNull(engineGeneratedArtifacts(generated).first)
        assertFalse(Files.isDirectory(apk))
    }

    private fun generated(): AppEnginePackageResult {
        val root = Files.createTempDirectory(temporary.root.toPath().toRealPath(), "generated-")
        return AppEnginePackageResult(root, AppEngine.Python, 1L, root.resolve("runtime-sources/python-corresponding-source.tar.xz"), emptyList())
    }

    private class BackendFixture {
        var active = ""
        var projectFailure: IllegalStateException? = null
        var supported = true
        var switchWhileDiscovering = false
        var tasks = listOf(RunTaskOption("assemble:app:debug", "Assemble app debug", "Native Android"))
        val opened = mutableListOf<String>()
        val started = mutableListOf<String>()
        val buildState = MutableStateFlow(BuildState())
        private val projects = object : ProjectService {
            override val projectEpoch = MutableStateFlow(0)
            override suspend fun openProject(rootPath: String): Boolean {
                opened += rootPath
                active = rootPath
                projectEpoch.value++
                return true
            }
        }
        private val build = object : BuildService {
            override val buildState get() = this@BackendFixture.buildState
            override fun supported() = this@BackendFixture.supported
            override fun runTasks(): List<RunTaskOption> {
                if (switchWhileDiscovering) active = "/unexpected-other-project"
                return tasks
            }
            override fun runTask(id: String) { started += id }
            override fun runBuild() = throw AssertionError("Never run a default task")
            override fun stopBuild() = throw AssertionError("Never stop unrelated builds")
        }
        val backend = Proxy.newProxyInstance(IdeBackend::class.java.classLoader, arrayOf(IdeBackend::class.java)) { proxy, method, arguments ->
            when (method.name) {
                "getProject" -> { projectFailure?.let { throw it }; ProjectInfo("Fixture", active, 0) }
                "getProjects" -> projects
                "getBuild" -> build
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                "toString" -> "EnginePackageBackendFake"
                else -> throw AssertionError("Unexpected backend call: ${method.name}")
            }
        } as IdeBackend
    }

    private fun rejects(action: () -> Unit) {
        try { action() } catch (_: IllegalStateException) { return }
        throw AssertionError("Expected action to fail closed")
    }
}
