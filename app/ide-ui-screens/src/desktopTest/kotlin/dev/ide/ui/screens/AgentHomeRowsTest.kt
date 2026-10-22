package dev.ide.ui.screens

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.AgentService
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.UiAgentChatState
import dev.ide.ui.backend.UiAgentConfig
import dev.ide.ui.backend.UiAgentModel
import dev.ide.ui.backend.UiAgentPermissionDecision
import dev.ide.ui.backend.UiAgentPermissionMode
import dev.ide.ui.backend.UiAgentPermissionRequest
import dev.ide.ui.backend.UiAgentProvider
import dev.ide.ui.theme.CodeAssistTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import dev.ide.ui.testing.DeviceConfig
import dev.ide.ui.testing.PaparazziSession
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end Compose UI test for [ProjectsHomeScreen] (phone agent-first Home):
 * - Verifies each stack (Android, Node.js, Python, Swift & iOS, Gradle) receives a distinct text color.
 * - Verifies inline repo tags render as plain text on the same single line as stack + modules (`·` separated).
 * - Verifies the most recent project row (`index == 0`) applies the text shimmer brush ONLY to the subtitle
 *   (stack, modules, tags) and NOT to the project title.
 * - Verifies the prompt card's provider status chip displays the real active provider & model (not a hardcoded "On-device").
 */
@OptIn(ExperimentalComposeUiApi::class)
class AgentHomeRowsTest {

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

    @Test
    fun distinctStackColorsForEachRuntime() {
        val colors = listOf(
            projectStackColor(isAndroid = true),
            projectStackColor(isNode = true),
            projectStackColor(isPython = true),
            projectStackColor(isSwift = true),
            projectStackColor(compatibility = true),
        )
        assertEquals(colors.size, colors.toSet().size, "Every runtime stack must have a distinct text color")
    }

    @Test
    fun homeRowsRenderInlineTagsRealProviderLabelAndSubtitleOnlyShimmerOnMostRecent() {
        val backend = object : StubBackend() {
            override val agent: AgentService = object : AgentService {
                override val chatState: StateFlow<UiAgentChatState> = MutableStateFlow(UiAgentChatState())
                override val permissionRequest: StateFlow<UiAgentPermissionRequest?> = MutableStateFlow(null)
                override val models: StateFlow<List<UiAgentModel>> = MutableStateFlow(emptyList())
                override fun config(): UiAgentConfig = UiAgentConfig(
                    providers = listOf(
                        UiAgentProvider(
                            id = "antigravity",
                            displayName = "Antigravity (Google)",
                            models = listOf(UiAgentModel("gemini-3.1-pro", "Gemini 3.1 Pro")),
                            defaultModel = "gemini-3.1-pro",
                            apiKey = "ya29.live",
                        ),
                    ),
                    selectedProvider = "antigravity",
                    model = "gemini-3.1-pro",
                    configured = true,
                    mode = UiAgentPermissionMode.ASK_EACH,
                )
                override fun refreshModels() {}
                override fun setModel(model: String) {}
                override fun selectProvider(id: String) {}
                override fun setProviderKey(providerId: String, key: String) {}
                override fun setGateway(baseUrl: String, model: String, caCert: String) {}
                override fun send(text: String) {}
                override fun retry() {}
                override fun stop() {}
                override fun newSession() {}
                override fun setPermissionMode(mode: UiAgentPermissionMode) {}
                override fun answerPermission(id: Int, decision: UiAgentPermissionDecision) {}
            }
        }

        val now = System.currentTimeMillis()
        val projects = listOf(
            ProjectInfo(
                name = "SignalAndroid",
                rootPath = "/ws/signal",
                moduleCount = 3,
                isAndroid = true,
                lastOpened = now - 60_000L,
                ownerAvatarUrl = "https://avatars.githubusercontent.com/signalapp?s=96",
                tags = listOf("android", "kotlin", " compose "),
            ),
            ProjectInfo(
                name = "FastApiService",
                rootPath = "/ws/fastapi",
                moduleCount = 1,
                isPython = true,
                lastOpened = now - 3_600_000L,
                tags = listOf("python", "asgi"),
            ),
            ProjectInfo(
                name = "NextDashboard",
                rootPath = "/ws/next",
                moduleCount = 2,
                isNode = true,
                lastOpened = now - 7_200_000L,
                tags = listOf("nodejs", "typescript"),
            ),
        )

        var cloneScreenOpened = false
        var quickClonedUrl: String? = null
        var quickClonedName: String? = null

        // Phone viewport (411 x 890 dp @ 2x) renders the real ProjectsHomeScreen directly (no onboarding gate).
        val scene = ImageComposeScene(width = 822, height = 1780, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                ProjectsHomeScreen(
                    projects = projects,
                    onOpen = {},
                    onNewProject = {},
                    onCloneRepository = { cloneScreenOpened = true },
                    onImportProject = {},
                    onCloneAndOpen = { url, name ->
                        quickClonedUrl = url
                        quickClonedName = name
                    },
                    backend = backend,
                )
            }
        }

