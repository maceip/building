package dev.ide.ui

import androidx.compose.ui.text.TextRange
import dev.ide.ui.backend.UiLibraryContent
import dev.ide.ui.backend.UiRenameResult
import dev.ide.ui.editor.core.EditorDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Regression guards for on-device editor crashes and the tab lifetimes involved. */
@OptIn(ExperimentalCoroutinesApi::class)
class CrashFixesTest {

    @Test
    fun lineAccessorsClampStaleIndex() {
        val doc = EditorDocument.of("a\nbb\nccc") // 3 lines: starts [0, 2, 5]
        // A line index captured a frame before an edit shortened the document must clamp to a valid line
        // instead of throwing ArrayIndexOutOfBounds (was the #1 editor crash: EditorDocument.lineStart).
        assertEquals(doc.lineStart(2), doc.lineStart(99))
        assertEquals(doc.lineEnd(2), doc.lineEnd(99))
        assertEquals(doc.lineStart(0), doc.lineStart(-5))
        // Valid indices unaffected.
        assertEquals(0, doc.lineStart(0))
        assertEquals(2, doc.lineStart(1))
        assertEquals(8, doc.lineEnd(2))
    }

    @Test
    fun clipboardCopyIsCappedKeepingTail() {
        assertEquals("hello", clipForClipboard("hello"))
        val big = "X".repeat(500_000) + "TAIL_END"
        val clipped = clipForClipboard(big)
        assertTrue(clipped.length < big.length, "a large log must be truncated (else TransactionTooLargeException)")
        assertTrue(clipped.length <= 200_000 + 200, "kept within the clipboard cap")
        assertTrue(clipped.endsWith("TAIL_END"), "keeps the tail where a build's errors/status live")
        assertTrue(clipped.startsWith("["), "notes that earlier text was dropped")
    }

