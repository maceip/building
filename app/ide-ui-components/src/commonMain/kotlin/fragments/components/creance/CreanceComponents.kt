package fragments.components.creance

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fragments.components.ReactUiPalette
import fragments.components.ReactUiPalettes
import fragments.components.UiButton
import fragments.components.UiDisclosure
import fragments.components.UiInput
import fragments.components.UiSurface
import fragments.components.UiText
import fragments.components.creance.icons.StopIcon
import fragments.components.creance.icons.SparkIcon
import fragments.components.creance.icons.ShieldIcon
import fragments.components.creance.icons.PauseIcon
import fragments.components.creance.icons.CrossIcon
import fragments.components.creance.icons.AlertIcon
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
private fun currentCreanceMillis(): Long = Clock.System.now().toEpochMilliseconds()

private fun chooseCreanceFiles(): List<String> = emptyList()

enum class MessageOrigin { User, Assistant }
enum class MessageStatus { Pending, Streaming, Done }
enum class ChatStatus { Ready, Working }
enum class InboxKind { Approval, Question, FailedRun, Review }
enum class InboxUrgency { High, Normal, Low }

@Immutable
data class Source(val title: String, val detail: String = "", val href: String? = null)

@Immutable
data class ChatMessage(
    val id: String,
    val from: MessageOrigin,
    val content: String,
    val status: MessageStatus = MessageStatus.Done,
    val reasoning: String? = null,
    val thinkingSeconds: Int? = null,
    val sources: List<Source> = emptyList(),
)

@Immutable
data class InboxItem(
    val id: String,
    val kind: InboxKind,
    val title: String,
    val agentName: String,
    val createdAt: Long,
    val urgency: InboxUrgency,
    val detail: String = "",
)

@Immutable
data class PromptAttachment(val id: String, val name: String, val detail: String = "")

fun sortInbox(items: List<InboxItem>): List<InboxItem> = items.sortedWith(compareBy<InboxItem> { it.urgency.ordinal }.thenBy { it.createdAt })

/** Creance's conversation shell: its messages, starters and composer share one 760dp column. */
@Composable
fun Chat(
    messages: List<ChatMessage>,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    agentName: String = "Assistant",
    status: ChatStatus = ChatStatus.Ready,
    onStop: (() -> Unit)? = null,
    suggestions: List<String> = emptyList(),
    palette: ReactUiPalette = ReactUiPalettes.CreanceDark,
    trailingContent: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxSize().background(palette.background)) {
        MessageList(messages, agentName, Modifier.weight(1f), palette, trailingContent)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (suggestions.isNotEmpty()) Suggestions(suggestions, onSend, palette = palette)
            PromptInput("Message $agentName", onSend, status = status, onStop = onStop, palette = palette)
        }
        val last = messages.lastOrNull()
        LiveRegion(if (last?.from == MessageOrigin.Assistant && last.status == MessageStatus.Done) "$agentName replied" else "")
    }
}

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    agentName: String = "Assistant",
    modifier: Modifier = Modifier,
    palette: ReactUiPalette = ReactUiPalettes.CreanceDark,
    trailingContent: @Composable () -> Unit = {},
) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        items(messages, key = { it.id }) { item ->
            Message(item.from, item.content, if (item.from == MessageOrigin.Assistant) agentName else "You", item.status, palette) {
                if (item.reasoning != null) Reasoning(item.reasoning, item.thinkingSeconds, item.status != MessageStatus.Done, palette = palette)
                if (item.sources.isNotEmpty()) Sources(item.sources, palette = palette)
            }
        }
        item(key = "trailing") { trailingContent() }
    }
}

@Composable
fun Message(
    from: MessageOrigin,
    content: String,
    name: String = if (from == MessageOrigin.User) "You" else "Assistant",
    status: MessageStatus = MessageStatus.Done,
    palette: ReactUiPalette = ReactUiPalettes.CreanceDark,
    extra: @Composable () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (from == MessageOrigin.User) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.fillMaxWidth(if (from == MessageOrigin.User) 0.78f else 1f)
                .background(if (from == MessageOrigin.User) palette.subtle else palette.surface, RoundedCornerShape(palette.radius))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (from == MessageOrigin.Assistant) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Avatar(name, palette = palette)
                UiText(name, palette, size = 12, weight = FontWeight.SemiBold)
            }
            if (status == MessageStatus.Pending) LoadingDots(palette = palette)
            if (content.isNotBlank()) UiText(content + if (status == MessageStatus.Streaming) " ▍" else "", palette, size = 15)
            extra()
        }
    }
}

