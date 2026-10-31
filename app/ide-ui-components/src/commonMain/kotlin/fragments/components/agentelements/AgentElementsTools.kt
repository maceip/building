package fragments.components.agentelements

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fragments.agentui.AgentUiTheme
import fragments.components.ReactUiPalette
import fragments.components.ReactUiPalettes
import fragments.components.UiButton
import fragments.components.UiDisclosure
import fragments.components.UiDivider
import fragments.components.UiSurface
import fragments.components.UiText

enum class ToolStatus { Running, Completed, Error, NeedsApproval }
enum class TodoItemStatus { Pending, InProgress, Completed }
@Immutable data class TodoItem(val content: String, val status: TodoItemStatus = TodoItemStatus.Pending,
    val activeForm: String? = null)
@Immutable data class PlanDocument(val title: String, val summary: String = "", val fileId: String? = null,
    val approved: Boolean = false, val approveLabel: String = "Approve")
@Immutable data class SearchResult(val source: String, val title: String, val date: String = "")

@Immutable
sealed interface AgentToolCall {
    val id: String
    val status: ToolStatus

    data class Bash(override val id: String, val command: String, val output: String = "", val exitCode: Int? = null, override val status: ToolStatus = ToolStatus.Completed) : AgentToolCall
    data class Edit(override val id: String, val file: String, val diff: List<String>, override val status: ToolStatus = ToolStatus.Completed) : AgentToolCall
    data class Search(override val id: String, val query: String, val results: List<String>, override val status: ToolStatus = ToolStatus.Completed,
        val richResults: List<SearchResult>? = null, val defaultOpen: Boolean = false) : AgentToolCall
    data class Todo(override val id: String, val items: List<String>, override val status: ToolStatus = ToolStatus.Completed,
        val todoItems: List<TodoItem>? = null) : AgentToolCall
    data class Plan(override val id: String, val steps: List<PlanStep>, override val status: ToolStatus = ToolStatus.Completed,
        val document: PlanDocument? = null) : AgentToolCall
    data class Thinking(override val id: String, val text: String, override val status: ToolStatus = ToolStatus.Running) : AgentToolCall
    data class Mcp(override val id: String, val server: String, val action: String, val result: String, override val status: ToolStatus = ToolStatus.Completed) : AgentToolCall
    data class Generic(override val id: String, val name: String, val detail: String, override val status: ToolStatus = ToolStatus.Completed) : AgentToolCall
}

@Immutable
data class PlanStep(val label: String, val completed: Boolean = false)

@Composable
fun ToolRenderer(tool: AgentToolCall, onApproval: (Boolean) -> Unit = {}, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    when (tool) {
        is AgentToolCall.Bash -> BashTool(tool, onApproval, palette)
        is AgentToolCall.Edit -> EditTool(tool, onApproval, palette)
        is AgentToolCall.Search -> SearchTool(tool, palette)
        is AgentToolCall.Todo -> TodoTool(tool, palette)
        is AgentToolCall.Plan -> PlanTool(tool, onApproval, palette)
        is AgentToolCall.Thinking -> ThinkingTool(tool, palette)
        is AgentToolCall.Mcp -> McpTool(tool, palette)
        is AgentToolCall.Generic -> GenericTool(tool, palette)
    }
}

@Composable
fun ToolGroup(tools: List<AgentToolCall>, onApproval: (String, Boolean) -> Unit = { _, _ -> }, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        tools.forEach { tool -> ToolRenderer(tool, { onApproval(tool.id, it) }, palette) }
    }
}

@Composable
fun ToolRowBase(
    completeLabel: String,
    isAnimating: Boolean,
    detail: String = "",
    expandable: Boolean = false,
    defaultOpen: Boolean = false,
    palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark,
    content: @Composable () -> Unit = {},
) {
    if (expandable) {
        var expanded by remember { mutableStateOf(defaultOpen) }
        UiDisclosure(completeLabel + if (detail.isNotBlank()) " · $detail" else "", expanded, { expanded = !expanded }, palette) { content() }
    } else {
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isAnimating) SpiralLoader(palette = palette)
            UiText(completeLabel, palette, muted = true)
            if (detail.isNotBlank()) UiText(detail, palette, muted = true)
        }
    }
}

