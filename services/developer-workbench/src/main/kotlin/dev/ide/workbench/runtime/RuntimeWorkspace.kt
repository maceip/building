package dev.ide.workbench.runtime

import kotlinx.serialization.json.*
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** One optional disk mirror, only for external storage that cannot support npm's file semantics.
 * Source saves copy inward before Run; new guest files copy outward ONLY through explicit syncBack().
 * Existing source files are never replaced without an editor-wide write lease; changed files require merging. */
internal class RuntimeWorkspace(private val source: Path, cache: Path, private val beforeSyncCommit: (Path) -> Unit = {}) {
    private val id = MessageDigest.getInstance("SHA-256").digest(source.toRealPath().toString().toByteArray())
        .joinToString("") { "%02x".format(it) }.take(24)
    val root: Path = cache.resolve(id).resolve("project")
    private val ledger: Path = cache.resolve(id).resolve("source-hashes.json")
    private val excluded = setOf("node_modules", ".venv", "venv", ".git", ".building", "__pycache__")

    fun prepare(): RuntimeSyncReport {
        Files.createDirectories(root)
        val previous = hashes()
        val current = inventory(source)
        val conflicts = mutableListOf<String>()
        var copied = 0
        current.forEach { (relative, hash) ->
            val dest = safeProjectPath(root, relative, false)
            val old = previous[relative]
            val destHash = if (Files.isRegularFile(dest, LinkOption.NOFOLLOW_LINKS)) hashFile(dest) else null
            if (Files.isSymbolicLink(dest)) { conflicts += relative; return@forEach }
            when {
                hash == destHash -> Unit
                destHash == null || destHash == old -> {
                    Files.createDirectories(dest.parent)
                    Files.copy(source.resolve(relative), dest, StandardCopyOption.REPLACE_EXISTING)
                    copied++
                }
                hash == old -> Unit // Runtime changed it. Keep that version until explicit sync.
                else -> conflicts += relative
            }
        }
        // Remove only unchanged files in our private copy when the source was deleted.
        (previous.keys - current.keys).forEach { relative ->
            val dest = safeProjectPath(root, relative, false)
            if (Files.isRegularFile(dest, LinkOption.NOFOLLOW_LINKS)) {
                if (hashFile(dest) == previous[relative]) Files.delete(dest) else conflicts += relative
            }
        }
        require(conflicts.isEmpty()) { "Workspace copy has conflicting edits: ${conflicts.take(8).joinToString()}. Sync or resolve them before running." }
        saveHashes(current)
        return RuntimeSyncReport(copied)
    }

    fun syncBack(): RuntimeSyncReport {
        val previous = hashes()
        val currentSource = inventory(source)
        val currentRuntime = inventory(root)
        val conflicts = mutableListOf<String>()
        val updated = previous.toMutableMap()
        var copied = 0
        currentRuntime.forEach { (relative, hash) ->
            val old = previous[relative]
            val sourceHash = currentSource[relative]
            when {
                hash == sourceHash -> { updated[relative] = hash }
                hash == old -> Unit // Only the editor changed it; never overwrite newer source.
                sourceHash == null && old == null -> {
                    val dest = safeProjectPath(source, relative, false)
                    Files.createDirectories(dest.parent)
                    // No editor-wide write lease exists. Never replace an existing source file.
                    // Stage bytes first, then an atomic hard-link create where supported; otherwise
                    // copy with CREATE_NEW semantics. Both refuse a file created after inventory.
                    val staged = Files.createTempFile(dest.parent, ".runtime-sync-", ".tmp")
                    try {
                        Files.copy(root.resolve(relative), staged, StandardCopyOption.REPLACE_EXISTING)
                        if (hashFile(staged) != hash) { conflicts += relative; return@forEach }
                        beforeSyncCommit(dest)
                        if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) { conflicts += relative; return@forEach }
                        try {
                            try { Files.createLink(dest, staged) }
                            catch (unsupported: java.nio.file.FileSystemException) {
                                if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) throw java.nio.file.FileAlreadyExistsException(dest.toString())
                                Files.copy(staged, dest) // no REPLACE_EXISTING: never clobber a racing editor save
                            }
                            catch (unsupported: UnsupportedOperationException) { Files.copy(staged, dest) }
                            updated[relative] = hash
                            copied++
                        } catch (exists: java.nio.file.FileAlreadyExistsException) { conflicts += relative }
                    } finally { Files.deleteIfExists(staged) }
                }
                else -> conflicts += relative
            }
        }
        // Deliberately never propagate deletions into the user's workspace.
        saveHashes(updated)
        return RuntimeSyncReport(copied, conflicts)
    }

    private fun hashes(): Map<String, String> = if (Files.isRegularFile(ledger)) readObject(ledger).mapValues { it.value.jsonPrimitive.content } else emptyMap()
    private fun saveHashes(values: Map<String, String>) {
        Files.createDirectories(ledger.parent)
        RuntimeFiles.writeUtf8(ledger, runtimeJson.encodeToString(JsonObject.serializer(), JsonObject(values.mapValues { JsonPrimitive(it.value) })))
    }

    private fun inventory(path: Path): Map<String, String> {
        val found = linkedMapOf<String, String>()
        var bytes = 0L
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != path && dir.fileName.toString() in excluded) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!attrs.isSymbolicLink) { "Internal-copy mode does not import project symlinks: ${path.relativize(file)}" }
                if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                bytes += attrs.size()
                require(bytes <= 128L * 1024 * 1024 && found.size < 20_000) { "Workspace copy exceeds 128 MiB / 20,000 files; keep dependencies in the runtime copy or use an internal project." }
                found[path.relativize(file).toString().replace('\\', '/')] = hashFile(file)
                return FileVisitResult.CONTINUE
            }
        })
        return found
    }
}
