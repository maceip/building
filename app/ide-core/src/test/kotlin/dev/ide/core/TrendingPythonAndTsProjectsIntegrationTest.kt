package dev.ide.core

import dev.ide.core.project.ImportableKind
import dev.ide.core.project.ProjectManager
import dev.ide.model.LanguageLevel
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.UiProjectFolderKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrendingPythonAndTsProjectsIntegrationTest {

    private val pythonRepos = listOf(
        "ai-job-search",
        "ComfyUI",
        "graphify",
        "headroom",
        "knowledge-work-plugins",
        "agents",
        "agentic-awesome-skills",
        "Kronos",
        "vllm",
        "open-source-games",
        "jasna",
        "artemis",
        "CLI-Anything",
        "claude-seo",
        "voice-pro",
        "Scrapegraph-ai",
        "tick-stock-panel",
        "ASPIRE",
    )

    private val tsRepos = listOf(
        "orca",
        "ruflo",
        "opencode",
        "t3code",
        "openclaw",
        "claude-code",
        "OpenWA",
        "itsaplan",
        "emdash",
        "openui",
        "lossless-cut",
        "iptv",
        "rakazo",
    )

    private fun cleanGeneratedMetadata(dir: Path) {
        for (sub in listOf(".platform", ".building")) {
            val target = dir.resolve(sub)
            if (Files.exists(target)) {
                runCatching {
                    Files.walk(target).use { s ->
                        s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                    }
                }
            }
        }
    }

    @Test
    fun detectsImportsBuildsAndRunsAll18TrendingPythonAnd13TrendingTypeScriptProjects() = withTempDir("trending-oct20-test") { temp ->
        val pyBase = Paths.get("/tmp/trending-oct20/python")
        val tsBase = Paths.get("/tmp/trending-oct20/typescript")
        if (!Files.isDirectory(pyBase) || !Files.isDirectory(tsBase)) return@withTempDir

        val manager = ProjectManager.desktop(temp.resolve("projects"))
        val backend = IdeServicesBackend(manager = manager)
        val sdk = IdeServices.defaultDesktopSdk()

        try {
            runBlocking {
                for (repo in pythonRepos) {
                    val dir = pyBase.resolve(repo)
                    cleanGeneratedMetadata(dir)
                    assertTrue(Files.isDirectory(dir), "Missing Python repo directory: $dir")
                    assertEquals(
                        ImportableKind.PYTHON,
                        manager.inspectFolder(dir),
                        "Expected $repo to be detected as PYTHON by ProjectManager.inspectFolder",
                    )
                    assertEquals(
                        UiProjectFolderKind.PYTHON,
                        backend.projects.inspectProjectFolder(dir.toString()),
                        "Expected $repo to be detected as UiProjectFolderKind.PYTHON",
                    )

                    assertTrue(
                        IdeServices.importExternalProjectAt(dir, sdk, LanguageLevel.JAVA_17, manager.env),
                        "Expected importExternalProjectAt to succeed for Python repo $repo",
                    )
                    IdeServices.openAt(dir, sdk, sharedCachesRoot = temp, env = manager.env, buildOnly = true).use { ide ->
                        val tasks = ide.build.runTasks()
                        assertTrue(tasks.isNotEmpty(), "Expected run tasks for Python repo $repo")
                        val primary = tasks.first()
                        ide.build.runTask(primary.id)
                        val state = withTimeout(60_000) {
                            ide.build.buildState.first { it.status != RunStatus.Running && it.log.isNotEmpty() }
                        }
                        assertEquals(
                            RunStatus.Succeeded,
                            state.status,
                            "Python repo $repo (${primary.id}) failed:\n${state.log.joinToString("\n") { it.message }}",
                        )
                        val console = ide.build.runConsole.value
                        println(
                            "✓ [PYTHON] $repo -> task=${primary.id} status=${state.status} " +
                                "guiFrame=${console?.frame != null} logLines=${state.log.size}",
                        )
                    }
                    cleanGeneratedMetadata(dir)
                }

                for (repo in tsRepos) {
                    val dir = tsBase.resolve(repo)
                    cleanGeneratedMetadata(dir)
                    assertTrue(Files.isDirectory(dir), "Missing TypeScript repo directory: $dir")
                    assertEquals(
                        ImportableKind.NODE,
                        manager.inspectFolder(dir),
                        "Expected $repo to be detected as NODE by ProjectManager.inspectFolder",
                    )
                    assertEquals(
                        UiProjectFolderKind.NODE,
                        backend.projects.inspectProjectFolder(dir.toString()),
                        "Expected $repo to be detected as UiProjectFolderKind.NODE",
                    )

                    assertTrue(
                        IdeServices.importExternalProjectAt(dir, sdk, LanguageLevel.JAVA_17, manager.env),
                        "Expected importExternalProjectAt to succeed for TypeScript repo $repo",
                    )
                    IdeServices.openAt(dir, sdk, sharedCachesRoot = temp, env = manager.env, buildOnly = true).use { ide ->
                        val tasks = ide.build.runTasks()
                        assertTrue(tasks.isNotEmpty(), "Expected run tasks for TypeScript repo $repo")
                        val primary = tasks.first()
                        ide.build.runTask(primary.id)
                        val state = withTimeout(60_000) {
                            ide.build.buildState.first { it.status != RunStatus.Running && it.log.isNotEmpty() }
                        }
                        assertEquals(
                            RunStatus.Succeeded,
                            state.status,
                            "TypeScript repo $repo (${primary.id}) failed:\n${state.log.joinToString("\n") { it.message }}",
                        )
                        val console = ide.build.runConsole.value
                        assertTrue(
                            console?.frame != null || console?.transcript?.isNotEmpty() == true,
                            "Expected GUI/Browser frame or console output for TypeScript repo $repo",
                        )
                        println(
                            "✓ [TYPESCRIPT] $repo -> task=${primary.id} status=${state.status} " +
                                "guiFrame=${console?.frame != null} logLines=${state.log.size}",
                        )
                    }
                    cleanGeneratedMetadata(dir)
                }
            }
        } finally {
            backend.close()
            manager.dispose()
        }
    }
}
