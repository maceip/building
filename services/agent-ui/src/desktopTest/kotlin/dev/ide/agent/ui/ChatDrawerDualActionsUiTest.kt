package dev.ide.agent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ChatDrawerDualActionsUiTest {

    private class RecordingBackend(
        override val project: ProjectInfo = ProjectInfo("CurrentApp", "/projects/CurrentApp", 1, lastOpened = 100L),
        private val allProjects: List<ProjectInfo> = listOf(
            ProjectInfo("CalculatorApp", "/projects/CalculatorApp", 1, lastOpened = 500L),
            ProjectInfo("CurrentApp", "/projects/CurrentApp", 1, lastOpened = 100L),
        ),
    ) : StubBackend() {
        val openedProjects = mutableListOf<String>()
        val sentPrompts = mutableListOf<String>()
        var buildRunCount = 0

        override fun projects(): List<ProjectInfo> = allProjects

        override suspend fun openProject(rootPath: String): Boolean {
            openedProjects += rootPath
            return true
        }

        override fun supported(): Boolean = true

        override fun runBuild() {
            buildRunCount++
        }

        override val agent: AgentService = object : AgentService {
            override val chatState: StateFlow<UiAgentChatState> = MutableStateFlow(UiAgentChatState())
            override val permissionRequest: StateFlow<UiAgentPermissionRequest?> = MutableStateFlow(null)
            override val models: StateFlow<List<UiAgentModel>> =
                MutableStateFlow(listOf(UiAgentModel("gemini-3.1-pro", "Gemini 3.1 Pro")))

            override fun config(): UiAgentConfig = UiAgentConfig(
                providers = listOf(
                    UiAgentProvider(
                        "antigravity",
                        "Antigravity (Google)",
                        listOf(UiAgentModel("gemini-3.1-pro", "Gemini 3.1 Pro")),
                        "gemini-3.1-pro",
                        "ag-live",
                        "https://cloudcode-pa.googleapis.com",
                    ),
                ),
                selectedProvider = "antigravity",
                model = "gemini-3.1-pro",
                configured = true,
                mode = UiAgentPermissionMode.AUTO_ACCEPT,
                effort = "high",
            )

            override fun refreshModels() {}
            override fun setModel(model: String) {}
            override fun selectProvider(id: String) {}
            override fun setProviderKey(providerId: String, key: String) {}
            override fun setGateway(baseUrl: String, model: String, caCert: String) {}
            override fun send(text: String) {
                sentPrompts += text
            }
            override fun retry() {}
            override fun stop() {}
            override fun newSession() {}
            override fun setPermissionMode(mode: UiAgentPermissionMode) {}
            override fun answerPermission(id: Int, decision: UiAgentPermissionDecision) {}
        }
    }

    @Test
    fun fullScreenChatDrawerRendersBothActionRowsAndExecutesBackendActions() = runComposeUiTest {
        val backend = RecordingBackend()
        val openedScreens = mutableListOf<String>()

        setContent {
            CodeAssistTheme(dark = true) {
                Box(Modifier.size(411.dp, 890.dp)) {
                    ChatDrawer(
                        backend = backend,
                        onClose = {},
                        openScreen = { openedScreens += it },
                    )
                }
            }
        }

        onNodeWithTag("agent-dual-action-rows", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agent-action-row-navigation", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agent-action-row-task", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Where you want to go", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("What you want done", useUnmergedTree = true).assertIsDisplayed()

        // Row 1 ("Where you want to go"): Open most recent project
        onNodeWithText("Open CalculatorApp", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agent-quick-action-open-recent-project", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf("/projects/CalculatorApp"), backend.openedProjects)
        assertTrue("ide.editor" in openedScreens)

        // Row 2 ("What you want done"): Rebuild project
        onNodeWithText("Rebuild project", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agent-quick-action-rebuild-project", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(1, backend.buildRunCount)

        // Row 2 ("What you want done"): Submit to the Google Play Store for inclusion
        onNodeWithText("Submit to the Google Play Store for inclusion", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agent-quick-action-submit-google-play-store", useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue("store.submit" in openedScreens)

        // Send a prompt through the Compose Agent UI ChatInput
        onNodeWithTag("agent-chat-input-field", useUnmergedTree = true).performTextInput("Add unit tests")
        onNodeWithTag("agent-chat-submit", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf("Add unit tests"), backend.sentPrompts)
    }

    @Test
    fun halfScreenChatDrawerRendersBothActionRowsAndExecutesBackendActions() = runComposeUiTest {
        val backend = RecordingBackend()
        val openedScreens = mutableListOf<String>()

        setContent {
            CodeAssistTheme(dark = false) {
                // Half-screen split height (< 500.dp) triggers compact mode while preserving both rows
                Box(Modifier.size(411.dp, 340.dp)) {
                    ChatDrawer(
                        backend = backend,
                        onClose = {},
                        openScreen = { openedScreens += it },
                    )
                }
            }
        }

        onNodeWithTag("agent-dual-action-rows", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agent-action-row-navigation", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agent-action-row-task", useUnmergedTree = true).assertIsDisplayed()

        onNodeWithTag("agent-quick-action-open-recent-project", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf("/projects/CalculatorApp"), backend.openedProjects)

        onNodeWithTag("agent-quick-action-rebuild-project", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(1, backend.buildRunCount)

        onNodeWithTag("agent-quick-action-submit-google-play-store", useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue("store.submit" in openedScreens)
    }
}
