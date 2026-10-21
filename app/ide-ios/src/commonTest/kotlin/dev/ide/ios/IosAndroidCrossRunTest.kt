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
                assertEquals(expectedPath, backend.project.rootPath, "Expected cloneRepository to open ${repo.dirName}")
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

                val transcriptText = console.transcript.joinToString("") { it.text }
                assertTrue(
                    transcriptText.contains("onCreate returned"),
                    "Expected :android-apk DexVm onCreate execution in transcript for ${repo.dirName}, got:\n$transcriptText",
                )
                val apkDir = IosFiles.join(expectedPath, "app/build/outputs/apk/debug")
                assertTrue(
                    IosFiles.isDirectory(apkDir) && IosFiles.list(apkDir).any { it.endsWith(".apk") },
                    "Expected built .apk artifact in $apkDir for ${repo.dirName}",
                )

                println(
                    "PASS [iOS App Tooling -> Android APK + Compose]: ${repo.dirName} | " +
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
}
