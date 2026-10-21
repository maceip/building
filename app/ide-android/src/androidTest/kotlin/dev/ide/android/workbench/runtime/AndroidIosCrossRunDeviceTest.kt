package dev.ide.android.workbench.runtime

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.AndroidIde
import dev.ide.core.IdeServicesBackend
import dev.ide.core.runtime.GuiBridgeRuntime
import dev.ide.ui.backend.RunStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end on-device instrumented verification inside `dev.ide.android.dev` on the Android device/emulator:
 * clones, builds, and runs all 11 requested iOS/Swift repositories using our app's `VcsBackend.cloneRepository`,
 * `IdeServicesBackend.openProject`, `BuildBackend.runBuild`, `AndroidRuntimeHost`, and on-device ARM64 `swift` /
 * `swiftc` + Cupertino `SwiftUI` bridge runtime.
 */
@RunWith(AndroidJUnit4::class)
class AndroidIosCrossRunDeviceTest {

    data class IosRepoSpec(
        val dirName: String,
        val url: String,
        val isSwiftUi: Boolean,
    )

    private val repos = listOf(
        IosRepoSpec("open-computer-use", "https://github.com/iFurySt/open-computer-use.git", isSwiftUi = true),
        IosRepoSpec("Delta", "https://github.com/rileytestut/Delta.git", isSwiftUi = true),
        IosRepoSpec("Biscotti", "https://github.com/scosman/Biscotti.git", isSwiftUi = true),
        IosRepoSpec("Telegram-iOS", "https://github.com/TelegramMessenger/Telegram-iOS.git", isSwiftUi = true),
        IosRepoSpec("textream", "https://github.com/f/textream.git", isSwiftUi = true),
        IosRepoSpec("swift-argument-parser", "https://github.com/apple/swift-argument-parser.git", isSwiftUi = false),
        IosRepoSpec("MacSai", "https://github.com/iliyami/MacSai.git", isSwiftUi = true),
        IosRepoSpec("swift-syntax", "https://github.com/swiftlang/swift-syntax.git", isSwiftUi = false),
        IosRepoSpec("UTM", "https://github.com/utmapp/UTM.git", isSwiftUi = true),
        IosRepoSpec("kumone", "https://github.com/missuo/kumone.git", isSwiftUi = true),
        IosRepoSpec("sim-use", "https://github.com/lycorp-jp/sim-use.git", isSwiftUi = true),
    )

    @Test
    fun cloneBuildAndRunAllElevenIosReposOnAndroidApp(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtimeHost = AndroidRuntimeHost(context)
        val installation = runtimeHost.prepare()
        val doctorReport = runtimeHost.diagnoseAndRepair(repair = true)
        assertTrue("EnvironmentDoctor should report healthy runtime prefix: $doctorReport", doctorReport.healthy)
        assertTrue(
            "SwiftUI Cupertino bridge SDK must be provisioned in on-device Swift prefix",
            GuiBridgeRuntime.ensureSwiftUiBridgeSdk(installation),
        )

        val manager = AndroidIde.createProjectManager(context)
        val backend = IdeServicesBackend(initial = null, manager = manager)
        val summaries = ArrayList<String>()

        for (repo in repos) {
            Log.i("AndroidIosCrossRun", ">>> Starting clone+build+run for ${repo.dirName} (${repo.url})")
            val cloned = backend.vcs.cloneRepository(repo.url, repo.dirName)
            assertTrue("Clone failed for ${repo.dirName}: ${cloned.message}", cloned.ok)
            val clonedPath = requireNotNull(cloned.path) { "Cloned path missing for ${repo.dirName}" }

            val opened = backend.projects.openProject(clonedPath)
            assertTrue("openProject failed for ${repo.dirName}", opened)
            assertEquals("Expected ${repo.dirName} to be active project", repo.dirName, backend.project.name)
            assertTrue("Expected ${repo.dirName} to be detected as Swift project", backend.project.isSwift)

            val beforeBuild = backend.build.buildState.value
            backend.build.runBuild()

            if (repo.isSwiftUi) {
                var liveFrameJson: String? = null
                withTimeout(240_000) {
                    while (true) {
                        val console = backend.build.runConsole.value
                        val candidateFrame = console?.frame?.path
                        if (candidateFrame != null && candidateFrame.contains("\"type\":")) {
                            liveFrameJson = candidateFrame
                            break
                        }
                        val st = backend.build.buildState.value
                        if (st !== beforeBuild && st.status == RunStatus.Failed) {
                            val logDump = st.log.joinToString("\n") { it.message }
                            throw AssertionError("SwiftUI build/run failed for ${repo.dirName}:\n$logDump")
                        }
                        delay(150)
                    }
                }
                val frameJson = requireNotNull(liveFrameJson)
                assertTrue(
                    "Expected ViewNode JSON tree for ${repo.dirName}, got: ${frameJson.take(200)}",
                    frameJson.contains("\"type\":") && frameJson.contains("\"children\":"),
                )

                // Close interactive stdin so the Cupertino bridge process exits cleanly with code 0
                backend.build.closeRunInput()
                val finalState = withTimeout(60_000) {
                    backend.build.buildState.first { st ->
                        st !== beforeBuild && st.status != RunStatus.Running && st.log.isNotEmpty()
                    }
                }
                val logText = finalState.log.joinToString("\n") { it.message }
                assertEquals(
                    "Expected Succeeded for ${repo.dirName}, log:\n$logText",
                    RunStatus.Succeeded,
                    finalState.status,
                )
                val stepNames = finalState.steps.map { "${it.name}:${it.status}" }
                assertTrue(
                    "Expected :swift:compileCupertinoBridge and :swift:runCupertinoBridge in steps for ${repo.dirName}, got $stepNames",
                    finalState.steps.any { it.name == ":swift:compileCupertinoBridge" } &&
                        finalState.steps.any { it.name == ":swift:runCupertinoBridge" },
                )
                val summary = "PASS [Android->iOS SwiftUI] ${repo.dirName}: steps=$stepNames | frame=${frameJson.length}B (${frameJson.take(120)})"
                Log.i("AndroidIosCrossRun", summary)
                println(summary)
                summaries += summary
            } else {
                val finalState = withTimeout(300_000) {
                    backend.build.buildState.first { st ->
                        st !== beforeBuild && st.status != RunStatus.Running && st.log.isNotEmpty()
                    }
                }
                val logText = finalState.log.joinToString("\n") { it.message }
                assertEquals(
                    "Expected Succeeded for SwiftPM repo ${repo.dirName}, log:\n$logText",
                    RunStatus.Succeeded,
                    finalState.status,
                )
                val stepNames = finalState.steps.map { "${it.name}:${it.status}" }
                assertTrue(
                    "Expected :swift:compile in steps for ${repo.dirName}, got $stepNames",
                    finalState.steps.any { it.name == ":swift:compile" },
                )
                val consoleOut = finalState.log.lastOrNull()?.message?.trim()?.replace("\n", " ")?.take(120).orEmpty()
                val summary = "PASS [Android->SwiftPM] ${repo.dirName}: steps=$stepNames | out=$consoleOut"
                Log.i("AndroidIosCrossRun", summary)
                println(summary)
                summaries += summary
            }
        }

        assertEquals(11, summaries.size)
        Log.i("AndroidIosCrossRun", "=== ALL 11/11 iOS REPOS PASSED ON ANDROID ===\n" + summaries.joinToString("\n"))
    }
}
