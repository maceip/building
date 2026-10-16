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

class TrendingAndroidProjectsIntegrationTest {

    @Test
    fun importsRealGitHubTrendingAndroidRepositories() = withTempDir("trending-android-test") { temp ->
        val reposRoot = Path.of("/Users/mac/.gemini/antigravity/worktrees/building/restructure_settings_and_menus/verification_repos/trending_android")
        val clearPdf = reposRoot.resolve("trending-clearpdf")
        val bitChord = reposRoot.resolve("trending-bitchord")

        assertTrue(Files.isDirectory(clearPdf), "trending-clearpdf must exist")
        assertTrue(Files.isDirectory(bitChord), "trending-bitchord must exist")

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
}
