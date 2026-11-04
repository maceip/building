package dev.ide.core.reliability

import dev.ide.agent.IncidentApplyResult
import dev.ide.agent.IncidentPatchReview
import dev.ide.agent.IncidentSourceSnapshot
import dev.ide.agent.impl.IncidentDigests
import dev.ide.build.ReliabilitySourceSnapshot
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections

/**
 * A single CLOSED existing production file, committed with one atomic sibling rename or not at all.
 * The caller holds its shared source-mutation lock throughout and supplies an authoritative live-buffer
 * gate: null permits, Pending/StaleSource refuses. The gate receives ALL original input hashes and the
 * changed relative paths, not just the on-disk source snapshot. Caller authorization failures propagate.
 *
 * This is not a multi-file transaction, a cross-process hostile-writer lock, or a crash-durability
 * protocol. The committed callback is only a notification; its failure cannot downgrade a rename.
 */
class IncidentPatchApplier(
    private val projectRoot: Path,
    private val privateWorkRoot: Path,
    private val snapshot: (Path) -> IncidentSourceSnapshot = { root ->
        val source = ReliabilitySourceSnapshot.capture(root)
        IncidentSourceSnapshot(source.revision, source.privateRelativeFiles.toMap())
    },
    /** Trusted seam: return ONLY after the atomic rename succeeds; never copy/write the target. */
    private val atomicReplace: (Path, Path) -> Unit = { staged, target ->
        Files.move(staged, target, ATOMIC_MOVE, REPLACE_EXISTING)
        Unit
    },
) {
    fun apply(
        review: IncidentPatchReview,
        checkCurrent: () -> Unit,
        checkBuffers: (Map<String, String>, Set<String>) -> IncidentApplyResult?,
        committed: (List<Path>) -> Unit,
    ): IncidentApplyResult {
        checkCurrent()
        var staged: Path? = null
        try {
            val patch = prepare(review)
            assertOriginal(patch)
            assertCopy(patch)
            checkBuffers(patch.inputs, patch.changed)?.let(::refuseBufferResult)

            // Refuse providers without private creation permissions, rather than exposing staged text.
            val permissions = pendingIo {
                val view = Files.getFileAttributeView(patch.target, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
                    ?: pending("The filesystem cannot provide private repair staging.")
                view.readAttributes().permissions().toSet()
            }
            checkCurrent()
            val temporary = pendingIo {
                Files.createTempFile(patch.target.parent, ".incident-patch-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            }
            staged = temporary
            pendingIo {
                FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { channel ->
                    val bytes = ByteBuffer.wrap(patch.after)
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
            }

            // Staging may have taken time. Recheck EVERY source/input and the authoritative buffers.
            assertOriginal(patch)
            assertCopy(patch)
            checkBuffers(patch.inputs, patch.changed)?.let(::refuseBufferResult)
            pendingIo {
                assertNoLinks(temporary)
                require(Files.isRegularFile(temporary, NOFOLLOW_LINKS) && temporary.toRealPath() == temporary)
                require(readBounded(temporary, MAX_TARGET_BYTES).contentEquals(patch.after))
                // Keep staging private until validation finishes. Open before restoring read-only modes.
                FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { channel ->
                    Files.setPosixFilePermissions(temporary, permissions)
                    channel.force(true)
                }
            }
            checkCurrent()
            pendingIo { atomicReplace(temporary, patch.target) }
            staged = null // Never delete a new file that subsequently reuses the former temp pathname.

            // No cancellation/authorization checks or suspensions after the commit point. Notifications
            // (including a cancelled subscriber) must not misreport Applied as retryable/uncommitted.
            runCatching { committed(listOf(patch.target)) }
            return IncidentApplyResult.Applied
        } catch (refused: Refused) {
            return refused.result
        } finally {
            // Only the particular sibling created by this invocation, never originals or the copy.
            staged?.let { temporary -> runCatching { Files.deleteIfExists(temporary) } }
        }
    }

    private data class Patch(
        val originalRoot: Path,
        val copyRoot: Path,
        val original: IncidentSourceSnapshot,
        val repaired: IncidentSourceSnapshot,
        val inputs: Map<String, String>,
        val name: String,
        val target: Path,
        val before: ByteArray,
        val after: ByteArray,
        val changed: Set<String>,
    )

    private fun prepare(review: IncidentPatchReview): Patch {
        requireReview(review.changes.size == 1, "Only one reviewed existing source file can be applied atomically.")
        requireReview(review.originalInputHashes.size <= MAX_FILES && review.originalSnapshot.files.size <= MAX_FILES &&
            review.repairedSnapshot.files.size <= MAX_FILES, "Repair inputs exceed the file-count limit.")
        val original = review.originalSnapshot.copy(files = immutableMap(review.originalSnapshot.files))
        val repaired = review.repairedSnapshot.copy(files = immutableMap(review.repairedSnapshot.files))
        val inputs = immutableMap(review.originalInputHashes)
        val change = review.changes.single()
        requireReview(inputs.keys.all(::allowedInputName) && original.files.keys.all(::allowedInputName) &&
            repaired.files.keys.all(::allowedInputName), "Repair inputs contain an unsupported or noncanonical path.")
        requireReview(inputs.values.all(HASH::matches) && original.files.values.all(HASH::matches) &&
            repaired.files.values.all(HASH::matches) && HASH.matches(original.revision) && HASH.matches(repaired.revision),
            "Repair input digests are malformed.")
        if (original.files.any { (name, hash) -> inputs[name] != hash }) stale()
        requireReview(original.files.keys == repaired.files.keys, "Reviewed source files were created, removed, or renamed.")
        requireReview(change.path in original.files && editableSource(change.path), "Only existing production source files may be patched.")
        requireReview(original.files.all { (name, hash) -> name == change.path || repaired.files[name] == hash },
            "The repaired source snapshot contains unreviewed changes.")
        val before = encode(change.before)
        val after = encode(change.after)
        requireReview(!before.contentEquals(after), "The reviewed patch makes no source change.")
        requireReview(IncidentDigests.bytes(before) == original.files[change.path] &&
            IncidentDigests.bytes(after) == repaired.files[change.path], "The reviewed diff does not match its source digests.")
        val originalRoot = originalIo { canonicalDirectory(projectRoot) }
        val copyRoot = pendingIo { checkedCopyRoot(review.isolatedRoot, originalRoot) }
        val target = originalIo { existingFile(originalRoot, change.path) }
        return Patch(originalRoot, copyRoot, original, repaired, inputs, change.path, target, before, after,
            Collections.singleton(change.path))
    }

    private fun assertOriginal(patch: Patch) = originalIo {
        require(canonicalDirectory(projectRoot) == patch.originalRoot)
        preflightInputs(patch.originalRoot, patch)
        // Inventory before the canonical hasher also prevents unreviewed, oversized new sources from
        // being read under the canonical helper's broader build-time limits.
        val found = HashSet<String>()
        Files.walkFileTree(patch.originalRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != patch.originalRoot && ignoredSourceDirectory(dir.fileName.toString())) FileVisitResult.SKIP_SUBTREE
                else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && !attrs.isSymbolicLink && canonicalSourceName(file.fileName.toString())) {
                    val name = relativeName(patch.originalRoot, file)
                    if (name !in patch.original.files) stale()
                    found += name
                }
                return FileVisitResult.CONTINUE
            }
        })
        if (found != patch.original.files.keys || snapshot(patch.originalRoot) != patch.original) stale()
        assertInputBytes(patch.originalRoot, patch, original = true)
    }

    private fun assertCopy(patch: Patch) = pendingIo {
        require(checkedCopyRoot(patch.copyRoot, patch.originalRoot) == patch.copyRoot)
        preflightInputs(patch.copyRoot, patch)
        val found = HashSet<String>()
        Files.walkFileTree(patch.copyRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != patch.copyRoot && dir.fileName.toString() in OUTPUT_DIRECTORIES) FileVisitResult.SKIP_SUBTREE
                else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = relativeName(patch.copyRoot, file)
                requireReview(attrs.isRegularFile && !attrs.isSymbolicLink && name in patch.inputs,
                    "The isolated copy contains an unreviewed input or a link.")
                found += name
                return FileVisitResult.CONTINUE
            }
        })
        // Approved inputs inside an excluded output directory are still individually validated below;
        // excluded outputs themselves never grant source/input authority.
        requireReview(patch.inputs.keys.all { it in found || it.split('/').dropLast(1).any(OUTPUT_DIRECTORIES::contains) },
            "The isolated copy is missing a reviewed input.")
        requireReview(snapshot(patch.copyRoot) == patch.repaired, "The isolated source snapshot changed since review.")
        assertInputBytes(patch.copyRoot, patch, original = false)
    }

    private fun preflightInputs(root: Path, patch: Patch) {
        var total = 0L
        patch.inputs.keys.forEach { name ->
            val size = Files.size(existingFile(root, name))
            require(size <= if (name == patch.name) MAX_TARGET_BYTES.toLong() else MAX_FILE_BYTES.toLong())
            total += size
            require(total <= MAX_TOTAL_BYTES)
        }
    }

    private fun assertInputBytes(root: Path, patch: Patch, original: Boolean) {
        var total = 0L
        for ((name, hash) in patch.inputs) {
            val bytes = readBounded(existingFile(root, name), if (name == patch.name) MAX_TARGET_BYTES else MAX_FILE_BYTES)
            total += bytes.size
            require(total <= MAX_TOTAL_BYTES)
            val expectedHash = if (!original && name == patch.name) patch.repaired.files.getValue(name) else hash
            val matches = IncidentDigests.bytes(bytes) == expectedHash &&
                (name != patch.name || bytes.contentEquals(if (original) patch.before else patch.after))
            if (!matches) {
                if (original) stale() else pending("An isolated source or immutable input changed since review.")
            }
        }
    }

    private fun checkedCopyRoot(isolated: Path, original: Path): Path {
        val work = canonicalDirectory(privateWorkRoot)
        val copy = canonicalDirectory(isolated)
        require(copy != work && copy.startsWith(work))
        require(!work.startsWith(original) && !original.startsWith(work))
        require(!copy.startsWith(original) && !original.startsWith(copy))
        return copy
    }

    private fun canonicalDirectory(path: Path): Path {
        val absolute = path.toAbsolutePath()
        require(absolute == absolute.normalize())
        assertNoLinks(absolute)
        require(Files.isDirectory(absolute, NOFOLLOW_LINKS) && absolute.toRealPath() == absolute)
        return absolute
    }

    private fun existingFile(root: Path, name: String): Path {
        require(canonicalRelative(name))
        val file = root.resolve(name)
        require(file != root && file.startsWith(root))
        assertNoLinks(file)
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && file.toRealPath() == file)
        return file
    }

    private fun assertNoLinks(path: Path) {
        var cursor: Path? = path
        while (cursor != null) {
            require(!Files.isSymbolicLink(cursor))
            cursor = cursor.parent
        }
    }

    private fun readBounded(path: Path, limit: Int): ByteArray {
        require(Files.size(path) <= limit)
        return Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(bytes.size().toLong() + read <= limit)
                bytes.write(buffer, 0, read)
            }
            bytes.toByteArray()
        }
    }

    private fun encode(text: String?): ByteArray {
        requireReview(text != null && text.length <= MAX_TARGET_BYTES, "Creates, deletes, and oversized text patches are unsupported.")
        return pendingIo {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text!!))
            require(encoded.remaining() <= MAX_TARGET_BYTES)
            ByteArray(encoded.remaining()).also(encoded::get)
        }
    }

    private fun refuseBufferResult(result: IncidentApplyResult): Nothing = when (result) {
        IncidentApplyResult.Applied -> pending("The buffer gate must authorize a rename, not report an application.")
        else -> throw Refused(result)
    }

    private inline fun <T> originalIo(block: () -> T): T = try {
        block()
    } catch (_: IOException) {
        stale()
    } catch (_: IllegalArgumentException) {
        stale()
    } catch (_: SecurityException) {
        stale()
    } catch (_: UnsupportedOperationException) {
        pending("The filesystem does not support checked repair application.")
    }

    private inline fun <T> pendingIo(block: () -> T): T = try {
        block()
    } catch (_: IOException) {
        pending("The verified copy could not be applied with a checked atomic rename.")
    } catch (_: IllegalArgumentException) {
        pending("The repair copy, paths, or input bounds are invalid.")
    } catch (_: SecurityException) {
        pending("The filesystem does not authorize checked repair application.")
    } catch (_: UnsupportedOperationException) {
        pending("The filesystem does not support checked atomic repair application.")
    }

    private class Refused(val result: IncidentApplyResult) : RuntimeException()
    private fun stale(): Nothing = throw Refused(IncidentApplyResult.StaleSource)
    private fun pending(reason: String): Nothing = throw Refused(IncidentApplyResult.Pending(reason))
    private fun requireReview(condition: Boolean, reason: String) { if (!condition) pending(reason) }

    private companion object {
        const val MAX_FILES = 20_000
        const val MAX_FILE_BYTES = 8 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val MAX_TARGET_BYTES = 512 * 1024
        val HASH = Regex("[a-f0-9]{64}")
        val SECRET_NAME = Regex("(?i)(^|[._-])(secret|secrets|credential|credentials|keystore|signing|password|token|env)([._-]|$)")
        // Membership preflight mirrors ReliabilitySourceSnapshot; its capture remains authoritative.
        val SOURCE_DIRECTORIES_IGNORED = setOf(".git", ".gradle", ".idea", ".platform", ".building", ".zcode", "build", "out",
            "target", "dist", "node_modules", ".cxx", ".externalNativeBuild", ".kotlin")
        val SOURCE_EXTENSIONS = setOf("java", "kt", "kts", "gradle", "xml", "aidl", "c", "cc", "cpp", "h", "hpp", "cmake", "pro", "proto", "toml")
        val OUTPUT_DIRECTORIES = setOf("build", "out", "target", "dist", "node_modules", ".gradle", ".cxx", ".externalNativeBuild",
            ".kotlin", ".incident-gradle", ".incident-home", ".incident-tmp")
        val PROTECTED_DIRECTORIES = setOf("test", "tests", "androidtest", "commontest", "jvmtest", "desktoptest", "testfixtures",
            "reproducer", "invariants", "generated", "buildsrc", "build-logic") + OUTPUT_DIRECTORIES.map { it.lowercase() }
        val EDITABLE_EXTENSIONS = setOf("java", "kt", "c", "cc", "cpp", "h", "hpp")
        val TEST_SOURCE = Regex("(?i)(test|tests|spec|reproducer|invariant)\\.(java|kt|c|cc|cpp|h|hpp)$")

        fun canonicalRelative(name: String): Boolean = name.isNotBlank() && name.length <= 1024 &&
            '\\' !in name && '\u0000' !in name && !Regex("^[A-Za-z]:").containsMatchIn(name) &&
            name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

        // Keep the production edit/copy restrictions of IncidentCopy, not just the source extensions.
        fun allowedInputName(name: String): Boolean = canonicalRelative(name) && name.split('/').all { part ->
            !part.startsWith('.') && part !in setOf("local.properties", "gradle.properties") && !SECRET_NAME.containsMatchIn(part) &&
                !part.endsWith(".jks", true) && !part.endsWith(".pem", true) && !part.endsWith(".p12", true)
        }
        fun editableSource(name: String): Boolean {
            val parts = name.lowercase().split('/')
            return allowedInputName(name) && parts.last() !in setOf("buildconfig.java", "buildconfig.kt") &&
                parts.last().substringAfterLast('.', "") in EDITABLE_EXTENSIONS && parts.none(PROTECTED_DIRECTORIES::contains) &&
                !TEST_SOURCE.containsMatchIn(parts.last())
        }
        fun ignoredSourceDirectory(name: String): Boolean = name in SOURCE_DIRECTORIES_IGNORED || name.startsWith('.') || SECRET_NAME.containsMatchIn(name)
        fun canonicalSourceName(name: String): Boolean = !name.startsWith('.') && !SECRET_NAME.containsMatchIn(name) &&
            (name.substringAfterLast('.', "") in SOURCE_EXTENSIONS || name in setOf("CMakeLists.txt", "Android.mk", "Application.mk"))
        fun relativeName(root: Path, file: Path): String = root.relativize(file).joinToString("/") { it.toString() }
        fun immutableMap(source: Map<String, String>): Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(source))
    }
}
