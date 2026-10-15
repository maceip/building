package dev.ide.android.workbench.runtime

import android.content.Context
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.UUID
import java.util.zip.ZipInputStream

object HonoProjectInstaller {
    const val REVISION = "4b47a0d8a5c12ed69a9f8493df69918f62fb61c0"

    enum class Project(val archive: String, val testFile: String) {
        NODE_ADAPTER("hono-node-server.zip", "test/server.test.ts"),
        WORKBENCH("hono-workbench.zip", "test/http.test.mjs"),
    }

    fun create(context: Context, project: Project = Project.NODE_ADAPTER): Path {
        val parent = context.filesDir.toPath().toRealPath().resolve("external-workspaces")
        Files.createDirectories(parent)
        val directory = Files.createDirectory(parent.resolve("hono-${UUID.randomUUID()}"))
        val root = Files.createDirectory(directory.resolve("project"))
        try {
            val seen = mutableSetOf<String>()
            var bytes = 0L
            context.assets.open("workbench-projects/${project.archive}").use { raw ->
                ZipInputStream(raw).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.removeSuffix("/")
                        require(name.isNotBlank() && '\\' !in name && '\u0000' !in name)
                        val relative = Paths.get(name)
                        require(!relative.isAbsolute && relative.none { it.toString() in setOf(".", "..", "") })
                        require(seen.add(name) && seen.size <= 2000)
                        val target = root.resolve(relative).normalize()
                        require(target.startsWith(root) && target != root)
                        if (entry.isDirectory) {
                            Files.createDirectories(target)
                        } else {
                            Files.createDirectories(target.parent)
                            Files.newOutputStream(target, CREATE_NEW).use { output ->
                                val buffer = ByteArray(8192)
                                var member = 0L
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    member += count
                                    bytes += count
                                    require(member <= 4L * 1024 * 1024 && bytes <= 16L * 1024 * 1024)
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            require(Files.isRegularFile(root.resolve("package.json"), NOFOLLOW_LINKS))
            require(Files.isRegularFile(root.resolve("package-lock.json"), NOFOLLOW_LINKS))
            require(Files.isRegularFile(root.resolve(project.testFile), NOFOLLOW_LINKS))
            require(Files.isRegularFile(root.resolve("LICENSE"), NOFOLLOW_LINKS))
            return root
        } catch (failure: Throwable) {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            throw failure
        }
    }
}
