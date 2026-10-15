package dev.ide.core

import dev.ide.core.project.ProjectManager
import dev.ide.core.python.PythonModuleType
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.UiProjectFolderKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PythonProjectIntegrationTest {

    @Test
    fun importsPythonProjectThroughPickerAndVerifiesProjectState() = withTempDir("python-import") { temp ->
        val source = temp.resolve("python-tool")
        writePythonProject(source)
        val manager = ProjectManager.desktop(temp.resolve("projects"))
        val backend = IdeServicesBackend(manager = manager)
        try {
            runBlocking {
                assertEquals(UiProjectFolderKind.PYTHON, backend.projects.inspectProjectFolder(source.toString()))
                val imported = backend.projects.importExternalProject(source.toString())
                assertTrue(imported.success, imported.message)
                val editorRoot = Path.of(assertNotNull(imported.rootPath))
                val root = editorRoot.toRealPath()

                assertTrue(backend.project.isPython)
                assertFalse(backend.project.isNode)
                assertFalse(backend.project.isSwift)
                assertEquals("my-python-app", backend.project.name)
                assertEquals(1, backend.project.moduleCount)
                assertFalse(backend.project.compatibility, "Python is not Gradle compatibility mode")
                assertNull(backend.projects.unrecognizedProjectInfo())
                assertNull(backend.projects.compatibilityInfo())
                assertTrue(manager.list().single().isPython)

                val tree = flatten(backend.files.fileTree())
                for (relative in listOf("pyproject.toml", "requirements.txt", "main.py")) {
                    assertTrue(
                        tree.any { it.filePath?.let { path -> Path.of(path).toRealPath() } == root.resolve(relative) },
                        "$relative must be editable from the Project tree; paths=${tree.mapNotNull { it.filePath }}",
                    )
                }

                val entry = editorRoot.resolve("main.py")
                val edited = "print(\"Updated Python service!\")\n"
                backend.editor.saveFile(entry.toString(), edited)
                assertEquals(edited, entry.readText())
            }
        } finally {
            backend.close()
        }
    }

    @Test
    fun importsRealGitHubPythonRepositories() = withTempDir("python-real-repos") { temp ->
        val reposRoot = Path.of("/Users/mac/.gemini/antigravity/worktrees/building/restructure_settings_and_menus/verification_repos")
        val bottle = reposRoot.resolve("python-bottle")
        val click = reposRoot.resolve("python-click")
        if (!Files.isDirectory(bottle) || !Files.isDirectory(click)) return@withTempDir

        val manager = ProjectManager.desktop(temp.resolve("projects"))
        val backend = IdeServicesBackend(manager = manager)
        try {
            runBlocking {
                // 1) python-bottle (setup.py)
                assertEquals(UiProjectFolderKind.PYTHON, backend.projects.inspectProjectFolder(bottle.toString()))
                val importedBottle = backend.projects.importExternalProject(bottle.toString())
                assertTrue(importedBottle.success, importedBottle.message)
                assertTrue(backend.project.isPython)
                assertEquals(1, backend.project.moduleCount)
                assertFalse(backend.project.compatibility)

                // 2) python-click (pyproject.toml)
                assertEquals(UiProjectFolderKind.PYTHON, backend.projects.inspectProjectFolder(click.toString()))
                val importedClick = backend.projects.importExternalProject(click.toString())
                assertTrue(importedClick.success, importedClick.message)
                assertTrue(backend.project.isPython)
                assertEquals(1, backend.project.moduleCount)
                assertFalse(backend.project.compatibility)

                assertEquals(2, manager.list().size)
                assertTrue(manager.list().all { it.isPython })
            }
        } finally {
            backend.close()
        }
    }

    private fun writePythonProject(dir: Path) {
        write(
            dir,
            "pyproject.toml",
            """
            [project]
            name = "my-python-app"
            version = "0.1.0"
            description = "A sample Python project"
            """.trimIndent(),
        )
        write(
            dir,
            "requirements.txt",
            """
            requests>=2.28.0
            """.trimIndent(),
        )
        write(
            dir,
            "main.py",
            """
            import sys
            print(f"Python running on {sys.platform}")
            """.trimIndent(),
        )
    }

    private fun write(dir: Path, rel: String, content: String) {
        val target = dir.resolve(rel)
        Files.createDirectories(target.parent)
        target.writeText(content)
    }

    private fun flatten(node: TreeNode): List<TreeNode> =
        listOf(node) + node.children.flatMap { flatten(it) }
}
