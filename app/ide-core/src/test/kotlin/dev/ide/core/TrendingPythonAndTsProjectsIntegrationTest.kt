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

    @Test
    fun detectsImportsBuildsAndRunsAllOct22TrendingPythonTypeScriptAndSwiftProjects() = withTempDir("trending-oct22-py-ts-swift") { temp ->
        val pyBase = Paths.get("/tmp/trending_oct22/python")
        val tsBase = Paths.get("/tmp/trending_oct22/ts")
        val swiftBase = Paths.get("/tmp/trending_oct22/swift")
        if (!Files.isDirectory(pyBase) || !Files.isDirectory(tsBase) || !Files.isDirectory(swiftBase)) return@withTempDir

        val oct22Python = listOf(
            "knowledge-work-plugins",
            "graphify",
            "fastapi",
            "claude-howto",
            "vllm",
            "headroom",
            "hackingtool",
            "Kronos",
            "MediaCrawler",
            "litellm",
            "ComfyUI",
            "onyx",
            "Vibe-Trading",
        )
        val oct22Ts = listOf(
            "supabase",
            "OpenStock",
            "twenty",
            "freellmapi",
            "genoffice",
            "worldmonitor",
            "remotion",
            "t3code",
            "vite",
            "firecrawl",
            "dokploy",
            "dsh-context",
            "compound-engineering-plugin",
            "docs",
            "OpenBot",
            "orca",
        )
        val oct22Swift = listOf(
            "FlappySwift",
            "SwiftUI-Calculator",
        )

        val manager = ProjectManager.desktop(temp.resolve("projects"))
        val backend = IdeServicesBackend(manager = manager)
        val sdk = IdeServices.defaultDesktopSdk()

        try {
            runBlocking {
                for (repo in oct22Python) {
                    val dir = pyBase.resolve(repo)
                    cleanGeneratedMetadata(dir)
                    assertTrue(Files.isDirectory(dir), "Missing Python repo directory: $dir")
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
                        assertTrue(
                            console?.frame != null || console?.transcript?.isNotEmpty() == true,
                            "Expected GUI frame or console output for Python repo $repo",
                        )
                        println(
                            "✓ [OCT22-PYTHON] $repo -> task=${primary.id} status=${state.status} " +
                                "guiFrame=${console?.frame != null} logLines=${state.log.size}",
                        )
                    }
                    cleanGeneratedMetadata(dir)
                }

                for (repo in oct22Ts) {
                    val dir = tsBase.resolve(repo)
                    cleanGeneratedMetadata(dir)
                    assertTrue(Files.isDirectory(dir), "Missing TypeScript repo directory: $dir")
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
                            "✓ [OCT22-TYPESCRIPT] $repo -> task=${primary.id} status=${state.status} " +
                                "guiFrame=${console?.frame != null} logLines=${state.log.size}",
                        )
                    }
                    cleanGeneratedMetadata(dir)
                }

                for (repo in oct22Swift) {
                    val dir = swiftBase.resolve(repo)
                    cleanGeneratedMetadata(dir)
                    assertTrue(Files.isDirectory(dir), "Missing Swift repo directory: $dir")
                    assertTrue(
                        IdeServices.importExternalProjectAt(dir, sdk, LanguageLevel.JAVA_17, manager.env),
                        "Expected importExternalProjectAt to succeed for Swift repo $repo",
                    )
                    IdeServices.openAt(dir, sdk, sharedCachesRoot = temp, env = manager.env, buildOnly = true).use { ide ->
                        val tasks = ide.build.runTasks()
                        assertTrue(tasks.isNotEmpty(), "Expected run tasks for Swift repo $repo")
                        val primary = tasks.first()
                        ide.build.runTask(primary.id)
                        withTimeout(90_000) {
                            while (true) {
                                val st = ide.build.buildState.value
                                val frame = ide.build.runConsole.value?.frame
                                if (frame != null || (st.status != RunStatus.Running && st.log.isNotEmpty())) {
                                    break
                                }
                                kotlinx.coroutines.delay(100)
                            }
                        }
                        // Give any background packaging probe a brief moment to settle and verify 0 errors
                        kotlinx.coroutines.delay(500)
                        val state = ide.build.buildState.value
                        val console = ide.build.runConsole.value
                        assertTrue(
                            state.status != RunStatus.Failed,
                            "Swift repo $repo (${primary.id}) failed:\n${state.log.joinToString("\n") { it.message }}",
                        )
                        assertEquals(
                            0,
                            state.diagnostics.count { it.severity.name.equals("Error", ignoreCase = true) },
                            "Swift repo $repo emitted unexpected error diagnostics: ${state.diagnostics}",
                        )
                        assertTrue(
                            console?.frame != null,
                            "Expected live interactive GUI frame for Swift repo $repo, logs:\n${state.log.joinToString("\n") { it.message }}",
                        )
                        val initialFrame = console!!.frame!!
                        val tapId = Regex("\"onTap\":\\s*(\\d+)").find(initialFrame.path)?.groupValues?.get(1)
                        if (tapId != null) {
                            ide.build.sendRunInput("{\"id\":\"$tapId\",\"kind\":\"void\"}\n")
                            withTimeout(10_000) {
                                while ((ide.build.runConsole.value?.frame?.seq ?: 0L) <= initialFrame.seq) {
                                    kotlinx.coroutines.delay(50)
                                }
                            }
                        }
                        val updatedFrame = ide.build.runConsole.value?.frame
                        println(
                            "✓ [OCT22-SWIFT] $repo -> task=${primary.id} status=${state.status} " +
                                "errors=0 guiFrameSeq=${updatedFrame?.seq} logLines=${state.log.size}",
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
