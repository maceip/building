package dev.ide.core

import dev.ide.core.node.NodeModuleType
import dev.ide.core.node.NodeProjectImporter
import dev.ide.core.project.ImportableKind
import dev.ide.core.project.ProjectManager
import dev.ide.core.sync.ProjectSyncService
import dev.ide.model.BuildSystemId
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.UiProjectFolderKind
import dev.ide.workbench.runtime.NodeProjectBridge
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeProjectIntegrationTest {
    @Test fun importsTypeScriptNodeThroughTheNormalPickerTreeEditorAndRuntimeRoot() = withTempDir("node-import") { temp ->
        val source = temp.resolve("hono-source")
        writeHonoPackage(source)
        write(source, "node_modules/vendor/hidden.js", "throw new Error('do not index dependencies')")
        val manager = ProjectManager.desktop(temp.resolve("projects"))
        val backend = IdeServicesBackend(manager = manager)
        try {
            runBlocking {
                assertEquals(UiProjectFolderKind.NODE, backend.projects.inspectProjectFolder(source.toString()))
                val imported = backend.projects.importExternalProject(source.toString())
                assertTrue(imported.success, imported.message)
                val editorRoot = Path.of(assertNotNull(imported.rootPath))
                val root = editorRoot.toRealPath()
                assertTrue(backend.project.isNode)
                assertEquals("@example/hono-server", backend.project.name)
                assertEquals(1, backend.project.moduleCount)
                assertFalse(backend.project.compatibility, "Node is not Gradle compatibility mode")
                assertNull(backend.projects.unrecognizedProjectInfo())
                assertNull(backend.projects.compatibilityInfo())
                assertTrue(manager.list().single().isNode)
                assertTrue(backend.build.runTasks().none { it.id.startsWith("gradle:") || it.id.startsWith("jvm:") }, "Node must not be offered the JVM build pipeline")

                val tree = flatten(backend.files.fileTree())
                for (relative in listOf("package.json", "src/index.ts", "test/server.test.ts", "tsconfig.json", ".npmrc")) {
                    assertTrue(
                        tree.any { it.filePath?.let { path -> Path.of(path).toRealPath() } == root.resolve(relative) },
                        "$relative must be editable from the Project tree; paths=${tree.mapNotNull { it.filePath }}",
                    )
                }
                assertFalse(tree.any { it.name == "node_modules" || it.name == "hidden.js" })
                assertFalse(tree.any { it.name == "src.index" }, "Node directories aren't compacted into Java packages")
                val entry = editorRoot.resolve("src/index.ts")
                val edited = "import { Hono } from 'hono'; export const app = new Hono().get('/', c => c.text('saved edit'));"
                backend.editor.saveFile(entry.toString(), edited)
                assertEquals(edited, entry.readText())
                assertTrue(backend.editor.analyze(entry.toString(), edited).isEmpty(), "A TypeScript buffer must not get Java diagnostics")
                val run = assertNotNull(NodeProjectBridge.inspect(root))
                assertEquals(root, run.root)
                assertEquals("src/index.ts", run.entryFile)
                assertEquals("npm:start", run.selectedTargetId)
                assertTrue(run.targets.any { it.id == "npm:build" })
                assertFalse(Files.exists(root.resolve("executed-on-import")), "No lifecycle script runs during import/open")
                assertFalse(Files.exists(source.resolve(".platform")), "Ordinary import leaves its source untouched")
            }
        } finally { backend.close(); manager.dispose() }
    }

    @Test
    fun importsRealGitHubNodeRepositories() = withTempDir("node-real-repos") { temp ->
        val reposRoot = Path.of("/Users/mac/.gemini/antigravity/worktrees/building/restructure_settings_and_menus/verification_repos")
        val chalk = reposRoot.resolve("node-chalk")
        val express = reposRoot.resolve("node-express")
        if (!Files.isDirectory(chalk) || !Files.isDirectory(express)) return@withTempDir

        val manager = ProjectManager.desktop(temp.resolve("projects"))
        val backend = IdeServicesBackend(manager = manager)
        try {
            runBlocking {
                // 1) node-chalk
                assertEquals(UiProjectFolderKind.NODE, backend.projects.inspectProjectFolder(chalk.toString()))
                val importedChalk = backend.projects.importExternalProject(chalk.toString())
                assertTrue(importedChalk.success, importedChalk.message)
                assertTrue(backend.project.isNode)
                assertEquals(1, backend.project.moduleCount)
                assertFalse(backend.project.compatibility)

                // 2) node-express
                assertEquals(UiProjectFolderKind.NODE, backend.projects.inspectProjectFolder(express.toString()))
                val importedExpress = backend.projects.importExternalProject(express.toString())
                assertTrue(importedExpress.success, importedExpress.message)
                assertTrue(backend.project.isNode)
                assertEquals(1, backend.project.moduleCount)
                assertFalse(backend.project.compatibility)

                assertEquals(2, manager.list().size)
                assertTrue(manager.list().all { it.isNode })
            }
        } finally { backend.close(); manager.dispose() }
    }

    @Test fun cloneAdoptionAndReopenKeepTheNodeModelAndScriptEditsCanSync() = withTempDir("node-adopt") { temp ->
        val manager = ProjectManager.desktop(temp.resolve("projects"))
        val project = manager.projectsRoot.resolve("cloned-node")
        writeHonoPackage(project)
        try {
            assertEquals(ImportableKind.NODE, manager.adoptFolderInPlace(project))
            manager.open(project.toString()).use { engine ->
                assertEquals(NodeModuleType.BUILD_SYSTEM, engine.store.workspace.projects.single().buildSystemId)
                assertEquals("node", engine.modules().single().type.id)
                assertEquals(project.toRealPath(), Path.of(engine.modules().single().dir.path).toRealPath())
                assertFalse(engine.isUnrecognizedProject())
                assertFalse(engine.isSyncStale())
                write(project, "package.json", """{"name":"@example/hono-server","scripts":{"dev":"tsx watch src/index.ts","test":"node --test"}}""")
                assertTrue(engine.isSyncStale())
                val outcome = runBlocking { engine.syncFromBuildFiles() }
                assertTrue(outcome.ok, outcome.message)
                assertFalse(engine.isSyncStale())
                assertEquals("npm:dev", NodeProjectBridge.inspect(project)?.selectedTargetId)
            }
            manager.open(project.toString()).use { reopened ->
                assertEquals(NodeModuleType.BUILD_SYSTEM, reopened.store.workspace.projects.single().buildSystemId)
                assertEquals("Node.js", reopened.modules().single().type.displayName)
                assertFalse(reopened.isCompatibilityMode())
            }
        } finally { manager.dispose() }
    }

    @Test fun anEarlierUnknownNodeImportUpgradesOnOpenWithoutExecutingScripts() = withTempDir("node-upgrade") { temp ->
        val manager = ProjectManager.desktop(temp.resolve("projects"))
        val project = manager.projectsRoot.resolve("legacy-node")
        writeHonoPackage(project)
        try {
            IdeServices.adoptPlainFolderAt(project, IdeServices.defaultDesktopSdk(), env = manager.env, origin = "https://example.test/hono.git")
            assertTrue(manager.list().single().unrecognized)
            manager.open(project.toString()).use { engine ->
                assertEquals(NodeModuleType.BUILD_SYSTEM, engine.store.workspace.projects.single().buildSystemId)
                assertEquals("node", engine.modules().single().type.id)
                assertFalse(engine.isUnrecognizedProject())
                assertFalse(Files.exists(project.resolve("executed-on-import")))
            }
            assertTrue(manager.list().single().isNode)
            assertFalse(manager.list().single().unrecognized)
        } finally { manager.dispose() }
    }

    @Test fun packageJsonDoesNotReplaceNativeOrGradleOwnership() = withTempDir("node-precedence") { temp ->
        val manager = ProjectManager.desktop(temp.resolve("projects"))
        try {
            manager.create("java-library", mapOf("name" to "native", "packageName" to "example.nativeproject")).use { native ->
                write(native.workspaceRoot, "package.json", """{"name":"tooling-only","scripts":{"lint":"eslint ."}}""")
                assertEquals(ImportableKind.CODE_ASSIST, manager.inspectFolder(native.workspaceRoot))
                assertNull(NodeProjectImporter().detect(native.workspaceRoot))
                assertEquals(BuildSystemId.NATIVE, native.store.workspace.projects.single().buildSystemId)
                assertFalse(native.isCompatibilityMode())
            }
            val gradle = temp.resolve("gradle")
            writeHonoPackage(gradle)
            write(gradle, "settings.gradle", "rootProject.name = 'mixed'\ninclude ':app'")
            write(gradle, "app/build.gradle", "plugins { id 'java-library' }")
            assertEquals(ImportableKind.EXTERNAL, manager.inspectFolder(gradle))
            assertEquals(BuildSystemId.GRADLE_COMPAT, ProjectSyncService.importerFor(manager.env.platform.extensions, gradle)?.id)
        } finally { manager.dispose() }
    }

    @Test fun malformedOrEscapingPackageJsonDoesNotCreateAnImportedModel() = withTempDir("node-invalid") { temp ->
        val manager = ProjectManager.desktop(temp.resolve("projects"))
        val source = temp.resolve("source")
        try {
            for (text in listOf("[1,2]", "{ broken", "null")) {
                write(source, "package.json", text)
                assertEquals(ImportableKind.NONE, manager.inspectFolder(source))
                assertNull(manager.importExternalProject(source))
                assertTrue(manager.list().isEmpty())
            }
            Files.delete(source.resolve("package.json"))
            val outside = temp.resolve("outside.json")
            outside.writeText("{}")
            Files.createSymbolicLink(source.resolve("package.json"), outside)
            assertEquals(ImportableKind.NONE, manager.inspectFolder(source))
            assertFalse(Files.exists(source.resolve(".platform")))
        } finally { manager.dispose() }
    }

    @Test fun linkedPrivateWorkspaceOpensInPlaceAndRemainsInThePickerAcrossRestart() = withTempDir("node-linked") { temp ->
        val privateRoot = temp.resolve("external-workspaces")
        val working = privateRoot.resolve("binding-123/project")
        writeHonoPackage(working)
        val projects = temp.resolve("projects")
        val manager = ProjectManager.desktop(projects, permittedInPlaceRoots = listOf(privateRoot))
        val backend = IdeServicesBackend(manager = manager)
        try {
            runBlocking {
                val result = backend.projects.openFolderInPlace(working.toString())
                assertTrue(result.success, result.message)
                assertEquals(working.toRealPath(), Path.of(assertNotNull(result.rootPath)))
                assertEquals(working.toRealPath(), Path.of(backend.project.rootPath))
                assertEquals(1, backend.projects.projectEpoch.value)
                val repeated = backend.projects.openFolderInPlace(working.toRealPath().toString())
                assertTrue(repeated.success, repeated.message)
                assertEquals(1, backend.projects.projectEpoch.value, "No duplicate engine for the same canonical root")
                assertEquals(working.toRealPath().toString(), manager.list().single().rootPath)
                assertTrue(manager.list().single().isNode)
                assertTrue(Files.list(projects).use { !it.findAny().isPresent }, "No second source copy in projectsRoot")
            }
        } finally { backend.close(); manager.dispose() }
        val restarted = ProjectManager.desktop(projects, permittedInPlaceRoots = listOf(privateRoot))
        try {
            assertEquals(working.toRealPath().toString(), restarted.list().single().rootPath)
        } finally { restarted.dispose() }
        val untrusted = ProjectManager.desktop(projects)
        try { assertTrue(untrusted.list().isEmpty(), "Preferences alone cannot authorize a private workspace") }
        finally { untrusted.dispose() }
    }

    @Test fun theNormalImportPathRetainsAnAuthorizedLinkedNodeRoot() = withTempDir("node-picker-linked") { temp ->
        val privateRoot = temp.resolve("external-workspaces")
        val working = privateRoot.resolve("linked-picker/project")
        writeHonoPackage(working)
        val manager = ProjectManager.desktop(temp.resolve("projects"), permittedInPlaceRoots = listOf(privateRoot))
        val backend = IdeServicesBackend(manager = manager)
        try {
            runBlocking {
                assertEquals(UiProjectFolderKind.NODE, backend.projects.inspectProjectFolder(working.toString()))
                val result = backend.projects.importExternalProject(working.toString())
                assertTrue(result.success, result.message)
                assertEquals(working.toRealPath(), Path.of(assertNotNull(result.rootPath)))
                assertEquals(working.toRealPath(), Path.of(backend.project.rootPath))
                assertEquals(working.toRealPath().toString(), manager.list().single().rootPath)
                assertTrue(backend.project.isNode)
                assertTrue(Files.list(manager.projectsRoot).use { !it.findAny().isPresent }, "Normal picker import must not make a second copy")
                assertFalse(Files.exists(working.resolve("executed-on-import")))
            }
        } finally { backend.close(); manager.dispose() }
    }

    @Test fun authorizedLinkedGradleKeepsExplicitImportAndPlainFoldersCanOpen() = withTempDir("linked-import") { temp ->
        val privateRoot = temp.resolve("external-workspaces")
        val gradle = privateRoot.resolve("gradle-binding/project")
        write(gradle, "settings.gradle", "rootProject.name = 'linked-gradle'\ninclude ':app'")
        write(gradle, "app/build.gradle", "plugins { id 'java-library' }")
        val plain = privateRoot.resolve("plain-binding/project")
        write(plain, "notes.txt", "plain")
        val manager = ProjectManager.desktop(temp.resolve("projects"), permittedInPlaceRoots = listOf(privateRoot))
        try {
            assertEquals(ImportableKind.EXTERNAL, manager.inspectFolder(gradle))
            assertFails { manager.openFolderInPlace(gradle) }
            assertFalse(Files.exists(gradle.resolve(".platform")), "Direct open never runs the foreign importer")
            assertNotNull(manager.importExternalProject(gradle)).use { engine ->
                assertEquals(gradle.toRealPath(), engine.workspaceRoot.toRealPath())
                assertEquals(BuildSystemId.GRADLE_COMPAT, engine.store.workspace.projects.single().buildSystemId)
            }
            assertNotNull(manager.importExternalProject(plain)).use { engine ->
                assertEquals(plain.toRealPath(), engine.workspaceRoot.toRealPath())
                assertTrue(engine.isUnrecognizedProject())
            }
            assertEquals(setOf(gradle.toRealPath().toString(), plain.toRealPath().toString()), manager.list().map { it.rootPath }.toSet())
            assertTrue(Files.list(manager.projectsRoot).use { !it.findAny().isPresent })
        } finally { manager.dispose() }
    }

    @Test fun hostPathAliasAndItsCanonicalLocationAuthorizeTheSamePrivateRoot() = withTempDir("node-alias") { temp ->
        val actual = Files.createDirectories(temp.resolve("actual"))
        val alias = temp.resolve("host-alias")
        Files.createSymbolicLink(alias, actual)
        val working = actual.resolve("external-workspaces/binding/project")
        writeHonoPackage(working)
        val manager = ProjectManager.desktop(
            temp.resolve("projects"), permittedInPlaceRoots = listOf(alias.resolve("external-workspaces")),
        )
        try {
            assertEquals(working.toRealPath(), manager.inPlaceFolder(alias.resolve("external-workspaces/binding/project")))
            assertEquals(working.toRealPath(), manager.inPlaceFolder(working.toRealPath()))
            manager.openFolderInPlace(working.toRealPath()).use { engine ->
                assertEquals(working.toRealPath(), engine.workspaceRoot.toRealPath())
            }
            assertEquals(working.toRealPath().toString(), manager.list().single().rootPath)
        } finally { manager.dispose() }
    }

    @Test fun inPlaceOpeningRejectsUnapprovedPathsAndSymlinkBindingsWithoutWriting() = withTempDir("node-inplace-safety") { temp ->
        val privateRoot = Files.createDirectories(temp.resolve("external-workspaces"))
        val manager = ProjectManager.desktop(temp.resolve("projects"), permittedInPlaceRoots = listOf(privateRoot))
        val outside = temp.resolve("outside")
        writeHonoPackage(outside)
        try {
            assertFails { manager.openFolderInPlace(outside) }
            Files.createSymbolicLink(privateRoot.resolve("binding-escape"), outside)
            writeHonoPackage(outside.resolve("project"))
            assertFails { manager.openFolderInPlace(privateRoot.resolve("binding-escape/project")) }
            val binding = Files.createDirectories(privateRoot.resolve("binding-leaf"))
            Files.createSymbolicLink(binding.resolve("project"), outside)
            assertFails { manager.openFolderInPlace(binding.resolve("project")) }
            writeHonoPackage(privateRoot.resolve("nested/deeper/project"))
            assertFails { manager.openFolderInPlace(privateRoot.resolve("nested/deeper/project")) }
            Files.createSymbolicLink(manager.projectsRoot.resolve("outside-link"), outside)
            assertFails { manager.openFolderInPlace(manager.projectsRoot.resolve("outside-link")) }
            assertFalse(Files.exists(outside.resolve(".platform")))
            assertFalse(Files.exists(outside.resolve("project/.platform")))
            assertTrue(manager.list().isEmpty())
        } finally { manager.dispose() }
    }

    @Test fun approvedPlainWorkingFolderRemainsEditableWithoutInventedNodeModel() = withTempDir("plain-inplace") { temp ->
        val manager = ProjectManager.desktop(temp.resolve("projects"))
        val folder = manager.projectsRoot.resolve("plain")
        write(folder, "notes.txt", "unchanged")
        try {
            manager.openFolderInPlace(folder).use { engine ->
                assertEquals(folder.toRealPath(), engine.workspaceRoot.toRealPath())
                assertTrue(engine.isUnrecognizedProject())
                assertTrue(engine.modules().isEmpty())
                assertEquals("unchanged", folder.resolve("notes.txt").readText())
            }
        } finally { manager.dispose() }
    }

    private fun writeHonoPackage(root: Path) {
        write(root, "package.json", """{
          "name":"@example/hono-server", "type":"module", "main":"src/index.ts", "packageManager":"pnpm@10.15.0",
          "scripts":{"preinstall":"node -e \"require('fs').writeFileSync('executed-on-import','unsafe')\"","build":"tsc","dev":"tsx watch src/index.ts","start":"node dist/index.js"},
          "dependencies":{"hono":"^4.0.0","@hono/node-server":"^2.1.5"}, "devDependencies":{"typescript":"^5.9.0"}
        }""")
        write(root, "src/index.ts", "import { Hono } from 'hono'; export const app = new Hono();")
        write(root, "test/server.test.ts", "import { app } from '../src/index.js';")
        write(root, "tsconfig.json", """{"compilerOptions":{"outDir":"dist","module":"NodeNext"},"include":["src"]}""")
        write(root, ".npmrc", "fund=false")
    }

    private fun write(root: Path, relative: String, text: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        file.writeText(text)
    }

    private fun flatten(node: TreeNode): List<TreeNode> = listOf(node) + node.children.flatMap(::flatten)
}
