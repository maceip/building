package fragments.components.agentelements

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.foundation.Image
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fragments.agentui.AgentUiTheme
import fragments.components.ReactUiPalette
import fragments.components.ReactUiPalettes
import fragments.components.UiButton
import fragments.components.UiInput
import fragments.components.UiSurface
import fragments.components.UiText

@Immutable
data class AgentChatMessage(
    val id: String,
    val role: AgentMessageRole,
    val text: String,
    val tool: AgentToolCall? = null,
)

enum class AgentMessageRole { User, Assistant, System }
enum class AgentChatStatus { Ready, Submitted, Streaming }
enum class EmptyStatePosition { Default, Center }
enum class EmptySuggestionsPlacement { Input, Empty, Both }

@Immutable
data class SuggestionItem(val label: String, val value: String = label)

@Immutable
data class FileAttachmentModel(val id: String, val name: String, val sizeLabel: String = "")

/** Compose port of Agent Elements' AgentChat: thread, suggestions, composer and stop state. */
@Composable
fun AgentChat(
    messages: List<AgentChatMessage>,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    status: AgentChatStatus = AgentChatStatus.Ready,
    onStop: (() -> Unit)? = null,
    suggestions: List<SuggestionItem> = emptyList(),
    onToolApproval: (String, Boolean) -> Unit = { _, _ -> },
    palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    attachments: List<FileAttachmentModel> = emptyList(),
    onAttach: (() -> Unit)? = null,
    onRemoveAttachment: (String) -> Unit = {},
    emptyStatePosition: EmptyStatePosition = EmptyStatePosition.Default,
    emptySuggestionsPlacement: EmptySuggestionsPlacement = EmptySuggestionsPlacement.Input,
) {
    var draft by remember { mutableStateOf("") }
    val centered = messages.isEmpty() && error == null && emptyStatePosition == EmptyStatePosition.Center
    val inputSuggestions = if (emptySuggestionsPlacement != EmptySuggestionsPlacement.Empty) suggestions else emptyList()
    val emptySuggestions = if (emptySuggestionsPlacement != EmptySuggestionsPlacement.Input) suggestions else emptyList()
    val inputBar: @Composable () -> Unit = {
        InputBar(
            value = draft,
            onValueChange = { draft = it },
            onSend = { if (draft.isNotBlank()) { onSend(draft.trim()); draft = "" } },
            onStop = onStop,
            status = status,
            suggestions = inputSuggestions,
            attachments = attachments,
            onAttach = onAttach,
            onRemoveAttachment = onRemoveAttachment,
            palette = palette,
        )
    }
    Column(modifier.fillMaxSize().background(palette.background)) {
        if (centered) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                    if (emptySuggestions.isNotEmpty()) Suggestions(emptySuggestions, onSelect = { draft = it.value }, palette = palette)
                    inputBar()
                }
            }
        } else {
            if (messages.isEmpty()) Spacer(Modifier.weight(1f))
            else MessageList(messages, onToolApproval, Modifier.weight(1f), palette)
            if (error != null) ErrorMessage(error, onRetry, palette)
            inputBar()
        }
    }
}

@Composable
fun AnAgentChat(
    messages: List<AgentChatMessage>,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    status: AgentChatStatus = AgentChatStatus.Ready,
    onStop: (() -> Unit)? = null,
    palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark,
) = AgentChat(messages, onSend, modifier, status, onStop, palette = palette)

@Composable
fun MessageList(
    messages: List<AgentChatMessage>,
    onToolApproval: (String, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark,
) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        items(messages, key = { it.id }) { message ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                    when (message.role) {
                        AgentMessageRole.User -> UserMessage(message.text, palette = palette)
                        AgentMessageRole.Assistant -> Column {
                            if (message.text.isNotBlank()) Markdown(message.text, palette = palette)
                            message.tool?.let { ToolRenderer(it, { approved -> onToolApproval(it.id, approved) }, palette = palette) }
                        }
                        AgentMessageRole.System -> UiText(message.text, palette, muted = true, size = 12)
                    }
                }
            }
        }
    }
}

@Composable
fun UserMessage(text: String, modifier: Modifier = Modifier, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(Modifier.background(palette.subtle, RoundedCornerShape(palette.radius)).padding(horizontal = 14.dp, vertical = 10.dp)) {
            UiText(text, palette)
        }
    }
}

