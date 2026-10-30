@file:OptIn(ExperimentalForeignApi::class)

package dev.ide.ios

import dev.ide.deps.impl.ArtifactFetcher
import dev.ide.ios.store.IosPreferences
import dev.ide.ui.backend.RunStatus
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end verification using ONLY the tooling provided by our iOS app (`:ide-ios`):
 * 1. `backend.vcs.cloneRepository(url, name)` clones each of the 8 Android/Kotlin repositories into `projectsRoot`
 *    and opens it in `IosBackend`.
 * 2. `backend.build.runBuild()` lowers the repository's real Kotlin Compose source (`@Preview` / `@Composable`)
 *    through `IosKotlinAnalysis` (`:lang-kotlin`) and executes it inside `IosPreviewRenderer` (`:jvm-vm` +
 *    `:interp-compose` + native iOS Skia) to produce a live `RunFrameUi` frame.
 */
class IosAndroidCrossRunTest {

    data class AndroidRepoTarget(
        val dirName: String,
        val url: String,
    )

    private val repos = listOf(
        AndroidRepoTarget("kotlin-android-template", "https://github.com/cortinico/kotlin-android-template"),
        AndroidRepoTarget("Sandwich", "https://github.com/skydoves/Sandwich"),
        AndroidRepoTarget("BitChord", "https://github.com/kushagrasinghx/BitChord"),
        AndroidRepoTarget("android-airplay-server", "https://github.com/jqssun/android-airplay-server"),
        AndroidRepoTarget("rikkahub", "https://github.com/rikkahub/rikkahub"),
        AndroidRepoTarget("mlmvpn_android", "https://github.com/mlmvpn/mlmvpn_android"),
        AndroidRepoTarget("vivi-music", "https://github.com/vivizzz007/vivi-music"),
        AndroidRepoTarget("web-to-app", "https://github.com/shiaho777/web-to-app"),
        AndroidRepoTarget("YumaPlayer", "https://github.com/MuwMx/YumaPlayer"),
        AndroidRepoTarget("StreetComplete", "https://github.com/streetcomplete/StreetComplete"),
    )

    @Test
    fun cloneBuildAndRunAllEightAndroidAppsUsingOnlyIosAppTooling() = runBlocking {
        val tempRoot = IosFiles.join(
            NSTemporaryDirectory().trimEnd('/'),
            "ios-app-tooling-run-${dev.ide.platform.epochMillis()}",
        )
        IosFiles.mkdirs(tempRoot)
        val prefs = IosPreferences(NSUserDefaults(suiteName = "ios-app-tooling-run"))
        val backend = IosBackend(tempRoot, prefs).apply {
            previewRuntimeJars = { PreviewTestClasspath.jars }
            dependenciesFor = { IosDependencies(it, ArtifactFetcher { null }) }
        }

        try {
            for (repo in repos) {
                println(">>> [iOS App Tooling] Cloning ${repo.url} via backend.vcs.cloneRepository...")
                val cloned = backend.vcs.cloneRepository(repo.url, repo.dirName)
                assertTrue(
                    cloned.ok,
                    "Expected backend.vcs.cloneRepository(${repo.url}, ${repo.dirName}) to succeed: ${cloned.message}",
                )
                val expectedPath = IosFiles.join(tempRoot, repo.dirName)
                assertEquals(expectedPath, cloned.path, "Expected cloned path to match projectsRoot/${repo.dirName}")
                assertTrue(backend.openProject(expectedPath), "Expected openProject($expectedPath) to succeed")
                assertEquals(expectedPath, backend.project.rootPath, "Expected project.rootPath to match ${repo.dirName}")
                assertTrue(backend.project.isAndroid, "Expected project.isAndroid=true for ${repo.dirName}")

                println(">>> [iOS App Tooling] Running ${repo.dirName} via backend.build.runBuild()...")
                backend.build.runBuild()

                var attempts = 0
                while (attempts < 1200) {
                    val status = backend.build.buildState.value.status
                    val frame = backend.build.runConsole.value?.frame
                    if (status == RunStatus.Failed) break
                    if (status == RunStatus.Succeeded && frame != null) break
                    delay(50)
                    attempts++
                }

                val buildState = backend.build.buildState.value
                val console = backend.build.runConsole.value
                val logMsg = buildState.log.joinToString("; ") { it.message }
                assertNotNull(console, "Expected non-null RunConsoleUi for ${repo.dirName}")
                assertEquals(
                    RunStatus.Succeeded,
                    buildState.status,
                    "Expected RunStatus.Succeeded for ${repo.dirName}, got ${buildState.status} ($logMsg)",
                )

                val frame = console.frame
                assertNotNull(
                    frame,
                    "Expected rendered Compose frame (RunFrameUi) from IosPreviewRenderer for ${repo.dirName} (entry=${console.mainClass})",
                )
                val frameBytes = IosFiles.readBytes(frame.path)
                assertNotNull(frameBytes, "Expected PNG frame bytes on disk at ${frame.path} for ${repo.dirName}")
                assertTrue(frameBytes.size > 100, "Expected non-empty PNG frame for ${repo.dirName}")
                assertEquals(0x89.toByte(), frameBytes[0], "Expected valid PNG magic byte 0")
                assertEquals(0x50.toByte(), frameBytes[1], "Expected valid PNG magic byte 1 ('P')")
                val inkedPixels = backend.countInkedPixels(frameBytes)
                assertTrue(
                    inkedPixels > 50,
                    "Expected > 50 visible inked pixels in rendered RunSurface frame for ${repo.dirName}, got $inkedPixels",
                )

                val transcriptText = console.transcript.joinToString("") { it.text }
                assertTrue(
                    transcriptText.contains("Rendered Compose UI entry"),
                    "Expected real Compose UI render entry in transcript for ${repo.dirName}, got:\n$transcriptText",
                )

                println(
                    "PASS [iOS App Tooling -> Android Compose]: ${repo.dirName} | " +
                        "pkg=${console.moduleName} | activity=${console.mainClass} | " +
                        "frame=${frame.width}x${frame.height} (${frameBytes.size} bytes)",
                )

                backend.build.stopBuild()
                delay(50)
            }
        } finally {
            backend.build.stopBuild()
            IosFiles.delete(tempRoot)
        }
    }