@Composable
fun Button(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, palette: ReactUiPalette = ReactUiPalettes.CreanceDark) =
    UiButton(label, onClick, palette, modifier, primary)

@Composable
fun LoadingDots(modifier: Modifier = Modifier, palette: ReactUiPalette = ReactUiPalettes.CreanceDark) {
    val transition = rememberInfiniteTransition(label = "creance dots")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(450, delayMillis = index * 150), RepeatMode.Reverse), label = "dot $index")
            Box(Modifier.size(5.dp).alpha(alpha).background(palette.muted, CircleShape))
        }
    }
}

@Composable
fun PromptInput(
    label: String,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    status: ChatStatus = ChatStatus.Ready,
    onStop: (() -> Unit)? = null,
    placeholder: String = "Message",
    attachments: List<PromptAttachment> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {},
    onAttach: ((List<String>) -> Unit)? = null,
    palette: ReactUiPalette = ReactUiPalettes.CreanceDark,
    value: String? = null,
    onValueChange: ((String) -> Unit)? = null,
    disabled: Boolean = false,
) {
    var ownDraft by remember { mutableStateOf("") }
    val draft = value ?: ownDraft
    fun change(next: String) {
        ownDraft = next
        onValueChange?.invoke(next)
    }
    fun submit() {
        if (!disabled && draft.isNotBlank()) {
            onSubmit(draft.trim())
            change("")
        }
    }
    UiSurface(palette, modifier.fillMaxWidth(), palette.radius) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (attachments.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                attachments.forEach { attachment ->
                    UiButton(attachment.name + " ×", { onRemoveAttachment(attachment.id) }, palette)
                }
            }
            UiInput(draft, ::change, placeholder, palette, Modifier.fillMaxWidth().onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && !it.isShiftPressed) {
                    submit()
                    true
                } else false
            })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onAttach != null) UiButton("+", {
                        chooseCreanceFiles().takeIf { it.isNotEmpty() }?.let(onAttach)
                    }, palette, enabled = !disabled)
                    UiText("Enter to send · Shift+Enter for a new line", palette, muted = true, size = 12)
                }
                UiButton(if (status == ChatStatus.Working) "Stop" else "Send", {
                    if (status == ChatStatus.Working) onStop?.invoke() else submit()
                }, palette, primary = true, enabled = !disabled && (status == ChatStatus.Working || draft.isNotBlank()))
            }
        }
    }
}

@Composable
fun Reasoning(text: String, seconds: Int? = null, thinking: Boolean = false, defaultOpen: Boolean = false, palette: ReactUiPalette = ReactUiPalettes.CreanceDark) {
    var expanded by remember(text) { mutableStateOf(defaultOpen) }
    val title = if (thinking) "Thinking" else if (seconds == null) "Thought" else "Thought for $seconds ${if (seconds == 1) "second" else "seconds"}"
    Row(verticalAlignment = Alignment.CenterVertically) {
        SparkIcon(size = 14.dp, color = palette.muted)
        Spacer(Modifier.width(5.dp))
        Column { UiDisclosure(title, expanded, { expanded = !expanded }, palette) { UiText(text, palette, Modifier.padding(start = 16.dp), muted = true) } }
    }
}

@Composable
fun Sources(items: List<Source>, onOpen: (Source) -> Unit = {}, palette: ReactUiPalette = ReactUiPalettes.CreanceDark) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        items.forEachIndexed { index, source ->
            Row(Modifier.fillMaxWidth().background(palette.subtle, RoundedCornerShape(8.dp)).clickable { onOpen(source) }.padding(8.dp)) {
                UiText("${index + 1}", palette.copy(text = palette.accent), weight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                Column { UiText(source.title, palette, size = 13); if (source.detail.isNotBlank()) UiText(source.detail, palette, muted = true, size = 12) }
            }
        }
    }
}

@Composable
fun Citation(index: Int, onClick: () -> Unit, palette: ReactUiPalette = ReactUiPalettes.CreanceDark) {
    UiText(index.toString(), palette.copy(text = palette.accent), Modifier.clickable(onClick = onClick), size = 12)
}

