package dev.ide.agent.ui

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.AgentService
import dev.ide.ui.backend.UiAgentChatState
import dev.ide.ui.backend.UiAgentConfig
import dev.ide.ui.backend.UiAgentModel
import dev.ide.ui.backend.UiAgentPermissionDecision
import dev.ide.ui.backend.UiAgentPermissionMode
import dev.ide.ui.backend.UiAgentPermissionRequest
import dev.ide.ui.backend.UiAgentProvider
import dev.ide.ui.ext.ScreenContext
import dev.ide.ui.theme.CodeAssistTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import dev.ide.ui.testing.DeviceConfig
import dev.ide.ui.testing.PaparazziSession
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end Compose UI test for [AgentSettingsHost] and [AgentSettingsScreen]:
 * - Verifies that no fake "On this phone" / "Cloud" tab switcher or broken "Download" button exists.
 * - Verifies clicking a provider card selects that provider in [AgentService] without flip-flopping.
 * - Verifies clicking a model chip updates the active model.
 * - Verifies clicking a reasoning effort segment updates `cfg.effort`.
 * - Verifies clicking a permission radio row updates `cfg.mode`.
 * - Verifies clicking "Editor, keymap, code style & tools" navigates to `__hub__`.
 */
@OptIn(ExperimentalComposeUiApi::class)
class AgentSettingsHostTest {

    private class FakeAgentService : AgentService {
        override val chatState: StateFlow<UiAgentChatState> = MutableStateFlow(UiAgentChatState())
        override val permissionRequest: StateFlow<UiAgentPermissionRequest?> = MutableStateFlow(null)
        override val models: StateFlow<List<UiAgentModel>> = MutableStateFlow(emptyList())

        var selectedProviderId = "openai"
        var selectedModelId = "gpt-5.4"
        var selectedEffort = "default"
        var selectedMode = UiAgentPermissionMode.ASK_EACH
        val providerKeys = mutableMapOf(
            "antigravity" to "ag-token",
            "openai" to "sk-test",
            "anthropic" to "",
        )
        val providerBaseUrls = mutableMapOf<String, String>()
        var gatewayUrl = ""
        var gatewayModel = ""
        var gatewayCa = ""

        override fun config(): UiAgentConfig = UiAgentConfig(
            providers = listOf(
                UiAgentProvider(
                    id = "antigravity",
                    displayName = "Antigravity (Google)",
                    models = listOf(
                        UiAgentModel("gemini-3.1-pro", "Gemini 3.1 Pro"),
                        UiAgentModel("claude-sonnet-4-6", "Claude Sonnet 4.6"),
                    ),
                    defaultModel = "gemini-3.1-pro",
                    apiKey = providerKeys["antigravity"].orEmpty(),
                    baseUrl = providerBaseUrls["antigravity"].orEmpty(),
                ),
                UiAgentProvider(
                    id = "openai",
                    displayName = "OpenAI",
                    models = listOf(
                        UiAgentModel("gpt-5.4", "GPT-5.4"),
                        UiAgentModel("gpt-5.4-mini", "GPT-5.4 Mini"),
                    ),
                    defaultModel = "gpt-5.4",
                    apiKey = providerKeys["openai"].orEmpty(),
                    baseUrl = providerBaseUrls["openai"].orEmpty(),
                ),
                UiAgentProvider(
                    id = "anthropic",
                    displayName = "Anthropic (Claude)",
                    models = listOf(
                        UiAgentModel("claude-sonnet-4-6", "Claude Sonnet 4.6"),
                    ),
                    defaultModel = "claude-sonnet-4-6",
                    apiKey = providerKeys["anthropic"].orEmpty(),
                    baseUrl = providerBaseUrls["anthropic"].orEmpty(),
                ),
            ),
            selectedProvider = selectedProviderId,
            model = selectedModelId,
            configured = providerKeys[selectedProviderId]?.isNotBlank() == true,
            mode = selectedMode,
            effort = selectedEffort,
            gatewayBaseUrl = gatewayUrl,
            gatewayModel = gatewayModel,
            gatewayCaCert = gatewayCa,
        )

        override fun selectProvider(id: String) {
            selectedProviderId = id
            val defaultModel = config().providers.firstOrNull { it.id == id }?.defaultModel.orEmpty()
            if (defaultModel.isNotBlank()) selectedModelId = defaultModel
        }

        override fun setProviderKey(providerId: String, key: String) {
            providerKeys[providerId] = key
        }

        override fun setProviderBaseUrl(providerId: String, baseUrl: String) {
            providerBaseUrls[providerId] = baseUrl
        }

        override fun setModel(model: String) {
            selectedModelId = model
        }

        override fun setEffort(effort: String) {
            selectedEffort = effort
        }

        override fun setPermissionMode(mode: UiAgentPermissionMode) {
            selectedMode = mode
        }

        override fun setGateway(baseUrl: String, model: String, caCert: String) {
            gatewayUrl = baseUrl
            gatewayModel = model
            gatewayCa = caCert
        }

        override fun refreshModels() {}
        override fun send(text: String) {}
        override fun retry() {}
        override fun stop() {}
        override fun newSession() {}
        override fun answerPermission(id: Int, decision: UiAgentPermissionDecision) {}
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
            node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
        }

