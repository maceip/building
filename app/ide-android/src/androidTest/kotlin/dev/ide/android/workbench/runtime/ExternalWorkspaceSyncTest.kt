package dev.ide.android.workbench.runtime

import dev.ide.android.workbench.ui.runtimeExternalSyncDetails
import dev.ide.android.workbench.ui.runtimeExternalSyncSummary
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Pure host-JVM fixtures; never require a device, user folder, command execution, or network. */
class ExternalWorkspaceSyncTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Fixture(val root: Path, val store: ExternalWorkspaceStore, val tree: Tree) {
        fun importer() = if (System.getProperty("java.vm.name") == "Dalvik")
            ExternalWorkspaceSync(store, tree, AndroidExternalWorkspaceFiles::publishNew, AndroidExternalWorkspaceFiles::rename)
        else ExternalWorkspaceSync(store, tree)
        fun open() = importer().importTree("content://fixtures/tree/project", root)
        fun link() = requireNotNull(store.read(root))
        fun sync() = importer().synchronize(link())
        fun local(path: String, value: String) {
            val file = root.resolve(path)
            Files.createDirectories(file.parent)
            Files.write(file, value.toByteArray())
        }
        fun text(path: String) = String(Files.readAllBytes(root.resolve(path)))
    }

    private fun fixture(vararg files: Pair<String, String>): Fixture {
        val base = temporary.newFolder().toPath().toRealPath()
        val tree = Tree()
        files.forEach { (name, value) -> tree.put(name, value.toByteArray()) }
        val store = if (System.getProperty("java.vm.name") == "Dalvik")
            ExternalWorkspaceStore(base.resolve("links"), AndroidExternalWorkspaceFiles::rename)
        else ExternalWorkspaceStore(base.resolve("links"))
        return Fixture(base.resolve("project"), store, tree)
    }

    @Test fun editedExistingAndNewFilesReallyReachOriginalAndAdvancePersistentBaseline() {
        val f = fixture("index.js" to "old", "media/keep.bin" to "media")
        val original = f.open()
        f.local("index.js", "edited")
        f.local("new/feature.js", "new source")
        val result = f.sync()
        assertTrue(result.toString(), result.complete)
        assertEquals(2, result.pushed)
        assertEquals(0, result.pulled)
        assertEquals("edited", f.tree.text("index.js"))
        assertEquals("new source", f.tree.text("new/feature.js"))
        assertEquals("media", f.tree.text("media/keep.bin"))
        assertEquals(listOf("old"), f.tree.backups().map { String(it.bytes) })
        assertTrue(result.recoveryPaths.single().startsWith(".building-backup-"))
        assertNotEquals(original.baseline.getValue("index.js").sha256, f.link().baseline.getValue("index.js").sha256)
        assertTrue(f.link().revision > original.revision)
        // A new store/sync instance reuses the on-disk baseline; retained backups never leak into source.
        val second = f.sync()
        assertTrue(second.toString(), second.complete)
        assertEquals(0, second.pushed)
        assertEquals(0, second.pulled)
        assertFalse(Files.exists(f.root.resolve(result.recoveryPaths.single())))
    }

    @Test fun providerChangesAndNewMediaPullIntoWorkingCopy() {
        val f = fixture("index.js" to "old")
        f.open()
        f.tree.put("index.js", "remote update".toByteArray())
        val binary = byteArrayOf(0, -1, 1, 2, 3, 0)
        f.tree.put("assets/image.png", binary)
        val result = f.sync()
        if (System.getProperty("java.vm.name") == "Dalvik" && android.os.Build.VERSION.SDK_INT < 30) {
            assertFalse(result.complete)
            assertEquals(0, result.pulled)
            assertEquals("old", f.text("index.js"))
            assertFalse(Files.exists(f.root.resolve("assets/image.png")))
            assertTrue(result.failures.single().reason.contains("Android 11"))
            return
        }
        assertTrue(result.toString(), result.complete)
        assertEquals(2, result.pulled)
        assertEquals("remote update", f.text("index.js"))
        assertArrayEquals(binary, Files.readAllBytes(f.root.resolve("assets/image.png")))
        assertEquals("remote update", f.tree.text("index.js"))
        assertTrue(runtimeExternalSyncDetails(result).contains("Reopen affected editor files"))
    }

    @Test fun bothSidesChangedRetainsBothAndOriginalBaseline() {
        val f = fixture("index.js" to "base")
        val original = f.open()
        f.local("index.js", "local edit")
        f.tree.put("index.js", "external edit".toByteArray())
        val result = f.sync()
        assertFalse(result.complete)
        assertEquals(listOf("index.js"), result.conflicts.map { it.path })
        assertEquals("local edit", f.text("index.js"))
        assertEquals("external edit", f.tree.text("index.js"))
        assertEquals(original.baseline, f.link().baseline)
        assertTrue(f.tree.backups().isEmpty())
        assertFalse(runtimeExternalSyncSummary(result).contains("sync complete"))
    }

    @Test fun sameContentDifferentMetadataIsNotConflictAndNewPathCollisionIs() {
        val f = fixture("index.js" to "base")
        f.open()
        f.tree.put("index.js", "base".toByteArray()) // provider lastModified changes, hash does not
        f.local("collision.txt", "local")
        f.tree.put("collision.txt", "remote".toByteArray())
        val result = f.sync()
        assertEquals(listOf("collision.txt"), result.conflicts.map { it.path })
        assertEquals("local", f.text("collision.txt"))
        assertEquals("remote", f.tree.text("collision.txt"))
    }

    @Test fun neitherSideDeletionIsPropagated() {
        val f = fixture("local-delete.txt" to "original", "remote-delete.txt" to "local copy")
        f.open()
        Files.delete(f.root.resolve("local-delete.txt"))
        f.tree.remove("remote-delete.txt")
        val result = f.sync()
        assertFalse(result.complete)
        assertEquals(2, result.conflicts.size)
        assertEquals("original", f.tree.text("local-delete.txt"))
        assertEquals("local copy", f.text("remote-delete.txt"))
        assertFalse(Files.exists(f.root.resolve("local-delete.txt")))
        assertFalse(f.tree.exists("remote-delete.txt"))
    }

    @Test fun revokedPermissionDoesNotAdvanceBaselineOrClaimSuccess() {
        val f = fixture("index.js" to "old")
        val original = f.open()
        f.local("index.js", "edited")
        f.tree.granted = false
        val result = f.sync()
        assertFalse(result.complete)
        assertEquals(0, result.pushed)
        assertTrue(result.failures.single().reason.contains("permission"))
        assertEquals(original.baseline, f.link().baseline)
        f.tree.granted = true
        assertEquals("old", f.tree.text("index.js"))
        assertTrue(f.sync().complete) // regrant + explicit retry, not a new baseline/import
        assertEquals("edited", f.tree.text("index.js"))
    }

    @Test fun unsupportedRenameNeverTruncatesOriginal() {
        val f = fixture("index.js" to "original")
        f.open()
        f.local("index.js", "edited")
        f.tree.renameSupported = false
        val result = f.sync()
        assertFalse(result.complete)
        assertEquals(0, result.pushed)
        assertTrue(result.failures.single().reason.contains("rename unsupported"))
        assertEquals("original", f.tree.text("index.js"))
        assertTrue(f.tree.staged().isEmpty())
    }

    @Test fun nullOrPartialProviderWriteNeverTouchesOriginal() {
        val f = fixture("index.js" to "original")
        val original = f.open()
        f.local("index.js", "edited value")
        f.tree.failWriteAfter = 3
        val result = f.sync()
        assertFalse(result.complete)
        assertEquals(0, result.pushed)
        assertEquals("original", f.tree.text("index.js"))
        assertEquals("edited value", f.text("index.js"))
        assertEquals(original.baseline, f.link().baseline)
        assertEquals("edi", String(f.tree.staged().single().bytes))
        assertTrue(result.recoveryPaths.single().contains(".building-stage-"))
    }

    @Test fun silentlyTruncatedProviderWriteIsDetectedByReadBackHash() {
        val f = fixture("index.js" to "original")
        f.open()
        f.local("index.js", "edited value")
        f.tree.silentWriteLimit = 2
        val result = f.sync()
        assertFalse(result.complete)
        assertEquals(0, result.pushed)
        assertEquals("original", f.tree.text("index.js"))
        assertTrue(result.conflicts.isNotEmpty())
    }

    @Test fun failureAfterOriginalBackupRestoresOriginalNameWithoutDeletingStaging() {
        val f = fixture("index.js" to "old")
        f.open()
        f.local("index.js", "new")
        f.tree.failPublish = true
        val result = f.sync()
        assertFalse(result.complete)
        assertEquals("old", f.tree.text("index.js"))
        assertEquals("new", String(f.tree.staged().single().bytes))
        assertEquals(0, result.pushed)
        assertEquals(1, result.failures.size)
        assertEquals(1, result.recoveryPaths.size)
    }

    @Test fun cancellationAfterBackupRetainsOriginalAndEditsAndReturnsPartialEvidence() {
        val f = fixture("index.js" to "old")
        f.open()
        f.local("index.js", "new")
        var cancelled = false
        f.tree.afterRename = { _, name -> if (name.startsWith(".building-backup-")) cancelled = true }
        val exception = assertThrows(ExternalSyncCancelled::class.java) {
            ExternalWorkspaceSync(f.store, f.tree) { if (cancelled) throw CancellationException("fixture cancelled") }.synchronize(f.link())
        }
        assertTrue(exception.report.cancelled)
        assertFalse(exception.report.complete)
        assertEquals("old", String(f.tree.backups().single().bytes))
        assertEquals("new", String(f.tree.staged().single().bytes))
        assertEquals("new", f.text("index.js"))
        assertEquals(2, exception.report.recoveryPaths.size)
        assertTrue(runtimeExternalSyncSummary(exception.report).startsWith("Folder sync cancelled"))
        assertEquals(2, f.link().recoveryPaths.size)
        // A fresh sync instance after process/screen restart still explains where the old original lives.
        cancelled = false
        f.tree.afterRename = null
        val retry = f.sync()
        assertFalse(retry.complete)
        assertEquals(exception.report.recoveryPaths.toSet(), retry.recoveryPaths.toSet())
        assertEquals("old", String(f.tree.backups().single().bytes))
    }

    @Test fun partialSyncReportsOnlyVerifiedCommitsAndStopsOnIoFailure() {
        val f = fixture("a.txt" to "old a", "b.txt" to "old b", "c.txt" to "old c")
        f.open()
        f.local("a.txt", "new a")
        f.local("b.txt", "new b")
        f.local("c.txt", "new c")
        f.tree.afterRename = { _, name -> if (name == "a.txt") f.tree.failWriteAfter = 1 }
        val result = f.sync()
        assertFalse(result.complete)
        assertEquals(1, result.pushed)
        assertEquals("new a", f.tree.text("a.txt"))
        assertEquals("old b", f.tree.text("b.txt"))
        assertEquals("old c", f.tree.text("c.txt"))
        assertEquals(listOf("b.txt"), result.failures.map { it.path })
        assertTrue(runtimeExternalSyncSummary(result).startsWith("Folder sync incomplete"))
    }

    @Test fun loadingCursorFailureAbortsBeforeAnyWrite() {
        val f = fixture("index.js" to "old")
        f.open()
        f.local("index.js", "edited")
        f.tree.listingFailure = true
        val result = f.sync()
        assertFalse(result.complete)
        assertEquals(0, result.pushed)
        f.tree.listingFailure = false
        assertEquals("old", f.tree.text("index.js"))
        assertTrue(f.tree.staged().isEmpty())
    }

    @Test fun partialProviderReadAbortsImportWithoutReturningPartialTree() {
        val f = fixture("index.js" to "whole value")
        f.tree.shortRead = true
        assertThrows(ExternalWorkspaceException::class.java) { f.open() }
        assertFalse(Files.exists(f.root))
        assertEquals(null, f.store.read(f.root))
    }

    @Test fun pathTraversalDuplicateNamesAndProviderCyclesAreRejected() {
        for (kind in listOf("traversal", "duplicate", "cycle")) {
            val f = fixture("index.js" to "safe")
            f.tree.badListing = kind
            assertThrows(Exception::class.java) { f.open() }
            assertFalse(Files.exists(f.root))
        }
    }

    @Test fun fileDirectoryCollisionAndLocalSymlinkCannotOverwriteOrEscape() {
        val f = fixture("file.txt" to "source")
        f.open()
        Files.delete(f.root.resolve("file.txt"))
        Files.createDirectory(f.root.resolve("file.txt"))
        val collision = f.sync()
        assertEquals(1, collision.conflicts.size)
        assertEquals("source", f.tree.text("file.txt"))
        val outside = temporary.newFile().toPath()
        Files.write(outside, "fixture outside".toByteArray())
        Files.createSymbolicLink(f.root.resolve("escape.txt"), outside)
        val blocked = f.sync()
        assertFalse(blocked.complete)
        assertTrue(blocked.failures.isNotEmpty())
        assertFalse(f.tree.exists("escape.txt"))
        assertEquals("fixture outside", String(Files.readAllBytes(outside)))
    }

    @Test fun dependencyCachesExcludedButSourceAndMediaAndExistingWorkspaceMetadataPreserved() {
        val f = fixture(
            "src/app.js" to "source", "assets/photo.jpg" to "binary bytes", ".platform/workspace.json" to "model",
            "node_modules/pkg/index.js" to "dependency", ".git/config" to "git fixture", "build/output.bin" to "generated",
        )
        f.open()
        assertEquals("binary bytes", f.text("assets/photo.jpg"))
        assertEquals("model", f.text(".platform/workspace.json"))
        assertFalse(Files.exists(f.root.resolve("node_modules")))
        assertFalse(Files.exists(f.root.resolve(".git")))
        f.local(".building/runtime.json", "local config")
        f.local(".platform/host-only.json", "new model data")
        f.local("node_modules/new/index.js", "regenerated")
        assertTrue(f.sync().complete)
        assertFalse(f.tree.exists(".building/runtime.json"))
        assertFalse(f.tree.exists(".platform/host-only.json"))
        assertEquals("dependency", f.tree.text("node_modules/pkg/index.js"))
        assertEquals("git fixture", f.tree.text(".git/config"))
    }

    @Test fun providerRaceAfterSnapshotIsConflictNotOverwrite() {
        val f = fixture("index.js" to "base")
        f.open()
        f.local("index.js", "local")
        f.tree.afterCreate = { name -> if (name.startsWith(".building-stage-")) f.tree.put("index.js", "racing external edit".toByteArray()) }
        val result = f.sync()
        assertFalse(result.complete)
        assertEquals(1, result.conflicts.size)
        assertEquals("racing external edit", f.tree.text("index.js"))
        assertEquals("local", f.text("index.js"))
        assertTrue(f.tree.backups().isEmpty())
    }

    @Test fun openHandleRaceAtPublishRestoresChangedOriginalAndRetainsLocalVersion() {
        val f = fixture("index.js" to "base")
        f.open()
        f.local("index.js", "local")
        f.tree.afterRename = { old, name ->
            if (old.startsWith(".building-stage-") && name == "index.js") {
                f.tree.backups().single().bytes = "external writer via old handle".toByteArray()
            }
        }
        val result = f.sync()
        assertFalse(result.complete)
        assertEquals(0, result.pushed)
        assertEquals(1, result.conflicts.size)
        assertEquals("external writer via old handle", f.tree.text("index.js"))
        assertEquals("local", String(f.tree.staged().single().bytes))
        assertEquals("local", f.text("index.js"))
    }

    @Test fun providerThrowsAfterBackupMutationReportsPossibleOriginalRecoveryName() {
        val f = fixture("index.js" to "base")
        f.open()
        f.local("index.js", "local")
        f.tree.afterRename = { _, name -> if (name.startsWith(".building-backup-")) throw IOException("fixture metadata failed after mutation") }
        val result = f.sync()
        assertFalse(result.complete)
        assertEquals(0, result.pushed)
        assertEquals("base", String(f.tree.backups().single().bytes))
        assertEquals("local", String(f.tree.staged().single().bytes))
        assertTrue(result.recoveryPaths.any { it.startsWith(".building-backup-") })
        assertTrue(result.recoveryPaths.any { it.startsWith(".building-stage-") })
    }

    @Test fun providerThrowsAfterStagingCreationStillReportsRecoveryName() {
        val f = fixture("index.js" to "base")
        f.open()
        f.local("index.js", "local")
        f.tree.afterCreate = { name -> if (name.startsWith(".building-stage-")) throw IOException("fixture metadata unavailable") }
        val result = f.sync()
        assertFalse(result.complete)
        assertEquals("base", f.tree.text("index.js"))
        assertTrue(result.recoveryPaths.single().startsWith(".building-stage-"))
        assertEquals(1, f.tree.staged().size)
    }

    @Test fun corruptBaselineFailsClosedRatherThanResettingAgainstLiveOriginal() {
        val f = fixture("index.js" to "base")
        f.open()
        f.local("index.js", "local")
        val path = f.root.parent.resolve("links").resolve(externalWorkspaceKey(f.root.toAbsolutePath().normalize().toString()) + ".json")
        Files.write(path, "{}".toByteArray())
        assertThrows(ExternalWorkspaceException::class.java) { f.sync() }
        assertEquals("base", f.tree.text("index.js"))
        assertEquals("local", f.text("index.js"))
    }

    @Test fun sizeEntryAndDepthBoundsRefuseBeforeOutwardMutation() {
        val f = fixture("index.js" to "base")
        f.open()
        f.local("index.js", "local")
        f.tree.declaredSize = 513L * 1024 * 1024
        val size = f.sync()
        assertFalse(size.complete)
        assertEquals(0, size.pushed)
        f.tree.declaredSize = null
        f.tree.tooMany = true
        val entries = f.sync()
        assertFalse(entries.complete)
        assertEquals(0, entries.pushed)
        f.tree.tooMany = false
        f.tree.put((1..65).joinToString("/") { "d$it" } + "/deep.txt", byteArrayOf(1))
        val depth = f.sync()
        assertFalse(depth.complete)
        assertEquals(0, depth.pushed)
        assertEquals("base", f.tree.text("index.js"))
    }

    /** Documents-provider contract fixture, not a second local-source copy masquerading as save-back. */
    private class Tree : ExternalDocumentTree {
        data class Node(val id: String, var name: String, val parent: String?, val directory: Boolean, var bytes: ByteArray = byteArrayOf(), var version: Long = 1)
        private val nodes = linkedMapOf("root" to Node("root", "fixture-project", null, true))
        private var nextId = 1
        var granted = true
        var renameSupported = true
        var failWriteAfter: Int? = null
        var silentWriteLimit: Int? = null
        var failPublish = false
        var listingFailure = false
        var shortRead = false
        var badListing: String? = null
        var declaredSize: Long? = null
        var tooMany = false
        var afterRename: ((String, String) -> Unit)? = null
        var afterCreate: ((String) -> Unit)? = null

        override fun requireAccess() { if (!granted) throw SecurityException("fixture access revoked") }
        override fun root(): ExternalDocument { requireAccess(); return metadata(nodes.getValue("root")) }
        override fun children(parent: ExternalDocument): List<ExternalDocument> {
            requireAccess()
            if (listingFailure) throw ExternalWorkspaceException("Fixture provider listing still loading.")
            val real = nodes.values.filter { it.parent == parent.id }.map(::metadata)
            if (parent.id == "root") {
                if (tooMany) return List(20_001) { ExternalDocument("many-$it", "file-$it", false, 0) }
                when (badListing) {
                    "traversal" -> return real + ExternalDocument("bad", "../escape", false, 0)
                    "duplicate" -> return real + real.first().copy(id = "duplicate")
                    "cycle" -> return real + metadata(nodes.getValue("root"))
                }
            }
            return real
        }
        override fun read(document: ExternalDocument): InputStream {
            requireAccess()
            val bytes = nodes.getValue(document.id).bytes
            return ByteArrayInputStream(if (shortRead) bytes.copyOf(bytes.size / 2) else bytes)
        }
        override fun create(parent: ExternalDocument, name: String, directory: Boolean): ExternalDocument {
            requireAccess()
            check(nodes.values.none { it.parent == parent.id && it.name == name })
            val node = Node("id-${nextId++}", name, parent.id, directory)
            nodes[node.id] = node
            afterCreate?.invoke(name)
            return metadata(node)
        }
        override fun writeStaged(document: ExternalDocument): OutputStream {
            requireAccess()
            check(document.name.startsWith(".building-stage-"))
            val node = nodes.getValue(document.id)
            return object : OutputStream() {
                private val bytes = ByteArrayOutputStream()
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(buffer: ByteArray, offset: Int, count: Int) {
                    requireAccess()
                    val remaining = ((failWriteAfter ?: silentWriteLimit ?: Int.MAX_VALUE) - bytes.size()).coerceAtLeast(0)
                    val size = minOf(remaining, count)
                    bytes.write(buffer, offset, size)
                    node.bytes = bytes.toByteArray()
                    node.version++
                    if (failWriteAfter != null && size < count) throw IOException("fixture partial provider write")
                }
            }
        }
        override fun rename(document: ExternalDocument, name: String): ExternalDocument {
            requireAccess()
            check(renameSupported)
            val node = nodes.getValue(document.id)
            if (failPublish && node.name.startsWith(".building-stage-") && !name.startsWith(".building-")) throw IOException("fixture failed publish")
            check(nodes.values.none { it.id != node.id && it.parent == node.parent && it.name == name })
            val oldName = node.name
            node.name = name
            node.version++
            afterRename?.invoke(oldName, name)
            return metadata(node)
        }

        fun put(relative: String, bytes: ByteArray) {
            var parent = nodes.getValue("root")
            val parts = relative.split('/')
            parts.dropLast(1).forEach { name ->
                parent = nodes.values.firstOrNull { it.parent == parent.id && it.name == name }
                    ?: Node("id-${nextId++}", name, parent.id, true).also { nodes[it.id] = it }
            }
            val existing = nodes.values.firstOrNull { it.parent == parent.id && it.name == parts.last() }
            if (existing != null) { existing.bytes = bytes.copyOf(); existing.version++ }
            else Node("id-${nextId++}", parts.last(), parent.id, false, bytes.copyOf()).also { nodes[it.id] = it }
        }
        fun remove(relative: String) { nodes.remove(find(relative)?.id) }
        fun exists(relative: String) = find(relative) != null
        fun text(relative: String) = String(requireNotNull(find(relative)) { "Missing fixture path: $relative" }.bytes)
        fun backups() = nodes.values.filter { it.name.startsWith(".building-backup-") }
        fun staged() = nodes.values.filter { it.name.startsWith(".building-stage-") }
        private fun find(relative: String): Node? {
            var parent = nodes.getValue("root")
            for (name in relative.split('/')) parent = nodes.values.firstOrNull { it.parent == parent.id && it.name == name } ?: return null
            return parent
        }
        private fun metadata(node: Node) = ExternalDocument(node.id, node.name, node.directory, if (node.directory) null else declaredSize ?: node.bytes.size.toLong(), node.version, renameSupported)
    }
}
