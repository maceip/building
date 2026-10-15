package dev.ide.workbench.runtime

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RuntimeSourceSnapshotTest {
    @TempDir lateinit var root: Path

    @Test fun javascriptTypescriptPythonAndPackageInputsChangeTheIdentity() {
        for (name in listOf("app.js", "server.ts", "helper.py", "package.json", "package-lock.json", "pnpm-lock.yaml", "binding.gyp")) {
            Files.write(root.resolve(name), "first".toByteArray())
            val first = RuntimeSourceSnapshot.capture(root)
            Files.write(root.resolve(name), "second".toByteArray())
            assertNotEquals(first.revision, RuntimeSourceSnapshot.capture(root).revision, name)
        }
        assertEquals(7, RuntimeSourceSnapshot.capture(root).fileCount)
    }

    @Test fun privateCredentialsDependenciesAndGeneratedOutputsAreExcluded() {
        Files.write(root.resolve("app.js"), "throw new Error('real source');".toByteArray())
        val expected = RuntimeSourceSnapshot.capture(root)
        for (relative in listOf(".env", "credentials.json", "access-token.json", "node_modules/pkg/index.js", "dist/app.js", ".data/state.json", ".building/runtime.json", ".git/config")) {
            val path = root.resolve(relative)
            Files.createDirectories(path.parent)
            Files.write(path, "PRIVATE".toByteArray())
        }
        assertEquals(expected, RuntimeSourceSnapshot.capture(root))
        assertTrue(!expected.toString().contains(root.toString()))
    }

    @Test fun renamedSourcesChangeIdentityButRelocatedWorkspacesDoNot() {
        val a = Files.createDirectory(root.resolve("a"))
        val b = Files.createDirectory(root.resolve("b"))
        Files.write(a.resolve("app.js"), "same".toByteArray())
        Files.write(b.resolve("app.js"), "same".toByteArray())
        assertEquals(RuntimeSourceSnapshot.capture(a).revision, RuntimeSourceSnapshot.capture(b).revision)
        Files.move(b.resolve("app.js"), b.resolve("other.js"))
        assertNotEquals(RuntimeSourceSnapshot.capture(a).revision, RuntimeSourceSnapshot.capture(b).revision)
    }

    @Test fun symlinksAreNotFollowedAndEmptySnapshotsAreNotInvented() {
        val project = Files.createDirectory(root.resolve("project"))
        val external = Files.write(root.resolve("outside.js"), "PRIVATE".toByteArray())
        Files.createSymbolicLink(project.resolve("link.js"), external)
        assertFailsWith<IllegalArgumentException> { RuntimeSourceSnapshot.capture(project) }
        Files.write(project.resolve("app.js"), "safe".toByteArray())
        assertEquals(1, RuntimeSourceSnapshot.capture(project).fileCount)
    }

    @Test fun interruptedCollectionDoesNotInventASourceHash() {
        Files.write(root.resolve("app.js"), "safe".toByteArray())
        Thread.currentThread().interrupt()
        try { assertFailsWith<InterruptedException> { RuntimeSourceSnapshot.capture(root) } }
        finally { Thread.interrupted() }
    }
}
