package dev.ide.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.theme.CodeAssistTheme
import fragments.agentui.ChatActionPurpose
import fragments.agentui.ChatQuickAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AgentHomeDualActionsUiTest {

    private class HomeTestBackend : StubBackend() {
        var buildRunCount = 0
        override fun supported(): Boolean = true
        override fun runBuild() {
            buildRunCount++
        }
    }

    private val sampleProjects = listOf(
        ProjectInfo(name = "ProjectX", rootPath = "/ws/ProjectX", moduleCount = 2, lastOpened = 2_000L),
        ProjectInfo(name = "OlderApp", rootPath = "/ws/OlderApp", moduleCount = 1, lastOpened = 1_000L),
    )

    @Test
    fun phoneHomeChatWindowDisplaysBothActionRowsAndExecutesActions() = runComposeUiTest {
        val backend = HomeTestBackend()
        val opened = mutableListOf<ProjectInfo>()
        val submitted = mutableListOf<ProjectInfo?>()

        setContent {
            CodeAssistTheme(dark = true) {
                Box(Modifier.size(400.dp, 860.dp)) {
                    ProjectsHomeScreen(
                        projects = sampleProjects,
                        onOpen = { opened += it },
                        onNewProject = {},
                        backend = backend,
                        onSubmitToStore = { submitted += it },
                    )
                }
            }
        }

        onNodeWithTag("agent-dual-action-rows", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agent-action-row-navigation", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agent-action-row-task", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Open ProjectX", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Rebuild project", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Submit to the Google Play Store for inclusion", useUnmergedTree = true).assertIsDisplayed()

        // Tap Row 1: Open ProjectX
        onNodeWithTag("agent-quick-action-open-recent-project", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf("ProjectX"), opened.map { it.name })

        // Tap Row 2: Rebuild project
        onNodeWithTag("agent-quick-action-rebuild-project", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(1, backend.buildRunCount)

        // Tap Row 2: Submit to the Google Play Store for inclusion
        onNodeWithTag("agent-quick-action-submit-google-play-store", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(1, submitted.size)
        assertEquals("ProjectX", submitted.first()?.name)
    }

    @Test
    fun wideHomeChatWindowDisplaysBothActionRowsAndExecutesActions() = runComposeUiTest {
        val backend = HomeTestBackend()
        val opened = mutableListOf<ProjectInfo>()
        val submitted = mutableListOf<ProjectInfo?>()

        setContent {
            CodeAssistTheme(dark = false) {
                Box(Modifier.size(1024.dp, 768.dp)) {
                    ProjectsHomeScreen(
                        projects = sampleProjects,
                        onOpen = { opened += it },
                        onNewProject = {},
                        backend = backend,
                        onSubmitToStore = { submitted += it },
                    )
                }
            }
        }

        onNodeWithTag("agent-dual-action-rows", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agent-action-row-navigation", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agent-action-row-task", useUnmergedTree = true).assertIsDisplayed()

        onNodeWithTag("agent-quick-action-wide_nav_open_recent", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf("ProjectX"), opened.map { it.name })

        onNodeWithTag("agent-quick-action-wide_task_rebuild_project", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(1, backend.buildRunCount)

        onNodeWithTag("agent-quick-action-wide_task_submit_play_store", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(1, submitted.size)
        assertEquals("ProjectX", submitted.first()?.name)
    }

    @Test
    fun halfScreenSplitDockDisplaysBothActionRowsAndExecutesActions() = runComposeUiTest {
        var prompt by mutableStateOf("")
        var openedProject = false
        var rebuiltProject = false
        var submittedToPlayStore = false

        setContent {
            CodeAssistTheme(dark = true) {
                Box(Modifier.size(400.dp, 320.dp)) {
                    AgentSplitConversation(
                        userText = "Inspect build",
                        replyText = "Ready to assist.",
                        prompt = prompt,
                        onPromptChange = { prompt = it },
                        onSend = {},
                        primaryActionLabel = "Open ProjectX",
                        onPrimaryAction = { openedProject = true },
                        secondaryActionLabel = "Rebuild project",
                        onSecondaryAction = { rebuiltProject = true },
                        taskActions = listOf(
                            ChatQuickAction(
                                id = "split-task-rebuild",
                                label = "Rebuild project",
                                purpose = ChatActionPurpose.Task,
                                primary = true,
                                onClick = { rebuiltProject = true },
                            ),
                            ChatQuickAction(
                                id = "split-task-submit-play-store",
                                label = "Submit to the Google Play Store for inclusion",
                                purpose = ChatActionPurpose.Task,
                                onClick = { submittedToPlayStore = true },
                            ),
                        ),
                    )
                }
            }
        }

        onNodeWithTag("agent-dual-action-rows", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agent-action-row-navigation", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agent-action-row-task", useUnmergedTree = true).assertIsDisplayed()

        onNodeWithTag("agent-quick-action-split-nav-primary", useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue(openedProject)

        onNodeWithTag("agent-quick-action-split-task-rebuild", useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue(rebuiltProject)

        onNodeWithTag("agent-quick-action-split-task-submit-play-store", useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue(submittedToPlayStore)
    }
}
