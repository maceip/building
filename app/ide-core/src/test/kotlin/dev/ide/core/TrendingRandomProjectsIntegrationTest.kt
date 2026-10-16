package dev.ide.core

import dev.ide.core.project.ProjectManager
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.UiProjectFolderKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrendingRandomProjectsIntegrationTest {

    @Test
    fun importsRandomlySampledTrendingRepositories() = withTempDir("trending-random-test") { temp ->
        val reposRoot = Path.of("/Users/mac/.gemini/antigravity/worktrees/building/restructure_settings_and_menus/verification_repos/trending_random")
        val pyVoiceStudio = reposRoot.resolve("py-voicestudio")
        val pyIFixAi = reposRoot.resolve("py-ifixai")
        val tsBillionContext = reposRoot.resolve("ts-billioncontext")
        val tsFreeLlmApi = reposRoot.resolve("ts-freellmapi")
        val ktJadxMcp = reposRoot.resolve("kt-jadx-mcp")
        val ktMusicfy = reposRoot.resolve("kt-musicfy")

        assertTrue(Files.isDirectory(pyVoiceStudio), "py-voicestudio must exist")
        assertTrue(Files.isDirectory(pyIFixAi), "py-ifixai must exist")
        assertTrue(Files.isDirectory(tsBillionContext), "ts-billioncontext must exist")
        assertTrue(Files.isDirectory(tsFreeLlmApi), "ts-freellmapi must exist")
        assertTrue(Files.isDirectory(ktJadxMcp), "kt-jadx-mcp must exist")
        assertTrue(Files.isDirectory(ktMusicfy), "kt-musicfy must exist")

        val manager = ProjectManager.desktop(temp.resolve("projects"))
        val backend = IdeServicesBackend(manager = manager)
        try {
            runBlocking {
                // 1) Python: debpalash/VoiceStudio
                assertEquals(UiProjectFolderKind.PYTHON, backend.projects.inspectProjectFolder(pyVoiceStudio.toString()))
                val importVoice = backend.projects.importExternalProject(pyVoiceStudio.toString())
                assertTrue(importVoice.success, importVoice.message)
                assertTrue(backend.project.isPython)
                assertFalse(backend.project.isNode)
                assertFalse(backend.project.isSwift)
                assertEquals("omnivoice", backend.project.name)

                // 2) Python: ifixai-ai/iFixAi
                assertEquals(UiProjectFolderKind.PYTHON, backend.projects.inspectProjectFolder(pyIFixAi.toString()))
                val importFixAi = backend.projects.importExternalProject(pyIFixAi.toString())
                assertTrue(importFixAi.success, importFixAi.message)
                assertTrue(backend.project.isPython)
                assertEquals("ifixai", backend.project.name)

                // 3) TypeScript / Node: ranxianglei/billion-context
                assertEquals(UiProjectFolderKind.NODE, backend.projects.inspectProjectFolder(tsBillionContext.toString()))
                val importBillion = backend.projects.importExternalProject(tsBillionContext.toString())
                assertTrue(importBillion.success, importBillion.message)
                assertTrue(backend.project.isNode)
                assertEquals("billion-context", backend.project.name)

                // 4) TypeScript / Node: tashfeenahmed/freellmapi
                assertEquals(UiProjectFolderKind.NODE, backend.projects.inspectProjectFolder(tsFreeLlmApi.toString()))
                val importFreeLlm = backend.projects.importExternalProject(tsFreeLlmApi.toString())
                assertTrue(importFreeLlm.success, importFreeLlm.message)
                assertTrue(backend.project.isNode)
                assertEquals("@freellmapi/monorepo", backend.project.name)

                // 5) Kotlin: 1013503897/jadx-headless-mcp
                assertEquals(UiProjectFolderKind.GRADLE, backend.projects.inspectProjectFolder(ktJadxMcp.toString()))
                val importJadx = backend.projects.importExternalProject(ktJadxMcp.toString())
                assertTrue(importJadx.success, importJadx.message)

                // 6) Kotlin: realidkroo/musicfy
                assertEquals(UiProjectFolderKind.GRADLE, backend.projects.inspectProjectFolder(ktMusicfy.toString()))
                val importMusicfy = backend.projects.importExternalProject(ktMusicfy.toString())
                assertTrue(importMusicfy.success, importMusicfy.message)

                assertEquals(6, manager.list().size)
            }
        } finally {
            backend.close()
        }
    }
}
