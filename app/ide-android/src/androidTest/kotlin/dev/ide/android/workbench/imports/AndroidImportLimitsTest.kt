package dev.ide.android.workbench.imports

import dev.ide.android.AndroidImportException
import dev.ide.android.AndroidImportLimits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CancellationException

/** Pure helpers: run under AndroidJUnitRunner on ART or standalone JUnit on a host JVM. */
class AndroidImportLimitsTest {
    @Test
    fun providerNamesMustBeSingleBoundedFileNames() {
        listOf<String?>(
            null, "", " ", ".", "..", "../sentinel.json", "..\\sentinel.json", "/sentinel.json",
            "nested/file.json", "nested\\file.json", "C:\\sentinel.json", "C:sentinel.json",
            "bad\u0000name", "bad\nname", "bad\u007fname", "bad\u0085name", "bad\u202ename",
            "a".repeat(256), "é".repeat(128),
        ).forEach { name -> refused { AndroidImportLimits.fileName(name) } }
        listOf(".platform", "workspace.json", "my release.aab", "Main.kt", "資料.json", "a".repeat(255)).forEach {
            assertEquals(it, AndroidImportLimits.fileName(it))
        }
    }

    @Test
    fun canonicalContainmentRejectsTraversalSiblingPrefixesAndSymlinks() = withWorkspace { workspace ->
        val root = File(workspace, "import").apply { check(mkdir()) }
        val sibling = File(workspace, "import-other").apply { check(mkdir()) }
        val sentinel = File(sibling, "sentinel.json")
        Files.write(sentinel.toPath(), "untouched".toByteArray())
        assertEquals(File(root.canonicalFile, "safe.json"), AndroidImportLimits.child(root, root, "safe.json"))
        refused { AndroidImportLimits.child(root, root, "../import-other/sentinel.json") }
        refused { AndroidImportLimits.child(root, sibling, "sentinel.json") }
        val link = File(root, "escape")
        Files.createSymbolicLink(link.toPath(), sibling.toPath())
        try {
            refused { AndroidImportLimits.child(root, root, "escape") }
            refused { AndroidImportLimits.child(root, link, "sentinel.json") }
            assertArrayEquals("untouched".toByteArray(), Files.readAllBytes(sentinel.toPath()))
        } finally {
            Files.delete(link.toPath())
        }
    }

    @Test
    fun declaredSizeIsOnlyAnEarlyRefusalAndNeverAnAllocationHint() {
        val input = EndlessInput()
        val budget = AndroidImportLimits.ByteBudget(8)
        val output = ByteArrayOutputStream()
        refused { AndroidImportLimits.copy(input, output, budget, fileLimit = 8, declaredBytes = Long.MAX_VALUE) }
        assertEquals(0, input.bytesRead)
        assertEquals(0, output.size())
        assertEquals(0L, budget.usedBytes)
        budget.checkFileSize(null, 8)
        budget.checkFileSize(-1, 8)
        budget.checkFileSize(0, 8)
        budget.consume(8)
        refused { budget.consume(Long.MAX_VALUE) }
        assertEquals(8L, budget.usedBytes)
    }

    @Test
    fun lyingUnknownAndExactSizesAreStillStreamBounded() {
        listOf<Long?>(null, -1, 0, 8).forEach { declared ->
            val input = EndlessInput()
            val output = ByteArrayOutputStream()
            refused {
                AndroidImportLimits.copy(input, output, AndroidImportLimits.ByteBudget(8), 8, declared)
            }
            assertEquals("Read at most limit plus one, not the rest of an endless stream", 9, input.bytesRead)
            assertTrue(output.size() <= 8)
        }
        val bytes = ByteArray(8) { it.toByte() }
        val output = ByteArrayOutputStream()
        val budget = AndroidImportLimits.ByteBudget(8)
        assertEquals(8L, AndroidImportLimits.copy(ByteArrayInputStream(bytes), output, budget, 8, 0))
        assertEquals(8L, budget.usedBytes)
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(0L, AndroidImportLimits.copy(ByteArrayInputStream(byteArrayOf()), output, budget, 8))
    }

    @Test
    fun aggregateBudgetIsSharedAcrossFilesWithoutOverwritingPastTheLimit() {
        val budget = AndroidImportLimits.ByteBudget(10)
        val first = ByteArrayOutputStream()
        assertEquals(6L, AndroidImportLimits.copy(ByteArrayInputStream(ByteArray(6)), first, budget, 8))
        assertEquals(6L, budget.usedBytes)
        val second = ByteArrayOutputStream()
        refused { budget.checkFileSize(5, 8) }
        refused { AndroidImportLimits.copy(ByteArrayInputStream(ByteArray(5)), second, budget, 8, 0) }
        assertEquals(6, first.size())
        assertTrue(first.size() + second.size() <= 10)
        assertTrue(budget.usedBytes <= 10)
    }

    @Test
    fun nonProgressingAndUnreadableStreamsAreFailuresNotEmptySuccess() {
        val stalled = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        refused { AndroidImportLimits.copy(stalled, ByteArrayOutputStream(), AndroidImportLimits.ByteBudget(8), 8) }
        val unreadable = object : InputStream() {
            override fun read(): Int = throw IOException("provider-private-path")
        }
        assertNull(AndroidImportLimits.orNull {
            AndroidImportLimits.copy(unreadable, ByteArrayOutputStream(), AndroidImportLimits.ByteBudget(8), 8)
        })
    }