        try {
            Snapshot.sendApplyNotifications()
            repeat(5) { frame -> scene.render((frame + 1) * 16_666_667L) }

            val allNodes = collectAllSemanticsNodes(scene)
            val allTextStrings = allNodes.flatMap { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty()
            }
            val plainStrings = allTextStrings.map { it.text }

            // 1. Onboarding gate ("What should we build?", "Continue", "Import or clone", "All projects") is gone;
            //    real action buttons + QuickCloneBar are rendered directly on ProjectsHomeScreen.
            assertFalse("What should we build?" in plainStrings, "Onboarding prompt gate must not be rendered")
            assertFalse("Import or clone" in plainStrings, "Unstyled 'Import or clone' text must not be rendered")
            assertFalse("Continue" in plainStrings, "Unstyled 'Continue' section must not be rendered")
            assertTrue("Clone app" in plainStrings, "Expected 'Clone app' button on ProjectsHomeScreen (present: $plainStrings)")
            assertTrue("Paste GitHub URL or owner/repo to clone…" in plainStrings, "Expected QuickCloneBar URL input on ProjectsHomeScreen")
            assertTrue("Paste" in plainStrings, "Expected 'Paste' button in QuickCloneBar")
            assertTrue("Clone" in plainStrings, "Expected 'Clone' button in QuickCloneBar")

            // 2. Most recent project ("SignalAndroid") title has NO shimmer brush, while its inline subtitle DOES have shimmer brushes.
            val recentTitle = allTextStrings.firstOrNull { it.text == "SignalAndroid" }
            assertNotNull(recentTitle)
            assertTrue(recentTitle.spanStyles.none { it.item.brush != null }, "Project title must NOT shimmer")

            val recentSubtitle = allTextStrings.firstOrNull { it.text.startsWith("Android · 3 modules · android · kotlin") }
            assertNotNull(recentSubtitle, "Expected inline single-line subtitle with stack, modules, and repo tags (present: $plainStrings)")
            assertTrue(recentSubtitle.spanStyles.isNotEmpty(), "Subtitle must have styled spans")
            assertTrue(recentSubtitle.spanStyles.all { it.item.brush != null }, "Most recent project subtitle (stack, modules, tags) must shimmer")

            // 3. Second project ("FastApiService") subtitle has plain colored text (Python gold) and NO shimmer brush.
            val secondSubtitle = allTextStrings.firstOrNull { it.text.startsWith("Python · 1 module · python · asgi") }
            assertNotNull(secondSubtitle, "Expected second project inline subtitle with stack, modules, and tags (present: $plainStrings)")
            assertTrue(secondSubtitle.spanStyles.none { it.item.brush != null }, "Non-most-recent project subtitle must NOT shimmer")
            val pythonSpan = secondSubtitle.spanStyles.first().item
            assertEquals(projectStackColor(isPython = true), pythonSpan.color)
            assertNotEquals(Color.Unspecified, pythonSpan.color)

            // 4. Third project ("NextDashboard") subtitle has Node.js green stack color.
            val thirdSubtitle = allTextStrings.firstOrNull { it.text.startsWith("Node.js · 2 modules · nodejs · typescript") }
            assertNotNull(thirdSubtitle)
            val nodeSpan = thirdSubtitle.spanStyles.first().item
            assertEquals(projectStackColor(isNode = true), nodeSpan.color)
            assertNull(nodeSpan.brush)
            assertFalse(cloneScreenOpened)
            assertNull(quickClonedUrl)
            assertNull(quickClonedName)
        } finally {
            scene.close()
        }
    }

    @Test
    fun paparazziGifFramesVerifySubtitleShimmerAnimatesWhileProjectTitleStaysStatic() {
        val reportDir = File("build/reports/paparazzi/home-rows")
        PaparazziSession(
            deviceConfig = DeviceConfig.PIXEL_9_PRO.withHeight(500),
            reportDir = reportDir,
        ) {
            CodeAssistTheme(dark = true) {
                AgentProjectRow(
                    title = "SignalAndroid",
                    meta = "Android · 3 modules",
                    time = "2m",
                    letter = "S",
                    stackLabel = "Android",
                    stackColor = projectStackColor(isAndroid = true),
                    modulesLabel = "3 modules",
                    tags = listOf("android", "kotlin", "compose"),
                    shimmerSubtitle = true,
                    selected = true,
                    onClick = {},
                )
            }
        }.use { session ->
            val titleNode = session.findNodeByText("SignalAndroid")
            assertNotNull(titleNode, "Expected project title node")
            val subtitleNode = session.findNodeByText("Android · 3 modules · android · kotlin · compose", substring = true)
            assertNotNull(subtitleNode, "Expected inline subtitle node")

            // Capture 6 animation frames @ 140ms step and generate GIF + PNG filmstrip + HTML report
            val gifResult = session.captureGifFrames(
                name = "most_recent_project_shimmer",
                frameCount = 6,
                frameStepMs = 140L,
            )
            assertTrue(gifResult.gifFile.exists() && gifResult.gifFile.length() > 500)
            assertTrue(gifResult.stripFile.exists() && gifResult.stripFile.length() > 1000)

            // Verify pixel animation: the subtitle's bounding box MUST have animated pixels across frames,
            // while the project title's bounding box must have 0 animated pixels!
            val titleAnimatedPixels = gifResult.countAnimatedPixelsInRect(titleNode.boundsInRoot)
            val subtitleAnimatedPixels = gifResult.countAnimatedPixelsInRect(subtitleNode.boundsInRoot)
            assertEquals(0, titleAnimatedPixels, "Project title pixels must NOT shimmer across animation frames")
            assertTrue(
                subtitleAnimatedPixels > 20,
                "Most recent project subtitle (OS, modules, tags) pixels MUST animate across frames (found $subtitleAnimatedPixels animated px)",
            )
        }
    }
}
