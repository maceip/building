package dev.ide.android.workbench.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Owns only a newly created test directory; never deletes an instrumentation-supplied input. */
internal class AcceptanceWorkspace(prefix: String) : Closeable {
    val root: Path = Files.createTempDirectory(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(),
        "workbench-acceptance-$prefix-",
    ).toRealPath()

    fun resolve(relative: String): Path = root.resolve(relative).normalize().also {
        require(it.startsWith(root) && it != root) { "Test file must remain in its disposable workspace" }
    }

    fun write(relative: String, text: String): Path = resolve(relative).also {
        Files.createDirectories(it.parent)
        Files.write(it, text.toByteArray(Charsets.UTF_8))
    }

    override fun close() {
        // The default walker does not follow symlinks, including ones a runtime sample may create.
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