@Composable
fun ToolApprovalFooter(onApprove: () -> Unit, onDeny: () -> Unit, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    val isMobile = AgentUiTheme.sizing.isMobile
    Row(
        modifier = if (isMobile) Modifier.fillMaxWidth() else Modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        UiButton("Approve", onApprove, palette, modifier = if (isMobile) Modifier.weight(1f) else Modifier, primary = true)
        UiButton("Deny", onDeny, palette, modifier = if (isMobile) Modifier.weight(1f) else Modifier)
    }
}

@Composable
fun BashTool(tool: AgentToolCall.Bash, onApproval: (Boolean) -> Unit = {}, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    var resolvedLocally by remember(tool.id, tool.status) { mutableStateOf(false) }
    UiSurface(palette, Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ToolRowBase("Bash", tool.status == ToolStatus.Running, tool.command, expandable = true, palette = palette) {
                BashToolTerminalCard(tool.command, tool.output, tool.exitCode, palette)
            }
            if (tool.status == ToolStatus.NeedsApproval && !resolvedLocally) ToolApprovalFooter(
                { resolvedLocally = true; onApproval(true) },
                { resolvedLocally = true; onApproval(false) }, palette)
        }
    }
}

@Composable
fun BashToolTerminalCard(command: String, output: String, exitCode: Int?, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    Column(
        Modifier.fillMaxWidth()
            .background(palette.background, RoundedCornerShape(6.dp))
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        UiText("$ $command", palette, mono = true, size = 12)
        if (output.isNotBlank()) UiText(output, palette, mono = true, size = 12)
        if (exitCode != null) UiText("Exit $exitCode", palette.copy(text = if (exitCode == 0) palette.success else palette.danger), size = 11)
    }
}

@Composable
fun EditTool(tool: AgentToolCall.Edit, onApproval: (Boolean) -> Unit = {}, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    var resolvedLocally by remember(tool.id, tool.status) { mutableStateOf(false) }
    UiSurface(palette, Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ToolRowBase("Edit", tool.status == ToolStatus.Running, tool.file, expandable = true, palette = palette) {
                EditToolDiffCard(tool.diff, palette)
            }
            if (tool.status == ToolStatus.NeedsApproval && !resolvedLocally) ToolApprovalFooter(
                { resolvedLocally = true; onApproval(true) },
                { resolvedLocally = true; onApproval(false) }, palette)
        }
    }
}

@Composable
fun EditToolDiffCard(lines: List<String>, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    Column(
        Modifier.fillMaxWidth()
            .background(palette.background, RoundedCornerShape(6.dp))
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        lines.forEach { line ->
            val tint = when {
                line.startsWith("+") -> palette.success.copy(alpha = 0.16f)
                line.startsWith("-") -> palette.danger.copy(alpha = 0.16f)
                else -> palette.background
            }
            Row(Modifier.fillMaxWidth().background(tint).padding(horizontal = 8.dp, vertical = 2.dp)) {
                UiText(line, palette.copy(text = when { line.startsWith("+") -> palette.success; line.startsWith("-") -> palette.danger; else -> palette.text }), mono = true, size = 12)
            }
        }
    }
}

@Composable
fun SearchTool(tool: AgentToolCall.Search, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    val results = tool.richResults ?: tool.results.map { SearchResult("", it) }
    val running = tool.status == ToolStatus.Running
    UiSurface(palette, Modifier.fillMaxWidth()) { ToolRowBase(
        completeLabel = if (running) "Searching..." else "Found ${results.size} ${if (results.size == 1) "result" else "results"}",
        isAnimating = running,
        detail = if (results.isEmpty()) tool.query else "",
        expandable = results.isNotEmpty(),
        defaultOpen = tool.defaultOpen,
        palette = palette,
    ) {
        SearchGroupRich(tool.results, palette, tool.query, tool.richResults)
    } }
}

@Composable
fun SearchGroupRich(results: List<String>, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark,
    query: String = "", richResults: List<SearchResult>? = null) {
    val shown = richResults ?: results.map { SearchResult("", it) }
    Column {
        if (query.isNotBlank()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                UiText("Searched for", palette, weight = FontWeight.Medium, size = 12)
                Spacer(Modifier.width(5.dp))
                UiText("“$query”", palette, muted = true, size = 12)
            }
            UiDivider(palette)
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
            shown.forEach { result ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UiText(result.title, palette, Modifier.weight(1f), size = 12)
                    val meta = result.date.ifBlank { result.source }
                    if (meta.isNotBlank()) UiText(meta, palette, muted = true, size = 11)
                }
            }
        }
    }
}

