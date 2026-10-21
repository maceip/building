@file:OptIn(ExperimentalForeignApi::class)

package dev.ide.ios

import dev.ide.deps.impl.ArtifactFetcher
import dev.ide.ios.store.IosPreferences
import dev.ide.ui.backend.RunPhase
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
 * Verifies that all 8 cloned Android/Kotlin repositories in `/tmp/cross-run-repos/android/`
 * can be opened, built into runnable `.apk` packages (`AndroidManifest.xml` + `classes.dex`),
 * executed on the iOS app (`IosBackend` -> `ApkRuntime` -> `DexVm`), and rendered to a live
 * preview frame (`RunFrameUi`).
 */
class IosAndroidCrossRunTest {

    data class ExpectedAndroidRepo(
        val dirName: String,
        val expectedPackagePrefix: String,
        val expectedActivitySuffix: String,
    )

    private val expectedRepos = listOf(
        ExpectedAndroidRepo("BitChord", "com.music.bitchord", "MainActivity"),
        ExpectedAndroidRepo("android-airplay-server", "io.github.jqssun.airplay", "MainActivity"),
        ExpectedAndroidRepo("rikkahub", "me.rerere.rikkahub", "RouteActivity"),
        ExpectedAndroidRepo("mlmvpn_android", "com.mlmvpn.scanner", "MainActivity"),
        ExpectedAndroidRepo("vivi-music", "com.music.vivi", "Activity"),
        ExpectedAndroidRepo("web-to-app", "com.webtoapp", "MainActivity"),
        ExpectedAndroidRepo("YumaPlayer", "moe.rukamori.archivetune", "MainActivity"),
        ExpectedAndroidRepo("StreetComplete", "de.westnordost.streetcomplete", "MainActivity"),
    )

    @Test
    fun buildAndRunAllEightAndroidAppsOnIosBackend() = runBlocking {
        val baseDir = "/tmp/cross-run-repos/android"
        if (!IosFiles.isDirectory(baseDir)) {
            println("Skipping local repo cross-run check because $baseDir is not present on this machine")
            return@runBlocking
        }

        val tempRoot = IosFiles.join(NSTemporaryDirectory().trimEnd('/'), "ios-android-cross-run-${ dev.ide.platform.epochMillis() }")
        IosFiles.mkdirs(tempRoot)
        val prefs = IosPreferences(NSUserDefaults(suiteName = "ios-android-cross-run"))
        val backend = IosBackend(tempRoot, prefs).apply {
            dependenciesFor = { IosDependencies(it, ArtifactFetcher { null }) }
        }

        try {
            for (repo in expectedRepos) {
                val repoPath = IosFiles.join(baseDir, repo.dirName)
                assertTrue(IosFiles.isDirectory(repoPath), "Expected cloned Android repo at $repoPath")
                assertTrue(
                    IosAndroidProjectBuilder.isAndroidSourceProject(repoPath),
                    "Expected $repoPath to be recognised as an Android source project",
                )

                val spec = IosAndroidProjectBuilder.inspectProject(repoPath)
                assertTrue(
                    spec.packageName.startsWith(repo.expectedPackagePrefix),
                    "Expected ${repo.dirName} packageName to start with ${repo.expectedPackagePrefix}, got ${spec.packageName}",
                )
                assertTrue(
                    spec.launchActivity.endsWith(repo.expectedActivitySuffix),
                    "Expected ${repo.dirName} launchActivity to end with ${repo.expectedActivitySuffix}, got ${spec.launchActivity}",
                )

                try {
                    val opened = backend.openProject(repoPath)
                    assertTrue(opened, "Expected IosBackend.openProject($repoPath) to succeed")
                    assertTrue(backend.project.isAndroid, "Expected project.isAndroid=true for ${repo.dirName}")

                    val tasks = backend.runTasks()
                    assertTrue(
                        tasks.any { it.id == "android:apk:run" },
                        "Expected android:apk:run task for ${repo.dirName}, got: ${tasks.map { it.id }}",
                    )

                    backend.runBuild()

                    // Wait for build & run to finish on backendScope
                    var attempts = 0
                    while (attempts < 200) {
                        val status = backend.buildState.value.status
                        val phase = backend.runConsole.value?.phase
                        if ((status == RunStatus.Succeeded || status == RunStatus.Failed) && phase == RunPhase.Finished) {
                            break
                        }
                        delay(25)
                        attempts++
                    }

                    val buildState = backend.buildState.value
                    val console = backend.runConsole.value
                    assertNotNull(console, "Expected non-null RunConsoleUi for ${repo.dirName}")
                    val fullTranscript = console.transcript.joinToString("") { it.text }

                    assertEquals(
                        RunStatus.Succeeded,
                        buildState.status,
                        "Expected RunStatus.Succeeded for ${repo.dirName}, got ${buildState.status}. Transcript:\n$fullTranscript",
                    )
                    assertEquals(0, console.exitCode, "Expected exitCode=0 for ${repo.dirName}. Transcript:\n$fullTranscript")
                    assertTrue(
                        fullTranscript.contains("onCreate returned"),
                        "Expected Dalvik VM onCreate execution in transcript for ${repo.dirName}:\n$fullTranscript",
                    )
                    assertTrue(
                        fullTranscript.contains("package=${spec.packageName}"),
                        "Expected package=${spec.packageName} in transcript for ${repo.dirName}:\n$fullTranscript",
                    )

                    val frame = console.frame
                    assertNotNull(frame, "Expected rendered preview frame (RunFrameUi) for ${repo.dirName}")
                    val frameBytes = IosFiles.readBytes(frame.path)
                    assertNotNull(frameBytes, "Expected PNG frame bytes on disk at ${frame.path} for ${repo.dirName}")
                    assertTrue(frameBytes.size > 100, "Expected non-empty PNG frame for ${repo.dirName}")
                    assertEquals(0x89.toByte(), frameBytes[0], "Expected valid PNG magic byte 0")
                    assertEquals(0x50.toByte(), frameBytes[1], "Expected valid PNG magic byte 1 ('P')")

                    println(
                        "PASS [iOS -> Android APK]: ${repo.dirName} | pkg=${spec.packageName} | " +
                            "activity=${spec.launchActivity} | classes=${spec.componentClasses.size + 1} | " +
                            "frame=${frame.width}x${frame.height} (${frameBytes.size} bytes)",
                    )
                } finally {
                    IosFiles.delete(IosFiles.join(repoPath, ".platform"))
                    IosFiles.delete(IosFiles.join(repoPath, "app/build/outputs/apk/debug"))
                }
            }
        } finally {
            IosFiles.delete(tempRoot)
        }
    }
}
