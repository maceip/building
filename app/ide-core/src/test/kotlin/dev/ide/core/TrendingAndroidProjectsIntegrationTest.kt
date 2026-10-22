package dev.ide.core

import dev.ide.core.project.ProjectManager
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.UiProjectFolderKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrendingAndroidProjectsIntegrationTest {

    @Test
    fun importsRealGitHubTrendingAndroidRepositories() = withTempDir("trending-android-test") { temp ->
        val reposRoot = Path.of("/Users/mac/.gemini/antigravity/worktrees/building/restructure_settings_and_menus/verification_repos/trending_android")
        val clearPdf = reposRoot.resolve("trending-clearpdf")
        val bitChord = reposRoot.resolve("trending-bitchord")

        assumeTrue(Files.isDirectory(clearPdf), "trending-clearpdf must exist")
        assumeTrue(Files.isDirectory(bitChord), "trending-bitchord must exist")

        val manager = ProjectManager.desktop(temp.resolve("projects"))
        val backend = IdeServicesBackend(manager = manager)
        try {
            runBlocking {
                // 1) ClearPDF (GitHub daily trending Kotlin/Android repo: Chethan616/ClearPDF)
                assertEquals(UiProjectFolderKind.GRADLE, backend.projects.inspectProjectFolder(clearPdf.toString()))
                val importClearPdf = backend.projects.importExternalProject(clearPdf.toString())
                assertTrue(importClearPdf.success, importClearPdf.message)
                assertEquals("ClearPDF", backend.project.name)
                assertEquals(5, backend.project.moduleCount)

                // 2) BitChord (GitHub daily trending Kotlin/Android repo: kushagrasinghx/BitChord)
                assertEquals(UiProjectFolderKind.GRADLE, backend.projects.inspectProjectFolder(bitChord.toString()))
                val importBitChord = backend.projects.importExternalProject(bitChord.toString())
                assertTrue(importBitChord.success, importBitChord.message)
                assertEquals("BitChord", backend.project.name)
                assertEquals(4, backend.project.moduleCount)

                assertEquals(2, manager.list().size)
            }
        } finally {
            backend.close()
        }
    }

    @Test
    fun importsBuildsAndRunsAll9Oct22TrendingKotlinRepositories() = withTempDir("trending-oct22-kotlin") { temp ->
        val oct22Root = Path.of("/tmp/trending_oct22/kotlin")
        assumeTrue(Files.isDirectory(oct22Root), "/tmp/trending_oct22/kotlin must exist")
        val repoDirs = listOf(
            "BitChord",
            "android-airplay-server",
            "nowinandroid",
            "web-to-app",
            "legado-with-MD3",
            "rethink-app",
            "tailscale-android",
            "private-compute-services",
            "AiCode",
        ).map { oct22Root.resolve(it) }.filter { Files.isDirectory(it) }
        assumeTrue(repoDirs.isNotEmpty(), "At least one Oct 22 Kotlin trending repo must be present")

        val fakeInstaller = object : ApkInstaller {
            override suspend fun installAndLaunch(apk: Path, packageName: String, log: (String) -> Unit): Boolean {
                log("Fake installed $packageName from ${apk.fileName}")
                return true
            }
        }
        val manager = ProjectManager.desktop(temp.resolve("projects"), apkInstaller = fakeInstaller)
        val backend = IdeServicesBackend(manager = manager)
        try {
            runBlocking {
                for (repoDir in repoDirs) {
                    val repoName = repoDir.fileName.toString()
                    val imported = backend.projects.importExternalProject(repoDir.toString())
                    assertTrue(imported.success, "Failed to import $repoName: ${imported.message}")
                    assertTrue(backend.project.moduleCount > 0, "$repoName imported 0 modules")

                    val tasks = backend.build.runTasks()
                    assertTrue(tasks.isNotEmpty(), "$repoName has 0 runTasks")
                    backend.build.runBuild()

                    val deadline = System.currentTimeMillis() + 180_000L
                    while (System.currentTimeMillis() < deadline) {
                        val st = backend.build.buildState.value.status
                        if (st == dev.ide.ui.backend.RunStatus.Succeeded || st == dev.ide.ui.backend.RunStatus.Failed) {
                            break
                        }
                        kotlinx.coroutines.delay(100)
                    }
                    val finalBuild = backend.build.buildState.value
                    val errors = finalBuild.diagnostics.filter { it.severity == dev.ide.ui.backend.UiSeverity.Error }
                    assertEquals(
                        dev.ide.ui.backend.RunStatus.Succeeded,
                        finalBuild.status,
                        "$repoName build failed: ${finalBuild.log.takeLast(10).joinToString("\n") { it.message }} | errors=$errors",
                    )
                    assertTrue(errors.isEmpty(), "$repoName had error diagnostics: $errors")
                    val rc = backend.build.runConsole.value
                    assertNotNull(rc, "$repoName did not open RunConsoleUi")
                    assertNotNull(rc.frame, "$repoName did not emit a live GUI frame")
                    assertTrue(rc.frame!!.path.contains("\"type\""), "$repoName frame is not valid ViewNode JSON")
                }
            }
        } finally {
            backend.close()
        }
    }
}