    @Test
    fun renamingOntoAnAlreadyOpenTabLeavesOneTabPerPath() {
        val disk = mutableMapOf("/p/A.java" to "class A {}", "/p/B.java" to "class B {}")
        val backend = RetainingBackend().apply { read = { disk[it] ?: "" } }
        // Unconfined dispatchers make the async tab reload run synchronously here.
        val state = IdeUiState(backend, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        runBlocking {
            state.openSuspend("/p/A.java", "A.java")
            state.openSuspend("/p/B.java", "B.java")
        }
        assertEquals(2, state.openFiles.size, "two distinct files, two tabs")

        // Rename A.java onto B.java, which is itself open: the followed tab lands on a path another tab holds.
        disk["/p/B.java"] = "class A {}"
        state.reloadAfterRename(activePath = "/p/A.java", newPath = "/p/B.java")

        // Two tabs sharing a path repeat the tab strip's lazy key, which throws and takes the editor down.
        assertEquals(
            listOf("/p/B.java"), state.openFiles.map { it.path },
            "the redundant tab must be dropped, leaving one tab per path",
        )
        assertEquals(0, state.activeIndex, "the active tab follows its path to the tab that kept it")
        assertEquals(setOf(1), backend.retained.keys, "the followed tab keeps its lease; only the duplicate releases")
        assertEquals(listOf(2), backend.releaseCalls)
        assertTrue(backend.closed.isEmpty(), "deduplication must not close the kept document in the backend")
        state.dispose()
        assertTrue(backend.retained.isEmpty(), "replacing an OpenFile must not orphan its original lease")
        assertEquals(listOf(2, 1), backend.releaseCalls)
    }

    @Test
    fun tabsSharingAPathStillGetDistinctStripKeys() {
        // Two OpenFile for one path (a re-point after rename/move, or a concurrent open on a slow device) must
        // NOT collide on the tab strip's LazyRow key — a duplicate key hard-crashes the measure pass
        // (`measureLazyList` precondition), the top non-native on-device crasher. The strip keys on the unique
        // tabId, so even a transient duplicate path yields distinct keys.
        val a = OpenFile("/p/Same.kt", "Same.kt", "class A")
        val b = OpenFile("/p/Same.kt", "Same.kt", "class A")
        assertTrue(a.tabId != b.tabId, "each tab gets a unique id even for the same path")
        val keys = listOf(a, b).map { it.tabId }
        assertEquals(keys.size, keys.toSet().size, "tab strip keys are unique (no repeated LazyRow key)")

        // A re-point that reuses the id (an in-place refresh of the SAME logical tab) keeps its strip identity.
        val refreshed = OpenFile("/p/Same.kt", "Same.kt", "class A v2", tabId = a.tabId)
        assertEquals(a.tabId, refreshed.tabId, "an in-place tab refresh keeps the tab's strip identity")
    }

    @Test
    fun documentIsRetainedBeforeReadAndUntilAfterCloseNotification() = runBlocking {
        val backend = RetainingBackend()
        val state = IdeUiState(backend, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        backend.read = { path ->
            assertEquals(listOf(path), backend.retained.values.toList(), "retain must precede the disk read")
            assertTrue(state.openFiles.isEmpty(), "the opening tab already protects the file before installation")
            "class A {}"
        }
        state.openSuspend("/p/A.java", "A.java")
        val tab = state.openFiles.single()
        // Focusing is not another retain/update/open, but it does refresh the focused tab from disk
        // (an outside copy shows on retap), so a second disk read with no change behind it is expected.
        state.openSuspend("/p/A.java", "A.java")
        assertEquals(
            listOf("retain:1", "read:/p/A.java", "update:/p/A.java", "open:/p/A.java", "read:/p/A.java"),
            backend.events,
        )
        backend.closing = { path ->
            assertTrue(tab !in state.openFiles, "remove the authoritative tab before notifying the backend")
            assertEquals(listOf(path), backend.retained.values.toList(), "the lease still covers onFileClosed")
        }
        state.close(tab)
        state.close(tab)
        state.dispose()
        state.dispose()
        assertEquals(listOf("close:/p/A.java", "release:1"), backend.events.takeLast(2))
        assertEquals(listOf(1), backend.releaseCalls, "close and repeated disposal release this acquisition once")
        assertTrue(backend.retained.isEmpty())
    }

    @Test
    fun concurrentDuplicateOpenReleasesOnlyItsOwnRetention() = runTest {
        val backend = RetainingBackend()
        val main = UnconfinedTestDispatcher(testScheduler)
        val state = IdeUiState(backend, mainDispatcher = main, ioDispatcher = StandardTestDispatcher(testScheduler))
        val first = launch(main) { state.openSuspend("/p/A.java", "A.java") }
        val duplicate = launch(main) { state.openSuspend("/p/A.java", "A.java") }
        assertEquals(setOf(1, 2), backend.retained.keys, "both suspended reads must own independent leases")
        assertTrue(state.openFiles.isEmpty())
        runCurrent()
        assertTrue(first.isCompleted && duplicate.isCompleted)
        assertEquals(1, state.openFiles.size)
        assertEquals(setOf(1), backend.retained.keys, "the installed tab's lease must survive the duplicate")
        assertEquals(listOf(2), backend.releaseCalls)
        assertEquals(1, backend.events.count { it.startsWith("update:") })
        assertEquals(1, backend.events.count { it.startsWith("open:") })
        assertTrue(backend.closed.isEmpty())
        state.dispose()
        assertEquals(listOf(2, 1), backend.releaseCalls)
        assertTrue(backend.retained.isEmpty())
    }

    @Test
    fun cancelledOpenReleasesItsOwnRetentionAndLetsConcurrentOpenFinish() = runTest {
        val backend = RetainingBackend()
        val main = UnconfinedTestDispatcher(testScheduler)
        val state = IdeUiState(backend, mainDispatcher = main, ioDispatcher = StandardTestDispatcher(testScheduler))
        val cancelled = launch(main) { state.openSuspend("/p/A.java", "A.java") }
        val winner = launch(main) { state.openSuspend("/p/A.java", "A.java") }
        assertEquals(setOf(1, 2), backend.retained.keys)
        cancelled.cancel()
        runCurrent()
        assertTrue(cancelled.isCancelled && winner.isCompleted)
        assertEquals(1, state.openFiles.size)
        assertEquals(setOf(2), backend.retained.keys, "cancellation must not release the other open's lease")
        assertEquals(listOf(1), backend.releaseCalls)
        assertEquals(1, backend.events.count { it.startsWith("read:") }, "the cancelled queued read does not run")
        assertTrue(backend.closed.isEmpty())
        state.dispose()
        assertEquals(listOf(1, 2), backend.releaseCalls)
    }

    @Test
    fun cancellationDuringReadReleasesBeforeInstallingATab() = runTest {
        val backend = RetainingBackend()
        val main = UnconfinedTestDispatcher(testScheduler)
        val state = IdeUiState(backend, mainDispatcher = main, ioDispatcher = StandardTestDispatcher(testScheduler))
        lateinit var opening: Job
        backend.read = { opening.cancel(); "class A {}" }
        opening = launch(main) { state.openSuspend("/p/A.java", "A.java") }
        runCurrent()
        assertTrue(opening.isCancelled)
        assertTrue(state.openFiles.isEmpty())
        assertEquals(listOf("retain:1", "read:/p/A.java", "release:1"), backend.events)
        assertTrue(backend.retained.isEmpty())
        state.dispose()
    }

    @Test
    fun readAndDocumentUpdateFailuresReleaseUninstalledRetention() = runBlocking {
        for (failRead in listOf(true, false)) {
            val backend = RetainingBackend()
            val state = IdeUiState(backend, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
            val failure = IllegalStateException(if (failRead) "read failed" else "update failed")
            if (failRead) backend.read = { throw failure } else backend.updating = { throw failure }
            val thrown = assertFailsWith<IllegalStateException> { state.openSuspend("/p/A.java", "A.java") }
            assertEquals(failure.message, thrown.message)
            assertTrue(state.openFiles.isEmpty())
            assertTrue(backend.retained.isEmpty())
            assertEquals(listOf(1), backend.releaseCalls)
            assertTrue(backend.events.none { it.startsWith("open:") || it.startsWith("close:") })
            state.dispose()
            assertEquals(listOf(1), backend.releaseCalls)
        }
    }

    @Test
    fun openNotificationFailureKeepsTheInstalledTabRetained() = runBlocking {
        val backend = RetainingBackend()
        val state = IdeUiState(backend, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        backend.opening = { error("open notification failed") }
        assertFailsWith<IllegalStateException> { state.openSuspend("/p/A.java", "A.java") }
        assertEquals(1, state.openFiles.size, "the tab was already installed before the notification")
        assertEquals(setOf(1), backend.retained.keys, "finally must not release a still-authoritative session")
        state.dispose()
        assertTrue(backend.retained.isEmpty())
        assertEquals(listOf(1), backend.releaseCalls)
    }

    @Test
    fun closeNotificationFailureStillReleasesTheRemovedTab() = runBlocking {
        val backend = RetainingBackend()
        val state = IdeUiState(backend, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        state.openSuspend("/p/A.java", "A.java")
        backend.closing = {
            assertTrue(state.openFiles.isEmpty())
            assertEquals(setOf(1), backend.retained.keys)
            error("close notification failed")
        }
        assertFailsWith<IllegalStateException> { state.close(state.openFiles.single()) }
        assertTrue(backend.retained.isEmpty())
        assertEquals(listOf("close:/p/A.java", "release:1"), backend.events.takeLast(2))
        state.dispose()
        assertEquals(listOf(1), backend.releaseCalls)
    }

    @Test
    fun disposalReleasesAllTabsWithoutTouchingAReplacementProjectState() = runBlocking {
        val backend = RetainingBackend()
        val oldState = IdeUiState(backend, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        oldState.openSuspend("/p/A.java", "A.java")
        oldState.openSuspend("/p/build.gradle.kts", "build.gradle.kts")
        oldState.openFiles.first().session.replaceRange(0, 0, "// unsaved\n", TextRange(0))
        assertTrue(oldState.openFiles.first().modified)
        backend.releasing = { assertTrue(oldState.openFiles.isEmpty(), "remove all old sessions before releasing") }
        // The shared backend now serves a fresh engine/project state; release identity belongs to the callback.
        val newState = IdeUiState(backend, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        newState.openSuspend("/p/A.java", "A.java")
        oldState.dispose()
        oldState.dispose()
        assertTrue(oldState.openFiles.isEmpty())
        assertEquals(-1, oldState.activeIndex)
        assertEquals(setOf(3), backend.retained.keys, "old state disposal cannot release the new state's same-path tab")
        assertEquals(listOf(1, 2), backend.releaseCalls, "clean and dirty tabs both release on project disposal")
        assertTrue(backend.closed.isEmpty(), "do not send new close notifications into a possibly replaced backend")
        assertFailsWith<CancellationException> { oldState.openSuspend("/p/C.java", "C.java") }
        assertEquals(setOf(3), backend.retained.keys, "a disposed state cannot retain or install another tab")
        newState.dispose()
        assertEquals(listOf(1, 2, 3), backend.releaseCalls)
        assertTrue(backend.retained.isEmpty())
    }

    @Test
    fun disposalCancelsAQueuedOpenAndReleasesItsRetention() = runTest {
        val backend = RetainingBackend()
        val state = IdeUiState(
            backend, mainDispatcher = UnconfinedTestDispatcher(testScheduler),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        state.open("/p/A.java", "A.java")
        assertEquals(setOf(1), backend.retained.keys)
        state.dispose()
        runCurrent()
        assertTrue(state.openFiles.isEmpty())
        assertTrue(backend.retained.isEmpty())
        assertEquals(listOf("retain:1", "release:1"), backend.events)
    }

    @Test
    fun disposalDuringAnExternallyOwnedOpenPreventsLateTabInstallation() = runTest {
        val backend = RetainingBackend()
        val main = UnconfinedTestDispatcher(testScheduler)
        val state = IdeUiState(backend, mainDispatcher = main, ioDispatcher = StandardTestDispatcher(testScheduler))
        backend.read = { state.dispose(); "class A {}" }
        val opening = launch(main) { state.openSuspend("/p/A.java", "A.java") }
        runCurrent()
        assertTrue(opening.isCancelled, "openSuspend must also check the project state's lifetime")
        assertTrue(state.openFiles.isEmpty())
        assertTrue(backend.retained.isEmpty())
        assertEquals(listOf("retain:1", "read:/p/A.java", "release:1"), backend.events)
    }

    @Test
    fun renamedTabCarriesItsRetentionUntilTheReplacementIsClosed() = runBlocking {
        val backend = RetainingBackend()
        val state = IdeUiState(backend, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        state.openSuspend("/p/A.java", "A.java")
        val original = state.openFiles.single()
        assertTrue(state.renamePath("/p/A.java", "B.java").success)
        val replacement = state.openFiles.single()
        assertEquals("/p/B.java", replacement.path)
        assertEquals(original.tabId, replacement.tabId)
        assertEquals(setOf(1), backend.retained.keys)
        state.close(original)
        assertTrue(backend.releaseCalls.isEmpty(), "a stale OpenFile must not close the replacement's lease")
        state.close(replacement)
        assertEquals(listOf("/p/B.java"), backend.closed)
        assertEquals(listOf(1), backend.releaseCalls)
        assertTrue(backend.retained.isEmpty())
        state.dispose()
    }

    @Test
    fun libraryAndInMemoryTabsDoNotAcquireDocumentRetentions() = runBlocking {
        val backend = RetainingBackend()
        val state = IdeUiState(backend, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        state.openLibrary("library://p.A")
        assertTrue(state.openFiles.single().readOnly)
        state.openFiles.add(OpenFile("snippet://example", "Example.java", "class Example {}"))
        assertTrue(backend.events.none { it.startsWith("retain:") })
        state.closeAll()
        state.dispose()
        assertTrue(backend.releaseCalls.isEmpty())
        assertTrue(state.openFiles.isEmpty())
    }

    @Test
    fun repeatedLazyKeysAreRenamedRatherThanCrashingTheMeasurePass() {
        // The generic guard behind `itemsKeyed`: whatever a caller's key lambda returns, the list a lazy
        // list is drawn with has no repeat, so `subcompose` can never throw "Key … was already used".
        val keys = uniqueKeys(listOf("a", "b", "a", "a", "c")) { it }
        assertEquals(keys.size, keys.toSet().size, "no repeated key survives")
        assertEquals(listOf<Any>("a", "b"), keys.take(2), "the first claim on a key keeps it unchanged")

        // Stable for the same content: an item does not lose its identity (or its animateItem) on recompose.
        assertEquals(keys, uniqueKeys(listOf("a", "b", "a", "a", "c")) { it })

        // The rename cannot collide with a natural key that already looks renamed.
        val adversarial = uniqueKeys(listOf("a", "a\u00001", "a", "a")) { it }
        assertEquals(adversarial.size, adversarial.toSet().size, "a key shaped like a rename is still safe")

        // Mixed key types (the store feed keys on ids, the leaderboards on ranks) stay distinct.
        val mixed = uniqueKeysIndexed(listOf(1, 1, 2)) { _, n -> n }
        assertEquals(mixed.size, mixed.toSet().size)
        assertEquals(1, mixed[0], "an unrepeated key keeps its original type")
    }

    /** Counts acquisitions, not paths: one attempt may only release the token it acquired. */
    private class RetainingBackend : StubBackend() {
        val retained = linkedMapOf<Int, String>()
        val releaseCalls = mutableListOf<Int>()
        val events = mutableListOf<String>()
        val closed = mutableListOf<String>()
        var read: (String) -> String = { "class A {}" }
        var updating: (String) -> Unit = {}
        var opening: (String) -> Unit = {}
        var closing: (String) -> Unit = {}
        var releasing: (Int) -> Unit = {}
        private var nextRetention = 0

        override fun retainDocument(path: String): () -> Unit {
            val token = ++nextRetention
            retained[token] = path
            events += "retain:$token"
            return {
                releaseCalls += token
                releasing(token)
                if (retained.remove(token) != null) events += "release:$token"
            }
        }

        override fun readFile(path: String): String {
            events += "read:$path"
            return read(path)
        }

        override fun updateDocument(path: String, text: String) {
            events += "update:$path"
            updating(path)
        }

        override fun onFileOpened(path: String) {
            events += "open:$path"
            opening(path)
        }

        override fun onFileClosed(path: String) {
            events += "close:$path"
            closed += path
            closing(path)
        }

        override suspend fun renamePath(path: String, newName: String): UiRenameResult =
            UiRenameResult(true, "renamed", newPath = path.substringBeforeLast('/') + "/" + newName)

        override suspend fun libraryContent(contextPath: String, fqn: String, forceJava: Boolean): UiLibraryContent =
            UiLibraryContent("library://$fqn", "A.java", "class A {}", "source")
    }
}
