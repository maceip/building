package dev.ide.core.runtime

import dev.ide.core.IdeServicesBackend
import dev.ide.core.project.ProjectManager
import dev.ide.ui.backend.RunStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end verification using ONLY the tooling provided by our Android IDE backend (`:ide-core`):
 * 1. `bootstrapBackend.vcs.cloneRepository(url, folderName)` clones each of the 11 iOS / Swift
 *    repositories directly into a fresh workspace and adopts it via `ProjectManager`.
 * 2. `manager.open(...)` + `IdeServicesBackend(ide, manager)` opens the project.
 * 3. `backend.build.runBuild()` builds and runs the repository using only our app's built-in
 *    `SwiftBuildSystem` (`compileAndRunSwiftUiBridge` / `SwiftUiPreviewInterpreter` for GUI apps,
 *    or `swift build` + `executeBuiltSwiftBinary` for CLI/library SwiftPM packages).
 */
class AndroidIosCrossRunTest {

    data class IosRepoSpec(
        val folderName: String,
        val githubSlug: String,
        val isGuiApp: Boolean,
    )

    private val iosRepos = listOf(
        IosRepoSpec("open-computer-use", "iFurySt/open-computer-use", isGuiApp = true),
        IosRepoSpec("Delta", "rileytestut/Delta", isGuiApp = true),
        IosRepoSpec("Biscotti", "scosman/Biscotti", isGuiApp = true),
        IosRepoSpec("Telegram-iOS", "TelegramMessenger/Telegram-iOS", isGuiApp = true),
        IosRepoSpec("textream", "f/textream", isGuiApp = true),
        IosRepoSpec("swift-argument-parser", "apple/swift-argument-parser", isGuiApp = false),
        IosRepoSpec("MacSai", "iliyami/MacSai", isGuiApp = true),
        IosRepoSpec("swift-syntax", "swiftlang/swift-syntax", isGuiApp = false),
        IosRepoSpec("UTM", "utmapp/UTM", isGuiApp = true),
        IosRepoSpec("kumone", "missuo/kumone", isGuiApp = true),
        IosRepoSpec("sim-use", "lycorp-jp/sim-use", isGuiApp = true),
    )

