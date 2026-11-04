package dev.ide.workbench.scenario

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

internal object ScenarioFiles {
    fun root(path: Path): Path {
        requireSafe(!Files.isSymbolicLink(path) && Files.isDirectory(path, NOFOLLOW_LINKS), "Select an existing, non-symlink project directory")
        return path.toRealPath()
    }

    fun validateRelative(relative: String) {
        requireSafe(relative.length in 1..240 && relative.none { it.code < 32 || it == '\\' } &&
            !relative.startsWith('/') && !relative.contains(':') &&
            relative.split('/').none { it.isEmpty() || it == "." || it == ".." }, "Paths must be normalized project-relative paths without traversal")
    }

    fun resolve(root: Path, relative: String, mustExist: Boolean = true): Path {
        validateRelative(relative)
        var current = root
        relative.split('/').forEach { component ->
            current = current.resolve(component)
            requireSafe(!Files.isSymbolicLink(current), "Symlinks are not allowed in scenario paths")
        }
        requireSafe(current.normalize().startsWith(root), "Path escapes the project")
        if (mustExist) requireSafe(Files.isRegularFile(current, NOFOLLOW_LINKS), "Scenario or source artifact is not a regular local file")
        return current
    }

    fun read(root: Path, relative: String, maximum: Int): ByteArray {
        val path = resolve(root, relative)
        requireSafe(Files.size(path) <= maximum, "Scenario or source artifact exceeds the byte limit")
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            val result = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(8192)
            while (true) {
                val count = channel.read(buffer)
                if (count < 0) break
                requireSafe(result.size() + count <= maximum, "Scenario or source artifact exceeds the byte limit")
                result.write(buffer.array(), 0, count)
                buffer.clear()
            }
            return result.toByteArray()
        }
    }

    fun artifacts(root: Path, relativePaths: List<String>): List<ScenarioArtifact> {
        var total = 0
        return relativePaths.distinct().sorted().map { relative ->
            val bytes = read(root, relative, 2 * 1024 * 1024)
            total += bytes.size
            requireSafe(total <= 8 * 1024 * 1024, "Reviewed source artifacts exceed 8 MiB")
            ScenarioArtifact(relative, sha256(bytes), bytes.size.toLong())
        }
    }

    fun artifactHash(artifacts: List<ScenarioArtifact>): String = sha256(artifacts.joinToString("\n") { "${it.relativePath}\u0000${it.sha256}" }.toByteArray())

    fun evidenceDirectory(root: Path): Path {
        var directory = root
        listOf(".building", "scenario-evidence").forEach { part ->
            directory = directory.resolve(part)
            requireSafe(!Files.isSymbolicLink(directory), "Evidence directories must not be symlinks")
            if (!Files.exists(directory, NOFOLLOW_LINKS)) Files.createDirectory(directory)
            requireSafe(Files.isDirectory(directory, NOFOLLOW_LINKS), "Evidence destination must be a local directory")
        }
        return directory
    }

    fun reserveEvidence(root: Path, filename: String): Pair<Path, FileChannel> {
        val file = evidenceDirectory(root).resolve(filename)
        return file to FileChannel.open(file, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

internal fun requireSafe(condition: Boolean, message: String) {
    if (!condition) throw ScenarioValidationException(message)
}
