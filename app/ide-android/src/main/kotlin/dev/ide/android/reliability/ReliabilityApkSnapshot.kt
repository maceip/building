package dev.ide.android.reliability

import android.content.Context
import dev.ide.workbench.reliability.ReliabilityError
import dev.ide.workbench.reliability.ReliabilityException
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/** Freezes the user-selected APK before signature confirmation; never executes or installs it. */
internal object ReliabilityApkSnapshot {
    private const val MAX_BYTES = 512L * 1_024 * 1_024
    private const val MAX_FILES = 2
    private const val EXPIRES_MS = 60L * 60 * 1_000

    @Synchronized fun create(context: Context, sourcePath: String): Path {
        var output: Path? = null
        try {
            val source = Paths.get(sourcePath)
            if (!source.isAbsolute || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) invalid()
            val expectedSize = Files.size(source)
            if (expectedSize !in 1..MAX_BYTES) invalid()
            val directory = context.cacheDir.toPath().resolve("reliability-apk-review")
            Files.createDirectories(directory)
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) invalid()
            val now = System.currentTimeMillis()
            val previous = Files.newDirectoryStream(directory).use { stream ->
                val files = ArrayList<Path>()
                for (file in stream) {
                    if (files.size >= 32) invalid()
                    if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && now - Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis() > EXPIRES_MS) {
                        Files.deleteIfExists(file)
                    } else files.add(file)
                }
                files
            }
            if (previous.size >= MAX_FILES) throw ReliabilityException(ReliabilityError.QUOTA)
            val snapshot = Files.createTempFile(directory, "review-", ".apk")
            output = snapshot
            Files.newInputStream(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                FileOutputStream(snapshot.toFile()).use { destination ->
                    val buffer = ByteArray(65_536)
                    var total = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val size = input.read(buffer)
                        if (size < 0) break
                        total += size
                        if (total > MAX_BYTES || total > expectedSize) invalid()
                        destination.write(buffer, 0, size)
                    }
                    if (total != expectedSize) invalid()
                    destination.flush()
                    destination.fd.sync()
                }
            }
            // Project code cannot later change this copy by replacing its original output file.
            snapshot.toFile().setReadOnly()
            return snapshot
        } catch (failure: Exception) {
            output?.let { runCatching { Files.deleteIfExists(it) } }
            if (failure is ReliabilityException) throw failure
            throw ReliabilityException(ReliabilityError.STORAGE_UNAVAILABLE)
        }
    }

    fun discard(path: String) { runCatching { Files.deleteIfExists(Paths.get(path)) } }
    private fun invalid(): Nothing = throw ReliabilityException(ReliabilityError.INVALID)
}
