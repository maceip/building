package dev.ide.android

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.CancellationException

/** Safe, provider-independent refusal messages; never include provider paths, names or exception text. */
internal class AndroidImportException(message: String) : IOException(message)

/** Bounds apply before an archive/parser sees a SAF copy, including when a provider lies about size. */
internal object AndroidImportLimits {
    const val MAX_FILE_BYTES = 512L * 1024 * 1024
    const val MAX_TREE_BYTES = 512L * 1024 * 1024
    const val MAX_SOURCE_BYTES = 64L * 1024 * 1024
    const val MAX_TREE_ENTRIES = 20_000
    const val MAX_TREE_DEPTH = 64
    private const val MAX_NAME_BYTES = 255
    private const val MAX_DOCUMENT_ID_LENGTH = 4096

    private val skippedDirectories = setOf("build", ".gradle", ".idea", "node_modules", ".git")

    // Do not skip .platform: it holds the actual CodeAssist workspace, SDK and library configuration.
    fun skipDirectory(name: String): Boolean = name in skippedDirectories

    /** Reject, rather than rewrite, names: rewriting can alias two different provider documents. */
    fun fileName(name: String?): String {
        if (name.isNullOrBlank() || name == "." || name == ".." || name.length > MAX_NAME_BYTES ||
            name.any { it == '/' || it == '\\' || it == ':' || Character.isISOControl(it) ||
                Character.getType(it) == Character.FORMAT.toInt() } ||
            File(name).isAbsolute || name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES
        ) {
            throw AndroidImportException("The selected item has an invalid file name.")
        }
        return name
    }

    /** Canonical, component-aware containment, not a vulnerable string-prefix comparison. */
    fun child(root: File, parent: File, name: String?): File {
        val safeName = fileName(name)
        val canonicalRoot = root.canonicalFile
        val canonicalParent = parent.canonicalFile
        val child = File(canonicalParent, safeName)
        val canonicalChild = child.canonicalFile
        if (!canonicalParent.toPath().startsWith(canonicalRoot.toPath()) ||
            canonicalChild == canonicalRoot ||
            !canonicalChild.toPath().startsWith(canonicalRoot.toPath()) ||
            canonicalChild.parentFile != canonicalParent
        ) {
            throw AndroidImportException("The selected item points outside the import folder.")
        }
        return child
    }

    class ByteBudget(private val limit: Long = MAX_TREE_BYTES) {
        init { require(limit >= 0) }
        var usedBytes: Long = 0
            private set
        val remainingBytes: Long get() = limit - usedBytes

        /** Metadata is only an early refusal, never permission to omit stream accounting. */
        fun checkFileSize(declaredBytes: Long?, fileLimit: Long = MAX_FILE_BYTES) {
            require(fileLimit >= 0)
            if (declaredBytes != null && declaredBytes >= 0) {
                if (declaredBytes > fileLimit) throw AndroidImportException("The selected file exceeds the import size limit.")
                if (declaredBytes > remainingBytes) throw AndroidImportException("The selected folder exceeds the total import size limit.")
            }
        }

        fun consume(bytes: Long) {
            require(bytes >= 0)
            // Subtract first so even a dishonest Long.MAX_VALUE length cannot overflow the check.
            if (bytes > remainingBytes) throw AndroidImportException("The selected folder exceeds the total import size limit.")
            usedBytes += bytes
        }
    }

    class TreeBudget(
        rootDocumentId: String?,
        private val maxEntries: Int = MAX_TREE_ENTRIES,
        private val maxDepth: Int = MAX_TREE_DEPTH,
        val bytes: ByteBudget = ByteBudget(),
    ) {
        private val visited = HashSet<String>()
        private var entries = 0

        init {
            require(maxEntries >= 0 && maxDepth >= 0)
            visited.add(documentId(rootDocumentId))
        }

        /** Root is depth zero. Count all encountered children, including excluded directories. */
        fun visit(id: String?, depth: Int) {
            checkInterrupted()
            val documentId = documentId(id)
            if (depth < 1 || depth > maxDepth) throw AndroidImportException("The selected folder is nested too deeply to import.")
            if (entries >= maxEntries) throw AndroidImportException("The selected folder contains too many entries to import.")
            if (!visited.add(documentId)) throw AndroidImportException("The selected folder contains repeated or cyclic documents.")
            entries++
        }

        private fun documentId(id: String?): String {
            if (id.isNullOrEmpty() || id.length > MAX_DOCUMENT_ID_LENGTH) {
                throw AndroidImportException("The selected folder contains an invalid document.")
            }
            return id
        }
    }

    /** Never reads more than the remaining allowance plus one byte, or writes beyond either bound. */
    fun copy(
        input: InputStream,
        output: OutputStream,
        budget: ByteBudget,
        fileLimit: Long = MAX_FILE_BYTES,
        declaredBytes: Long? = null,
    ): Long {
        budget.checkFileSize(declaredBytes, fileLimit)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            checkInterrupted()
            val remaining = minOf(fileLimit - copied, budget.remainingBytes)
            val requested = if (remaining >= buffer.size) buffer.size else (remaining + 1).toInt()
            val count = input.read(buffer, 0, requested)
            checkInterrupted()
            if (count == -1) return copied
            // Reject a broken/non-progressing stream instead of spinning or silently truncating it.
            if (count <= 0 || count > requested) throw AndroidImportException("The selected file could not be read completely.")
            if (count.toLong() > fileLimit - copied) throw AndroidImportException("The selected file exceeds the import size limit.")
            budget.consume(count.toLong())
            output.write(buffer, 0, count)
            copied += count
        }
    }

    /** Only this invocation's successfully-created directory is eligible for failure cleanup. */
    fun <T> inFreshDirectory(cacheDir: File, prefix: String, block: (File) -> T): T {
        checkInterrupted()
        val root = cacheDir.canonicalFile
        val directory = child(root, root, "$prefix-${UUID.randomUUID()}")
        if (!directory.mkdir()) throw AndroidImportException("A private import folder could not be created.")
        var completed = false
        try {
            val result = block(directory)
            checkInterrupted()
            completed = true
            return result
        } finally {
            if (!completed) {
                // No FOLLOW_LINKS: cleanup must never follow a link into an existing user directory.
                runCatching {
                    Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            Files.delete(file)
                            return FileVisitResult.CONTINUE
                        }

                        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                            if (exc != null) throw exc
                            Files.delete(dir)
                            return FileVisitResult.CONTINUE
                        }
                    })
                }
            }
        }
    }

    fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Import cancelled.")
    }

    /** Preserve the existing nullable host API, but never turn cancellation into an ordinary refusal. */
    fun <T> orNull(block: () -> T): T? = try {
        checkInterrupted()
        block().also { checkInterrupted() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
    } catch (_: Exception) {
        checkInterrupted()
        null
    }
}
