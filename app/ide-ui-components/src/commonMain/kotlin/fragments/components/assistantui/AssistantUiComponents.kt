package fragments.components.assistantui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import fragments.components.ReactUiPalette
import fragments.components.ReactUiPalettes
import fragments.components.UiButton
import fragments.components.UiDivider
import fragments.components.UiText
import fragments.components.IconGeometry
import fragments.components.SourceIcon
import fragments.components.agentelements.icons.CheckIcon
import fragments.components.agentelements.icons.IconSpinner

enum class SpeakerKind { User, Agent, Subagent, Tool }
enum class TaskCardState { Working, Waiting, Done, Failed, Cancelled }

@Immutable
data class SpeakerTurn(val id: String, val kind: SpeakerKind, val name: String, val text: String, val detail: String = "")

@Immutable
data class SubagentItem(val name: String, val model: String)

@Immutable
data class AgentSkill(val name: String, val description: String)

@Immutable
data class TriggerItem(val id: String, val label: String, val category: String,
    val description: String = "", val type: String = "agent")

@Immutable
data class TriggerCategory(val id: String, val label: String)

@Composable
fun SpeakerIdentity(turns: List<SpeakerTurn>, modifier: Modifier = Modifier, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    Column(modifier.widthIn(max = 384.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        turns.forEach { turn ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val tone = if (turn.kind == SpeakerKind.Agent) palette.accent.copy(alpha = 0.14f) else palette.subtle
                Box(Modifier.size(24.dp).background(tone, if (turn.kind == SpeakerKind.Subagent) CircleShape else RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center) {
                    SpeakerGlyph(turn.kind, palette)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        UiText(turn.name, palette, size = 13, weight = FontWeight.Medium)
                        if (turn.detail.isNotBlank()) UiText(turn.detail, palette, muted = true, size = 11, mono = true)
                    }
                    UiText(turn.text, palette, muted = true, size = 13)
                }
            }
        }
    }
}

@Composable
private fun SpeakerGlyph(kind: SpeakerKind, palette: ReactUiPalette, iconSize: Dp = 13.dp) {
    val geometry = when (kind) {
        SpeakerKind.User -> listOf(
            IconGeometry.Circle(12f, 8f, 4f),
            IconGeometry.Path("M4 21v-2a8 8 0 0 1 16 0v2"),
        )
        SpeakerKind.Tool -> listOf(
            IconGeometry.Path("M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4L15 12l-3-3z"),
        )
        SpeakerKind.Agent, SpeakerKind.Subagent -> listOf(
            IconGeometry.Rect(4f, 7f, 16f, 13f, radius = 3f),
            IconGeometry.Path("M12 7V4M9 4h6M2 12v4M22 12v4"),
            IconGeometry.Circle(9f, 13f, 0.9f, filled = true),
            IconGeometry.Circle(15f, 13f, 0.9f, filled = true),
            IconGeometry.Path("M9 17h6"),
        )
    }
    SourceIcon(geometry, 24f, Modifier.size(iconSize), if (kind == SpeakerKind.Agent) palette.text else palette.muted)
}

@Composable
fun SubagentList(
    agents: List<SubagentItem>,
    completedCount: Int,
    progress: List<Int>,
    showSummary: Boolean,
    summaryAgent: SubagentItem,
    modifier: Modifier = Modifier,
    palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark,
) {
    Column(modifier.widthIn(max = 320.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        agents.forEachIndexed { index, agent ->
            val done = index < completedCount
            val target = (progress.getOrNull(index) ?: 0).coerceIn(0, 100) / 100f
            val animated by animateFloatAsState(target, tween(700), label = "subagent progress")
            Column(Modifier.fillMaxWidth().background(palette.surface, RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                SubagentHeader(agent, done, palette)
                Box(Modifier.fillMaxWidth().height(3.dp).background(palette.subtle, CircleShape)
                    .semantics { contentDescription = "${agent.name} progress";
                        progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f) }) {
                    Box(Modifier.fillMaxWidth(animated).height(3.dp).background(if (done) palette.success else palette.text.copy(alpha = 0.6f), CircleShape))
                }
            }
        }
        if (showSummary) Column(Modifier.fillMaxWidth().background(palette.surface, RoundedCornerShape(16.dp)).padding(12.dp)) {
            SubagentHeader(summaryAgent, false, palette)
            ShimmerLabel("Summarizing…", palette = palette)
            val shimmer = rememberInfiniteTransition(label = "subagent summary shimmer")
            val shimmerPosition by shimmer.animateFloat(-0.25f, 1.25f,
                infiniteRepeatable(tween(1_100)), label = "summary progress position")
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape)
                .background(palette.subtle)
                .drawBehind {
                    val center = shimmerPosition * size.width
                    drawRect(brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, palette.text.copy(alpha = 0.48f), Color.Transparent),
                        startX = center - size.width * 0.24f,
                        endX = center + size.width * 0.24f,
                    ))
                }
                .semantics { contentDescription = "${summaryAgent.name} progress";
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
                .testTag("assistant-subagent-summary-progress"))
        }
    }
}

