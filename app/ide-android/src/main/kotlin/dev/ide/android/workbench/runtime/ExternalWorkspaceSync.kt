package dev.ide.android.workbench.runtime

import dev.ide.android.AndroidImportLimits
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException

internal data class ExternalDocument(
    val id: String,
    val name: String,
    val directory: Boolean,
    val size: Long? = null,
    val modifiedMillis: Long? = null,
    val canRename: Boolean = false,
)

/** Only granted-tree operations. No delete and, deliberately, no in-place overwrite operation. */
internal interface ExternalDocumentTree {
    fun requireAccess()
    fun root(): ExternalDocument
    /** A complete listing or an exception, never a loading/partially read cursor. */
    fun children(parent: ExternalDocument): List<ExternalDocument>
    fun read(document: ExternalDocument): InputStream
    fun create(parent: ExternalDocument, name: String, directory: Boolean): ExternalDocument
    /** Called ONLY for a fresh, unpredictable staging document, never for a user's existing file. */
    fun writeStaged(document: ExternalDocument): OutputStream
    fun rename(document: ExternalDocument, name: String): ExternalDocument
}

internal data class ExternalSyncIssue(val path: String, val reason: String)

internal data class ExternalSyncReport(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val conflicts: List<ExternalSyncIssue> = emptyList(),
    val failures: List<ExternalSyncIssue> = emptyList(),
    val recoveryPaths: List<String> = emptyList(),
    val cancelled: Boolean = false,
) {
    val complete: Boolean get() = !cancelled && conflicts.isEmpty() && failures.isEmpty()
}

/** Cancellation remains cancellation, but callers can display the commits that already happened. */
internal class ExternalSyncCancelled(val report: ExternalSyncReport) : CancellationException("Folder sync cancelled; inspect its partial result.")

/**
 * Three-way content comparison, not mtimes. Sync is explicit and never propagates deletions.
 * Existing provider files are never opened for writing: stage, verify, retain the old document under a
 * unique backup name, then publish the staged document. Providers lacking rename fail closed.
 * SAF has no compare-and-swap transaction: recheck content/identity at each boundary, keep backups and
 * report any interrupted/ambiguous publication as partial, never as a generic success.
 */
