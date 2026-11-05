package fragments.agentui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import fragments.chrome.AgentUiBaseTokens
import fragments.components.UiText

enum class ChatInputStatus { Ready, Working }

enum class ChatAttachmentKind { File, Image }

data class ChatAttachment(
    val id: String,
    val name: String,
    val detail: String = "",
    val kind: ChatAttachmentKind = ChatAttachmentKind.File,
)

internal fun attachmentKindForFile(file: File): ChatAttachmentKind =
    if (file.extension.lowercase() in setOf(
            "avif", "bmp", "gif", "heic", "heif", "jpeg", "jpg", "png", "svg", "tif", "tiff", "webp",
        )) ChatAttachmentKind.Image else ChatAttachmentKind.File

data class ChatSlashAction(val id: String, val label: String, val description: String = "")
data class ChatMention(val id: String, val label: String)

internal fun chooseFilesWithSwing(): List<File> = emptyList()

private class ComposerMenuPositioner : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        y = (anchorBounds.top - popupContentSize.height - 6).coerceAtLeast(0),
    )
}

/**
 * The kit's single recommended composer. Its layout follows prompt-kit's growing textarea and
 * actions row; attachments and send/stop behavior follow Creance. The caller owns and clears value.
 * Enter submits, Shift+Enter inserts a line, and Enter during IME composition does not submit.
 */
