package fragments.agentui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fragments.components.UiButton
import fragments.components.UiDivider
import fragments.components.UiText

/** A single kit-owned tool surface. Its summary row stays fixed as details open below it. */
@Composable
fun AgentToolCard(
    tool: ChatTool,
    onApproval: (Boolean) -> Unit,
    palette: AgentUiPalette = AgentUiTheme.palette,
    rendererRegistry: ToolRendererRegistry? = null,
) {
    val genericRenderer = (tool as? ChatTool.Generic)?.let { rendererRegistry?.renderer(it.kind) }
    val searchResults = (tool as? ChatTool.Search)?.let { search ->
        search.richResults ?: search.results.map { ChatSearchResult("", it) }
    }.orEmpty()
    val expandable = when (tool) {
        is ChatTool.Bash, is ChatTool.Edit, is ChatTool.Thinking, is ChatTool.Mcp -> true
        is ChatTool.Search -> searchResults.isNotEmpty()
        else -> false
    }
    var expanded by remember(tool.id) {
        mutableStateOf((tool as? ChatTool.Search)?.defaultOpen == true)
    }
    var decisionSent by remember(tool.id, tool.status) { mutableStateOf(false) }
    var planApproved by remember(tool.id, (tool as? ChatTool.Plan)?.document) { mutableStateOf(false) }
    var fullPlan by remember(tool.id) { mutableStateOf(false) }
    val label = when (tool) {
        is ChatTool.Bash -> "Bash · ${tool.command}"
        is ChatTool.Edit -> "Edit · ${tool.file}"
        is ChatTool.Search -> if (tool.status == ChatToolStatus.Running) "Searching..."
            else "Found ${searchResults.size} ${if (searchResults.size == 1) "result" else "results"}"
        is ChatTool.Todo -> "Todo"
        is ChatTool.Plan -> "Plan"
        is ChatTool.Thinking -> "Thinking"
        is ChatTool.Mcp -> "MCP · ${tool.server} · ${tool.action}"
        is ChatTool.Generic -> tool.name
    }
    val shape = RoundedCornerShape(palette.toolRadius)
    Column(
        Modifier.fillMaxWidth().testTag("agent-tool-${tool.id}")
            .background(palette.surface, shape).border(1.dp, palette.border, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().then(
                if (expandable) Modifier.clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                else Modifier,
            ).padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (expandable) Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) {
                UiText(if (expanded) "⌄" else "›", palette, size = 14)
            }
            BasicText(
                label,
                Modifier.weight(1f),
                style = TextStyle(color = palette.text, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium, lineHeight = 18.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (tool is ChatTool.Search && searchResults.isEmpty() && tool.query.isNotBlank())
                UiText(tool.query, palette, muted = true, size = 12)
            if (tool.status == ChatToolStatus.Running && tool !is ChatTool.Search)
                UiText("Running", palette, muted = true, size = 11)
        }
        AnimatedVisibility(
            visible = expandable && expanded,
            enter = fadeIn(tween(120)) + expandVertically(tween(150)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(130)),
        ) {
            Column(Modifier.fillMaxWidth()) {
                UiDivider(palette)
                Column(Modifier.fillMaxWidth().background(palette.background).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    when (tool) {
                        is ChatTool.Bash -> {
                            UiText("$ ${tool.command}", palette, mono = true, size = 12)
                            if (tool.output.isNotBlank()) UiText(tool.output, palette, mono = true, size = 12)
                            tool.exitCode?.let { exit -> UiText("Exit $exit",
                                palette.copy(text = if (exit == 0) palette.success else palette.danger), size = 11) }
                        }
                        is ChatTool.Edit -> tool.diff.forEach { line ->
                            val color = when {
                                line.startsWith("+") -> palette.success
                                line.startsWith("-") -> palette.danger
                                else -> palette.text
                            }
                            UiText(line, palette.copy(text = color), mono = true, size = 12)
                        }
                        is ChatTool.Search -> {
                            if (tool.query.isNotBlank()) {
                                UiText("Searched for", palette, size = 12, weight = FontWeight.Medium)
                                UiText("“${tool.query}”", palette, muted = true, size = 12)
                                UiDivider(palette)
                            }
                            Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                                searchResults.forEach { result ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        UiText(result.title, palette, Modifier.weight(1f), size = 12)
                                        val metadata = result.date.ifBlank { result.source }
                                        if (metadata.isNotBlank()) UiText(metadata, palette, muted = true, size = 11)
                                    }
                                }
                            }
                        }
                        is ChatTool.Thinking -> UiText(tool.text, palette, muted = true, size = 12)
                        is ChatTool.Mcp -> UiText(tool.result, palette, size = 12)
                        else -> Unit
                    }
                }
            }
        }
        if (tool is ChatTool.Todo) {
            val items = tool.todoItems ?: tool.items.map { ChatTodoItem(it) }
            if (items.isNotEmpty() || tool.status == ChatToolStatus.Running) {
                UiDivider(palette)
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (items.isEmpty()) UiText("Creating to-do list...", palette, muted = true, size = 12)
                    items.forEach { item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            UiText(when (item.status) {
                                ChatTodoStatus.Pending -> "○"
                                ChatTodoStatus.InProgress -> "→"
                                ChatTodoStatus.Completed -> "✓"
                            }, palette.copy(text = if (item.status == ChatTodoStatus.Completed)
                                palette.success else palette.muted))
                            UiText(item.content, palette, muted = item.status != ChatTodoStatus.InProgress)
                        }
                    }
                }
            }
        }
        if (tool is ChatTool.Plan) {
            UiDivider(palette)
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                val document = tool.document
                if (document == null) {
                    tool.steps.forEachIndexed { index, step ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            UiText(if (step.completed) "✓" else "${index + 1}.",
                                palette.copy(text = if (step.completed) palette.success else palette.muted), size = 13)
                            UiText(step.label, palette, muted = step.completed, size = 13)
                        }
                    }
                } else {
                    val rawId = document.fileId?.trim().orEmpty()
                    UiText(when {
                        rawId.isEmpty() -> "plan-working.md"
                        rawId.endsWith(".md") -> rawId
                        else -> "plan-$rawId.md"
                    }, palette, muted = true, size = 12)
                    UiText(document.title, palette, weight = FontWeight.Medium)
                    BasicText(document.summary.ifBlank { "No plan summary provided." }.trim(),
                        style = TextStyle(color = palette.muted, fontSize = 13.sp, lineHeight = 19.sp),
                        maxLines = if (fullPlan) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis)
                    if (document.summary.isNotBlank()) UiButton(
                        if (fullPlan) "Hide detailed plan" else "Read detailed plan",
                        { fullPlan = !fullPlan }, palette)
                    if (document.approved || planApproved) UiText("Approved", palette.copy(text = palette.success), size = 12)
                    else UiButton(document.approveLabel, {
                        planApproved = true
                        onApproval(true)
                    }, palette, primary = true)
                }
            }
        }
        if (tool is ChatTool.Generic && (tool.detail.isNotBlank() || tool.fields.isNotEmpty() || genericRenderer != null)) {
            UiDivider(palette)
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (genericRenderer != null) genericRenderer.Render(tool)
                else {
                    if (tool.detail.isNotBlank()) UiText(tool.detail, palette, muted = true, size = 12)
                    tool.fields.entries.sortedBy { it.key }.forEach { (name, value) ->
                        UiText("$name: $value", palette, muted = true, size = 12)
                    }
                }
            }
        }
        if (tool.status == ChatToolStatus.NeedsApproval &&
            (tool is ChatTool.Bash || tool is ChatTool.Edit || tool is ChatTool.Generic) && !decisionSent) {
            UiDivider(palette)
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UiButton("Approve", { decisionSent = true; onApproval(true) }, palette, primary = true)
                UiButton("Deny", { decisionSent = true; onApproval(false) }, palette)
            }
        }
    }
}
