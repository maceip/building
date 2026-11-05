package fragments.agentui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import fragments.components.UiDisclosure
import fragments.components.UiText

/** One parent tool call and its ordered child calls. Groups can nest for subagents. */
@Immutable
data class ChatToolGroup(
    val id: String,
    val title: String,
    val tools: List<ChatTool>,
    val description: String = "",
    val status: ChatToolStatus? = null,
    val durationMillis: Long? = null,
    val children: List<ChatToolGroup> = emptyList(),
)

private fun ChatToolGroup.allTools(): Sequence<ChatTool> =
    tools.asSequence() + children.asSequence().flatMap { it.allTools() }

/** Compact parent row with nested tool calls. Child approvals keep their own ids. */
@Composable
fun AgentToolGroup(
    group: ChatToolGroup,
    onApproval: (toolId: String, approved: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
    palette: AgentUiPalette = AgentUiTheme.palette,
    rendererRegistry: ToolRendererRegistry? = null,
) {
    var expanded by remember(group.id) { mutableStateOf(defaultExpanded) }
    val allTools = remember(group) { group.allTools().toList() }
    val status = group.status ?: when {
        allTools.any { it.status == ChatToolStatus.NeedsApproval } -> ChatToolStatus.NeedsApproval
        allTools.any { it.status == ChatToolStatus.Running } -> ChatToolStatus.Running
        allTools.any { it.status == ChatToolStatus.Error } -> ChatToolStatus.Error
        else -> ChatToolStatus.Completed
    }
    val stateLabel = when (status) {
        ChatToolStatus.Running -> "Running"
        ChatToolStatus.Completed -> "Completed"
        ChatToolStatus.Error -> "Failed"
        ChatToolStatus.NeedsApproval -> "Needs approval"
    }
    val count = allTools.size
    val label = buildString {
        append(group.title).append(" · ").append(stateLabel).append(" · ")
        append(count).append(if (count == 1) " tool" else " tools")
        group.durationMillis?.let { append(" · ").append(it).append("ms") }
    }
    val sizing = AgentUiTheme.sizing
    UiDisclosure(label, expanded, { expanded = !expanded }, palette,
        modifier.testTag("agent-tool-group-${group.id}")) {
        Column(
            Modifier.padding(start = if (sizing.isMobile) 10.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (sizing.isMobile) 6.dp else 3.dp),
        ) {
            if (group.description.isNotBlank()) UiText(group.description, palette, muted = true, size = 12)
            group.tools.forEach { tool -> key(tool.id) {
                AgentToolCard(tool, onApproval = { onApproval(tool.id, it) }, palette = palette,
                    rendererRegistry = rendererRegistry)
            } }
            group.children.forEach { child -> key(child.id) {
                AgentToolGroup(child, onApproval, defaultExpanded = false, palette = palette,
                    rendererRegistry = rendererRegistry)
            } }
        }
    }
}
