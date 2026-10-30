package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.AgentUi
import fragments.agentui.AgentDualActionRows
import fragments.agentui.ChatActionPurpose
import fragments.agentui.ChatInput
import fragments.agentui.ChatInputStatus
import fragments.agentui.ChatQuickAction
import fragments.agentui.rememberIdeAgentUiPalette

/** Screen id of the redesigned Agent settings destination (Settings hub → Agent, Home → agent icon). */
const val SCREEN_AGENT_SETTINGS: String = "agent.settings"

/** One row in the Home "Continue" list. */
data class AgentContinueItem(
    val id: String,
    val title: String,
    val meta: String,
    val time: String,
    val letter: String,
    val needsAttention: Boolean = false,
)

/**
 * Agent-first Home: "What should we build?" prompt card powered by `io.fragments:agent-ui-compose`
 * ([ChatInput] + [AgentDualActionRows]), status chips, and Continue list.
 *
 * Always renders the two persistent action rows:
 * 1. **Where you want to go** ([ChatActionPurpose.Navigation]): `"Open <Most Recent Project>"`, `"All projects"`, `"New project"`, `"Import or clone"`.
 * 2. **What you want done** ([ChatActionPurpose.Task]): `"Rebuild project"`, `"Submit to the Google Play Store for inclusion"`, `"Scaffold a new app with AI"`.
 */
