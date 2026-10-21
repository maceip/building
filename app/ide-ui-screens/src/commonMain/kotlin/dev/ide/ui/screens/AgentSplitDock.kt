package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.AgentUi

/** Where the agent pane sits in a split layout. */
enum class AgentPanePlacement {
    /** Agent occupies the top pane (default mobile split from the redesign). */
    Top,
    /** Agent occupies the bottom half; code/content stays live above. */
    Bottom,
}

/**
 * Split workspace: agent pane on top or bottom (half-screen multitasking) with a companion
 * explore/code/web pane, a drag handle, and swap — the redesign's Split screens.
 */
@Composable
fun AgentSplitLayout(
    placement: AgentPanePlacement,
    onPlacementChange: (AgentPanePlacement) -> Unit,
    agentContent: @Composable () -> Unit,
    secondaryLabel: String,
    secondaryContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    agentFraction: Float = 0.52f,
    onAgentFractionChange: (Float) -> Unit = {},
) {
    var fraction by remember(placement) { mutableFloatStateOf(agentFraction) }
    val agentOnTop = placement == AgentPanePlacement.Top

    Column(modifier = modifier.fillMaxSize().background(AgentUi.chrome.bg)) {
        if (agentOnTop) {
            Box(Modifier.weight(fraction).fillMaxWidth()) {
                AgentPaneScaffold(agentContent)
            }
            SplitHandle(
                label = secondaryLabel,
                onSwap = { onPlacementChange(AgentPanePlacement.Bottom) },
                onDragDelta = { delta ->
                    fraction = (fraction + delta).coerceIn(0.28f, 0.78f)
                    onAgentFractionChange(fraction)
                },
            )
            Box(Modifier.weight(1f - fraction).fillMaxWidth()) {
                SecondaryPane(secondaryLabel, secondaryContent)
            }
        } else {
            Box(Modifier.weight(1f - fraction).fillMaxWidth()) {
                SecondaryPane(secondaryLabel, secondaryContent)
            }
            SplitHandle(
                label = "AGENT",
                onSwap = { onPlacementChange(AgentPanePlacement.Top) },
                onDragDelta = { delta ->
                    // dragging handle down grows the bottom agent pane
                    fraction = (fraction - delta).coerceIn(0.28f, 0.78f)
                    onAgentFractionChange(fraction)
                },
            )
            Box(Modifier.weight(fraction).fillMaxWidth()) {
                AgentPaneScaffold(agentContent, roundedTop = true)
            }
        }
    }
}

@Composable
private fun AgentPaneScaffold(
    content: @Composable () -> Unit,
    roundedTop: Boolean = false,
) {
    val shape = if (roundedTop) {
        RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    } else {
        RoundedCornerShape(0.dp)
    }
    Column(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(AgentUi.chrome.surface)
            .then(
                if (roundedTop) Modifier.border(1.dp, AgentUi.chrome.hairlineStrong, shape)
                else Modifier,
            ),
    ) {
        content()
    }
}

@Composable
private fun SecondaryPane(label: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(AgentUi.chrome.bg),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AgentSectionLabel(label)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun SplitHandle(
    label: String,
    onSwap: () -> Unit,
    onDragDelta: (Float) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, fontSize = 11.sp, color = AgentUi.chrome.textFaint)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .width(64.dp)
                .height(28.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        onDragDelta(dragAmount / 1200f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(40.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(AgentUi.chrome.textFaint.copy(alpha = 0.55f)),
            )
        }
        Box(
            Modifier
                .size(36.dp)
                .clickable(onClick = onSwap),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                CaIcons.arrowRight,
                contentDescription = "Swap panes",
                tint = AgentUi.chrome.textFaint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Compact top bar for the agent pane inside a split dock: back, title, on-device badge.
 */
@Composable
fun AgentSplitHeader(
    title: String,
    badge: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AgentIconCircle(CaIcons.arrowLeft, "Back", onBack, Modifier.size(44.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AgentUi.chrome.text)
        Spacer(Modifier.weight(1f))
        if (badge != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(end = 10.dp),
            ) {
                AgentStatusDot(active = true, size = 6.dp)
                Text(badge.uppercase(), fontSize = 11.sp, color = AgentUi.chrome.textMuted, letterSpacing = 0.04.sp)
            }
        }
    }
}

/**
 * A compact agent conversation strip used inside the split agent pane.
 */
@Composable
fun AgentSplitConversation(
    userText: String,
    replyText: String,
    prompt: String,
    onPromptChange: (String) -> Unit,
    onSend: () -> Unit,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    secondaryActionLabel: String,
    onSecondaryAction: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Ask about what’s below…",
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AgentUserBubble(userText, Modifier.fillMaxWidth(0.84f))
        AgentReplyBody(replyText)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentPrimaryButton(primaryActionLabel, onPrimaryAction)
            AgentGhostButton(secondaryActionLabel, onSecondaryAction)
        }
        Spacer(Modifier.weight(1f))
        AgentComposer(
            value = prompt,
            onValueChange = onPromptChange,
            onSend = onSend,
            placeholder = placeholder,
        )
    }
}
