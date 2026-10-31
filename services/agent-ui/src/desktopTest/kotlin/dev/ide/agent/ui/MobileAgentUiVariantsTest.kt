package dev.ide.agent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.ide.ui.theme.CodeAssistTheme
import fragments.agentui.AgentApprovalCard
import fragments.agentui.AgentCapabilities
import fragments.agentui.AgentChatPanel
import fragments.agentui.AgentId
import fragments.agentui.AgentParticipant
import fragments.agentui.AgentPlanDraftCard
import fragments.agentui.AgentPlanDraftStep
import fragments.agentui.AgentStructuredCommandCard
import fragments.agentui.AgentStructuredCommandDraft
import fragments.agentui.AgentStructuredCommandField
import fragments.agentui.AgentToolTimeline
import fragments.agentui.ChatApprovalChoice
import fragments.agentui.ChatApprovalStatus
import fragments.agentui.ChatDiffReviewResult
import fragments.agentui.ChatRiskLevel
import fragments.agentui.ChatTimelinePhase
import fragments.agentui.ChatToolTimelineCall
import fragments.agentui.ChatToolTiming
import fragments.agentui.CommandResult
import fragments.agentui.ConversationCommand
import fragments.agentui.ConversationEvent
import fragments.agentui.ConversationHandle
import fragments.agentui.ConversationId
import fragments.agentui.ConversationPart
import fragments.agentui.ConversationSnapshot
import fragments.agentui.ConversationTurn
import fragments.agentui.ProvideAgentUiViewportSizing
import fragments.agentui.TurnId
import fragments.agentui.rememberIdeAgentUiPalette
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MobileAgentUiVariantsTest {

    @Test
    fun mobileApprovalCardStacksFullWidthButtonsAndResolvesDecision() = runComposeUiTest {
        var recordedChoice: ChatApprovalChoice? = null

        setContent {
            CodeAssistTheme(dark = true) {
                val palette = rememberIdeAgentUiPalette()
                ProvideAgentUiViewportSizing(viewportWidth = 390.dp, forceMobile = true) {
                    Box(Modifier.size(390.dp, 640.dp)) {
                        AgentApprovalCard(
                            toolName = "Bash",
                            status = ChatApprovalStatus.Pending,
                            description = "Execute gradle test task",
                            input = """{"command":"./gradlew test"}""",
                            risk = ChatRiskLevel.Medium,
                            onDecide = { recordedChoice = it },
                            palette = palette,
                        )
                    }
                }
            }
        }

        onNodeWithTag("agent-approval-card", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Approve once", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Deny", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Approve once", useUnmergedTree = true).performClick()
        waitForIdle()

        assertNotNull(recordedChoice)
        assertEquals("allow-once", recordedChoice.decision)
    }

    @Test
    fun mobilePlanDraftAndStructuredCommandCardsWorkErgonomically() = runComposeUiTest {
        var steps by mutableStateOf(
            listOf(
                AgentPlanDraftStep(
                    id = "s1",
                    instruction = "Audit mobile components",
                    agentId = AgentId("claude"),
                    requiresApproval = true,
                ),
            ),
        )
        var sentSteps: List<AgentPlanDraftStep>? = null

        var commandDraft by mutableStateOf(
            AgentStructuredCommandDraft(
                command = "/deploy",
                title = "Build & Install APK",
                agentId = AgentId("claude"),
                fields = listOf(
                    AgentStructuredCommandField(
                        id = "device",
                        label = "Target Device",
                        value = "Pixel 11 Pro XL",
                        required = true,
                    ),
                ),
            ),
        )
        var sentCommandDraft: AgentStructuredCommandDraft? = null

        val participants = listOf(
            AgentParticipant(
                id = AgentId("claude"),
                name = "Claude",
                capabilities = AgentCapabilities(),
            ),
        )

        setContent {
            CodeAssistTheme(dark = true) {
                val palette = rememberIdeAgentUiPalette()
                ProvideAgentUiViewportSizing(viewportWidth = 390.dp, forceMobile = true) {
                    Column(Modifier.size(390.dp, 844.dp)) {
                        AgentPlanDraftCard(
                            steps = steps,
                            agents = participants,
                            onChange = { steps = it },
                            onSend = { sentSteps = it },
                            palette = palette,
                        )
                        AgentStructuredCommandCard(
                            draft = commandDraft,
                            agents = participants,
                            onChange = { commandDraft = it },
                            onSend = { sentCommandDraft = it },
                            palette = palette,
                        )
                    }
                }
            }
        }

        onNodeWithTag("agent-plan-draft", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Add step", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(2, steps.size)

        onNodeWithTag("agent-structured-command-/deploy", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agent-structured-send", useUnmergedTree = true).performClick()
        waitForIdle()

        assertNotNull(sentCommandDraft)
        assertEquals("Pixel 11 Pro XL", sentCommandDraft.fields.first().value)
    }

    @Test
    fun mobileToolTimelineRendersWaterfallAndExpandsCallDetails() = runComposeUiTest {
        setContent {
            CodeAssistTheme(dark = true) {
                val palette = rememberIdeAgentUiPalette()
                ProvideAgentUiViewportSizing(viewportWidth = 390.dp, forceMobile = true) {
                    Box(Modifier.size(390.dp, 500.dp)) {
                        AgentToolTimeline(
                            calls = listOf(
                                ChatToolTimelineCall(
                                    id = "c1",
                                    name = "ReadFile",
                                    phase = ChatTimelinePhase.Success,
                                    summary = "ChatDrawer.kt",
                                    timing = ChatToolTiming(startedAtMillis = 1000L, endedAtMillis = 1120L),
                                    input = """{"path":"ChatDrawer.kt"}""",
                                    output = """{"lines":2066}""",
                                ),
                            ),
                            active = false,
                            palette = palette,
                        )
                    }
                }
            }
        }

        onNodeWithTag("agent-tool-timeline", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agent-timeline-waterfall", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agent-timeline-call-c1", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithText("2066", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun mobileChatPanelOpensBottomSheetsForBrowserAndDiffReview() = runComposeUiTest {
        val snapshotFlow = MutableStateFlow(
            ConversationSnapshot(
                conversationId = ConversationId("mobile-sheet-test"),
                participants = listOf(
                    AgentParticipant(
                        id = AgentId("agent-1"),
                        name = "Gemini",
                        capabilities = AgentCapabilities(interrupt = true),
                    ),
                ),
                turns = listOf(
                    ConversationTurn(
                        id = TurnId("turn-1"),
                        authorId = "agent-1",
                        agentId = AgentId("agent-1"),
                        parts = listOf(
                            ConversationPart.Browser(
                                id = "browser-1",
                                sessionId = "sess-1",
                                title = "Verify docs",
                                url = "https://developer.android.com",
                            ),
                            ConversationPart.Diff(
                                id = "diff-1",
                                reviewId = "rev-1",
                                title = "MainActivity.kt",
                                summary = "+2 -1 lines",
                                patch = """
                                    diff --git a/MainActivity.kt b/MainActivity.kt
                                    --- a/MainActivity.kt
                                    +++ b/MainActivity.kt
                                    @@ -1,2 +1,3 @@
                                     class MainActivity {
                                    +    val mobileReady = true
                                     }
                                """.trimIndent(),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val handle = object : ConversationHandle {
            override val conversationId: ConversationId = ConversationId("mobile-sheet-test")
            override val snapshots: StateFlow<ConversationSnapshot> = snapshotFlow
            override val events: Flow<ConversationEvent> = emptyFlow()
            override fun history(): List<ConversationEvent> = emptyList()
            override fun setDraft(target: AgentId, value: String) {}
            override fun addAttachments(target: AgentId, files: List<fragments.agentui.File>) {}
            override fun removeAttachment(target: AgentId, attachmentId: String) {}
            override suspend fun dispatch(command: ConversationCommand): CommandResult =
                CommandResult.Accepted("corr-1")
        }

        var diffSubmitted: ChatDiffReviewResult? = null

        setContent {
            CodeAssistTheme(dark = true) {
                val palette = rememberIdeAgentUiPalette()
                Box(Modifier.size(390.dp, 844.dp)) {
                    AgentChatPanel(
                        handle = handle,
                        modifier = Modifier.fillMaxSize(),
                        palette = palette,
                        onSubmitDiffReview = { _, result -> diffSubmitted = result },
                    )
                }
            }
        }

        // Tap "Open preview" -> should open AgentMobileBrowserSheet on a 390dp phone viewport
        onNodeWithTag("browser-preview-action", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("browser-preview-action", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("agent-browser-top-bar", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agent-browser-bottom-bar", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agent-mini-browser-close", useUnmergedTree = true).performClick()
        waitForIdle()

        // Tap "Review" on diff entry -> should open AgentMobileDiffReviewSheet on a 390dp phone viewport
        onNodeWithTag("agent-diff-entry-review", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agent-diff-entry-review", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("agent-diff-review-pane", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Accept file", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithText("Apply 1 of 1", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(1, diffSubmitted?.acceptedCount)
    }
}