@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, palette: ReactUiPalette = ReactUiPalettes.CreanceDark) {
    Box(modifier.size(25.dp).background(palette.subtle, CircleShape), contentAlignment = Alignment.Center) {
        UiText(name.take(1).uppercase(), palette, size = 12, weight = FontWeight.SemiBold)
    }
}

@Composable
fun Suggestions(items: List<String>, onSelect: (String) -> Unit, modifier: Modifier = Modifier, palette: ReactUiPalette = ReactUiPalettes.CreanceDark) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(7.dp)) { items.forEach { UiButton(it, { onSelect(it) }, palette) } }
}

@Composable
fun NeedsYouInbox(
    items: List<InboxItem>,
    onOpen: (InboxItem) -> Unit,
    modifier: Modifier = Modifier,
    now: Long = currentCreanceMillis(),
    title: String = "Needs you",
    emptyText: String = "Nothing is waiting on you.",
    palette: ReactUiPalette = ReactUiPalettes.CreanceDark,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        UiText("$title  ${items.size}", palette, size = 20, weight = FontWeight.SemiBold)
        if (items.isEmpty()) UiText(emptyText, palette, muted = true)
        sortInbox(items).forEach { item ->
            UiSurface(palette, Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(item) }) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        when (item.kind) {
                            InboxKind.Approval -> ShieldIcon(size = 14.dp, color = palette.muted)
                            InboxKind.Question -> PauseIcon(size = 14.dp, color = palette.muted)
                            InboxKind.FailedRun -> CrossIcon(size = 14.dp, color = palette.danger)
                            InboxKind.Review -> AlertIcon(size = 14.dp, color = palette.muted)
                        }
                        UiText(item.kind.name + if (item.urgency == InboxUrgency.High) " · Urgent" else "", palette.copy(text = if (item.urgency == InboxUrgency.High) palette.danger else palette.muted), size = 12)
                    }
                    UiText(item.title, palette, weight = FontWeight.SemiBold)
                    if (item.detail.isNotBlank()) UiText(item.detail, palette, muted = true, size = 12)
                    UiText("${item.agentName} · waiting ${((now - item.createdAt).coerceAtLeast(0) / 60_000)}m", palette, muted = true, size = 12)
                }
            }
        }
    }
}

/** A release before the hold interval cancels the stop, matching Creance's KillSwitch. */
@Composable
fun KillSwitch(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    holdMs: Long = 1_000,
    stopped: Boolean = false,
    onResume: (() -> Unit)? = null,
    label: String = "Hold to stop",
    palette: ReactUiPalette = ReactUiPalettes.CreanceDark,
) {
    var holding by remember { mutableStateOf(false) }
    var completedHold by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(if (holding) 1f else 0f, tween(holdMs.toInt()), label = "kill switch hold")
    LaunchedEffect(holding, stopped) {
        if (holding && !stopped) {
            delay(holdMs)
            if (holding && !completedHold) { completedHold = true; holding = false; onStop() }
        }
    }
    if (stopped) Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UiText("Agent stopped", palette.copy(text = palette.danger))
        if (onResume != null) UiButton("Resume run", onResume, palette)
    } else Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.widthIn(min = 120.dp).height(36.dp).background(palette.subtle, RoundedCornerShape(8.dp))
                .pointerInput(holdMs) { detectTapGestures(onPress = { if (!completedHold) holding = true; tryAwaitRelease(); holding = false; completedHold = false }) }
                .onPreviewKeyEvent {
                    if (it.key == Key.Enter || it.key == Key.Spacebar) {
                        if (it.type == KeyEventType.KeyUp) { holding = false; completedHold = false }
                        else if (!completedHold) holding = true
                        true
                    } else false
                }
                .focusable()
                .semantics {
                    role = Role.Button
                    contentDescription = label
                    stateDescription = if (holding) "Hold in progress" else "Hold to stop"
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(palette.danger, RoundedCornerShape(8.dp)))
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StopIcon(size = 15.dp, color = palette.text)
                UiText(if (holding) "Keep holding" else label, palette)
            }
        }
        UiText("Press and hold for ${holdMs / 1000.0} seconds.", palette, muted = true, size = 12)
    }
}

@Composable
fun LiveRegion(message: String) {
    Box(Modifier.size(0.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = message })
}

@Composable
fun VisuallyHidden(label: String) {
    Box(Modifier.size(0.dp).semantics { contentDescription = label })
}
