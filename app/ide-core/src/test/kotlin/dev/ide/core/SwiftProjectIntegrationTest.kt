package dev.ide.core

import dev.ide.core.project.ProjectManager
import dev.ide.core.swift.SwiftModuleType
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

class SwiftProjectIntegrationTest {

    @Test
    fun importsSwiftPackageThroughPickerAndVerifiesProjectState() = withTempDir("swift-import") { temp ->
        val source = temp.resolve("swift-cli")
        writeSwiftPackage(source)
        val manager = ProjectManager.desktop(temp.resolve("projects"))
        val backend = IdeServicesBackend(manager = manager)
        try {
            runBlocking {
                assertEquals(UiProjectFolderKind.SWIFT, backend.projects.inspectProjectFolder(source.toString()))
                val imported = backend.projects.importExternalProject(source.toString())
                assertTrue(imported.success, imported.message)
                val editorRoot = Path.of(assertNotNull(imported.rootPath))
                val root = editorRoot.toRealPath()

                assertTrue(backend.project.isSwift)
                assertFalse(backend.project.isNode)
                assertFalse(backend.project.isPython)
                assertEquals("SwiftCliTool", backend.project.name)
                assertEquals(1, backend.project.moduleCount)
                assertFalse(backend.project.compatibility, "Swift is not Gradle compatibility mode")
                assertNull(backend.projects.unrecognizedProjectInfo())
                assertNull(backend.projects.compatibilityInfo())
                assertTrue(manager.list().single().isSwift)

                val tree = flatten(backend.files.fileTree())
                for (relative in listOf("Package.swift", "Sources/main.swift")) {
                    assertTrue(
                        tree.any { it.filePath?.let { path -> Path.of(path).toRealPath() } == root.resolve(relative) },
                        "$relative must be editable from the Project tree; paths=${tree.mapNotNull { it.filePath }}",
                    )
                }

                val entry = editorRoot.resolve("Sources/main.swift")
                val edited = "print(\"Hello from updated Swift!\")\n"
                backend.editor.saveFile(entry.toString(), edited)
                assertEquals(edited, entry.readText())
            }
        } finally {
            backend.close()
        }
    }

    @Test
    fun importsRealGitHubSwiftRepositories() = withTempDir("swift-real-repos") { temp ->
        val reposRoot = Path.of("/Users/mac/.gemini/antigravity/worktrees/building/restructure_settings_and_menus/verification_repos")
        val swiftLog = reposRoot.resolve("swift-log")
        val swiftDealer = reposRoot.resolve("swift-dealer")
        if (!Files.isDirectory(swiftLog) || !Files.isDirectory(swiftDealer)) return@withTempDir

        val manager = ProjectManager.desktop(temp.resolve("projects"))
        val backend = IdeServicesBackend(manager = manager)
        try {
            runBlocking {
                // 1) swift-log
                assertEquals(UiProjectFolderKind.SWIFT, backend.projects.inspectProjectFolder(swiftLog.toString()))
                val importedLog = backend.projects.importExternalProject(swiftLog.toString())
                assertTrue(importedLog.success, importedLog.message)
                assertTrue(backend.project.isSwift)
                assertEquals(1, backend.project.moduleCount)
                assertFalse(backend.project.compatibility)

                // 2) swift-dealer
                assertEquals(UiProjectFolderKind.SWIFT, backend.projects.inspectProjectFolder(swiftDealer.toString()))
                val importedDealer = backend.projects.importExternalProject(swiftDealer.toString())
                assertTrue(importedDealer.success, importedDealer.message)
                assertTrue(backend.project.isSwift)
                assertEquals(1, backend.project.moduleCount)
                assertFalse(backend.project.compatibility)

                assertEquals(2, manager.list().size)
                assertTrue(manager.list().all { it.isSwift })
            }
        } finally {
            backend.close()
        }
    }

    private fun writeSwiftPackage(dir: Path) {
        write(
            dir,
            "Package.swift",
            """
            // swift-tools-version:5.9
            import PackageDescription

            let package = Package(
                name: "SwiftCliTool",
                targets: [
                    .executableTarget(name: "SwiftCliTool", path: "Sources"),
                ]
            )
            """.trimIndent(),
        )
        write(
            dir,
            "Sources/main.swift",
            """
            print("Hello from Swift!")
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