@Composable
fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Message",
    placeholder: String = "Ask anything…",
    status: ChatInputStatus = ChatInputStatus.Ready,
    onStop: (() -> Unit)? = null,
    attachments: List<ChatAttachment> = emptyList(),
    onRemoveAttachment: ((String) -> Unit)? = null,
    onAttachFiles: ((List<File>) -> Unit)? = null,
    filePicker: () -> List<File> = ::chooseFilesWithSwing,
    enabled: Boolean = true,
    palette: AgentUiPalette = AgentUiTheme.palette,
    slashActions: List<ChatSlashAction> = emptyList(),
    mentions: List<ChatMention> = emptyList(),
    onSlashAction: ((ChatSlashAction) -> Unit)? = null,
    /** Receives the selected agent and the full draft with the mention token inserted. */
    onMentionSelected: ((ChatMention, String) -> Unit)? = null,
    navigationActions: List<ChatQuickAction> = emptyList(),
    taskActions: List<ChatQuickAction> = emptyList(),
    navigationActionsTitle: String = "Where you want to go",
    taskActionsTitle: String = "What you want done",
    compactActions: Boolean = false,
    onRequestAttach: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) {
        if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    }
    val working = status == ChatInputStatus.Working
    val canSend = enabled && !working && (value.isNotBlank() || attachments.isNotEmpty())
    val shape = RoundedCornerShape(AgentUiBaseTokens.RadiusPill)
    val fieldInteractions = remember { MutableInteractionSource() }
    val focused by fieldInteractions.collectIsFocusedAsState()
    val outline by animateColorAsState(
        if (focused && enabled) palette.accent else palette.border,
        animationSpec = tween(120),
        label = "chat input focus outline",
    )
    val currentWord = field.text.take(field.selection.start.coerceIn(0, field.text.length)).substringAfterLast(' ')
        .substringAfterLast('\n')
    val slashQuery = currentWord.takeIf { it.startsWith('/') && field.text.take(field.selection.start).trimStart().startsWith('/') }
        ?.drop(1)
    val mentionQuery = currentWord.takeIf { it.startsWith('@') }?.drop(1)
    val visibleSlashActions = if (slashQuery != null) slashActions.filter {
        it.label.removePrefix("/").contains(slashQuery, ignoreCase = true)
    } else emptyList()
    val visibleMentions = if (mentionQuery != null) mentions.filter {
        it.label.contains(mentionQuery, ignoreCase = true) || it.id.contains(mentionQuery, ignoreCase = true)
    } else emptyList()
    var launcherOpen by remember { mutableStateOf(false) }
    val menuSlashActions = if (launcherOpen) slashActions else visibleSlashActions
    val menuToken = when {
        launcherOpen && slashActions.isNotEmpty() -> "launcher"
        visibleSlashActions.isNotEmpty() -> "slash:${field.text}:${field.selection.start}"
        visibleMentions.isNotEmpty() -> "mention:${field.text}:${field.selection.start}"
        else -> null
    }
    var dismissedMenuToken by remember { mutableStateOf<String?>(null) }
    val menuOpen = enabled && menuToken != null && menuToken != dismissedMenuToken
    val hasAttachmentTrigger = onAttachFiles != null || onRequestAttach != null
    val launcherHasAttachment = launcherOpen && hasAttachmentTrigger
    val menuActionOffset = if (launcherHasAttachment) 1 else 0
    val menuSize = if (menuSlashActions.isNotEmpty())
        menuSlashActions.size + menuActionOffset else visibleMentions.size
    var menuIndex by remember(slashQuery, mentionQuery, launcherOpen) { mutableStateOf(0) }

    fun triggerAttachmentPicker() {
        if (onRequestAttach != null) {
            onRequestAttach()
        } else {
            onAttachFiles?.let { callback -> filePicker().takeIf { it.isNotEmpty() }?.let(callback) }
        }
    }

    fun chooseMenuItem(index: Int) {
        if (menuSlashActions.isNotEmpty()) {
            if (launcherHasAttachment && index == 0) {
                dismissedMenuToken = menuToken
                launcherOpen = false
                triggerAttachmentPicker()
                return
            }
            val action = menuSlashActions.getOrNull(index - menuActionOffset) ?: return
            dismissedMenuToken = menuToken
            launcherOpen = false
            if (onSlashAction != null) onSlashAction(action)
            else onValueChange(action.label + " ")
        } else {
            val mention = visibleMentions.getOrNull(index) ?: return
            dismissedMenuToken = menuToken
            val start = (field.selection.start - currentWord.length).coerceAtLeast(0)
            var end = field.selection.start.coerceAtMost(field.text.length)
            while (end < field.text.length && !field.text[end].isWhitespace()) end++
            val separator = if (end == field.text.length || !field.text[end].isWhitespace()) " " else ""
            val updatedDraft = field.text.replaceRange(start, end, "@${mention.label}$separator")
            if (onMentionSelected != null) onMentionSelected(mention, updatedDraft)
            else onValueChange(updatedDraft)
        }
    }

    fun submit() {
        if (canSend && field.composition == null) onSend(value.trim())
    }

    Column(
        modifier.fillMaxWidth()
            .testTag("agent-chat-input")
            .background(palette.composerSurface, shape)
            .border(AgentUiBaseTokens.HairlineBorder, outline, shape)
            .padding(if (compactActions) 10.dp else AgentUiBaseTokens.Space2),
        verticalArrangement = Arrangement.spacedBy(AgentUiBaseTokens.Space1),
    ) {
        if (menuOpen) Popup(popupPositionProvider = remember { ComposerMenuPositioner() }, onDismissRequest = {
            dismissedMenuToken = menuToken
            launcherOpen = false
        }) {
            Column(
                Modifier.widthIn(min = 230.dp, max = 340.dp)
                    .background(palette.surface, RoundedCornerShape(10.dp))
                    .border(1.dp, palette.border, RoundedCornerShape(10.dp))
                    .padding(5.dp)
                    .testTag(if (menuSlashActions.isNotEmpty()) "agent-chat-slash-menu" else "agent-chat-mention-menu"),
            ) {
                if (launcherHasAttachment) Row(
                    Modifier.fillMaxWidth()
                        .background(if (menuIndex == 0) palette.subtle else palette.surface, RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button) { chooseMenuItem(0) }
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                        .testTag("agent-chat-attach"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AgentIcon(AgentIconId.Plus, size = 14.dp, color = palette.text)
                    UiText("Attach files", palette, size = 13)
                }
                if (menuSlashActions.isNotEmpty()) menuSlashActions.forEachIndexed { index, action ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (index + menuActionOffset == menuIndex) palette.subtle else palette.surface, RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button) { chooseMenuItem(index + menuActionOffset) }
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                            .testTag("agent-chat-slash-${action.id}"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        UiText(action.label, palette, size = 13)
                        if (action.description.isNotBlank()) UiText(action.description, palette, muted = true, size = 11)
                    }
                } else visibleMentions.forEachIndexed { index, mention ->
                    Box(
                        Modifier.fillMaxWidth()
                            .background(if (index == menuIndex) palette.subtle else palette.surface, RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button) { chooseMenuItem(index) }
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                            .testTag("agent-chat-mention-${mention.id}"),
                    ) { UiText("@${mention.label}", palette, size = 13) }
                }
            }
        }
        if (attachments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AgentUiBaseTokens.Space1),
            ) {
                attachments.forEach { attachment ->
                    Row(
                        Modifier.background(palette.subtle, RoundedCornerShape(AgentUiBaseTokens.RadiusChip))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        AgentIcon(
                            if (attachment.kind == ChatAttachmentKind.Image) AgentIconId.Image else AgentIconId.File,
                            Modifier.semantics {
                                contentDescription = if (attachment.kind == ChatAttachmentKind.Image)
                                    "Image attachment" else "File attachment"
                            },
                            size = 14.dp,
                            color = palette.muted,
                        )
                        UiText(attachment.name, palette, size = 12)
                        if (attachment.detail.isNotBlank()) UiText(attachment.detail, palette, muted = true, size = 11)
                        if (onRemoveAttachment != null) Box(
                            Modifier.size(20.dp)
                                .clickable(enabled = enabled, role = Role.Button) { onRemoveAttachment(attachment.id) }
                                .semantics { contentDescription = "Remove ${attachment.name}" },
                            contentAlignment = Alignment.Center,
                        ) { AgentIcon(AgentIconId.Close, size = 13.dp, color = palette.muted) }
                    }
                }
            }
        }

        if (navigationActions.isNotEmpty() || taskActions.isNotEmpty()) {
            AgentDualActionRows(
                navigationActions = navigationActions,
                taskActions = taskActions,
                navigationTitle = navigationActionsTitle,
                taskTitle = taskActionsTitle,
                compact = compactActions,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                palette = palette,
            )
        }

        Row(
            Modifier.fillMaxWidth().heightIn(min = if (compactActions) 44.dp else 72.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AgentUiBaseTokens.Space2),
        ) {
            if (hasAttachmentTrigger && slashActions.isNotEmpty()) {
                Box(
                    Modifier.size(40.dp)
                        .clickable(enabled = enabled, role = Role.Button) {
                            dismissedMenuToken = null
                            launcherOpen = !launcherOpen
                        }
                        .semantics { contentDescription = "Open chat actions" }
                        .testTag("agent-chat-actions"),
                    contentAlignment = Alignment.Center,
                ) { AgentIcon(AgentIconId.Plus, size = 22.dp, color = palette.text) }
            } else if (hasAttachmentTrigger) {
                Box(
                    Modifier.size(40.dp)
                        .clickable(enabled = enabled, role = Role.Button) {
                            triggerAttachmentPicker()
                        }
                        .semantics { contentDescription = "Attach files" }
                        .testTag("agent-chat-attach"),
                    contentAlignment = Alignment.Center,
                ) { AgentIcon(AgentIconId.Plus, size = 22.dp, color = palette.text) }
            } else if (slashActions.isNotEmpty()) {
                Box(
                    Modifier.size(40.dp)
                        .clickable(enabled = enabled, role = Role.Button) {
                            dismissedMenuToken = null
                            launcherOpen = !launcherOpen
                        }
                        .semantics { contentDescription = "Open chat actions" }
                        .testTag("agent-chat-actions"),
                    contentAlignment = Alignment.Center,
                ) { AgentIcon(AgentIconId.Plus, size = 22.dp, color = palette.text) }
            }
            BasicTextField(
                value = field,
                onValueChange = { next ->
                    field = next
                    launcherOpen = false
                    onValueChange(next.text)
                },
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 44.dp, max = 240.dp)
                    .padding(horizontal = 4.dp, vertical = 7.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && menuOpen && event.key == Key.Escape) {
                            dismissedMenuToken = menuToken
                            launcherOpen = false
                            true
                        } else if (event.type == KeyEventType.KeyDown && menuOpen && event.key == Key.DirectionDown) {
                            menuIndex = (menuIndex + 1) % menuSize
                            true
                        } else if (event.type == KeyEventType.KeyDown && menuOpen && event.key == Key.DirectionUp) {
                            menuIndex = (menuIndex - 1 + menuSize) % menuSize
                            true
                        } else if (event.type == KeyEventType.KeyDown && menuOpen && event.key == Key.Enter &&
                            field.composition == null
                        ) {
                            chooseMenuItem(menuIndex)
                            true
                        } else if (event.type == KeyEventType.KeyDown && event.key == Key.Enter &&
                            !event.isShiftPressed && field.composition == null
                        ) {
                            submit()
                            true
                        } else false
                    }
                    .semantics { contentDescription = label }
                    .testTag("agent-chat-input-field"),
                textStyle = TextStyle(color = palette.text, fontSize = 15.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(palette.accent),
                interactionSource = fieldInteractions,
                minLines = 1,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                decorationBox = { inner ->
                    Box {
                        if (field.text.isEmpty()) UiText(placeholder, palette, muted = true, size = 15)
                        inner()
                    }
                },
            )
            actions()
            val buttonEnabled = enabled && if (working) onStop != null else canSend
            val buttonColor = if (buttonEnabled) palette.accent else palette.accent.copy(alpha = 0.28f)
            Box(
                Modifier.size(44.dp)
                    .background(buttonColor, CircleShape)
                    .clickable(enabled = buttonEnabled, role = Role.Button) { if (working) onStop?.invoke() else submit() }
                    .semantics { contentDescription = if (working) "Stop generation" else "Send message" }
                    .testTag("agent-chat-submit"),
                contentAlignment = Alignment.Center,
            ) {
                if (working) AgentIcon(AgentIconId.Stop, size = 18.dp, color = palette.accentText)
                else AgentIcon(AgentIconId.ArrowUp, size = 20.dp, color = if (buttonEnabled) palette.accentText else palette.muted)
            }
        }
    }
}