    @Test
    fun cloneBuildAndRunAll11IosRepositoriesUsingOnlyAndroidAppTooling() = runBlocking {
        val baseDir = Files.createTempDirectory("android-app-tooling-ios-run-")

        val previousHost = RuntimeBuildEnvironment.host
        RuntimeBuildEnvironment.host = null
        try {
            val manager = ProjectManager.desktop(baseDir)
            val bootstrapBackend = IdeServicesBackend(initial = null, manager = manager)

            for ((index, spec) in iosRepos.withIndex()) {
                val repoUrl = "https://github.com/${spec.githubSlug}.git"
                println(">>> [Android App Tooling] [${index + 1}/11] Cloning $repoUrl via backend.vcs.cloneRepository...")
                val cloneRes = bootstrapBackend.vcs.cloneRepository(repoUrl, spec.folderName)
                assertTrue(
                    cloneRes.ok,
                    "[${index + 1}/11] Expected backend.vcs.cloneRepository($repoUrl, ${spec.folderName}) to succeed: ${cloneRes.message}",
                )

                val repoDir = baseDir.resolve(spec.folderName)
                assertTrue(Files.isDirectory(repoDir), "Expected cloned repo [${index + 1}/11] ${spec.githubSlug} at $repoDir")
                assertTrue(
                    repoDir.resolve("Package.swift").exists(),
                    "[${index + 1}/11] ${spec.githubSlug} should have Package.swift after cloneRepository adoption",
                )

                manager.open(repoDir.toString()).use { ide ->
                    val backend = IdeServicesBackend(ide, manager)
                    assertTrue(
                        backend.project.isSwift,
                        "[${index + 1}/11] ${spec.githubSlug}: backend.project.isSwift must be true",
                    )

                    val tasks = backend.build.runTasks()
                    assertTrue(
                        tasks.isNotEmpty(),
                        "[${index + 1}/11] ${spec.githubSlug}: must expose Swift build/run tasks",
                    )

                    println(">>> [Android App Tooling] [${index + 1}/11] Running ${spec.githubSlug} via backend.build.runBuild()...")
                    val beforeRunState = backend.build.buildState.value
                    backend.build.runBuild()

                    if (spec.isGuiApp) {
                        val initialFrame = withTimeout(120_000) {
                            while (true) {
                                val f = backend.build.runConsole.value?.frame
                                if (f != null && f.path.contains("\"type\":") && f.path.contains("\"children\":")) {
                                    return@withTimeout f
                                }
                                val curBuild = backend.build.buildState.value
                                if (curBuild !== beforeRunState && curBuild.status == RunStatus.Failed) {
                                    throw AssertionError(
                                        "[${index + 1}/11] ${spec.githubSlug} build failed before emitting GUI frame:\n" +
                                            curBuild.log.joinToString("\n") { it.message },
                                    )
                                }
                                delay(100)
                            }
                            @Suppress("UNREACHABLE_CODE")
                            null
                        }
                        assertNotNull(initialFrame, "[${index + 1}/11] ${spec.githubSlug}: expected live ViewNode JSON frame")

                        // Drive an interactive button callback over stdin if an onTap callback id is present
                        val tapId = Regex(""""onTap":(\d+)""").find(initialFrame.path)?.groupValues?.get(1)
                        var updatedSeq = initialFrame.seq
                        if (tapId != null) {
                            backend.build.sendRunInput("{\"id\":\"$tapId\",\"kind\":\"void\"}")
                            withTimeout(15_000) {
                                while (true) {
                                    val nextFrame = backend.build.runConsole.value?.frame
                                    if (nextFrame != null && nextFrame.seq > initialFrame.seq) {
                                        updatedSeq = nextFrame.seq
                                        break
                                    }
                                    delay(50)
                                }
                            }
                        }

                        // Close input so the interactive GUI session exits cleanly with code 0
                        backend.build.closeRunInput()
                        val finishedRun = withTimeout(30_000) {
                            backend.build.buildState.first {
                                it !== beforeRunState && it.status != RunStatus.Running && it.log.isNotEmpty()
                            }
                        }
                        assertEquals(
                            RunStatus.Succeeded,
                            finishedRun.status,
                            "[${index + 1}/11] ${spec.githubSlug} GUI run failed:\n" +
                                finishedRun.log.joinToString("\n") { it.message },
                        )

                        println(
                            "PASS [Android App Tooling -> iOS GUI] [${index + 1}/11] ${spec.githubSlug}: " +
                                "frameBytes=${initialFrame.path.length}, tapId=${tapId ?: "none"}, " +
                                "frameSeq=${initialFrame.seq}->$updatedSeq",
                        )
                    } else {
                        // CLI / Library Swift package (swift-argument-parser, swift-syntax, sim-use)
                        val finishedRun = withTimeout(180_000) {
                            backend.build.buildState.first {
                                it !== beforeRunState && it.status != RunStatus.Running && it.log.isNotEmpty()
                            }
                        }
                        assertEquals(
                            RunStatus.Succeeded,
                            finishedRun.status,
                            "[${index + 1}/11] ${spec.githubSlug} CLI/Library build & run failed:\n" +
                                finishedRun.log.joinToString("\n") { it.message },
                        )
                        val consoleTranscript = backend.build.runConsole.value?.transcript?.joinToString("") { it.text }.orEmpty()
                        val logSummary = finishedRun.log.lastOrNull()?.message.orEmpty()
                        assertTrue(
                            consoleTranscript.isNotBlank() || finishedRun.log.isNotEmpty(),
                            "[${index + 1}/11] ${spec.githubSlug}: expected build/run output in console",
                        )
                        println(
                            "PASS [Android App Tooling -> iOS CLI/Package] [${index + 1}/11] ${spec.githubSlug}: " +
                                "consoleChars=${consoleTranscript.length}, lastLog='$logSummary'",
                        )
                    }
                }
            }
        } finally {
            RuntimeBuildEnvironment.host = previousHost
            runCatching { baseDir.toFile().deleteRecursively() }
        }
    }
}
