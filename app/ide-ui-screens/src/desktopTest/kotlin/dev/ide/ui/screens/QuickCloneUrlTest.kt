package dev.ide.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.ide.ui.testing.DeviceConfig
import dev.ide.ui.testing.PaparazziSession
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class QuickCloneUrlTest {

    /**
     * The phone home's clone slot: expanded, typing a URL and tapping the trailing fork glyph submits
     * it through the same normalization the docked bar used.
     */
    @Test
    fun cloneFieldSubmitsOnTrailingGlyphTap() {
        val tempDir = Files.createTempDirectory("clone-field-ui-test").toFile()
        try {
            var dispatchedUrl: String? = null
            var fallbackOpened = false
            var typedUrl = ""
            PaparazziSession(
                deviceConfig = DeviceConfig.PIXEL_9_PRO.withHeight(240),
                reportDir = tempDir,
            ) {
                MaterialTheme {
                    HomeCloneField(
                        expanded = true,
                        url = typedUrl,
                        onUrlChange = { typedUrl = it },
                        onExpand = {},
                        onSubmit = {
                            dispatchQuickCloneUrl(
                                rawUrl = typedUrl,
                                onCloneAndOpen = { url, _ -> dispatchedUrl = url },
                                onCloneRepository = { fallbackOpened = true },
                            )
                        },
                    )
                }
            }.use { session ->
                val root = session.scene.semanticsOwners.first().unmergedRootSemanticsNode
                fun walk(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> =
                    listOf(node) + node.children.flatMap { walk(it) }

                // Type the repo into the field, then submit with the trailing fork glyph — the node
                // labelled "Clone" that carries an OnClick action.
                val editable = walk(root).first { it.config.getOrNull(SemanticsActions.SetText) != null }
                editable.config[SemanticsActions.SetText].action?.invoke(
                    androidx.compose.ui.text.AnnotatedString("microsoft/vscode"),
                )
                session.advanceFrames(3)

                val submitGlyph = walk(root).first { node ->
                    val desc = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    "Clone" in desc && node.config.getOrNull(SemanticsActions.OnClick) != null
                }
                submitGlyph.config[SemanticsActions.OnClick].action?.invoke()
                session.advanceFrames(3)

                assertEquals("https://github.com/microsoft/vscode", dispatchedUrl)
                assertTrue(!fallbackOpened)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun quickCloneBarRendersAndTriggersCloneInComposeScene() {
        val tempDir = Files.createTempDirectory("quick-clone-ui-test").toFile()
        try {
            var dispatchedUrl: String? = null
            var cloneDialogCount = 0
            PaparazziSession(
                deviceConfig = DeviceConfig.PIXEL_9_PRO.withHeight(240),
                reportDir = tempDir,
            ) {
                MaterialTheme {
                    QuickCloneBar(
                        onCloneUrl = { raw ->
                            dispatchQuickCloneUrl(
                                rawUrl = raw,
                                onCloneAndOpen = { url, _ -> dispatchedUrl = url },
                                onCloneRepository = { cloneDialogCount++ },
                            )
                        },
                    )
                }
            }.use { session ->
                // Edit text in the BasicTextField via SetText semantics action
                val root = session.scene.semanticsOwners.first().unmergedRootSemanticsNode
                fun walk(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> =
                    listOf(node) + node.children.flatMap { walk(it) }
                val allNodes = walk(root)
                val editable = allNodes.first { it.config.getOrNull(SemanticsActions.SetText) != null }
                editable.config[SemanticsActions.SetText].action?.invoke(
                    androidx.compose.ui.text.AnnotatedString("AfrazCodes/SwiftUI-Calculator"),
                )
                session.advanceFrames(3)

                // Click the Clone button via OnClick semantics action
                val cloneBtn = walk(session.scene.semanticsOwners.first().unmergedRootSemanticsNode).first { node ->
                    val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                        node.children.flatMap { c -> c.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
                    "Clone" in texts && node.config.getOrNull(SemanticsActions.OnClick) != null
                }
                cloneBtn.config[SemanticsActions.OnClick].action?.invoke()
                session.advanceFrames(3)

                assertEquals("https://github.com/AfrazCodes/SwiftUI-Calculator", dispatchedUrl)
                assertEquals(0, cloneDialogCount)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun normalizesOwnerRepoAndDispatchesCloneAndOpen() {
        var clonedUrl: String? = null
        var clonedName: String? = null
        var fallbackOpened = false

        dispatchQuickCloneUrl(
            rawUrl = "  kushagrasinghx/BitChord  ",
            onCloneAndOpen = { url, name ->
                clonedUrl = url
                clonedName = name
            },
            onCloneRepository = { fallbackOpened = true },
        )

        assertEquals("https://github.com/kushagrasinghx/BitChord", clonedUrl)
        assertEquals("BitChord", clonedName)
        assertTrue(!fallbackOpened)
    }

    @Test
    fun preservesFullHttpsUrlAndStripsGitSuffix() {
        var clonedUrl: String? = null
        var clonedName: String? = null

        dispatchQuickCloneUrl(
            rawUrl = "https://github.com/fullstackio/FlappySwift.git",
            onCloneAndOpen = { url, name ->
                clonedUrl = url
                clonedName = name
            },
            onCloneRepository = null,
        )

        assertEquals("https://github.com/fullstackio/FlappySwift.git", clonedUrl)
        assertEquals("FlappySwift", clonedName)
    }

    @Test
    fun emptyUrlOpensCloneRepositoryDialog() {
        var clonedUrl: String? = null
        var fallbackOpened = false

        dispatchQuickCloneUrl(
            rawUrl = "   ",
            onCloneAndOpen = { url, _ -> clonedUrl = url },
            onCloneRepository = { fallbackOpened = true },
        )

        assertEquals(null, clonedUrl)
        assertTrue(fallbackOpened)
    }
}
