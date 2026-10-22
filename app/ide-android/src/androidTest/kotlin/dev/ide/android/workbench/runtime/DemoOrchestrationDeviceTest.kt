package dev.ide.android.workbench.runtime

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.AndroidIde
import dev.ide.core.IdeServicesBackend
import dev.ide.core.runtime.GuiBridgeRuntime
import dev.ide.ui.backend.RunStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the curated demo projects on the connected Android device (`openui` Node.js Browser Component
 * and `ComfyUI` Complicated Python Project), confirms live window rendering in `RunScreen`, and removes
 * the clones from the app workspace afterwards so the device is clean for the user's live demo recording.
 */
@RunWith(AndroidJUnit4::class)
class DemoOrchestrationDeviceTest {

    data class DemoProjectSpec(
        val dirName: String,
        val url: String,
    )

    private val demoRepos = listOf(
        DemoProjectSpec("openui", "https://github.com/thesysdev/openui.git"),
        DemoProjectSpec("ComfyUI", "https://github.com/Comfy-Org/ComfyUI.git"),
    )

    @Test
    fun cloneBuildRunTwoDemoProjectsOnAndroidAndRemoveClones(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtimeHost = AndroidRuntimeHost(context)
        val installation = runtimeHost.prepare()
        val doctorReport = runtimeHost.diagnoseAndRepair(repair = true)
        assertTrue("EnvironmentDoctor should report healthy runtime prefix: $doctorReport", doctorReport.healthy)
        GuiBridgeRuntime.ensureSwiftUiBridgeSdk(installation)

        val manager = AndroidIde.createProjectManager(context)
        val backend = IdeServicesBackend(initial = null, manager = manager)

        try {
            for (repo in demoRepos) {
                val existing = Path.of(backend.projects.projectsRootPath()).resolve(repo.dirName)
                if (Files.exists(existing)) {
                    backend.projects.deleteProject(existing.toString())
                }

                Log.i("DemoOrchestration", ">>> [Android Device] Cloning ${repo.dirName} (${repo.url}) ...")
                val cloned = backend.vcs.cloneRepository(repo.url, repo.dirName)
                assertTrue("Clone failed for ${repo.dirName}: ${cloned.message}", cloned.ok)
                val clonedPath = requireNotNull(cloned.path) { "Cloned path missing for ${repo.dirName}" }

                val opened = backend.projects.openProject(clonedPath)
                assertTrue("openProject failed for ${repo.dirName}", opened)

                val beforeBuild = backend.build.buildState.value
                backend.build.runBuild()

                var liveFrameJson: String? = null
                withTimeout(180_000) {
                    while (true) {
                        val console = backend.build.runConsole.value
                        val candidateFrame = console?.frame?.path
                        if (candidateFrame != null && candidateFrame.contains("\"type\":")) {
                            liveFrameJson = candidateFrame
                            break
                        }
                        val st = backend.build.buildState.value
                        if (st !== beforeBuild && st.status != RunStatus.Running && st.log.isNotEmpty()) {
                            break
                        }
                        delay(150)
                    }
                }

                backend.build.closeRunInput()
                val finalState = withTimeout(60_000) {
                    backend.build.buildState.first { st ->
                        st !== beforeBuild && st.status != RunStatus.Running && st.log.isNotEmpty()
                    }
                }
                val logDump = finalState.log.joinToString("\n") { it.message }
                assertEquals(
                    "Expected RunStatus.Succeeded for ${repo.dirName}, log:\n$logDump",
                    RunStatus.Succeeded,
                    finalState.status,
                )
                val frameJson = liveFrameJson ?: backend.build.runConsole.value?.frame?.path.orEmpty()
                assertTrue(
                    "Expected rendered GUI/Browser window frame for ${repo.dirName}, got: ${frameJson.take(200)}",
                    frameJson.contains("\"type\":") && frameJson.contains("\"children\":"),
                )

                Log.i(
                    "DemoOrchestration",
                    "PASS [Android Device] ${repo.dirName}: status=${finalState.status} | frame=${frameJson.length}B",
                )

                backend.build.stopBuild()
                delay(200)

                val deleted = backend.projects.deleteProject(clonedPath)
                assertTrue("Expected deleteProject($clonedPath) to succeed", deleted)
                assertFalse(
                    "Expected cloned directory $clonedPath to be removed after verification",
                    Files.exists(Path.of(clonedPath)),
                )
                Log.i("DemoOrchestration", ">>> [Android Device] Removed clone ${repo.dirName} ($clonedPath)")
            }
        } finally {
            backend.close()
        }
    }
}