@Composable
private fun SubagentHeader(agent: SubagentItem, done: Boolean, palette: ReactUiPalette) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (done) CheckIcon(size = 14.dp, color = palette.success)
        else IconSpinner(size = 14.dp, color = palette.muted)
        UiText(agent.name, palette, Modifier.weight(1f), size = 13,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        UiText(agent.model, palette, Modifier.widthIn(max = 96.dp), muted = true, size = 11,
            mono = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun AgentCard(
    name: String,
    description: String,
    provider: String,
    version: String,
    model: String,
    endpoint: String,
    skills: List<AgentSkill>,
    connected: Boolean,
    onConnect: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.widthIn(max = 384.dp).fillMaxWidth().background(palette.surface, shape)
        .border(1.dp, palette.border.copy(alpha = 0.6f), shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top) {
            Box(Modifier.size(36.dp).background(palette.subtle, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center) {
                SpeakerGlyph(SpeakerKind.Agent, palette, 16.dp)
            }
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom) {
                    UiText(name, palette, Modifier.weight(1f), size = 13, weight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    UiText("v$version", palette, Modifier.widthIn(max = 90.dp), muted = true,
                        mono = true, size = 11, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                UiText(provider, palette, Modifier.fillMaxWidth(), muted = true, size = 12,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        UiText(description, palette, muted = true, size = 12)
        skills.forEach { skill ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(palette.subtle, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)) {
                    UiText(skill.name, palette, Modifier.widthIn(max = 100.dp), mono = true,
                        muted = true, size = 11, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                UiText(skill.description, palette, Modifier.weight(1f), muted = true, size = 12,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        UiDivider(palette)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            UiText(endpoint, palette, Modifier.weight(1f), muted = true, mono = true, size = 11,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            UiText(model, palette, Modifier.widthIn(max = 96.dp), muted = true, mono = true,
                size = 11, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) 0.96f else 1f,
            tween(150), label = "agent connect press scale")
        Box(Modifier.fillMaxWidth().height(32.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(if (connected) palette.subtle else palette.text, CircleShape)
            .clickable(enabled = !connected && onConnect != null, role = Role.Button,
                interactionSource = interaction, indication = null) { onConnect?.invoke() },
            contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (connected) CheckIcon(size = 14.dp, color = palette.success)
                UiText(if (connected) "Connected" else "Connect",
                    palette.copy(text = if (connected) palette.muted else palette.background),
                    size = 12, weight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun TaskStateIcon(state: TaskCardState, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    when (state) {
        TaskCardState.Working -> IconSpinner(size = 14.dp, color = palette.muted)
        TaskCardState.Done -> CheckIcon(size = 14.dp, color = palette.success)
        TaskCardState.Failed -> SourceIcon(listOf(
            IconGeometry.Path("M18 6 6 18M6 6l12 12")), 24f, Modifier.size(14.dp), palette.danger)
        TaskCardState.Cancelled -> SourceIcon(listOf(
            IconGeometry.Circle(12f, 12f, 9f), IconGeometry.Path("M5.7 5.7l12.6 12.6")),
            24f, Modifier.size(14.dp), palette.muted)
        TaskCardState.Waiting -> Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(6.dp).border(1.dp, palette.muted, CircleShape))
        }
    }
}

@Composable
fun TaskCard(
    label: String,
    state: TaskCardState,
    modifier: Modifier = Modifier,
    meta: String = "",
    elapsed: String = "",
    result: String = "",
    palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark,
    open: Boolean? = null,
    onOpenChange: ((Boolean) -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
    resultContent: (@Composable () -> Unit)? = null,
    transcript: (@Composable () -> Unit)? = null,
) {
    var localOpen by remember(label) { mutableStateOf(false) }
    val expanded = open ?: localOpen
    val hasTranscript = transcript != null
    val inert = open != null && onOpenChange == null
    val chevronRotation by animateFloatAsState(if (expanded) 90f else 0f,
        tween(200), label = "task disclosure chevron")
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.widthIn(max = 384.dp).fillMaxWidth().background(palette.surface, shape)
        .border(1.dp, palette.border.copy(alpha = 0.6f), shape)) {
        val summaryModifier = if (hasTranscript) Modifier.clickable(enabled = !inert,
            role = Role.Button) {
            val next = !expanded
            if (open == null) localOpen = next
            onOpenChange?.invoke(next)
        } else Modifier
        BoxWithConstraints(Modifier.fillMaxWidth().then(summaryModifier)
            .padding(horizontal = 14.dp, vertical = 10.dp)) {
            val compact = maxWidth < 300.dp
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.semantics { contentDescription = state.name.lowercase() }) {
                    TaskStateIcon(state, palette)
                }
                Column(Modifier.weight(1f)) {
                    UiText(label, palette, Modifier.fillMaxWidth().testTag("task-card-label"), size = 13,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (compact && meta.isNotBlank()) UiText(meta, palette,
                        Modifier.fillMaxWidth().testTag("task-card-meta"),
                        muted = true, mono = true, size = 11, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                if (!compact && meta.isNotBlank()) UiText(meta, palette,
                    Modifier.widthIn(max = 96.dp).testTag("task-card-meta"), muted = true, mono = true, size = 11,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (elapsed.isNotBlank()) UiText(elapsed, palette,
                    Modifier.widthIn(max = 64.dp).testTag("task-card-elapsed"), muted = true, mono = true, size = 11,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (hasTranscript) SourceIcon(listOf(IconGeometry.Path("m9 18 6-6-6-6")),
                    24f, Modifier.size(12.dp).rotate(chevronRotation), palette.muted)
            }
        }
        if (actions != null) {
            UiDivider(palette)
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                actions()
            }
        }
        AnimatedVisibility(hasTranscript && expanded) {
            Column(Modifier.fillMaxWidth()) {
                UiDivider(palette)
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    transcript?.invoke()
                }
            }
        }
        if (resultContent != null || result.isNotBlank()) {
            UiDivider(palette)
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                if (resultContent != null) resultContent()
                else UiText(result, palette, muted = true, size = 12)
            }
        }
    }
}

@Composable
fun ComposerTriggerPopover(
    categories: List<TriggerCategory>,
    items: List<TriggerItem>,
    onSelect: (TriggerItem) -> Unit,
    modifier: Modifier = Modifier,
    palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark,
    isLoading: Boolean = false,
    backLabel: String = "Back",
    emptyCategoriesLabel: String = "No items available",
    emptyItemsLabel: String = "No matching items",
    loadingLabel: String = "Loading…",
    onDismiss: (() -> Unit)? = null,
) {
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var highlighted by remember(selectedCategory) { mutableStateOf(if (selectedCategory == null) 0 else 1) }
    val filtered = items.filter { it.category == selectedCategory }
    val rowCount = if (selectedCategory == null) categories.size else filtered.size + 1
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.widthIn(max = 256.dp).fillMaxWidth()
        .background(palette.surface, shape).border(1.dp, palette.border, shape)
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionDown -> { if (rowCount > 0) highlighted = (highlighted + 1) % rowCount; true }
                Key.DirectionUp -> { if (rowCount > 0) highlighted = (highlighted + rowCount - 1) % rowCount; true }
                Key.Enter -> {
                    if (selectedCategory == null) categories.getOrNull(highlighted)?.let { selectedCategory = it.id }
                    else if (highlighted == 0) selectedCategory = null
                    else filtered.getOrNull(highlighted - 1)?.let(onSelect)
                    true
                }
                Key.Escape, Key.DirectionLeft -> if (selectedCategory != null) {
                    selectedCategory = null
                    true
                } else {
                    onDismiss?.invoke()
                    onDismiss != null
                }
                else -> false
            }
        }
        .focusable().padding(vertical = 4.dp).testTag("assistant-trigger-popover")) {
        if (selectedCategory == null) {
            if (categories.isEmpty()) UiText(emptyCategoriesLabel, palette,
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp), muted = true, size = 13)
            categories.forEachIndexed { index, category ->
                val hoverSource = remember { MutableInteractionSource() }
                val hovered by hoverSource.collectIsHoveredAsState()
                LaunchedEffect(hovered) { if (hovered) highlighted = index }
                val rowColor by animateColorAsState(
                    if (highlighted == index) palette.subtle else palette.surface,
                    tween(120), label = "trigger category highlight")
                Row(Modifier.fillMaxWidth().background(rowColor, RoundedCornerShape(6.dp))
                    .hoverable(hoverSource)
                    .clickable(role = Role.Button) { selectedCategory = category.id }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("assistant-trigger-category-${category.id}"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TriggerSparkle(palette, 16.dp)
                    UiText(category.label, palette, Modifier.weight(1f), size = 14,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SourceIcon(listOf(IconGeometry.Path("m9 18 6-6-6-6")),
                        24f, Modifier.size(16.dp), palette.muted)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { selectedCategory = null }
                .padding(horizontal = 12.dp, vertical = 8.dp).testTag("assistant-trigger-back"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                SourceIcon(listOf(IconGeometry.Path("m15 18-6-6 6-6")),
                    24f, Modifier.size(14.dp), palette.muted)
                UiText(backLabel.uppercase(), palette, muted = true, size = 11)
            }
            UiDivider(palette)
            if (filtered.isEmpty()) UiText(if (isLoading) loadingLabel else emptyItemsLabel,
                palette, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), muted = true, size = 13)
            filtered.forEachIndexed { index, item ->
                val hoverSource = remember { MutableInteractionSource() }
                val hovered by hoverSource.collectIsHoveredAsState()
                LaunchedEffect(hovered) { if (hovered) highlighted = index + 1 }
                val rowColor by animateColorAsState(
                    if (highlighted == index + 1) palette.subtle else palette.surface,
                    tween(120), label = "trigger item highlight")
                Column(Modifier.fillMaxWidth().background(rowColor, RoundedCornerShape(6.dp))
                    .hoverable(hoverSource)
                    .clickable(role = Role.Button) { onSelect(item) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("assistant-trigger-item-${item.id}"),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        TriggerSparkle(palette, 14.dp)
                        UiText(item.label, palette, Modifier.weight(1f), size = 14,
                            weight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (item.description.isNotBlank()) UiText(item.description, palette,
                        Modifier.fillMaxWidth().padding(start = 22.dp), muted = true, size = 12,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun TriggerSparkle(palette: ReactUiPalette, size: Dp) = SourceIcon(
    listOf(IconGeometry.Path("M12 2l1.8 6.2L20 10l-6.2 1.8L12 18l-1.8-6.2L4 10l6.2-1.8z")),
    24f, Modifier.size(size), palette.muted)

@Immutable
sealed interface DirectiveTextSegment {
    data class Text(val text: String) : DirectiveTextSegment
    data class Mention(val type: String, val label: String, val id: String) : DirectiveTextSegment
}

typealias DirectiveTextFormatter = (String) -> List<DirectiveTextSegment>

fun createDirectiveText(formatter: DirectiveTextFormatter): DirectiveTextFormatter = formatter

@Composable
fun DirectiveText(text: String, formatter: DirectiveTextFormatter = ::defaultDirectiveFormatter, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        formatter(text).forEach { segment -> when (segment) {
            is DirectiveTextSegment.Text -> UiText(segment.text, palette)
            is DirectiveTextSegment.Mention -> UiText(segment.label, palette.copy(text = palette.accent), Modifier.background(palette.accent.copy(alpha = 0.12f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 2.dp), size = 13)
        } }
    }
}

fun defaultDirectiveFormatter(text: String): List<DirectiveTextSegment> {
    val pattern = Regex("@([A-Za-z0-9_]+)")
    val parts = mutableListOf<DirectiveTextSegment>()
    var cursor = 0
    pattern.findAll(text).forEach { match ->
        if (match.range.first > cursor) parts += DirectiveTextSegment.Text(text.substring(cursor, match.range.first))
        parts += DirectiveTextSegment.Mention("agent", match.value, match.groupValues[1])
        cursor = match.range.last + 1
    }
    if (cursor < text.length) parts += DirectiveTextSegment.Text(text.substring(cursor))
    return parts
}

@Composable
fun ShimmerLabel(text: String, active: Boolean = true, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    val transition = rememberInfiniteTransition(label = "shimmer label")
    val animated by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "opacity")
    UiText(text, palette, Modifier.alpha(if (active) animated else 1f), size = 12)
}

@Composable
fun SwapLabel(active: Int, first: String, second: String, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    AnimatedContent(targetState = active, label = "swap label") { selected ->
        UiText(if (selected == 0) first else second, palette)
    }
}
