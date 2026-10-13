package dev.ide.agent.impl

import dev.ide.agent.AgentWorkspace
import dev.ide.agent.DiagnosticInfo
import dev.ide.agent.FileChange
import dev.ide.agent.IncidentSourceSnapshot
import dev.ide.agent.IncidentTask
import dev.ide.agent.IncidentVerificationPlan
import dev.ide.agent.ProjectOverview
import dev.ide.agent.SymbolHit
import dev.ide.agent.TextEdit
import dev.ide.agent.TextMatch
import dev.ide.agent.WorkspaceEntry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** File/task digests only; aggregate SDK source revisions come from the injected canonical snapshotter. */
object IncidentDigests {
    fun bytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    fun file(path: Path): String {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Expected a regular immutable input." }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }

    fun entries(entries: Map<String, String>): String = bytes(buildString {
        entries.toSortedMap().forEach { (key, value) ->
            append(key.length).append(':').append(key).append(value.length).append(':').append(value)
        }
    }.toByteArray(Charsets.UTF_8))

    fun task(task: IncidentTask): String = entries(linkedMapOf(
        "id" to task.id, "kind" to task.kind.name, "command" to encode(task.command),
        "reports" to encode(task.reportFiles.sorted()), "tests" to encode(task.expectedTests.sorted()),
        "timeout" to task.timeoutMillis.toString(),
    ))

    private fun encode(values: List<String>) = values.joinToString("") { "${it.length}:$it" }
}

/** Reject traversal and EVERY symlink component, including dangling symlinks. */
internal fun incidentPath(root: Path, relative: String, existing: Boolean = true): Path {
    require(relative.isNotBlank() && relative.length <= 1024 && '\\' !in relative && '\u0000' !in relative)
    val raw = Paths.get(relative)
    require(!raw.isAbsolute && raw.none { it.toString() in setOf(".", "..", "") }) { "Only copy-relative paths are permitted." }
    val base = root.toAbsolutePath().normalize()
    val resolved = base.resolve(raw).normalize()
    require(resolved.startsWith(base) && resolved != base) { "Path is outside the isolated copy." }
    var cursor: Path? = resolved
    while (cursor != null) {
        require(!Files.isSymbolicLink(cursor)) { "Symbolic links are not permitted in repair workspaces." }
        cursor = cursor.parent
    }
    if (existing) require(Files.isRegularFile(resolved, NOFOLLOW_LINKS)) { "Expected an existing regular file." }
    return resolved
}

private fun allowedCopyName(path: String): Boolean = path.split('/').all { part ->
    !part.startsWith('.') && part !in setOf("local.properties", "gradle.properties") &&
        !Regex("(?i)(^|[._-])(secret|secrets|credential|credentials|keystore|signing|password|token|env)([._-]|$)").containsMatchIn(part) &&
        !part.endsWith(".jks", true) && !part.endsWith(".pem", true) && !part.endsWith(".p12", true)
}

internal fun editableIncidentSource(path: String): Boolean {
    val parts = path.lowercase().split('/')
    val name = parts.last()
    return allowedCopyName(path) && name !in setOf("buildconfig.java", "buildconfig.kt") &&
        name.substringAfterLast('.', "") in setOf("java", "kt", "c", "cc", "cpp", "h", "hpp") &&
        parts.none { it in setOf("test", "tests", "androidtest", "commontest", "jvmtest", "desktoptest", "testfixtures", "reproducer", "invariants", "generated", "buildsrc", "build-logic") } &&
        !Regex("(?i)(test|tests|spec|reproducer|invariant)\\.(java|kt|c|cc|cpp|h|hpp)$").containsMatchIn(name)
}

/** A physical COPY, never a link/worktree to dirty user files and never a checkpoint-based rollback. */
internal class IncidentCopy private constructor(
    val root: Path,
    val originalBytes: Map<String, ByteArray>,
    val readableFiles: Set<String>,
    private val editable: Set<String>,
    private val sourcePaths: Set<String>,
) {
    fun workspace(authorized: () -> Boolean): AgentWorkspace = IncidentCopyWorkspace(root, readableFiles, editable, authorized)

    fun assertUnchangedInputs(plan: IncidentVerificationPlan) {
        plan.immutableFiles.forEach { (name, hash) ->
            check(IncidentDigests.file(incidentPath(root, name)) == hash) { "Immutable verification input changed." }
        }
        originalBytes.filterKeys { it !in editable }.forEach { (name, bytes) ->
            check(Files.readAllBytes(incidentPath(root, name)).contentEquals(bytes)) { "A protected copy input changed." }
        }
    }

    fun changes(): List<FileChange> = editable.sorted().mapNotNull { name ->
        val before = decode(originalBytes.getValue(name))
        val after = readText(incidentPath(root, name))
        if (before == after) null else FileChange(name, before, after)
    }

    fun assertSourceScope(snapshot: IncidentSourceSnapshot) {
        check(snapshot.files.keys == sourcePaths) { "Source files were created, removed, or renamed outside the patch scope." }
        originalBytes.filterKeys { it in sourcePaths && it !in editable }.forEach { (path, before) ->
            check(snapshot.files[path] == IncidentDigests.bytes(before)) { "A protected source changed." }
        }
    }

    fun delete() {
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return
        // walk() does not follow symbolic links, including one planted by a task.
        Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    companion object {
        suspend fun create(
            original: Path, privateRoot: Path, snapshot: IncidentSourceSnapshot, plan: IncidentVerificationPlan,
        ): IncidentCopy {
            val source = original.toRealPath()
            val target = privateRoot.toAbsolutePath().normalize()
            require(!target.startsWith(source) && !source.startsWith(target)) { "Repair copies need a private directory outside the project." }
            var parent: Path? = target
            while (parent != null) {
                require(!Files.isSymbolicLink(parent)) { "Repair copy parent must not be a symlink." }
                parent = parent.parent
            }
            Files.createDirectories(target)
            runCatching { Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------")) }
            val paths = (snapshot.files.keys + plan.additionalCopyFiles + plan.immutableFiles.keys).toSortedSet()
            require(paths.size <= 20_000 && paths.all(::allowedCopyName)) { "Copy scope contains unsupported or secret inputs." }
            require(plan.editableFiles.all { it in snapshot.files && editableIncidentSource(it) }) { "Only explicit production source files may be patched." }
            require(plan.immutableFiles.values.all { Regex("[a-f0-9]{64}").matches(it) })
            require(plan.editableFiles.size <= 32)
            val bytes = LinkedHashMap<String, ByteArray>()
            var total = 0L
            for (name in paths) {
                currentCoroutineContext().ensureActive()
                val input = incidentPath(source, name)
                require(Files.size(input) <= MAX_FILE_BYTES) { "A copy input exceeds the size limit." }
                val content = Files.readAllBytes(input)
                total += content.size
                require(content.size <= MAX_FILE_BYTES && total <= 64L * 1024 * 1024) { "Repair copy exceeds the size limit." }
                val hash = IncidentDigests.bytes(content)
                snapshot.files[name]?.let { check(hash == it) { "Source changed while taking the repair copy." } }
                plan.immutableFiles[name]?.let { check(hash == it) { "Immutable input does not match approved verification plan." } }
                bytes[name] = content
            }
            val root = Files.createTempDirectory(target, "incident-")
            val copy = IncidentCopy(root, bytes, paths, plan.editableFiles, snapshot.files.keys.toSet())
            try {
                for ((name, content) in bytes) {
                    currentCoroutineContext().ensureActive()
                    val out = incidentPath(root, name, existing = false)
                    Files.createDirectories(out.parent)
                    Files.write(out, content, StandardOpenOption.CREATE_NEW)
                }
                copy.assertUnchangedInputs(plan)
                return copy
            } catch (e: Throwable) {
                copy.delete()
                throw e
            }
        }
    }
}

private const val MAX_FILE_BYTES = 8 * 1024 * 1024
private const val MAX_TEXT_BYTES = 512 * 1024
private fun decode(bytes: ByteArray): String {
    require(bytes.size <= MAX_TEXT_BYTES) { "Text input exceeds the agent limit." }
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}
private fun readText(path: Path): String {
    require(Files.size(path) <= MAX_TEXT_BYTES)
    return decode(Files.readAllBytes(path))
}

/** Intentionally does not delegate ANY capability to the live IDE workspace. */
private class IncidentCopyWorkspace(
    private val root: Path,
    private val readable: Set<String>,
    private val editable: Set<String>,
    private val authorized: () -> Boolean,
) : AgentWorkspace {
    override fun projectRoot(): String = root.toString()
    private fun checkAuthorization() { check(authorized()) { "Repair authorization was revoked." } }
    private fun readPath(path: String): Path {
        checkAuthorization()
        require(path in readable) { "File is outside the authorized source-read scope." }
        return incidentPath(root, path)
    }
    override suspend fun readFile(path: String, startLine: Int?, endLine: Int?): String {
        val text = readText(readPath(path))
        // Credentials accidentally embedded in source are not automatically uploaded either.
        require(!Regex("(?i)(-----BEGIN .*PRIVATE KEY-----|(?:api[_-]?key|password|secret|access[_-]?token)\\s*[:=]\\s*[\"'][^\"']{4,})").containsMatchIn(text)) {
            "Source may contain credentials; automatic sharing is refused."
        }
        if (startLine == null && endLine == null) return text
        val lines = text.lines()
        val from = ((startLine ?: 1) - 1).coerceIn(0, lines.size)
        val to = (endLine ?: lines.size).coerceIn(from, lines.size)
        return lines.subList(from, to).joinToString("\n")
    }
    override suspend fun listDir(path: String): List<WorkspaceEntry> {
        checkAuthorization()
        val prefix = if (path == "." || path.isEmpty()) "" else path.trimEnd('/') + "/"
        require(prefix.isEmpty() || readable.any { it.startsWith(prefix) })
        return readable.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.distinct().sorted().map { name ->
            val relative = prefix + name
            WorkspaceEntry(name, relative, relative !in readable)
        }
    }
    override suspend fun searchText(query: String, regex: Boolean, caseSensitive: Boolean, limit: Int): List<TextMatch> {
        require(!regex) { "Only literal search is available in the bounded repair workspace." }
        require(query.length in 1..500)
        val results = mutableListOf<TextMatch>()
        for (name in readable.sorted()) {
            currentCoroutineContext().ensureActive()
            val text = try { readFile(name, null, null) } catch (_: IllegalArgumentException) { continue }
            text.lineSequence().forEachIndexed { index, line ->
                val column = line.indexOf(query, ignoreCase = !caseSensitive)
                if (column >= 0 && results.size < limit.coerceIn(1, 100)) results += TextMatch(name, index + 1, column + 1, line.take(500))
            }
            if (results.size >= limit.coerceIn(1, 100)) break
        }
        return results
    }
    override suspend fun findSymbol(query: String, limit: Int): List<SymbolHit> = emptyList()
    override suspend fun diagnostics(path: String): List<DiagnosticInfo> = error("Use the host's verified task receipts, not simulated diagnostics.")
    override suspend fun projectOverview() = ProjectOverview("Isolated incident repair copy", emptyList())
    override suspend fun writeFile(path: String, content: String) {
        checkAuthorization()
        require(path in editable && content.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) { "Write is outside the authorized patch scope." }
        Files.write(incidentPath(root, path), content.toByteArray(Charsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING)
    }
    override suspend fun applyEdits(path: String, edits: List<TextEdit>) {
        var content = readFile(path, null, null)
        var lastStart = content.length
        for (edit in edits.sortedByDescending { it.offset }) {
            require(edit.offset >= 0 && edit.oldLength >= 0 && edit.offset + edit.oldLength <= lastStart)
            content = content.replaceRange(edit.offset, edit.offset + edit.oldLength, edit.newText)
            lastStart = edit.offset
        }
        writeFile(path, content)
    }
    override suspend fun createFile(path: String, content: String): String = error("Creating files is outside the repair scope.")
    override suspend fun createDir(path: String): String = error("Creating directories is outside the repair scope.")
    override suspend fun renamePath(path: String, newName: String): String = error("Renaming is outside the repair scope.")
    override suspend fun movePath(path: String, destDir: String): String = error("Moving files is outside the repair scope.")
    override suspend fun deletePath(path: String): Boolean = error("Deleting files is outside the repair scope.")
    override suspend fun addDependency(module: String, coordinate: String): String = error("Dependency changes are outside the repair scope.")
}
