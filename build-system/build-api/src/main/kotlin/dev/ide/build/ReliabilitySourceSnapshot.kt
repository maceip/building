package dev.ide.build

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * Canonical source-content identity shared by native builds, real Gradle and source-bound repairs.
 * Only [Snapshot.files] is safe to publish in SDK metadata: basename + content hash, never paths or
 * source contents. [Snapshot.privateRelativeFiles] is host-private evidence for exact change checks.
 * Dirty working-tree source is included. Timestamps, Git identity, outputs and credentials are not.
 */
object ReliabilitySourceSnapshot {
    private val ignoredDirs = setOf(
        ".git", ".gradle", ".idea", ".platform", ".building", ".zcode", "build", "out", "target",
        "dist", "node_modules", ".cxx", ".externalNativeBuild", ".kotlin",
    )
    private val extensions = setOf("java", "kt", "kts", "gradle", "xml", "aidl", "c", "cc", "cpp", "h", "hpp", "cmake", "pro", "proto", "toml")
    private val secretName = Regex("(?i)(^|[._-])(secret|secrets|credential|credentials|keystore|signing|password|token|env)([._-]|$)")
    private const val MAX_FILES = 100_000
    private const val MAX_SOURCE_BYTES = 256L * 1024 * 1024

    data class Snapshot(
        val revision: String,
        /** Deterministically ordered basename/hash entries, with duplicates preserved. */
        val files: List<Map<String, String>>,
        /** Never serialize into an APK, event, user repository file or AI prompt. */
        val privateRelativeFiles: Map<String, String>,
    )

    fun capture(projectRoot: Path, checkCanceled: () -> Unit = {}): Snapshot {
        val root = projectRoot.toRealPath()
        val files = ArrayList<Path>()
        var totalBytes = 0L
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCanceled()
                return if (dir != root && (dir.fileName.toString() in ignoredDirs ||
                    dir.fileName.toString().startsWith('.') || secretName.containsMatchIn(dir.fileName.toString())))
                    FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCanceled()
                val name = file.fileName.toString()
                if (attrs.isRegularFile && !attrs.isSymbolicLink && !name.startsWith('.') &&
                    !secretName.containsMatchIn(name) &&
                    (name.substringAfterLast('.', "") in extensions || name in setOf("CMakeLists.txt", "Android.mk", "Application.mk"))) {
                    totalBytes += attrs.size()
                    require(files.size < MAX_FILES && totalBytes <= MAX_SOURCE_BYTES) {
                        "Reliability: source snapshot exceeds its 100000-file / 256 MiB limit."
                    }
                    files.add(file)
                }
                return FileVisitResult.CONTINUE
            }
        })
        val digest = MessageDigest.getInstance("SHA-256")
        val relativeFiles = linkedMapOf<String, String>()
        val publicFiles = files.sortedBy { root.relativize(it).toString().replace('\\', '/') }.map { file ->
            checkCanceled()
            require(!Files.isSymbolicLink(file) && file.toRealPath().startsWith(root)) { "Reliability: a source changed into a symlink during the build." }
            val relative = root.relativize(file).toString().replace('\\', '/')
            val hash = sha256(file)
            relativeFiles[relative] = hash
            digest.update(relative.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(hash.toByteArray(Charsets.US_ASCII))
            digest.update('\n'.code.toByte())
            linkedMapOf("basename" to file.fileName.toString(), "sha256" to hash)
        }.sortedWith(compareBy({ it.getValue("basename") }, { it.getValue("sha256") }))
        return Snapshot(hex(digest.digest()), publicFiles, relativeFiles)
    }

    fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val bytes = ByteArray(8192)
            while (true) {
                val count = stream.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