    private fun findNodeByText(scene: ImageComposeScene, text: String): SemanticsNode? =
        collectAllSemanticsNodes(scene).firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        }

    @Test
    fun rendersRealProvidersAndControlsAndNavigatesToIdeSettings() {
        val fakeAgent = FakeAgentService()
        val backend = object : StubBackend() {
            override val agent: AgentService = fakeAgent
        }
        val openedScreens = mutableListOf<String>()
        var backCalled = false
        val ctx = object : ScreenContext {
            override val backend = backend
            override fun back() {
                backCalled = true
            }
            override fun openScreen(id: String) {
                openedScreens += id
            }
        }

        // Tall viewport so every card (providers, reasoning effort, permissions, IDE settings) is laid out in bounds.
        val scene = ImageComposeScene(width = 820, height = 2400, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                AgentSettingsScreen(ctx)
            }
        }

        var time = 0L
        fun frames(count: Int = 10) {
            Snapshot.sendApplyNotifications()
            repeat(count) {
                time += 16_666_667L
                scene.render(time)
            }
        }

        fun clickText(label: String) {
            val node = findNodeByText(scene, label)
            assertNotNull(node, "Expected semantics node with text '$label' in scene (present: ${collectTexts(scene)})")
            val center = node.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, center)
            scene.sendPointerEvent(PointerEventType.Release, center)
            frames()
        }

        try {
            frames()
            val texts = collectTexts(scene)

            // 1. Verify no fake "On this phone" / "Cloud" tab switcher or broken "Download" button exists.
            assertFalse("On this phone" in texts, "Fake 'On this phone' tab must not be rendered")
            assertFalse("Download" in texts, "Broken 'Download' button must not be rendered")
            assertFalse(texts.any { it.contains("Download when you switch to Cloud") }, "Fake download copy must not exist")

            // 2. Verify all real providers and section headers are present.
            assertTrue("Antigravity (Google)" in texts)
            assertTrue("OpenAI" in texts)
            assertTrue("Anthropic (Claude)" in texts)
            assertEquals("openai", fakeAgent.selectedProviderId)

            // 3. Click "Antigravity (Google)" -> selects "antigravity". Clicking it again keeps "antigravity" (never flip-flops).
            clickText("Antigravity (Google)")
            assertEquals("antigravity", fakeAgent.selectedProviderId)
            clickText("Antigravity (Google)")
            assertEquals("antigravity", fakeAgent.selectedProviderId)

            // 4. Click model chip "Claude Sonnet 4.6" inside the expanded Antigravity card.
            clickText("Claude Sonnet 4.6")
            assertEquals("claude-sonnet-4-6", fakeAgent.selectedModelId)

            // 5. Click reasoning effort "High" -> updates effort in AgentService.
            clickText("High")
            assertEquals("high", fakeAgent.selectedEffort)

            // 6. Click permission radio "Full auto in this project" -> updates permission mode to AUTO_ACCEPT.
            clickText("Full auto in this project")
            assertEquals(UiAgentPermissionMode.AUTO_ACCEPT, fakeAgent.selectedMode)

            clickText("Suggest only — I apply every edit")
            assertEquals(UiAgentPermissionMode.PLAN_ONLY, fakeAgent.selectedMode)

            // 7. Click "Editor, keymap, code style & tools" -> navigates to "__hub__".
            clickText("Editor, keymap, code style & tools")
            assertEquals(listOf("__hub__"), openedScreens)
        } finally {
            scene.close()
        }
    }

    @Test
    fun paparazziAccessibilityAuditAndZeroDeadClickablesOnAgentSettings() {
        val fakeAgent = FakeAgentService()
        val backend = object : StubBackend() {
            override val agent: AgentService = fakeAgent
        }
        val openedScreens = mutableListOf<String>()
        var backCalls = 0
        val ctx = object : ScreenContext {
            override val backend = backend
            override fun back() {
                backCalls++
            }
            override fun openScreen(id: String) {
                openedScreens += id
            }
        }

        val reportDir = File("build/reports/paparazzi/agent-settings")
        PaparazziSession(
            deviceConfig = DeviceConfig.PIXEL_9_PRO.withHeight(2400),
            reportDir = reportDir,
        ) {
            CodeAssistTheme(dark = true) {
                AgentSettingsScreen(ctx)
            }
        }.use { session ->
            // 1. Capture side-by-side AccessibilityRenderExtension overlay + legend
            val a11yPng = session.snapshot("agent_settings_accessibility", includeAccessibilityOverlay = true)
            assertTrue(a11yPng.exists() && a11yPng.length() > 5_000)

            // 2. Capture 3-pane golden comparison
            val cmp = session.verifySnapshot("agent_settings")
            assertTrue(cmp.passed)

            // 3. Audit every clickable control on AgentSettingsScreen for dead/no-op handlers
            val verified = session.assertNoDeadClickables(
                backendStateSnapshot = {
                    listOf(
                        fakeAgent.selectedProviderId,
                        fakeAgent.selectedModelId,
                        fakeAgent.selectedEffort,
                        fakeAgent.selectedMode,
                        openedScreens.toList(),
                        backCalls,
                    )
                },
            )
            assertTrue(verified.size >= 8, "Expected at least 8 verified interactive controls, found ${verified.size}")
        }
    }
}