    @Test
    fun documentCyclesDuplicateIdsDepthAndEntryLimitsFailClosed() {
        refused { AndroidImportLimits.TreeBudget(null) }
        refused { AndroidImportLimits.TreeBudget("x".repeat(4097)) }
        val cycle = AndroidImportLimits.TreeBudget("root")
        cycle.visit("child", 1)
        refused { cycle.visit("root", 2) }
        refused { cycle.visit("child", 2) }
        refused { cycle.visit(null, 1) }
        val depth = AndroidImportLimits.TreeBudget("root")
        for (level in 1..64) depth.visit("directory-$level", level)
        refused { depth.visit("too-deep", 65) }
        val entries = AndroidImportLimits.TreeBudget("root")
        repeat(20_000) { entries.visit("document-$it", 1) }
        refused { entries.visit("one-too-many", 1) }
    }

    @Test
    fun exclusionsKeepRealCodeAssistWorkspaceMetadata() {
        listOf("build", ".gradle", ".idea", "node_modules", ".git").forEach {
            assertTrue(AndroidImportLimits.skipDirectory(it))
        }
        listOf(".platform", "src", "assets", "build.gradle.kts", "dist").forEach {
            assertFalse(AndroidImportLimits.skipDirectory(it))
        }
    }

    @Test
    fun repeatedPicksGetFreshSnapshotsAndFailuresOnlyCleanTheirOwnDirectory() = withWorkspace { workspace ->
        val sentinel = File(workspace, "sentinel.json")
        Files.write(sentinel.toPath(), "existing".toByteArray())
        fun pick(content: String): File = AndroidImportLimits.inFreshDirectory(workspace, "picked") { root ->
            val dest = AndroidImportLimits.child(root, root, "release.aab")
            Files.write(dest.toPath(), content.toByteArray(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            dest
        }
        val first = pick("previous preflight")
        val second = pick("new provider content")
        assertNotEquals(first.absolutePath, second.absolutePath)
        assertArrayEquals("previous preflight".toByteArray(), Files.readAllBytes(first.toPath()))
        assertArrayEquals("new provider content".toByteArray(), Files.readAllBytes(second.toPath()))
        val beforeFailure = workspace.list()!!.toSet()
        assertNull(AndroidImportLimits.orNull {
            AndroidImportLimits.inFreshDirectory(workspace, "picked") { root ->
                Files.write(File(root, "partial").toPath(), byteArrayOf(1))
                // Reproduce a hostile DISPLAY_NAME without touching the neighboring sentinel.
                AndroidImportLimits.child(root, root, "../sentinel.json")
            }
        })
        assertEquals(beforeFailure, workspace.list()!!.toSet())
        assertArrayEquals("existing".toByteArray(), Files.readAllBytes(sentinel.toPath()))
        assertArrayEquals("previous preflight".toByteArray(), Files.readAllBytes(first.toPath()))
    }

    @Test
    fun cleanupDoesNotFollowLinksIntoExistingDirectories() = withWorkspace { workspace ->
        val existing = File(workspace, "existing").apply { check(mkdir()) }
        val sentinel = File(existing, "sentinel.json")
        Files.write(sentinel.toPath(), "untouched".toByteArray())
        assertNull(AndroidImportLimits.orNull {
            AndroidImportLimits.inFreshDirectory(workspace, "picked") { root ->
                Files.createSymbolicLink(File(root, "outside").toPath(), existing.toPath())
                throw IOException("provider-private-message")
            }
        })
        assertEquals(setOf("existing"), workspace.list()!!.toSet())
        assertArrayEquals("untouched".toByteArray(), Files.readAllBytes(sentinel.toPath()))
    }

    @Test
    fun cancellationPropagatesAndCleansStaging() = withWorkspace { workspace ->
        val cancelled = CancellationException("cancel")
        try {
            AndroidImportLimits.orNull {
                AndroidImportLimits.inFreshDirectory(workspace, "picked") { root ->
                    Files.write(File(root, "partial").toPath(), byteArrayOf(1))
                    throw cancelled
                }
            }
            throw AssertionError("Cancellation must not become null")
        } catch (e: CancellationException) {
            assertTrue(e === cancelled)
        }
        assertTrue(workspace.list()!!.isEmpty())
        Thread.currentThread().interrupt()
        try {
            try {
                AndroidImportLimits.orNull {
                    AndroidImportLimits.copy(EndlessInput(), ByteArrayOutputStream(), AndroidImportLimits.ByteBudget(8), 8)
                }
                throw AssertionError("Thread interruption must propagate")
            } catch (_: InterruptedIOException) {
                assertTrue(Thread.currentThread().isInterrupted)
            }
        } finally {
            Thread.interrupted()
        }
    }

    private fun refused(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected a safe import refusal")
        } catch (e: AndroidImportException) {
            assertFalse(e.message.orEmpty().contains("sentinel"))
        }
    }

    private fun withWorkspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("android-import-limits-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    private class EndlessInput : InputStream() {
        var bytesRead: Int = 0
            private set
        override fun read(): Int { bytesRead++; return 1 }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            buffer.fill(1, offset, offset + length)
            bytesRead += length
            return length
        }
    }
}
