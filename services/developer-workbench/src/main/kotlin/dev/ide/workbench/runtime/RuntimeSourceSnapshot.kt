package dev.ide.workbench.runtime

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Saved source identity before execution, not an attestation of later reads, dependencies or commands. */
object RuntimeSourceSnapshot {
    const val POLICY = "runtime-source-v1"
    private const val MAX_FILES = 20_000
    private const val MAX_BYTES = 64L * 1024 * 1024
    private val excludedDirectories = setOf(
        "node_modules", "build", "dist", "out", "target", "coverage", "vendor", "venv", "__pycache__",
    )
    private val extensions = setOf(
        "js", "mjs", "cjs", "ts", "mts", "cts", "jsx", "tsx", "py", "pyi", "sh",
        "html", "htm", "css", "scss", "json", "yaml", "yml", "toml", "ini",
        "c", "cc", "cpp", "h", "hpp", "gyp", "gypi", "cmake",
    )
    private val secretName = Regex("(?i)(^|[._-])(secret|secrets|credential|credentials|keystore|signing|password|token|env)([._-]|$)")

    data class Snapshot(val revision: String, val fileCount: Int, val sourceBytes: Long)

    fun capture(projectRoot: Path): Snapshot {
        val root = projectRoot.toRealPath()
        require(Files.isDirectory(root, NOFOLLOW_LINKS))
        val files = ArrayList<Path>()
        var visited = 0
        var listedBytes = 0L
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkInterrupted()
                require(++visited <= MAX_FILES * 4) { "Runtime source snapshot exceeds its entry limit." }
                val name = dir.fileName.toString()
                return if (dir != root && (name.startsWith('.') || name in excludedDirectories || secretName.containsMatchIn(name))) {
                    FileVisitResult.SKIP_SUBTREE
                } else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkInterrupted()
                require(++visited <= MAX_FILES * 4) { "Runtime source snapshot exceeds its entry limit." }
                val name = file.fileName.toString()
                if (attrs.isRegularFile && !attrs.isSymbolicLink && !name.startsWith('.') &&
                    !secretName.containsMatchIn(name) &&
                    (name.substringAfterLast('.', "").lowercase() in extensions || name in setOf("Makefile", "CMakeLists.txt", "requirements.txt", "Pipfile", "Pipfile.lock", "poetry.lock", "uv.lock"))) {
                    listedBytes += attrs.size()
                    require(files.size < MAX_FILES && listedBytes <= MAX_BYTES) { "Runtime source snapshot exceeds its size limit." }
                    files.add(file)
                }
                return FileVisitResult.CONTINUE
            }
        })
        require(files.isNotEmpty()) { "No supported saved source files were found." }
        val snapshot = MessageDigest.getInstance("SHA-256")
        snapshot.update("$POLICY\u0000".toByteArray(Charsets.US_ASCII))
        var bytesRead = 0L
        for (file in files.sortedBy { root.relativize(it).toString().replace('\\', '/') }) {
            checkInterrupted()
            val before = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(before.isRegularFile && !before.isSymbolicLink && file.toRealPath().startsWith(root)) { "Source changed during snapshot." }
            val content = MessageDigest.getInstance("SHA-256")
            var fileBytes = 0L
            Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    checkInterrupted()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    bytesRead += count
                    fileBytes += count
                    require(bytesRead <= MAX_BYTES) { "Runtime source snapshot exceeds its size limit." }
                    content.update(buffer, 0, count)
                }
            }
            val after = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(after.isRegularFile && !after.isSymbolicLink && fileBytes == before.size() && before.size() == after.size() &&
                before.lastModifiedTime() == after.lastModifiedTime() && before.fileKey() == after.fileKey() && file.toRealPath().startsWith(root)) {
                "Source changed during snapshot."
            }
            snapshot.update(root.relativize(file).toString().replace('\\', '/').toByteArray(Charsets.UTF_8))
            snapshot.update(0.toByte())
            snapshot.update(content.digest())
            snapshot.update('\n'.code.toByte())
        }
        return Snapshot(hex(snapshot.digest()), files.size, bytesRead)
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Runtime source snapshot cancelled.")
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
