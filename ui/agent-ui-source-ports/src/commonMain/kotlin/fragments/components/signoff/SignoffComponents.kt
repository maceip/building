package fragments.components.signoff

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fragments.agentui.AgentUiTheme
import fragments.components.ReactUiPalette
import fragments.components.ReactUiPalettes
import fragments.components.UiButton
import fragments.components.UiDisclosure
import fragments.components.UiInput
import fragments.components.UiSurface
import fragments.components.UiText
import fragments.components.agentelements.Markdown as AgentMarkdown

enum class ApprovalStatus { Pending, Approved, Denied }
enum class RiskLevel { Low, Medium, High, Critical }
enum class ToolPhase { Streaming, Running, AwaitingApproval, Success, Error, Denied }
enum class AgentState { Idle, Thinking, Working, AwaitingApproval, Done, Stopped, Error }

@Immutable
data class ApprovalChoice(val decision: String, val reason: String? = null, val input: String? = null)

@Immutable
data class ToolCall(val id: String, val name: String, val input: String, val output: String = "", val phase: ToolPhase = ToolPhase.Running)

@Immutable
data class SignoffSource(val title: String, val detail: String = "", val url: String? = null)

@Immutable
sealed interface MessagePart {
    data class Text(val value: String) : MessagePart
    data class Reasoning(val value: String) : MessagePart
    data class Source(val value: SignoffSource) : MessagePart
    data class Tool(val value: ToolCall) : MessagePart
    data class Image(val alt: String, val bitmap: ImageBitmap?) : MessagePart
}

@Immutable
data class SignoffMessage(val id: String, val role: String, val parts: List<MessagePart>)

@Immutable
data class RunUsage(val inputTokens: Int? = null, val outputTokens: Int? = null, val cacheReadTokens: Int? = null, val reasoningTokens: Int? = null)

@Immutable
data class ModelPricing(val inputPerMillion: Double, val outputPerMillion: Double)

val LocalSignoffLabels = staticCompositionLocalOf<Map<String, String>> { emptyMap() }
val LocalMarkdownRenderer = staticCompositionLocalOf<(@Composable (String) -> Unit)?> { null }

@Composable
fun SignoffLabelsProvider(labels: Map<String, String>, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalSignoffLabels provides labels, content = content)

@Composable
fun WithLabels(labels: Map<String, String>?, content: @Composable () -> Unit) {
    val parent = LocalSignoffLabels.current
    CompositionLocalProvider(LocalSignoffLabels provides (parent + (labels ?: emptyMap())), content = content)
}

@Composable
fun MarkdownRendererProvider(renderer: (@Composable (String) -> Unit)?, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalMarkdownRenderer provides renderer, content = content)

@Composable
fun PlainText(text: String, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) = UiText(text, palette)

@Composable
fun CustomMarkdown(text: String, renderer: @Composable (String) -> Unit) = renderer(text)

@Composable
fun Markdown(text: String, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark, size: Int = 14,
    citationCount: Int = 0, onCitation: ((Int) -> Unit)? = null) {
    val custom = LocalMarkdownRenderer.current
    if (custom != null) custom(text) else AgentMarkdown(text, palette = palette, size = size,
        citationCount = citationCount, onCitation = onCitation)
}

@Composable
fun AgentMessage(message: SignoffMessage, streaming: Boolean = false, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) =
    AgentMessageBase(message, streaming, palette)

@Composable
fun AgentMessageBase(message: SignoffMessage, streaming: Boolean = false, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        message.parts.forEachIndexed { index, part ->
            when (part) {
                is MessagePart.Text -> Markdown(part.value + if (streaming && index == message.parts.lastIndex) " ▍" else "", palette)
                is MessagePart.Reasoning -> ReasoningBase(part.value, palette = palette)
                is MessagePart.Source -> Sources(listOf(part.value), palette = palette)
                is MessagePart.Tool -> ToolCallTimeline(listOf(part.value), palette = palette)
                is MessagePart.Image -> Img(part.bitmap, part.alt, palette = palette)
            }
        }
    }
}