@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: (() -> Unit)? = null,
    status: AgentChatStatus = AgentChatStatus.Ready,
    suggestions: List<SuggestionItem> = emptyList(),
    attachments: List<FileAttachmentModel> = emptyList(),
    onAttach: (() -> Unit)? = null,
    onRemoveAttachment: (String) -> Unit = {},
    palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (suggestions.isNotEmpty()) Suggestions(suggestions, { onValueChange(it.value) }, palette = palette)
        if (attachments.isNotEmpty()) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                attachments.forEach { FileAttachment(it, { onRemoveAttachment(it.id) }, palette = palette) }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onAttach != null) AttachmentButton(onAttach, palette = palette)
            UiInput(
                value,
                onValueChange,
                "Send a message...",
                palette,
                Modifier.weight(1f).onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && !it.isShiftPressed) {
                        if (value.isNotBlank()) onSend()
                        true
                    } else false
                },
            )
            SendButton(
                status = status,
                enabled = value.isNotBlank() || status == AgentChatStatus.Streaming,
                onClick = if (status == AgentChatStatus.Streaming) onStop ?: {} else onSend,
                palette = palette,
            )
        }
    }
    }
}

@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark,
    size: Int = 14, citationCount: Int = 0, onCitation: ((Int) -> Unit)? = null) {
    ParsedMarkdown(text, modifier, palette, size, citationCount, onCitation)
}

@Composable
fun ErrorMessage(message: String, onRetry: (() -> Unit)? = null, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    UiSurface(palette) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UiText(message, palette, Modifier.weight(1f))
            if (onRetry != null) UiButton("Retry", onRetry, palette)
        }
    }
}

@Composable
fun ImageLightbox(image: ImageBitmap?, description: String, onClose: () -> Unit, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    Box(Modifier.fillMaxSize().background(palette.background.copy(alpha = 0.96f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (image != null) Image(BitmapPainter(image), description, Modifier.fillMaxWidth(0.85f))
            UiText(description, palette, Modifier.padding(12.dp))
            UiButton("Close", onClose, palette)
        }
    }
}

@Composable
fun TextShimmer(text: String, modifier: Modifier = Modifier, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    val transition = rememberInfiniteTransition(label = "text shimmer")
    val alpha by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "shimmer alpha")
    UiText(text, palette, modifier.alpha(alpha))
}

@Composable
fun SpiralLoader(modifier: Modifier = Modifier, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    val transition = rememberInfiniteTransition(label = "spiral loader")
    val rotation by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1100)), label = "rotation")
    Canvas(modifier.size(20.dp)) { drawArc(palette.accent, rotation, 270f, false, style = Stroke(width = 2.dp.toPx())) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Suggestions(items: List<SuggestionItem>, onSelect: (SuggestionItem) -> Unit, modifier: Modifier = Modifier, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item -> UiButton(item.label, { onSelect(item) }, palette) }
    }
}

@Composable
fun AttachmentButton(onClick: () -> Unit, modifier: Modifier = Modifier, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) =
    UiButton("+", onClick, palette, modifier)

@Composable
fun FileAttachment(file: FileAttachmentModel, onRemove: () -> Unit, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(palette.subtle).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        UiText(file.name + if (file.sizeLabel.isNotBlank()) " · ${file.sizeLabel}" else "", palette, size = 12)
        Spacer(Modifier.width(5.dp))
        UiText("×", palette, Modifier.clickable(onClick = onRemove), size = 14)
    }
}

@Composable
fun SendButton(status: AgentChatStatus, enabled: Boolean, onClick: () -> Unit, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) =
    UiButton(if (status == AgentChatStatus.Streaming) "Stop" else "↑", onClick, palette, primary = true, enabled = enabled)

@Immutable
data class QuestionOption(val id: String, val label: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuestionPrompt(question: String, options: List<QuestionOption>, selected: String?, onSelect: (String) -> Unit, palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark) {
    val isMobile = AgentUiTheme.sizing.isMobile
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        UiText(question, palette, weight = FontWeight.Medium)
        if (isMobile) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { option ->
                    UiButton(option.label, { onSelect(option.id) }, palette, modifier = Modifier.fillMaxWidth(), primary = selected == option.id)
                }
            }
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                options.forEach { option -> UiButton(option.label, { onSelect(option.id) }, palette, primary = selected == option.id) }
            }
        }
    }
}

@Composable
fun QuestionTool(question: String, options: List<QuestionOption>, onSubmit: (String) -> Unit,
    palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark, submitting: Boolean = false) {
    var selected by remember(question, options) { mutableStateOf<String?>(null) }
    var freeText by remember(question, options) { mutableStateOf("") }
    UiSurface(palette) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (submitting) {
                UiText(question, palette, weight = FontWeight.Medium)
                UiText("Submitting answer…", palette, muted = true)
            } else {
                QuestionPrompt(question, options, selected, { selected = it }, palette)
                UiInput(freeText, { freeText = it }, "Other answer", palette)
                UiButton("Submit", { onSubmit(freeText.ifBlank { selected.orEmpty() }) }, palette,
                    primary = true, enabled = freeText.isNotBlank() || selected != null)
            }
        }
    }
}
