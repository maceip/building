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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.AgentUi
import dev.ide.ui.theme.Symbol

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
 * Agent-first Home: "What should we build?" prompt card, status chips, Continue list.
 * Matches the redesign Home screen for both iOS and Android.
 */
@Composable
fun AgentHomeContent(
    appName: String = "[APP NAME]",
    prompt: String,
    onPromptChange: (String) -> Unit,
    onStart: () -> Unit,
    onOpenAgentSettings: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onNewProject: () -> Unit = {},
    onImportOrClone: () -> Unit = {},
    onDeviceLabel: String = "On-device",
    continueItems: List<AgentContinueItem> = emptyList(),
    allProjectsLabel: String = "All projects",
    onOpenItem: (AgentContinueItem) -> Unit = {},
    onSeeAllProjects: () -> Unit = {},
    modifier: Modifier = Modifier,
    agentIcon: ImageVector = CaIcons.arrowRight,
    accountIcon: ImageVector = CaIcons.close,
    trendingWidgetSlot: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AgentUi.chrome.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
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

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "What should we build?",
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 35.sp,
                color = AgentUi.chrome.text,
            )
            AgentSurface(
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(16.dp),
                stroke = AgentUi.chrome.hairlineStrong,
            ) {
                // Prompt field
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Transparent),
                ) {
                    if (prompt.isEmpty()) {
                        Text(
                            "Describe an app, or a change to one of your projects…",
                            fontSize = 17.sp,
                            color = AgentUi.chrome.textMuted,
                        )
                    }
                    androidx.compose.foundation.text.BasicTextField(
                        value = prompt,
                        onValueChange = onPromptChange,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 17.sp,
                            color = AgentUi.chrome.text,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(AgentUi.chrome.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AgentChip("New project", selected = false, onClick = onNewProject)
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
                    Spacer(Modifier.weight(1f))
                    AgentIconCircle(CaIcons.arrowRight, "Start", onStart, Modifier.size(44.dp), accent = true)
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
        trendingWidgetSlot?.invoke()
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
    /** Paste a Git/GitHub URL and clone + open — same contract as the home QuickCloneBar. */
    onCloneUrl: (String) -> Unit = {},
    onCloneGit: () -> Unit = {},
    onOpenFolder: () -> Unit = {},
    onImportPackage: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var cloneUrl by rememberSaveable { mutableStateOf("") }
    val fieldShape = RoundedCornerShape(10.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(c.surfaceContainer)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(c.outlineVariant),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "New project",
                style = MaterialTheme.typography.titleLarge,
                color = c.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButtonCa(
                icon = CaIcons.close,
                contentDescription = "Close",
                onClick = onClose,
                iconSize = 18,
                boxSize = 40,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Describe it — the agent picks the stack",
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.surfaceContainerLow)
                    .border(1.dp, c.outlineVariant, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                BasicTextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    singleLine = false,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
                    cursorBrush = SolidColor(c.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth()) {
                            if (prompt.isEmpty()) {
                                Text(
                                    "A habit tracker with a home-screen widget…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = c.outline,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                stacks.forEachIndexed { i, label ->
                    val selected = i == selectedStack
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectStack(i) },
                        label = {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
            Button(
                onClick = onStartBuilding,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text("Start building", style = MaterialTheme.typography.labelLarge)
            }
        }

        Text(
            "Or clone a repository",
            style = MaterialTheme.typography.labelSmall,
            color = c.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(fieldShape)
                    .background(c.surfaceContainerLow)
                    .border(1.dp, c.outlineVariant, fieldShape)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Symbol(CaSymbols.forkRight, contentDescription = null, size = 16.dp, tint = c.onSurfaceVariant)
                BasicTextField(
                    value = cloneUrl,
                    onValueChange = { cloneUrl = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.onSurface),
                    cursorBrush = SolidColor(c.primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (cloneUrl.isEmpty()) {
                                Text(
                                    "Paste GitHub URL or owner/repo to clone…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
                if (cloneUrl.isNotEmpty()) {
                    IconButtonCa(
                        CaIcons.close,
                        "Clear",
                        { cloneUrl = "" },
                        iconSize = 14,
                        boxSize = 24,
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    val clip = clipboard.getText()?.text?.trim().orEmpty()
                    if (clip.isNotBlank()) cloneUrl = clip
                },
                shape = fieldShape,
                border = BorderStroke(1.dp, c.outlineVariant),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.height(38.dp),
            ) {
                Text("Paste", style = MaterialTheme.typography.labelLarge)
            }
            Button(
                onClick = {
                    val url = cloneUrl.trim()
                    if (url.isNotEmpty()) onCloneUrl(url)
                },
                shape = fieldShape,
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.height(38.dp),
                enabled = cloneUrl.isNotBlank(),
            ) {
                Symbol(CaSymbols.forkRight, contentDescription = null, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text("Clone", style = MaterialTheme.typography.labelLarge)
            }
        }

        Text(
            "Or bring existing code",
            style = MaterialTheme.typography.labelSmall,
            color = c.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NewProjectSheetAction(CaSymbols.forkRight, "Clone a Git repository", onCloneGit)
            NewProjectSheetAction(CaSymbols.folderOpen, "Open a folder on this phone", onOpenFolder)
            NewProjectSheetAction(CaSymbols.download, "Import a .caproj package", onImportPackage)
        }
    }
}

/** Sheet row matching [SheetAction]: icon tile + title, full-width tap target. */
@Composable
private fun NewProjectSheetAction(glyph: Char, label: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        contentColor = c.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(c.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(glyph, contentDescription = null, size = 20.dp, tint = c.onSurfaceVariant)
            }
            Text(label, style = MaterialTheme.typography.titleSmall, color = c.onSurface)
        }
    }
}

/**
 * Agent settings: on-device vs cloud, model cards, permission radios, privacy toggles.
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
                options = listOf("On this phone", "Cloud"),
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
                        AgentGhostButton("Download", onDownloadModel)
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
                    "Never send code off this phone",
                    "Blocks cloud fallback and web tools",
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