@Composable
fun ApprovalCard(
    toolName: String,
    description: String = "",
    input: String = "",
    risk: RiskLevel? = null,
    status: ApprovalStatus? = null,
    decisions: List<String> = listOf("allow-once", "deny-once"),
    editable: Boolean = false,
    onDecide: (ApprovalChoice) -> Unit,
    palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark,
) {
    var localStatus by remember(toolName, input) { mutableStateOf(ApprovalStatus.Pending) }
    var reason by remember(toolName) { mutableStateOf("") }
    var editedInput by remember(toolName, input) { mutableStateOf(input) }
    val current = status ?: localStatus
    UiSurface(palette) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                UiText(toolName, palette, weight = FontWeight.SemiBold)
                if (risk != null) UiText(risk.name, palette.copy(text = if (risk >= RiskLevel.High) palette.danger else palette.muted), size = 12)
            }
            if (description.isNotBlank()) UiText(description, palette, muted = true)
            if (editable) UiInput(editedInput, { editedInput = it }, "Arguments", palette)
            else if (input.isNotBlank()) JsonView(input, palette)
            if (current == ApprovalStatus.Pending) {
                val isMobile = AgentUiTheme.sizing.isMobile
                UiInput(reason, { reason = it }, "Reason for denying (optional)", palette)
                if (isMobile) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        decisions.forEach { choice ->
                            val allowed = choice.startsWith("allow")
                            val label = when (choice) {
                                "allow-once" -> "Approve once"
                                "allow-session" -> "Approve for session"
                                "allow-always" -> "Always approve"
                                "deny-always" -> "Always deny"
                                else -> "Deny"
                            }
                            UiButton(
                                label,
                                {
                                    if (localStatus == ApprovalStatus.Pending) {
                                        localStatus = if (allowed) ApprovalStatus.Approved else ApprovalStatus.Denied
                                        onDecide(ApprovalChoice(choice, if (allowed) null else reason.ifBlank { null }, if (allowed) editedInput else null))
                                    }
                                },
                                palette,
                                modifier = Modifier.fillMaxWidth(),
                                primary = allowed && choice == "allow-once",
                            )
                        }
                    }
                } else {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        decisions.forEach { choice ->
                            val allowed = choice.startsWith("allow")
                            val label = when (choice) {
                                "allow-once" -> "Approve once"
                                "allow-session" -> "Approve for session"
                                "allow-always" -> "Always approve"
                                "deny-always" -> "Always deny"
                                else -> "Deny"
                            }
                            UiButton(label, {
                                if (localStatus == ApprovalStatus.Pending) {
                                    localStatus = if (allowed) ApprovalStatus.Approved else ApprovalStatus.Denied
                                    onDecide(ApprovalChoice(choice, if (allowed) null else reason.ifBlank { null }, if (allowed) editedInput else null))
                                }
                            }, palette, primary = allowed && choice == "allow-once")
                        }
                    }
                }
            } else UiText(if (current == ApprovalStatus.Approved) "Approved" else "Denied", palette.copy(text = if (current == ApprovalStatus.Approved) palette.success else palette.danger))
        }
    }
}

@Composable
fun ToolApprovalCard(tool: ToolCall, onDecide: (String, ApprovalChoice) -> Unit, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) =
    ApprovalCard(tool.name, input = tool.input, onDecide = { onDecide(tool.id, it) }, palette = palette)

@Composable
fun ToolApprovalBatch(tools: List<ToolCall>, onDecide: (String, ApprovalChoice) -> Unit, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    require(tools.all { it.id.isNotBlank() } && tools.map { it.id }.distinct().size == tools.size) {
        "Tool approval ids must be nonblank and unique"
    }
    val resolved = remember { mutableStateMapOf<String, ApprovalStatus>() }
    val pending = tools.filter { it.id !in resolved }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tools.forEach { tool ->
            androidx.compose.runtime.key(tool.id) {
                ApprovalCard(tool.name, input = tool.input, status = resolved[tool.id], onDecide = { choice ->
                    if (tool.id !in resolved) {
                        resolved[tool.id] = if (choice.decision.startsWith("allow")) ApprovalStatus.Approved else ApprovalStatus.Denied
                        onDecide(tool.id, choice)
                    }
                }, palette = palette)
            }
        }
        if (pending.size > 1) UiButton("Approve remaining (${pending.size})", {
            pending.forEach { tool ->
                if (tool.id !in resolved) {
                    resolved[tool.id] = ApprovalStatus.Approved
                    onDecide(tool.id, ApprovalChoice("allow-once", input = tool.input))
                }
            }
        }, palette, primary = true)
    }
}

@Composable
fun ReasoningBase(text: String, defaultExpanded: Boolean = false, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    var expanded by remember(text) { mutableStateOf(defaultExpanded) }
    UiDisclosure("Reasoning", expanded, { expanded = !expanded }, palette) { UiText(text, palette, Modifier.padding(start = 16.dp), muted = true) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Sources(items: List<SignoffSource>, onOpen: (SignoffSource) -> Unit = {},
    palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark, startIndex: Int = 1) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEachIndexed { index, source -> UiButton("${startIndex + index} ${source.title}", { onOpen(source) }, palette) }
    }
}