internal class ExternalWorkspaceSync(
    private val store: ExternalWorkspaceStore,
    private val tree: ExternalDocumentTree,
    /** Atomically publish without replacing target. Implementations may consume the staged source. */
    private val publishNew: (target: Path, staged: Path) -> Unit = { target, staged -> Files.createLink(target, staged); Unit },
    private val moveLocal: (source: Path, target: Path) -> Unit = { source, target -> Files.move(source, target); Unit },
    private val checkCancelled: () -> Unit = AndroidImportLimits::checkInterrupted,
) {
    private data class Content(val hash: String, val size: Long)
    private data class RemoteFile(val document: ExternalDocument, val content: Content)
    private data class RemoteSnapshot(val root: ExternalDocument, val files: Map<String, RemoteFile>, val directories: Map<String, ExternalDocument>, val recoveries: Set<String>)
    private data class LocalSnapshot(val files: Map<String, Content>, val directories: Set<String>)
    private class Changed(message: String) : IOException(message)
    private val noOutput = object : OutputStream() { override fun write(value: Int) = Unit; override fun write(buffer: ByteArray, offset: Int, count: Int) = Unit }

    fun importTree(treeUri: String, workingRoot: Path): ExternalWorkspaceBinding {
        checkpoint()
        tree.requireAccess()
        require(!Files.exists(workingRoot, NOFOLLOW_LINKS)) { "A working copy already exists; it will not be overwritten." }
        val snapshot = remoteSnapshot(emptySet())
        Files.createDirectories(workingRoot)
        val base = linkedMapOf<String, ExternalFileBaseline>()
        try {
            snapshot.directories.keys.filter { it.isNotEmpty() }.sortedBy { it.count { c -> c == '/' } }.forEach { relative ->
                Files.createDirectory(localPath(workingRoot, relative))
            }
            snapshot.files.forEach { (relative, remote) ->
                checkpoint()
                val file = localPath(workingRoot, relative)
                val copied = tree.read(remote.document).use { input ->
                    Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                        digest(input, output, AndroidImportLimits.ByteBudget(), remote.document.size)
                    }
                }
                if (copied != remote.content) throw Changed("The selected folder changed while it was being opened. No link was created; try again.")
                base[relative] = remote.baseline()
            }
            checkpoint()
            return ExternalWorkspaceBinding(treeUri, snapshot.root.name, workingRoot.toRealPath().toString(), snapshot.root.id, baseline = base)
                .also(store::write)
        } catch (failure: Exception) {
            // Only our fresh, never-returned working copy is eligible for cleanup, without following links.
            removeStagingTree(workingRoot)
            throw failure
        }
    }

    fun synchronize(initial: ExternalWorkspaceBinding): ExternalSyncReport {
        var binding = initial
        val root = java.nio.file.Paths.get(binding.workingRoot)
        var pushed = 0
        var pulled = 0
        val conflicts = mutableListOf<ExternalSyncIssue>()
        val failures = mutableListOf<ExternalSyncIssue>()
        val recoveries = linkedSetOf<String>()
        fun report(cancelled: Boolean = false) = ExternalSyncReport(pushed, pulled, conflicts.toList(), failures.toList(), recoveries.toList(), cancelled)
        fun persist(next: ExternalWorkspaceBinding) { store.write(next); binding = next }
        fun reserve(vararg paths: String) {
            // Persist intent BEFORE any provider mutation, so a killed app never treats its backups as source.
            persist(binding.copy(revision = binding.revision + 1, recoveryPaths = binding.recoveryPaths + paths))
        }
        fun baseline(relative: String, remote: RemoteFile) {
            persist(binding.copy(revision = binding.revision + 1, baseline = binding.baseline + (relative to remote.baseline())))
        }
        try {
            checkpoint()
            tree.requireAccess()
            require(Files.isDirectory(root, NOFOLLOW_LINKS) && root.toRealPath() == root.toAbsolutePath().normalize()) { "The linked working copy is missing or was replaced by a symbolic link." }
            val remote = remoteSnapshot(binding.recoveryPaths)
            recoveries += remote.recoveries
            check(remote.root.id == binding.rootDocumentId) { "The granted folder identity changed. No files were synchronized." }
            val local = localSnapshot(root, binding)
            // Abort on incomplete snapshots before performing ANY outward write.
            val paths = (remote.files.keys + local.files.keys + binding.baseline.keys).sorted()
            for (relative in paths) {
                checkpoint()
                val outside = remote.files[relative]
                val inside = local.files[relative]
                val old = binding.baseline[relative]
                if (relative in remote.directories || relative in local.directories) {
                    conflicts += ExternalSyncIssue(relative, "A file and folder occupy the same path; both were kept.")
                    continue
                }
                if (outside == null && inside == null) continue // Never propagate either side's deletion.
                if (outside != null && inside == outside.content) {
                    if (old != outside.baseline()) baseline(relative, outside)
                    continue
                }
                if (old != null && (outside == null || inside == null)) {
                    conflicts += ExternalSyncIssue(relative, "Missing on one side. Deletions are not propagated; the surviving version was kept.")
                    continue
                }
                val pull = outside != null && (inside == null && old == null || inside?.hash == old?.sha256 && outside.content.hash != old?.sha256)
                val push = inside != null && (outside == null && old == null || outside?.content?.hash == old?.sha256 && inside.hash != old?.sha256)
                if (!pull && !push) {
                    conflicts += ExternalSyncIssue(relative, "Both versions changed or the same new path exists on both sides. Neither was overwritten; merge explicitly.")
                    continue
                }
                try {
                    if (pull) {
                        val source = requireNotNull(outside)
                        pull(root, relative, inside, source, recoveries)
                        pulled++
                        baseline(relative, source)
                    } else {
                        val source = requireNotNull(inside)
                        val result = push(root, relative, source, outside, binding.recoveryPaths, { stage, backup -> reserve(stage, backup) }, recoveries)
                        pushed++
                        baseline(relative, result)
                    }
                } catch (changed: Changed) {
                    conflicts += ExternalSyncIssue(relative, changed.message ?: "A file changed during sync. Both versions were kept.")
                } catch (failure: Exception) {
                    if (isCancellation(failure)) throw failure
                    failures += ExternalSyncIssue(relative, safeFailure(failure))
                    // A provider/ledger failure may invalidate later assumptions. Stop, preserving exact partial counts.
                    break
                }
            }
        } catch (failure: Exception) {
            if (isCancellation(failure)) throw ExternalSyncCancelled(report(cancelled = true)).also { it.initCause(failure) }
            failures += ExternalSyncIssue("", safeFailure(failure))
        }
        return report()
    }

    private fun push(
        root: Path,
        relative: String,
        expected: Content,
        old: RemoteFile?,
        recoveryPaths: Set<String>,
        reserve: (String, String) -> Unit,
        recoveries: MutableSet<String>,
    ): RemoteFile {
        val parts = externalRelativePath(relative)
        val parent = ensureRemoteParent(parts.dropLast(1), recoveryPaths)
        val originalName = parts.last()
        val current = uniqueChildren(parent)
        val existing = current.firstOrNull { it.name == originalName }
        if (old == null && existing != null || old != null && (existing == null || existing.directory || existing.id != old.document.id)) {
            throw Changed("The original path changed during sync. It was not overwritten.")
        }
        if (old != null && !requireNotNull(existing).canRename) {
            throw ExternalWorkspaceException("This provider cannot safely replace an existing file (rename unsupported). The original and local edits were kept.")
        }
        if (old != null) verifyRemote(requireNotNull(existing), old.content)
        val stageName = ".building-stage-${UUID.randomUUID()}"
        val backupName = ".building-backup-${UUID.randomUUID()}"
        val parentPath = parts.dropLast(1).joinToString("/")
        fun sibling(name: String) = if (parentPath.isEmpty()) name else "$parentPath/$name"
        val stagePath = sibling(stageName)
        val backupPath = sibling(backupName)
        reserve(stagePath, backupPath)
        checkpoint()
        tree.requireAccess()
        recoveries += stagePath
        val staged = tree.create(parent, stageName, false)
        require(staged.name == stageName && !staged.directory && staged.canRename && current.none { it.id == staged.id }) {
            "The provider did not create a safely publishable staging file. Original files were not written."
        }
        val local = localPath(root, relative)
        val copy = Files.newInputStream(local, NOFOLLOW_LINKS).use { input ->
            tree.writeStaged(staged).use { output -> digest(input, output, AndroidImportLimits.ByteBudget()) }
        }
        if (copy != expected || localContent(local) != expected) throw Changed("The working file changed during sync; staged bytes were retained, original unchanged.")
        verifyRemote(staged, expected)
        checkpoint()
        val before = uniqueChildren(parent).firstOrNull { it.name == originalName }
        if (old == null && before != null || old != null && (before == null || before.directory || before.id != old.document.id)) {
            throw Changed("The original path changed before publication. It was not overwritten; staged bytes were retained.")
        }
        var backup: ExternalDocument? = null
        var publishedDocument: ExternalDocument? = null
        try {
            if (old != null) {
                verifyRemote(requireNotNull(before), old.content)
                checkpoint()
                tree.requireAccess()
                // Record possible locations before provider calls: mutation can succeed then metadata fail.
                recoveries += backupPath
                // No truncation and no delete: the complete old document remains even if publication fails.
                backup = tree.rename(before, backupName)
                require(backup.name == backupName && !backup.directory) { "The provider could not preserve the original under its recovery name." }
                verifyRemote(backup, old.content)
            }
            checkpoint()
            if (uniqueChildren(parent).any { it.name == originalName }) throw Changed("Another writer created the destination. The original backup and staged edits were retained.")
            verifyRemote(staged, expected)
            if (backup != null) verifyRemote(backup, requireNotNull(old).content)
            tree.requireAccess()
            recoveries += relative // Publication may complete even when the provider throws afterwards.
            val published = tree.rename(staged, originalName)
            publishedDocument = published
            require(published.name == originalName && !published.directory) { "The provider published a different name. Inspect the recovery files before retrying." }
            val final = uniqueChildren(parent).singleOrNull { it.name == originalName }
                ?: throw Changed("The destination is ambiguous after publication. Inspect the recovery files; nothing was deleted.")
            require(final.id == published.id) { "The provider changed the published document identity." }
            verifyRemote(final, expected)
            if (backup != null) verifyRemote(backup, requireNotNull(old).content)
            recoveries.remove(stagePath)
            recoveries.remove(relative)
            return RemoteFile(final, expected)
        } catch (failure: Exception) {
            // Best-effort restoration never overwrites a path. If access vanished, original + staged data
            // remain at the pre-recorded names. SAF is not an atomic transaction; report every ambiguity.
            if (backup != null && !isCancellation(failure)) {
                try {
                    val occupant = uniqueChildren(parent).firstOrNull { it.name == originalName }
                    // If our published bytes are still present, move only OUR replacement aside before
                    // restoring the latest original (which another app may have written via an open handle).
                    if (publishedDocument != null && occupant?.id == publishedDocument.id &&
                        uniqueChildren(parent).none { it.name == stageName }
                    ) {
                        verifyRemote(occupant, expected)
                        tree.rename(occupant, stageName)
                        recoveries += stagePath
                    }
                    if (uniqueChildren(parent).none { it.name == originalName }) {
                        val restored = tree.rename(backup, originalName)
                        if (restored.name == originalName) {
                            recoveries.remove(backupPath)
                            recoveries.remove(relative)
                        }
                    }
                } catch (_: Exception) { /* report remains partial, never hide the failed publication */ }
            }
            throw failure
        }
    }

    private fun pull(root: Path, relative: String, expectedLocal: Content?, source: RemoteFile, recoveries: MutableSet<String>) {
        val target = localPath(root, relative)
        var operation = "create local staging"
        var staging: Path? = null
        try {
            val directory = store.freshStaging()
            staging = directory
            val staged = directory.resolve("incoming")
            operation = "read original file"
            val copied = tree.read(source.document).use { input ->
                Files.newOutputStream(staged, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                    digest(input, output, AndroidImportLimits.ByteBudget(), source.document.size)
                }
            }
            if (copied != source.content) throw Changed("The original changed while being read. The working file was kept.")
            verifyRemote(source.document, source.content)
            operation = "create working folders"
            createLocalParents(root, relative)
            val current = if (Files.isRegularFile(target, NOFOLLOW_LINKS)) localContent(target) else null
            if (current != expectedLocal || Files.exists(target, NOFOLLOW_LINKS) && current == null) {
                throw Changed("The working file changed while syncing. It was not replaced.")
            }
            checkpoint()
            operation = "publish working file"
            if (expectedLocal == null) {
                publishNew(target, staged)
            } else {
                // Retain the exact local inode before replacement; any racing writer's contents remain here.
                val backup = directory.resolve("previous-working-file")
                operation = "retain previous working file"
                moveLocal(target, backup)
                recoveries += backup.toString()
                if (localContent(backup) != expectedLocal) {
                    if (!Files.exists(target, NOFOLLOW_LINKS)) publishNew(target, backup)
                    throw Changed("A local save raced with sync. Its bytes were kept in folder-link recovery storage.")
                }
                operation = "publish working file"
                try { publishNew(target, staged) }
                catch (failure: Exception) {
                    if (!Files.exists(target, NOFOLLOW_LINKS)) publishNew(target, backup)
                    throw failure
                }
                // Keep previous-local bytes as crash/racing-save recovery, outside the exported workspace.
                return
            }
        } catch (failure: Exception) {
            if (isCancellation(failure) || failure is Changed || failure is ExternalWorkspaceException || failure is SecurityException) throw failure
            throw ExternalWorkspaceException("Could not $operation (${failure.javaClass.simpleName}). Original and recovery files were kept; sync is incomplete.").also { it.initCause(failure) }
        } finally {
            // A retained previous-working-file is user data, not disposable staging.
            staging?.let { if (!Files.exists(it.resolve("previous-working-file"), NOFOLLOW_LINKS)) removeStagingTree(it) }
        }
    }

    private fun ensureRemoteParent(parts: List<String>, ignored: Set<String>): ExternalDocument {
        var parent = tree.root()
        for ((index, part) in parts.withIndex()) {
            checkpoint()
            val path = parts.take(index + 1).joinToString("/")
            if (path in ignored || skippedDirectory(path)) throw Changed("A generated/recovery folder cannot be used as a source destination.")
            val children = uniqueChildren(parent)
            val found = children.firstOrNull { it.name == part }
            parent = if (found != null) {
                if (!found.directory) throw Changed("A file blocks a destination folder. It was kept.")
                found
            } else {
                tree.requireAccess()
                tree.create(parent, part, true).also { created ->
                    require(created.name == part && created.directory && children.none { it.id == created.id }) { "The provider could not create the requested folder safely." }
                }
            }
        }
        return parent
    }

    private fun remoteSnapshot(ignored: Set<String>): RemoteSnapshot {
        checkpoint()
        val root = tree.root()
        require(root.directory) { "The granted item is not a folder." }
        AndroidImportLimits.fileName(root.name)
        val files = linkedMapOf<String, RemoteFile>()
        val directories = linkedMapOf("" to root)
        val recoveries = linkedSetOf<String>()
        val budget = AndroidImportLimits.TreeBudget(root.id)
        fun visit(parent: ExternalDocument, prefix: String, depth: Int) {
            for (child in uniqueChildren(parent)) {
                budget.visit(child.id, depth + 1)
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                externalRelativePath(path)
                if (path in ignored) { recoveries += path; continue }
                if (child.directory) {
                    if (skippedDirectory(path)) continue
                    directories[path] = child
                    visit(child, path, depth + 1)
                } else {
                    val content = tree.read(child).use { digest(it, noOutput, budget.bytes, child.size) }
                    files[path] = RemoteFile(child, content)
                }
            }
        }
        visit(root, "", 0)
        return RemoteSnapshot(root, files, directories, recoveries)
    }

    private fun localSnapshot(root: Path, binding: ExternalWorkspaceBinding): LocalSnapshot {
        val files = linkedMapOf<String, Content>()
        val directories = linkedSetOf<String>()
        val bytes = AndroidImportLimits.ByteBudget()
        var entries = 0
        fun visit(directory: Path, prefix: String, depth: Int) {
            checkpoint()
            require(depth <= AndroidImportLimits.MAX_TREE_DEPTH) { "The working copy exceeds the folder depth limit." }
            Files.newDirectoryStream(directory).use { stream ->
                for (entry in stream) {
                    checkpoint()
                    require(++entries <= AndroidImportLimits.MAX_TREE_ENTRIES) { "The working copy contains too many files." }
                    val name = AndroidImportLimits.fileName(entry.fileName.toString())
                    val path = if (prefix.isEmpty()) name else "$prefix/$name"
                    externalRelativePath(path)
                    if (path in binding.recoveryPaths) continue
                    if (Files.isSymbolicLink(entry)) {
                        // npm's regenerable directory can contain links; never follow/import them.
                        if (skippedDirectory(path)) continue
                        throw ExternalWorkspaceException("The working copy contains a symbolic link. Sync refuses links rather than reading outside the workspace.")
                    }
                    if (Files.isDirectory(entry, NOFOLLOW_LINKS)) {
                        if (skippedDirectory(path)) continue
                        directories += path
                        visit(entry, path, depth + 1)
                    } else {
                        require(Files.isRegularFile(entry, NOFOLLOW_LINKS)) { "The working copy contains a non-regular file." }
                        // The importer creates its model locally. Never export new host metadata as source.
                        if ((path.startsWith(".platform/") || path.startsWith(".building/")) && path !in binding.baseline) continue
                        files[path] = Files.newInputStream(entry, NOFOLLOW_LINKS).use { digest(it, noOutput, bytes, Files.size(entry)) }
                    }
                }
            }
        }
        visit(root, "", 0)
        return LocalSnapshot(files, directories)
    }

    private fun uniqueChildren(parent: ExternalDocument): List<ExternalDocument> {
        checkpoint()
        val children = tree.children(parent)
        require(children.size <= AndroidImportLimits.MAX_TREE_ENTRIES) { "The provider returned too many folder entries." }
        val names = HashSet<String>()
        val ids = HashSet<String>()
        children.forEach {
            AndroidImportLimits.fileName(it.name)
            require(it.id.length in 1..4096 && it.id != parent.id && ids.add(it.id)) { "The provider returned repeated or cyclic document identities." }
            require(names.add(it.name)) { "The provider returned ambiguous duplicate file names." }
        }
        return children
    }

    private fun verifyRemote(document: ExternalDocument, expected: Content) {
        val actual = tree.read(document).use { digest(it, noOutput, AndroidImportLimits.ByteBudget()) }
        if (actual != expected) throw Changed("The provider file changed or was not written completely. Existing versions and recovery files were kept.")
    }

    private fun localContent(path: Path): Content = Files.newInputStream(path, NOFOLLOW_LINKS).use {
        digest(it, noOutput, AndroidImportLimits.ByteBudget(), Files.size(path))
    }

    private fun digest(input: InputStream, output: OutputStream, budget: AndroidImportLimits.ByteBudget, declaredSize: Long? = null): Content {
        val hash = MessageDigest.getInstance("SHA-256")
        val digestOutput = DigestOutputStream(output, hash)
        val size = AndroidImportLimits.copy(object : InputStream() {
            override fun read(): Int { checkpoint(); return input.read() }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { checkpoint(); return input.read(buffer, offset, length) }
        }, digestOutput, budget, declaredBytes = declaredSize)
        checkpoint()
        if (declaredSize != null && declaredSize >= 0 && declaredSize != size) throw ExternalWorkspaceException("A provider file was only partially read or changed size. No complete sync was reported.")
        return Content(hash.digest().joinToString("") { "%02x".format(it) }, size)
    }

    private fun localPath(root: Path, relative: String): Path {
        var path = root
        for (part in externalRelativePath(relative)) {
            path = path.resolve(part)
            require(!Files.isSymbolicLink(path)) { "A symbolic link blocks the workspace path." }
        }
        require(path.normalize().startsWith(root.toAbsolutePath().normalize()) && path != root)
        return path
    }

    private fun createLocalParents(root: Path, relative: String) {
        var parent = root
        for (part in externalRelativePath(relative).dropLast(1)) {
            parent = parent.resolve(part)
            require(!Files.isSymbolicLink(parent)) { "A symbolic link blocks the workspace folder." }
            if (!Files.exists(parent, NOFOLLOW_LINKS)) Files.createDirectory(parent)
            require(Files.isDirectory(parent, NOFOLLOW_LINKS)) { "A file blocks the workspace folder." }
        }
    }

    private fun skippedDirectory(path: String): Boolean = path.substringAfterLast('/') in setOf(
        "build", ".gradle", ".idea", "node_modules", ".git", ".venv", "venv", "__pycache__",
    ) || path == ".platform/caches" || path.startsWith(".platform/caches/")

    private fun RemoteFile.baseline() = ExternalFileBaseline(content.hash, content.size, document.id, document.modifiedMillis)
    private fun checkpoint() { checkCancelled(); AndroidImportLimits.checkInterrupted() }
    private fun isCancellation(failure: Exception) = failure is CancellationException || failure is InterruptedException || failure is InterruptedIOException || Thread.currentThread().isInterrupted
    private fun safeFailure(failure: Exception): String = when (failure) {
        is SecurityException -> "Folder permission was revoked or is read-only. Re-select the original folder to grant read/write access; local edits were kept."
        is ExternalWorkspaceException -> failure.message ?: "Folder sync did not complete; working files were kept."
        else -> "Folder sync stopped because the provider or local storage could not complete an operation. Inspect recovery files before retrying; partial changes are not a successful sync."
    }

    private fun removeStagingTree(root: Path) {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return
        // Temporary/import tree only; walk does not follow symbolic links.
        runCatching { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}
