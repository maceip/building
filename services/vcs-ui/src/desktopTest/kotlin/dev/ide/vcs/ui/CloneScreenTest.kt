package dev.ide.vcs.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.UiProjectFolderKind
import dev.ide.ui.backend.UiVcsAccount
import dev.ide.ui.backend.UiVcsActivity
import dev.ide.ui.backend.UiVcsResult
import dev.ide.ui.backend.UiVcsStatus
import dev.ide.ui.backend.VcsService
import dev.ide.ui.ext.ScreenContext
import dev.ide.ui.theme.CodeAssistTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class CloneScreenTest {

    private class FakeVcsService : VcsService {
        override val status = MutableStateFlow(UiVcsStatus())
        override val accounts = MutableStateFlow(emptyList<UiVcsAccount>())
        override val activity = MutableStateFlow(UiVcsActivity())
        override fun supported(): Boolean = true

        val cloneCalls = mutableListOf<Pair<String, String>>()
        var nextCloneOutcome: (String, String) -> UiVcsResult = { _, folder ->
            UiVcsResult(
                ok = true,
                path = "/workspace/$folder",
                projectKind = UiProjectFolderKind.GRADLE,
                message = "Cloned $folder",
            )
        }

        override suspend fun cloneRepository(url: String, directoryName: String): UiVcsResult {
            cloneCalls += url to directoryName
            return nextCloneOutcome(url, directoryName)
        }
    }

    private class FakeClipboard(var clip: String = "") : ClipboardManager {
        override fun getText(): AnnotatedString? = if (clip.isEmpty()) null else AnnotatedString(clip)
        override fun setText(annotatedString: AnnotatedString) {
            clip = annotatedString.text
        }
    }

    private fun collectAllSemanticsNodes(scene: ImageComposeScene): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            out += node
            node.children.forEach(::visit)
        }
        scene.semanticsOwners.forEach { owner ->
            visit(owner.unmergedRootSemanticsNode)
        }
        return out
    }

    private fun collectTexts(scene: ImageComposeScene): List<String> =
        collectAllSemanticsNodes(scene).flatMap { node ->
            val texts = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
            val editable = node.config.getOrNull(SemanticsProperties.EditableText)?.text?.let(::listOf).orEmpty()
            texts + editable
        }

    private fun findNodeByText(scene: ImageComposeScene, text: String): SemanticsNode? =
        collectAllSemanticsNodes(scene).firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
                node.config.getOrNull(SemanticsProperties.EditableText)?.text == text
        }

    @Test
    fun cloneUrlInputBoxPastesClonesNormalizesShorthandAndKeepsRecommendedListStable() {
        val fakeVcs = FakeVcsService()
        val openedProjects = mutableListOf<String>()
        val backend = object : StubBackend() {
            override val vcs: VcsService = fakeVcs
            override suspend fun openProject(rootPath: String): Boolean {
                openedProjects += rootPath
                return true
            }
        }
        val ctx = object : ScreenContext {
            override val backend = backend
            override fun back() {}
            override fun openScreen(id: String) {}
        }
        val clipboard = FakeClipboard("kushagrasinghx/BitChord")

        val scene = ImageComposeScene(width = 900, height = 4200, density = Density(2f)) {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                CodeAssistTheme(dark = true) {
                    CloneScreen(ctx)
                }
            }
        }

        var time = 0L
        fun frames(count: Int = 12) {
            Snapshot.sendApplyNotifications()
            repeat(count) {
                time += 16_666_667L
                scene.render(time)
            }
        }

        fun clickText(label: String) {
            val node = findNodeByText(scene, label)
            assertNotNull(node, "Expected node '$label' in ${collectTexts(scene)}")
            val center = node.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, center)
            scene.sendPointerEvent(PointerEventType.Release, center)
            frames()
        }

        try {
            frames()
            val initialTexts = collectTexts(scene)

            // 1. Verify pinned URL input card and Paste URL button exist at the top
            assertTrue("Clone from GitHub or Git URL" in initialTexts)
            assertTrue("Paste URL" in initialTexts)
            assertTrue("https://github.com/owner/repo or owner/repo" in initialTexts)

            // 2. Record initial order of recommended repositories
            val expectedCompanions = listOf(
                "cortinico/kotlin-android-template",
                "skydoves/Sandwich",
                "AfrazCodes/SwiftUI-Calculator",
                "ricardomongza99/Calculator",
                "johnsundell/Ink",
                "karpathy/micrograd",
                "karpathy/nanoGPT",
                "jameshibbard/nodegui-password-generator",
                "lukeed/polka",
            )
            val initialOrder = expectedCompanions.map { name ->
                val node = findNodeByText(scene, name)
                assertNotNull(node, "Missing companion repo '$name'")
                name to node.boundsInRoot.top
            }
            for (i in 0 until initialOrder.size - 1) {
                assertTrue(
                    initialOrder[i].second < initialOrder[i + 1].second,
                    "Expected ${initialOrder[i].first} above ${initialOrder[i + 1].first}",
                )
            }

            // 3. Tap a companion card ("AfrazCodes/SwiftUI-Calculator") -> clones without reordering the list
            // or auto-teleporting (openWhenDone = false for companion cards)
            clickText("AfrazCodes/SwiftUI-Calculator")
            assertEquals(
                listOf("https://github.com/AfrazCodes/SwiftUI-Calculator.git" to "SwiftUI-Calculator"),
                fakeVcs.cloneCalls,
            )
            assertTrue(openedProjects.isEmpty(), "Tapping a companion card should show 'Open Project' instead of auto-teleporting")
            assertTrue("Open Project" in collectTexts(scene))

            // Verify all 9 companion cards are still present in the exact same order
            val afterCompanionOrder = expectedCompanions.map { name ->
                val node = findNodeByText(scene, name)
                assertNotNull(node, "Companion repo '$name' disappeared after cloning!")
                name
            }
            assertEquals(expectedCompanions, afterCompanionOrder)

            // 4. Tap "Paste URL" -> populates urlInput ("kushagrasinghx/BitChord") and folderInput ("BitChord")
            clickText("Paste URL")
            val textsAfterPaste = collectTexts(scene)
            assertTrue("kushagrasinghx/BitChord" in textsAfterPaste)
            assertTrue("BitChord" in textsAfterPaste)

            // 5. Tap "Clone" -> normalizes shorthand to https://github.com/kushagrasinghx/BitChord.git and clones
            clickText("Clone")
            assertEquals(
                "https://github.com/kushagrasinghx/BitChord.git" to "BitChord",
                fakeVcs.cloneCalls.last(),
            )

            // 6. Verify clone error banner renders when clone fails
            fakeVcs.nextCloneOutcome = { url, _ ->
                UiVcsResult(
                    ok = false,
                    path = null,
                    message = "Repository not found: $url (HTTP 404)",
                )
            }
            clipboard.clip = "https://github.com/5vivizzz007/vivi-music"
            clickText("Paste URL")
            clickText("Clone")
            val errorTexts = collectTexts(scene)
            assertTrue(
                errorTexts.any { it.contains("Repository not found: https://github.com/5vivizzz007/vivi-music (HTTP 404)") },
                "Expected error banner in $errorTexts",
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun midCloneAppSwitchOrCompositionDisposalDoesNotAbortCloneOrLoseQueuedRepos() {
        val cloneCompleted = kotlinx.coroutines.CompletableDeferred<UiVcsResult>()
        val fakeVcs = object : VcsService {
            override val status = MutableStateFlow(UiVcsStatus())
            override val accounts = MutableStateFlow(emptyList<UiVcsAccount>())
            override val activity = MutableStateFlow(UiVcsActivity())
            override fun supported(): Boolean = true

            val calls = mutableListOf<Pair<String, String>>()

            override suspend fun cloneRepository(url: String, directoryName: String): UiVcsResult {
                calls += url to directoryName
                activity.value = UiVcsActivity(busy = true, task = "Receiving objects", fraction = 0.42f)
                return try {
                    if (calls.size == 1) {
                        cloneCompleted.await()
                    } else {
                        UiVcsResult(
                            ok = true,
                            path = "/workspace/$directoryName",
                            projectKind = UiProjectFolderKind.NODE,
                            message = "Cloned $directoryName",
                        )
                    }
                } finally {
                    activity.value = UiVcsActivity()
                }
            }
        }

        val openedProjects = mutableListOf<String>()
        val backend = object : StubBackend() {
            override val vcs: VcsService = fakeVcs
            override suspend fun openProject(rootPath: String): Boolean {
                openedProjects += rootPath
                return true
            }
        }
        val ctx = object : ScreenContext {
            override val backend = backend
            override fun back() {}
            override fun openScreen(id: String) {}
        }
        val clipboard = FakeClipboard("maceip/building")

        // 1. Mount first composition, paste URL, start clone, and queue a companion repo
        val scene1 = ImageComposeScene(width = 900, height = 4200, density = Density(2f)) {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                CodeAssistTheme(dark = true) {
                    CloneScreen(ctx)
                }
            }
        }
        var time = 0L
        fun frames(scene: ImageComposeScene, count: Int = 12) {
            Snapshot.sendApplyNotifications()
            repeat(count) {
                time += 16_666_667L
                scene.render(time)
            }
        }
        fun clickText(scene: ImageComposeScene, label: String) {
            val node = findNodeByText(scene, label)
            assertNotNull(node, "Expected node '$label' in ${collectTexts(scene)}")
            val center = node.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, center)
            scene.sendPointerEvent(PointerEventType.Release, center)
            frames(scene)
        }

        frames(scene1)
        clickText(scene1, "Paste URL")
        clickText(scene1, "Clone")
        assertEquals(listOf("https://github.com/maceip/building.git" to "building"), fakeVcs.calls)
        assertTrue(fakeVcs.activity.value.busy, "Clone should be in flight")

        // Queue a companion repo while primary clone is in flight
        clickText(scene1, "jameshibbard/nodegui-password-generator")
        assertTrue("Queued ✓" in collectTexts(scene1))

        // 2. Simulate switching focused Android apps / disposing composition mid-clone
        scene1.close()

        // 3. Complete the primary clone while UI composition is disposed
        cloneCompleted.complete(
            UiVcsResult(
                ok = true,
                path = "/workspace/building",
                projectKind = UiProjectFolderKind.GRADLE,
                message = "Cloned building",
            ),
        )

        // 4. Re-mount CloneScreen (user switches back to the app) and verify both primary and queued clones succeeded!
        val scene2 = ImageComposeScene(width = 900, height = 4200, density = Density(2f)) {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                CodeAssistTheme(dark = true) {
                    CloneScreen(ctx)
                }
            }
        }
        try {
            frames(scene2, 40)
            assertEquals(
                listOf(
                    "https://github.com/maceip/building.git" to "building",
                    "https://github.com/jameshibbard/nodegui-password-generator.git" to "nodegui-password-generator",
                ),
                fakeVcs.calls,
                "Both the primary clone and the queued companion repo must complete even when composition was disposed mid-clone",
            )
            val textsAfterResume = collectTexts(scene2)
            assertTrue("Cloned nodegui-password-generator ✓" in textsAfterResume, "Expected 'Cloned nodegui-password-generator ✓' in $textsAfterResume")
            assertTrue("Open Project" in textsAfterResume, "Expected 'Open Project' in $textsAfterResume")
        } finally {
            scene2.close()
        }
    }

    @Test
    fun normalizeCloneUrlSanitizesGboardAutoCorrectSpacesAndCapitalization() {
        assertEquals(
            "https://github.com/maceip/building.git",
            normalizeCloneUrl("https://GitHub. com/maceip/building. git"),
        )
        assertEquals(
            "https://github.com/maceip/building.git",
            normalizeCloneUrl("maceip/building"),
        )
        assertEquals(
            "https://github.com/maceip/building",
            normalizeCloneUrl("GitHub. com/maceip/building"),
        )
    }
}