@Composable
fun ToolCallTimeline(tools: List<ToolCall>, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tools.forEach { tool ->
            var expanded by remember(tool.id) { mutableStateOf(tool.phase == ToolPhase.Error) }
            UiDisclosure("${tool.name} · ${tool.phase.name.lowercase().replaceFirstChar { it.uppercase() }}", expanded, { expanded = !expanded }, palette) {
                ToolCallDetails(tool, palette)
            }
        }
    }
}

@Composable
fun ToolCallDetails(tool: ToolCall, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (tool.input.isNotBlank()) JsonView(tool.input, palette)
        if (tool.output.isNotBlank()) JsonView(tool.output, palette)
    }
}

private fun formatFourDecimals(value: Double): String {
    val scaled = kotlin.math.round(value * 10_000.0).toLong()
    val sign = if (scaled < 0) "-" else ""
    val absScaled = kotlin.math.abs(scaled)
    val whole = absScaled / 10_000L
    val frac = (absScaled % 10_000L).toString().padStart(4, '0')
    return "$sign$whole.$frac"
}

@Composable
fun RunMeter(
    usage: RunUsage?,
    model: String = "",
    pricing: ModelPricing? = null,
    cost: Double? = null,
    ttftMs: Long? = null,
    durationMs: Long? = null,
    expanded: Boolean = false,
    live: Boolean = false,
    palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark,
) {
    val input = usage?.inputTokens
    val output = usage?.outputTokens
    val estimated = if (usage != null && pricing != null && input != null && output != null) {
        (input * pricing.inputPerMillion + output * pricing.outputPerMillion) / 1_000_000.0
    } else null
    val animatedInput by animateFloatAsState((input ?: 0).toFloat(), tween(200), label = "input tokens")
    val animatedOutput by animateFloatAsState((output ?: 0).toFloat(), tween(200), label = "output tokens")
    val items = listOf("↑ ${if (input == null) "–" else animatedInput.toInt()}", "↓ ${if (output == null) "–" else animatedOutput.toInt()}", "${(cost ?: estimated)?.let { "$" + formatFourDecimals(it) } ?: "–"}", "${ttftMs?.let { "${it}ms" } ?: "–"}", "${durationMs?.let { "${it}ms" } ?: "–"}")
    if (expanded) UiSurface(palette) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val heading = buildString {
                append(if (model.isBlank()) "Run" else "Run · $model")
                if (live) append(" · Live")
            }
            UiText(heading, palette, weight = FontWeight.SemiBold)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(15.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) { items.forEach { UiText(it, palette, mono = true, size = 12) } }
            if (input != null && output != null && input + output > 0) {
                val fraction = (input.toFloat() / (input + output)).coerceIn(0.001f, 0.999f)
                Row(Modifier.fillMaxWidth().background(palette.subtle, RoundedCornerShape(4.dp))) {
                    Box(Modifier.weight(fraction).height(8.dp).background(palette.accent, RoundedCornerShape(4.dp)))
                    Box(Modifier.weight(1f - fraction).height(8.dp).background(palette.success, RoundedCornerShape(4.dp)))
                }
                UiText("Input $input · Output $output", palette, muted = true, size = 11)
            }
            if (usage?.cacheReadTokens != null && input != null && input > 0) UiText("Cache hit ${(usage.cacheReadTokens * 100 / input)}%", palette, muted = true, size = 12)
            if (usage?.reasoningTokens != null) UiText("Reasoning ${usage.reasoningTokens} tokens", palette, muted = true, size = 12)
        }
    } else {
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { items.forEach { UiText(it, palette, mono = true, size = 12) } }
    }
}

@Composable
fun Img(bitmap: ImageBitmap?, alt: String, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    if (bitmap != null) Image(BitmapPainter(bitmap), alt, Modifier.fillMaxWidth()) else UiText(alt, palette, muted = true)
}

@Composable
fun Kbd(label: String, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    Box(Modifier.background(palette.surface, RoundedCornerShape(5.dp)).border(1.dp, palette.border, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 2.dp)) {
        UiText(label, palette, size = 11, mono = true)
    }
}

@Composable
fun LiveRegion(message: String) { Box(Modifier.size(0.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = message }) }

@Composable
fun CopyButton(text: String, label: String = "Copy", palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    var copied by remember(text) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    UiButton(if (copied) "Copied" else label, {
        try { clipboard.setText(AnnotatedString(text)); copied = true } catch (_: Exception) { copied = false }
    }, palette)
}

@Composable
fun CodeLines(lines: List<String>, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    Column(Modifier.fillMaxWidth().background(palette.background, RoundedCornerShape(6.dp)).padding(8.dp)) {
        lines.forEachIndexed { index, line -> UiText("${index + 1}  $line", palette, mono = true, size = 12) }
    }
}

@Composable
fun JsonView(json: String, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) = CodeLines(json.lines(), palette)
