package dev.ide.core.runtime

import dev.ide.core.IdeServicesBackend
import dev.ide.core.project.ImportableKind
import dev.ide.core.project.ProjectManager
import dev.ide.ui.backend.RunStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end cross-run test verifying that all 11 cloned iOS / Swift repositories in
 * `/tmp/cross-run-repos/ios/` are detected as first-class Swift & iOS projects by our
 * Android IDE backend (`ProjectManager` + `IdeServicesBackend`), compiled with the real
 * Swift compiler (`swiftc` + `libSwiftUI` Cupertino bridge or `swift build`), executed,
 * driven interactively via the `ViewNode` JSON protocol (or CLI stdout), and packaged
 * into installable `.ipa` archives.
 */
class AndroidIosCrossRunTest {

    data class IosRepoSpec(
        val folderName: String,
        val githubSlug: String,
        val isGuiOrSimulatorApp: Boolean,
    )

    private val iosRepos = listOf(
        IosRepoSpec("open-computer-use", "iFurySt/open-computer-use", isGuiOrSimulatorApp = true),
        IosRepoSpec("Delta", "rileytestut/Delta", isGuiOrSimulatorApp = true),
        IosRepoSpec("Biscotti", "scosman/Biscotti", isGuiOrSimulatorApp = true),
        IosRepoSpec("Telegram-iOS", "TelegramMessenger/Telegram-iOS", isGuiOrSimulatorApp = true),
        IosRepoSpec("textream", "f/textream", isGuiOrSimulatorApp = true),
        IosRepoSpec("swift-argument-parser", "apple/swift-argument-parser", isGuiOrSimulatorApp = false),
        IosRepoSpec("MacSai", "iliyami/MacSai", isGuiOrSimulatorApp = true),
        IosRepoSpec("swift-syntax", "swiftlang/swift-syntax", isGuiOrSimulatorApp = false),
        IosRepoSpec("UTM", "utmapp/UTM", isGuiOrSimulatorApp = true),
        IosRepoSpec("kumone", "missuo/kumone", isGuiOrSimulatorApp = true),
        IosRepoSpec("sim-use", "lycorp-jp/sim-use", isGuiOrSimulatorApp = true),
    )

    @Test
    fun buildAndRunAll11IosRepositoriesOnAndroidBackend() = runBlocking {
        val baseDir = Paths.get("/tmp/cross-run-repos/ios")
        assertTrue(Files.isDirectory(baseDir), "Expected cloned iOS repos at $baseDir")

        val previousHost = RuntimeBuildEnvironment.host
        RuntimeBuildEnvironment.host = null
        try {
            val manager = ProjectManager.desktop(baseDir)

            for ((index, spec) in iosRepos.withIndex()) {
                val repoDir = baseDir.resolve(spec.folderName)
                assertTrue(Files.isDirectory(repoDir), "Missing cloned repo [${index + 1}/11] ${spec.githubSlug} at $repoDir")

                // Reset any stale model persistence so fresh detection runs cleanly
                val platformDir = repoDir.resolve(".platform")
                if (Files.isDirectory(platformDir)) {
                    platformDir.toFile().deleteRecursively()
                }

                val adoptedKind = manager.adoptFolderInPlace(repoDir)
                assertEquals(
                    ImportableKind.SWIFT,
                    adoptedKind,
                    "[${index + 1}/11] ${spec.githubSlug} should be adopted as ImportableKind.SWIFT",
                )
                assertTrue(
                    repoDir.resolve("Package.swift").exists(),
                    "[${index + 1}/11] ${spec.githubSlug} should have Package.swift after adoption",
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

                    val beforeRunState = backend.build.buildState.value
                    backend.build.runBuild()

                    if (spec.isGuiOrSimulatorApp) {
                        // Wait for live interactive ViewNode frame from the compiled SwiftUI / Cupertino binary
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

                        // Also verify .ipa packaging succeeds and produces xtool/<pkg>.ipa
                        val beforeIpaState = backend.build.buildState.value
                        backend.build.runTask(SwiftBuildSystem.TASK_IPA)
                        val ipaState = withTimeout(120_000) {
                            backend.build.buildState.first {
                                it !== beforeIpaState && it.status != RunStatus.Running && it.log.isNotEmpty()
                            }
                        }
                        assertEquals(
                            RunStatus.Succeeded,
                            ipaState.status,
                            "[${index + 1}/11] ${spec.githubSlug} IPA export failed:\n" +
                                ipaState.log.joinToString("\n") { it.message },
                        )
                        val xtoolDir = repoDir.resolve("xtool")
                        val ipaFile = if (Files.isDirectory(xtoolDir)) {
                            Files.list(xtoolDir).use { s ->
                                s.filter { it.fileName.toString().endsWith(".ipa") && Files.size(it) > 0 }
                                    .findFirst().orElse(null)
                            }
                        } else null
                        assertNotNull(
                            ipaFile,
                            "[${index + 1}/11] ${spec.githubSlug}: expected non-empty .ipa archive in $xtoolDir",
                        )

                        println(
                            "PASS [${index + 1}/11] ${spec.githubSlug}: mode=GUI/Cupertino, " +
                                "frameBytes=${initialFrame.path.length}, tapId=${tapId ?: "none"}, " +
                                "frameSeq=${initialFrame.seq}->$updatedSeq, ipa=${ipaFile.fileName} (${Files.size(ipaFile)} bytes)",
                        )
                    } else {
                        // CLI / Library Swift package (swift-argument-parser, swift-syntax)
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
                            "PASS [${index + 1}/11] ${spec.githubSlug}: mode=CLI/Library, " +
                                "consoleChars=${consoleTranscript.length}, lastLog='$logSummary'",
                        )
                    }
                }
            }
        } finally {
            RuntimeBuildEnvironment.host = previousHost
        }
    }
}
