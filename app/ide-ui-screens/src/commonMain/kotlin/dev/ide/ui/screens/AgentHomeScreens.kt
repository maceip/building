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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.AgentUi

/** Screen id of the redesigned Agent settings destination (Settings hub → Agent, Home → agent icon). */
const val SCREEN_AGENT_SETTINGS: String = "agent.settings"

/** Distinct text color for each project operating system / runtime stack (plain colored text, no pill badge). */
fun projectStackColor(
    isAndroid: Boolean = false,
    isNode: Boolean = false,
    isPython: Boolean = false,
    isSwift: Boolean = false,
    compatibility: Boolean = false,
): Color = when {
    isSwift -> Color(0xFFFB923C)      // Swift orange
    isPython -> Color(0xFFFACC15)     // Python gold
    isNode -> Color(0xFF4ADE80)       // Node.js green
    isAndroid -> Color(0xFF3DDC84)    // Android green
    compatibility -> Color(0xFFA78BFA) // Gradle/Kotlin purple
    else -> Color(0xFF38BDF8)         // Sky blue
}

/** One row in the Home "Continue" list. */
data class AgentContinueItem(
    val id: String,
    val title: String,
    val meta: String,
    val time: String,
    val letter: String,
    val needsAttention: Boolean = false,
    val stackLabel: String = "",
    val stackColor: Color = Color.Unspecified,
    val modulesLabel: String = "",
    val tags: List<String> = emptyList(),
    val avatar: ImageBitmap? = null,
    val shimmerSubtitle: Boolean = false,
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
    onOpenSettings: (() -> Unit)? = null,
    onOpenAccount: () -> Unit = {},
    onNewProject: () -> Unit = {},
    onImportOrClone: () -> Unit = {},
    onDeviceLabel: String = "Configure AI",
    providerConfigured: Boolean = false,
    continueItems: List<AgentContinueItem> = emptyList(),
    allProjectsLabel: String = "All projects",
    onOpenItem: (AgentContinueItem) -> Unit = {},
    onSeeAllProjects: () -> Unit = {},
    modifier: Modifier = Modifier,
    agentIcon: ImageVector = CaIcons.sparkle,
    settingsIcon: ImageVector = CaIcons.gear,
    accountIcon: ImageVector = CaIcons.account,
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                appName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.02.sp,
                color = AgentUi.chrome.textMuted,
                modifier = Modifier.weight(1f),
            )
            AgentIconCircle(agentIcon, "Agent settings", onOpenAgentSettings, Modifier.size(44.dp))
            if (onOpenSettings != null) {
                AgentIconCircle(settingsIcon, "Settings", onOpenSettings, Modifier.size(44.dp))
            }
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
                        AgentStatusDot(active = providerConfigured, size = 7.dp)
                        Text(onDeviceLabel, fontSize = 13.sp, color = AgentUi.chrome.textMuted, maxLines = 1)
                    }
                    Spacer(Modifier.weight(1f))
                    AgentIconCircle(CaIcons.arrowRight, "Start", onStart, Modifier.size(44.dp), accent = true)
                }
            }
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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentSectionLabel("Or bring existing code")
            // Real tappable rows (surface + chevron) — never bare text pretending to be a control.
            listOf(
                Triple("Clone a Git repo", onCloneGit, CaIcons.arrowRight),
                Triple("Open a folder on this phone", onOpenFolder, CaIcons.arrowRight),
                Triple("Import a .caproj package", onImportPackage, CaIcons.arrowRight),
            ).forEach { (label, onClick, chevron) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AgentUi.chrome.bg)
                        .border(1.dp, AgentUi.chrome.hairline, RoundedCornerShape(12.dp))
                        .clickable(onClick = onClick)
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        label,
                        fontSize = 15.sp,
                        color = AgentUi.chrome.text,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        chevron,
                        contentDescription = null,
                        tint = AgentUi.chrome.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