@Composable
fun TodoTool(tool: AgentToolCall.Todo, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    val items = tool.todoItems ?: tool.items.map { TodoItem(it) }
    UiSurface(palette, Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            UiText("Todo", palette, weight = FontWeight.Medium)
            if (items.isEmpty() && tool.status == ToolStatus.Running)
                TextShimmer("Creating to-do list...", palette = palette)
            items.forEach { item ->
                val icon = when (item.status) {
                    TodoItemStatus.Pending -> "○"
                    TodoItemStatus.InProgress -> "→"
                    TodoItemStatus.Completed -> "✓"
                }
                Row(Modifier.fillMaxWidth().padding(4.dp)) {
                    UiText(icon, palette.copy(text = if (item.status == TodoItemStatus.Completed) palette.success else palette.muted))
                    Spacer(Modifier.width(8.dp))
                    UiText(item.content, palette, muted = item.status != TodoItemStatus.InProgress)
                }
            }
        }
    }
}

@Composable
fun PlanTool(tool: AgentToolCall.Plan, onApproval: (Boolean) -> Unit = {},
    palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    val document = tool.document
    if (document != null) {
        var expanded by remember(tool.id, document.fileId) { mutableStateOf(false) }
        var approvedLocally by remember(tool.id, document) { mutableStateOf(false) }
        val approved = document.approved || approvedLocally
        val rawId = document.fileId?.trim().orEmpty()
        val fileName = when {
            rawId.isEmpty() -> "plan-working.md"
            rawId.endsWith(".md") -> rawId
            else -> "plan-$rawId.md"
        }
        UiSurface(palette, Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    UiText(fileName, palette, muted = true, size = 12)
                    if (document.summary.isNotBlank()) UiButton(
                        if (expanded) "Hide detailed plan" else "Read detailed plan",
                        { expanded = !expanded }, palette)
                }
                UiDivider(palette)
                UiText(document.title, palette, weight = FontWeight.Medium)
                if (document.summary.isBlank()) UiText("No plan summary provided.", palette, muted = true, size = 12)
                else BasicText(document.summary.trim(), maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = palette.muted, fontSize = 13.sp, lineHeight = 19.sp))
                if (approved) UiText("Approved", palette.copy(text = palette.success), size = 12)
                else UiButton(
                    document.approveLabel,
                    {
                        approvedLocally = true
                        onApproval(true)
                    },
                    palette,
                    modifier = if (AgentUiTheme.sizing.isMobile) Modifier.fillMaxWidth() else Modifier,
                    primary = true,
                )
            }
        }
        return
    }
    UiSurface(palette, Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            UiText("Plan", palette, weight = FontWeight.Medium)
            tool.steps.forEachIndexed { index, step ->
                Row {
                    UiText(if (step.completed) "✓" else "${index + 1}.", palette.copy(text = if (step.completed) palette.success else palette.muted))
                    Spacer(Modifier.width(8.dp))
                    UiText(step.label, palette, muted = step.completed)
                }
            }
        }
    }
}

@Composable
fun ThinkingTool(tool: AgentToolCall.Thinking, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    var expanded by remember(tool.id) { mutableStateOf(false) }
    UiSurface(palette, Modifier.fillMaxWidth()) {
        Column {
            UiDisclosure("Thinking", expanded, { expanded = !expanded }, palette) {
                UiText(tool.text, palette, Modifier.padding(start = 16.dp, bottom = 8.dp), muted = true)
            }
            if (tool.status == ToolStatus.Running) TextShimmer("Thinking…", palette = palette)
        }
    }
}

@Composable
fun ThinkingCollapsed(onExpand: () -> Unit, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    UiText("› Thinking", palette, Modifier.clickable(onClick = onExpand), muted = true)
}

@Composable
fun McpTool(tool: AgentToolCall.Mcp, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    UiSurface(palette, Modifier.fillMaxWidth()) { ToolRowBase("MCP · ${tool.server}", tool.status == ToolStatus.Running, tool.action, expandable = true, palette = palette) {
        UiText(tool.result, palette, Modifier.padding(start = 16.dp), size = 12)
    } }
}

@Composable
fun GenericTool(tool: AgentToolCall.Generic, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    UiSurface(palette, Modifier.fillMaxWidth()) { GenericToolRow(tool.name, tool.detail, palette) }
}

@Composable
fun GenericToolRow(name: String, detail: String, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    ToolRowBase(name, false, detail, palette = palette)
}
