package dev.ide.agent.ui

import dev.ide.ui.components.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAgentPermissionDecision
import dev.ide.ui.backend.UiAgentPermissionRequest
import dev.ide.agent.ui.generated.resources.Res
import dev.ide.agent.ui.generated.resources.chat_allow
import dev.ide.agent.ui.generated.resources.chat_allow_session
import dev.ide.agent.ui.generated.resources.chat_deny
import dev.ide.agent.ui.generated.resources.chat_perm_title
import dev.ide.ui.screens.AgentStatusDot
import dev.ide.ui.theme.AgentUi
import org.jetbrains.compose.resources.stringResource

/**
 * The agent's write-permission prompt (ASK_EACH mode): while a mutating tool call is blocked awaiting a
 * decision, this overlays and routes the answer back. Observes [IdeBackend.agent] and mirrors the run
 * sandbox's [PermissionDialog]. Dismissing denies, the safe default. Hosted app-wide in [dev.ide.ui.AppOverlays].
 */
@Composable
fun AgentPermissionDialog(backend: IdeBackend) {
    val request by backend.agent.permissionRequest.collectAsState()
    var shown by remember { mutableStateOf<UiAgentPermissionRequest?>(null) }
    if (request != null) shown = request

    fun answer(decision: UiAgentPermissionDecision) { shown?.let { backend.agent.answerPermission(it.id, decision) } }

    CenteredDialog(visible = request != null, onDismiss = { answer(UiAgentPermissionDecision.DENY) }) {
        shown?.let { req -> AgentPermissionCard(req, ::answer) }
    }
}

@Composable
private fun AgentPermissionCard(req: UiAgentPermissionRequest, answer: (UiAgentPermissionDecision) -> Unit) {
    val chrome = AgentUi.chrome
    Column(
        Modifier
            // Wider when it carries a diff, which needs the room more than the buttons do.
            .widthIn(max = if (req.changes.isEmpty()) 420.dp else 640.dp)
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            // Opaque: this card is the guard on every file write, so it must not depend on what is behind it.
            .background(chrome.surface, RoundedCornerShape(AgentUi.radius.sheet))
            .border(1.dp, chrome.hairlineStrong, RoundedCornerShape(AgentUi.radius.sheet))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentStatusDot(active = true, size = 8.dp, color = chrome.warn)
            Text(
                stringResource(Res.string.chat_perm_title),
                color = chrome.text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(req.summary, color = chrome.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        req.path?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, color = chrome.textFaint, style = MaterialTheme.typography.bodySmall)
        }
        // The change itself, so approving is a decision about the edit rather than about a file name.
        if (req.changes.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FileChangesList(req.changes, expandedByDefault = true, maxDiffHeight = 280)
        }
        Spacer(Modifier.height(16.dp))

        AgentPermButton(stringResource(Res.string.chat_allow), AgentPermKind.Accent) { answer(UiAgentPermissionDecision.ALLOW_ONCE) }
        Spacer(Modifier.height(8.dp))
        AgentPermButton(stringResource(Res.string.chat_allow_session), AgentPermKind.Neutral) { answer(UiAgentPermissionDecision.ALLOW_SESSION) }
        Spacer(Modifier.height(8.dp))
        AgentPermButton(stringResource(Res.string.chat_deny), AgentPermKind.Danger) { answer(UiAgentPermissionDecision.DENY) }
    }
}

private enum class AgentPermKind { Accent, Neutral, Danger }

@Composable
private fun AgentPermButton(label: String, kind: AgentPermKind, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val chrome = AgentUi.chrome
    // Primary CTA is lime with dark ink; neutral is a ghost pill; deny carries the danger ink on a quiet fill.
    val fill = when (kind) {
        AgentPermKind.Accent -> chrome.accent
        AgentPermKind.Danger -> chrome.surfaceAlt
        AgentPermKind.Neutral -> chrome.bg
    }
    val fg = when (kind) {
        AgentPermKind.Accent -> chrome.accentInk
        AgentPermKind.Danger -> chrome.danger
        AgentPermKind.Neutral -> chrome.text
    }
    val stroke = when (kind) {
        AgentPermKind.Accent -> Color.Transparent
        AgentPermKind.Danger -> chrome.hairlineStrong
        AgentPermKind.Neutral -> chrome.hairlineStrong
    }
    val shape = RoundedCornerShape(AgentUi.radius.control)
    Box(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clip(shape)
            .background(fill, shape)
            .then(if (stroke != Color.Transparent) Modifier.border(1.dp, stroke, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 13.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}