@Composable
fun AgentHomeContent(
    appName: String = "building",
    prompt: String,
    onPromptChange: (String) -> Unit,
    onStart: () -> Unit,
    onOpenAgentSettings: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onNewProject: () -> Unit = {},
    onImportOrClone: () -> Unit = {},
    onDeviceLabel: String = "Cloud AI",
    continueItems: List<AgentContinueItem> = emptyList(),
    allProjectsLabel: String = "All projects",
    onOpenItem: (AgentContinueItem) -> Unit = {},
    onSeeAllProjects: () -> Unit = {},
    onRebuildProject: (() -> Unit)? = null,
    onSubmitToStore: (() -> Unit)? = null,
    navigationActions: List<ChatQuickAction> = emptyList(),
    taskActions: List<ChatQuickAction> = emptyList(),
    modifier: Modifier = Modifier,
    agentIcon: ImageVector = CaIcons.arrowRight,
    accountIcon: ImageVector = CaIcons.close,
) {
    val palette = rememberIdeAgentUiPalette()
    val mostRecent = continueItems.firstOrNull()
    val resolvedNavigationActions = remember(navigationActions, continueItems) {
        navigationActions.ifEmpty {
            buildList {
                if (mostRecent != null) {
                    add(
                        ChatQuickAction(
                            id = "open-recent-project",
                            label = "Open ${mostRecent.title}",
                            purpose = ChatActionPurpose.Navigation,
                            primary = true,
                            onClick = { onOpenItem(mostRecent) },
                        ),
                    )
                }
                add(
                    ChatQuickAction(
                        id = "go-all-projects",
                        label = allProjectsLabel,
                        purpose = ChatActionPurpose.Navigation,
                        primary = mostRecent == null,
                        onClick = onSeeAllProjects,
                    ),
                )
                add(
                    ChatQuickAction(
                        id = "go-new-project",
                        label = "New project",
                        purpose = ChatActionPurpose.Navigation,
                        onClick = onNewProject,
                    ),
                )
                add(
                    ChatQuickAction(
                        id = "go-import-clone",
                        label = "Import or clone",
                        purpose = ChatActionPurpose.Navigation,
                        onClick = onImportOrClone,
                    ),
                )
            }
        }
    }
    val resolvedTaskActions = remember(taskActions, mostRecent, onRebuildProject, onSubmitToStore) {
        taskActions.ifEmpty {
            buildList {
                add(
                    ChatQuickAction(
                        id = "rebuild-project",
                        label = "Rebuild project",
                        purpose = ChatActionPurpose.Task,
                        primary = true,
                        onClick = {
                            if (onRebuildProject != null) {
                                onRebuildProject()
                            } else if (mostRecent != null) {
                                onOpenItem(mostRecent)
                            } else {
                                onNewProject()
                            }
                        },
                    ),
                )
                add(
                    ChatQuickAction(
                        id = "submit-google-play-store",
                        label = "Submit to the Google Play Store for inclusion",
                        purpose = ChatActionPurpose.Task,
                        onClick = {
                            if (onSubmitToStore != null) {
                                onSubmitToStore()
                            } else if (mostRecent != null) {
                                onOpenItem(mostRecent)
                            } else {
                                onSeeAllProjects()
                            }
                        },
                    ),
                )
                add(
                    ChatQuickAction(
                        id = "scaffold-app-ai",
                        label = "Scaffold a new app with AI",
                        purpose = ChatActionPurpose.Task,
                        onClick = onStart,
                    ),
                )
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AgentUi.chrome.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                appName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.02.sp,
                color = AgentUi.chrome.textMuted,
                modifier = Modifier.weight(1f),
            )
            AgentIconCircle(agentIcon, "Agent settings", onOpenAgentSettings, Modifier.size(44.dp))
            AgentIconCircle(accountIcon, "Account", onOpenAccount, Modifier.size(44.dp))
        }

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "What should we build?",
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 35.sp,
                color = AgentUi.chrome.text,
            )
            AgentDualActionRows(
                navigationActions = resolvedNavigationActions,
                taskActions = resolvedTaskActions,
                navigationTitle = "Where you want to go",
                taskTitle = "What you want done",
                palette = palette,
                modifier = Modifier.fillMaxWidth(),
            )
            ChatInput(
                value = prompt,
                onValueChange = onPromptChange,
                onSend = {
                    onPromptChange(it)
                    onStart()
                },
                status = ChatInputStatus.Ready,
                placeholder = "Describe an app, or a change to one of your projects…",
                palette = palette,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AgentChip("New project", selected = false, onClick = onNewProject)
                Spacer(Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = onOpenAgentSettings)
                        .padding(horizontal = 12.dp),
                ) {
                    AgentStatusDot(active = true, size = 7.dp)
                    Text(onDeviceLabel, fontSize = 13.sp, color = AgentUi.chrome.textMuted)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            ) {
                AgentSectionLabel("Continue")
                Spacer(Modifier.weight(1f))
                Text(
                    "Import or clone",
                    fontSize = 14.sp,
                    color = AgentUi.chrome.textMuted,
                    modifier = Modifier.clickable(onClick = onImportOrClone),
                )
            }
            continueItems.forEach { item ->
                AgentProjectRow(
                    title = item.title,
                    meta = item.meta,
                    time = item.time,
                    letter = item.letter,
                    selected = item.needsAttention,
                    statusColor = if (item.needsAttention) AgentUi.chrome.warn else AgentUi.chrome.accent,
                    onClick = { onOpenItem(item) },
                )
            }
            Text(
                allProjectsLabel,
                fontSize = 14.sp,
                color = AgentUi.chrome.textMuted,
                modifier = Modifier
                    .clickable(onClick = onSeeAllProjects)
                    .padding(12.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * "Start something new" bottom sheet: describe-it prompt, stack chips, primary CTA,
 * then bring-existing-code rows.
 */
@Composable
fun NewProjectSheetContent(
    prompt: String,
    onPromptChange: (String) -> Unit,
    onStartBuilding: () -> Unit,
    onClose: () -> Unit = {},
    stacks: List<String> = listOf("Android app", "Python", "Node.js", "Let agent decide"),
    selectedStack: Int = 0,
    onSelectStack: (Int) -> Unit = {},
    onCloneGit: () -> Unit = {},
    onOpenFolder: () -> Unit = {},
    onImportPackage: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(AgentUi.chrome.surface)
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AgentUi.chrome.textFaint.copy(alpha = 0.45f)),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "New project",
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = AgentUi.chrome.text,
                modifier = Modifier.weight(1f),
            )
            AgentIconCircle(CaIcons.close, "Close", onClose, Modifier.size(44.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Describe it — the agent picks the stack",
                fontSize = 13.sp,
                color = AgentUi.chrome.textMuted,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(AgentUi.chrome.bg)
                    .border(1.dp, AgentUi.chrome.hairlineStrong, RoundedCornerShape(16.dp))
                    .padding(14.dp),
            ) {
                if (prompt.isEmpty()) {
                    Text(
                        "A habit tracker with a home-screen widget…",
                        fontSize = 15.sp,
                        color = AgentUi.chrome.textMuted,
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 15.sp,
                        color = AgentUi.chrome.text,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(AgentUi.chrome.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                stacks.forEachIndexed { i, label ->
                    AgentChip(
                        label = label,
                        selected = i == selectedStack,
                        onClick = { onSelectStack(i) },
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(AgentUi.chrome.accent)
                    .clickable(onClick = onStartBuilding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Start building",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AgentUi.chrome.accentInk,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            AgentSectionLabel("Or bring existing code")
            listOf(
                Triple("Clone a Git repo", onCloneGit, CaIcons.arrowRight),
                Triple("Open a folder on this phone", onOpenFolder, CaIcons.arrowRight),
                Triple("Import a .caproj package", onImportPackage, CaIcons.arrowRight),
            ).forEach { (label, onClick, _) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clickable(onClick = onClick)
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(label, fontSize = 15.sp, color = AgentUi.chrome.text)
                }
            }
        }
    }
}

/**
 * Agent settings: provider selection, active & alternate model cards, permission radios, privacy toggles.
 */
@Composable
fun AgentSettingsContent(
    modeIndex: Int,
    onModeChange: (Int) -> Unit,
    localModelTitle: String,
    localModelMeta: String,
    secondaryModelTitle: String,
    secondaryModelMeta: String,
    permissionIndex: Int,
    onPermissionChange: (Int) -> Unit,
    offlineOnly: Boolean,
    onOfflineOnlyChange: (Boolean) -> Unit,
    pauseLowBattery: Boolean,
    onPauseLowBatteryChange: (Boolean) -> Unit,
    onOpenEditorPrefs: () -> Unit = {},
    onDownloadModel: () -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    modeOptions: List<String> = listOf("Gateway", "Cloud API"),
    secondaryActionLabel: String = "Use provider",
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AgentUi.chrome.bg)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        AgentTopBar(title = "", onBack = onBack, backIcon = CaIcons.arrowLeft)
        Column(
            Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                "Agent",
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                color = AgentUi.chrome.text,
            )
            AgentSegmented(
                options = modeOptions,
                selectedIndex = modeIndex,
                onSelect = onModeChange,
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentSurface(
                    stroke = AgentUi.chrome.accent,
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AgentStatusDot(active = true, size = 8.dp)
                        Column {
                            Text(localModelTitle, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AgentUi.chrome.text)
                            Text("Active", fontSize = 12.sp, color = AgentUi.chrome.textFaint)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(localModelMeta, fontSize = 12.sp, color = AgentUi.chrome.textMuted)
                }
                AgentSurface(contentPadding = PaddingValues(16.dp), stroke = AgentUi.chrome.hairline) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .border(1.5.dp, AgentUi.chrome.textFaint, RoundedCornerShape(2.dp)),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(secondaryModelTitle, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AgentUi.chrome.text)
                            Text(secondaryModelMeta, fontSize = 12.sp, color = AgentUi.chrome.textFaint)
                        }
                        AgentGhostButton(secondaryActionLabel, onDownloadModel)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                AgentSectionLabel("The agent may…")
                val perms = listOf(
                    "Suggest only — I apply every edit",
                    "Edit and build — ask before installs",
                    "Full auto in this project",
                )
                perms.forEachIndexed { i, label ->
                    AgentRadioRow(label = label, selected = i == permissionIndex, onSelect = { onPermissionChange(i) })
                }
            }
            Column {
                AgentToggleRow(
                    "Restrict external web tools",
                    "Disables web search and external HTTP fetches",
                    offlineOnly,
                    onOfflineOnlyChange,
                )
                AgentToggleRow(
                    "Pause when battery is low",
                    "Resumes on charger · below 20%",
                    pauseLowBattery,
                    onPauseLowBatteryChange,
                )
            }
            Text(
                "Editor, keymap & code style",
                fontSize = 14.sp,
                color = AgentUi.chrome.textMuted,
                modifier = Modifier
                    .clickable(onClick = onOpenEditorPrefs)
                    .padding(vertical = 8.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}