    @Test
    fun cloneBuildRunTwoDemoProjectsOnIosAndRemoveClones() = runBlocking {
        val tempRoot = IosFiles.join(
            NSTemporaryDirectory().trimEnd('/'),
            "ios-demo-orchestration-${dev.ide.platform.epochMillis()}",
        )
        IosFiles.mkdirs(tempRoot)
        val prefs = IosPreferences(NSUserDefaults(suiteName = "ios-demo-orchestration"))
        val backend = IosBackend(tempRoot, prefs).apply {
            previewRuntimeJars = { PreviewTestClasspath.jars }
            dependenciesFor = { IosDependencies(it, ArtifactFetcher { null }) }
        }

        try {
            // 1. Android Music Player: vivi-music
            val vivi = AndroidRepoTarget("vivi-music", "https://github.com/vivizzz007/vivi-music")
            val clonedVivi = backend.vcs.cloneRepository(vivi.url, vivi.dirName)
            assertTrue(clonedVivi.ok, "Expected cloneRepository(${vivi.url}) to succeed: ${clonedVivi.message}")
            val viviPath = requireNotNull(clonedVivi.path)
            assertTrue(backend.openProject(viviPath), "Expected openProject(vivi-music) to succeed")
            assertTrue(backend.project.isAndroid, "Expected vivi-music to be detected as Android project")

            backend.build.runBuild()
            var attempts = 0
            while (attempts < 1200) {
                val st = backend.build.buildState.value.status
                val f = backend.build.runConsole.value?.frame
                if (st == RunStatus.Failed) break
                if (st == RunStatus.Succeeded && f != null) break
                delay(50)
                attempts++
            }
            assertEquals(RunStatus.Succeeded, backend.build.buildState.value.status)
            val viviFrame = assertNotNull(backend.build.runConsole.value?.frame, "Expected rendered frame for vivi-music")
            val viviBytes = assertNotNull(IosFiles.readBytes(viviFrame.path))
            assertTrue(viviBytes.size > 100, "Expected non-empty PNG frame for vivi-music")
            backend.build.stopBuild()
            delay(50)
            assertTrue(backend.projects.deleteProject(viviPath), "Expected deleteProject(vivi-music) to succeed")
            assertTrue(!IosFiles.exists(viviPath), "Expected vivi-music directory to be removed after test")
            println("PASS [iOS Demo 1/2 -> vivi-music]: rendered ${viviFrame.width}x${viviFrame.height} frame (${viviBytes.size}B) + removed clone")

            // 2. iOS SwiftUI Window: SwiftUI-Calculator
            val swiftCalc = AndroidRepoTarget("SwiftUI-Calculator", "https://github.com/AfrazCodes/SwiftUI-Calculator")
            val clonedCalc = backend.vcs.cloneRepository(swiftCalc.url, swiftCalc.dirName)
            assertTrue(clonedCalc.ok, "Expected cloneRepository(${swiftCalc.url}) to succeed: ${clonedCalc.message}")
            val calcPath = requireNotNull(clonedCalc.path)
            assertTrue(backend.openProject(calcPath), "Expected openProject(SwiftUI-Calculator) to succeed")
            assertTrue(backend.project.isSwift, "Expected SwiftUI-Calculator to be detected as Swift/iOS project")

            backend.build.runBuild()
            attempts = 0
            while (attempts < 600) {
                val st = backend.build.buildState.value.status
                val f = backend.build.runConsole.value?.frame
                if (st == RunStatus.Failed) break
                if (st == RunStatus.Succeeded && f != null) break
                delay(50)
                attempts++
            }
            assertEquals(RunStatus.Succeeded, backend.build.buildState.value.status)
            val calcFrame = assertNotNull(backend.build.runConsole.value?.frame, "Expected Cupertino ViewNode frame for SwiftUI-Calculator")
            assertTrue(
                calcFrame.path.contains("\"type\":\"ScrollView\"") && calcFrame.path.contains("SwiftUI-Calculator"),
                "Expected Cupertino ViewNode JSON tree for SwiftUI-Calculator, got: ${calcFrame.path.take(200)}",
            )
            // Exercise interactive callback in BridgeGuiWindow
            backend.build.sendRunInput("""{"id":"1","kind":"void"}""")
            val updatedCalcFrame = assertNotNull(backend.build.runConsole.value?.frame)
            assertTrue(updatedCalcFrame.seq > calcFrame.seq, "Expected frame seq to advance after button tap")
            assertTrue(updatedCalcFrame.path.contains("\"interactions\":1"), "Expected \"interactions\":1 in updated frame")

            backend.build.stopBuild()
            delay(50)
            assertTrue(backend.projects.deleteProject(calcPath), "Expected deleteProject(SwiftUI-Calculator) to succeed")
            assertTrue(!IosFiles.exists(calcPath), "Expected SwiftUI-Calculator directory to be removed after test")
            println("PASS [iOS Demo 2/2 -> SwiftUI-Calculator]: rendered interactive Cupertino window (${updatedCalcFrame.path.length}B) + removed clone")
        } finally {
            backend.build.stopBuild()
            IosFiles.delete(tempRoot)
        }
    }
}
