package fragments.agentui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import fragments.components.UiButton
import fragments.components.UiText
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.roundToInt

/** Host-owned renderers are installed once and disposed with their feature. */
fun interface MessagePartRenderer {
    @Composable fun Render(part: ConversationPart.Custom)
}

class MessagePartRegistry {
    private val renderers = mutableStateMapOf<String, MessagePartRenderer>()
    fun register(kind: String, renderer: MessagePartRenderer): AutoCloseable {
        require(kind.isNotBlank())
        require(kind !in renderers) { "Renderer already registered: $kind" }
        renderers[kind] = renderer
        return AutoCloseable { if (renderers[kind] === renderer) renderers.remove(kind) }
    }
    fun renderer(kind: String): MessagePartRenderer? = renderers[kind]
}

data class BrowserCardPresentation(
    val subtitle: String,
    val actionLabel: String = "Open preview",
    val description: BrowserActionDescription? = null,
    val paused: Boolean = false,
)
data class BrowserHandleBadge(val id: String, val label: String)
data class BrowserPlaybackControls(
    val isLive: Boolean,
    val stepIndex: Int,
    val stepCount: Int,
    val onToggleLive: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onScrub: (Int) -> Unit,
)

/** The conversation owns its identity and run state, even when mounted beside another pane. */
@Composable
fun ConversationHeader(
    participant: AgentParticipant?,
    runState: RunState?,
    title: String = "Chats",
    modifier: Modifier = Modifier,
    palette: AgentUiPalette = AgentUiTheme.palette,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().height(52.dp).background(palette.background)
            .padding(horizontal = 18.dp).testTag("conversation-header"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
        if (participant != null) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("conversation-header-identity")) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(15.dp))
                    .background(palette.subtle), contentAlignment = Alignment.Center) {
                    BasicText(participant.name.take(1).uppercase(),
                        style = TextStyle(color = palette.text, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold))
                }
                Column {
                    BasicText(participant.name, Modifier.widthIn(max = 150.dp), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = TextStyle(color = palette.text,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
                    val status = when (runState) {
                        RunState.Working -> "Working"
                        RunState.AwaitingApproval -> "Needs approval"
                        RunState.Failed -> "Failed"
                        RunState.Interrupted -> "Interrupted"
                        RunState.Complete, null -> "Ready"
                    }
                    BasicText(status,
                        style = TextStyle(color = palette.muted, fontSize = 11.sp))
                }
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, content = actions)
    }
}

/**
 * One embeddable chat panel, independent of window navigation. The handle owns state; this panel
 * may be mounted in more than one pane without creating another agent session.
 */
@Composable
fun AgentChatPanel(
    handle: ConversationHandle,
    modifier: Modifier = Modifier,
    palette: AgentUiPalette = AgentUiTheme.palette,
    rendererRegistry: MessagePartRegistry = remember { MessagePartRegistry() },
    browserPreview: @Composable ((ConversationPart.Browser) -> Unit)? = null,
    browserPresentation: ((ConversationPart.Browser) -> BrowserCardPresentation)? = null,
    browserPlayback: ((ConversationPart.Browser) -> BrowserPlaybackControls?)? = null,
    initialBrowser: ConversationPart.Browser? = null,
    onOpenBrowser: ((ConversationPart.Browser) -> Unit)? = null,
    onOpenReview: ((ConversationPart.Diff) -> Unit)? = null,
    onOpenSource: ((ConversationPart.Source) -> Unit)? = null,
    onAttachFiles: ((List<File>) -> Unit)? = null,
    filePicker: () -> List<File> = ::chooseFilesWithSwing,
    slashActions: List<ChatSlashAction> = emptyList(),
    onSlashAction: ((ChatSlashAction, AgentId) -> Unit)? = null,
    browserHandles: List<BrowserHandleBadge> = emptyList(),
    currentBrowserUrl: String? = null,
    hoveredBrowserHandleId: String? = null,
    onBrowserHandleHover: ((String?) -> Unit)? = null,
    toolRendererRegistry: ToolRendererRegistry = remember { ToolRendererRegistry() },
    navigationActions: List<ChatQuickAction> = emptyList(),
    taskActions: List<ChatQuickAction> = emptyList(),
    navigationActionsTitle: String = "Where you want to go",
    taskActionsTitle: String = "What you want done",
    compactActions: Boolean = false,
    showHeader: Boolean = true,
    headerTitle: String = "Chats",
    placeholder: String? = null,
    onRequestAttach: (() -> Unit)? = null,
    emptyState: (@Composable () -> Unit)? = null,
    headerActions: @Composable RowScope.() -> Unit = {},
) {
    val snapshot by handle.snapshots.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedAgent by remember(handle.conversationId) { mutableStateOf<AgentId?>(null) }
    var targetMenuOpen by remember { mutableStateOf(false) }
    var commandError by remember { mutableStateOf<String?>(null) }
    val submittingQuestions = remember(handle.conversationId) { mutableStateMapOf<Pair<TurnId, String>, Boolean>() }
    val participants = snapshot.participants
    val target = selectedAgent?.takeIf { id -> participants.any { it.id == id } } ?: participants.firstOrNull()?.id
    val participant = participants.firstOrNull { it.id == target }
    val draft = target?.let { snapshot.drafts[it] }.orEmpty()
    val draftAttachments = target?.let { snapshot.attachments[it] }.orEmpty()
    val activeRun = snapshot.runs.lastOrNull { it.agentId == target && it.state == RunState.Working }
    val showAgentLabels = snapshot.turns.filter { it.authorId != "user" }
        .mapNotNull { it.agentId }.distinct().size > 1
    val listState = rememberLazyListState()
    val awayFromBottom by remember { derivedStateOf { listState.canScrollForward } }
    var userScrolledAway by remember(handle.conversationId) { mutableStateOf(false) }
    var autoScrolling by remember(handle.conversationId) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress && listState.canScrollForward && !autoScrolling }.collect { scrollingAway ->
            if (scrollingAway) userScrolledAway = true
        }
    }
    androidx.compose.runtime.LaunchedEffect(awayFromBottom) {
        if (!awayFromBottom) userScrolledAway = false
    }
    androidx.compose.runtime.LaunchedEffect(snapshot.turns) {
        val unanswered = snapshot.turns.flatMap { turn ->
            turn.parts.filterIsInstance<ConversationPart.Question>()
                .filter { it.answer == null }.map { turn.id to it.id }
        }.toSet()
        submittingQuestions.keys.toList().forEach { if (it !in unanswered) submittingQuestions.remove(it) }
        if (snapshot.turns.isNotEmpty() && !userScrolledAway) {
            autoScrolling = true
            try {
                listState.scrollToItem(snapshot.turns.lastIndex)
                if (snapshot.turns.last().parts.none { it is ConversationPart.BrowserStep }) {
                    listState.scrollBy(1_000_000f)
                }
            } finally {
                autoScrolling = false
            }
        }
    }

    Column(modifier.fillMaxSize().background(palette.background).testTag("agent-chat-panel")) {
        if (showHeader) {
            ConversationHeader(
                participant = participant,
                runState = snapshot.runs.lastOrNull { it.agentId == target }?.state
                    ?.takeIf { it == RunState.Working || it == RunState.AwaitingApproval },
                title = headerTitle,
                palette = palette,
                actions = headerActions,
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val browserCardWidthCap = if (maxHeight < 600.dp) 360.dp else 480.dp
            LazyColumn(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize().testTag("agent-conversation-timeline"),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = 20.dp),
            ) {
                if (snapshot.turns.isEmpty() && initialBrowser != null) {
                    item(key = "initial-browser-${initialBrowser.id}") {
                        BrowserEmbedCard(initialBrowser, browserPreview, onOpenBrowser, palette,
                            browserPresentation?.invoke(initialBrowser), browserPlayback?.invoke(initialBrowser),
                            browserHandles, hoveredBrowserHandleId, onBrowserHandleHover,
                            browserCardWidthCap)
                    }
                } else if (snapshot.turns.isEmpty() && emptyState != null) {
                    item(key = "empty-state") {
                        emptyState()
                    }
                }
                items(snapshot.turns, key = { it.id.value }) { turn ->
                    AgentMessageTurn(
                        turn, participants, palette, rendererRegistry,
                        browserPreview, onOpenBrowser, onOpenReview, onOpenSource,
                        toolRendererRegistry = toolRendererRegistry,
                        onApproval = { toolId, approved ->
                            val agent = turn.agentId
                            val run = turn.runId
                            if (agent != null && run != null) scope.launch {
                                val result = handle.dispatch(ConversationCommand.ResolveApproval(agent, run, toolId, approved))
                                commandError = (result as? CommandResult.Rejected)?.reason
                            } else commandError = "This approval has no agent run to receive the decision"
                        },
                        onQuestion = { questionId, answer ->
                            val agent = turn.agentId
                            val run = turn.runId
                            if (agent != null && run != null) {
                                val questionKey = turn.id to questionId
                                if (submittingQuestions[questionKey] != true) {
                                    submittingQuestions[questionKey] = true
                                    scope.launch {
                                        val result = handle.dispatch(ConversationCommand.RespondToQuestion(agent, run, turn.id, questionId, answer))
                                        commandError = (result as? CommandResult.Rejected)?.reason
                                        if (result is CommandResult.Rejected) submittingQuestions.remove(questionKey)
                                    }
                                }
                            } else commandError = "This question has no agent run to receive the answer"
                        },
                        questionSubmitting = { questionId -> submittingQuestions[turn.id to questionId] == true },
                        browserPresentation = browserPresentation,
                        browserPlayback = browserPlayback,
                        browserHandles = browserHandles,
                        currentBrowserUrl = currentBrowserUrl,
                        hoveredBrowserHandleId = hoveredBrowserHandleId,
                        onBrowserHandleHover = onBrowserHandleHover,
                        runState = snapshot.runs.firstOrNull { it.id == turn.runId }?.state,
                        showAuthorLabel = showAgentLabels,
                        browserCardWidthCap = browserCardWidthCap,
                    )
                }
            }
            if (awayFromBottom && userScrolledAway && snapshot.turns.isNotEmpty()) {
                UiButton(
                    "Jump to latest", { scope.launch {
                        autoScrolling = true
                        try {
                            listState.scrollToItem(snapshot.turns.lastIndex)
                            listState.scrollBy(1_000_000f)
                            userScrolledAway = false
                        } finally {
                            autoScrolling = false
                        }
                    } }, palette,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).testTag("agent-jump-latest"),
                )
            }
        }
        if (commandError != null) {
            UiText(commandError.orEmpty(), palette.copy(text = palette.danger), Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), size = 12)
        }
        if (navigationActions.isNotEmpty() || taskActions.isNotEmpty()) {
            AgentDualActionRows(
                navigationActions = navigationActions,
                taskActions = taskActions,
                navigationTitle = navigationActionsTitle,
                taskTitle = taskActionsTitle,
                compact = compactActions,
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                palette = palette,
            )
        }
        androidx.compose.runtime.key(target) { ChatInput(
            value = draft,
            onValueChange = { value -> target?.let { handle.setDraft(it, value) } },
            onSend = { text -> target?.let { agent -> scope.launch {
                commandError = (handle.dispatch(ConversationCommand.Send(agent, text, draftAttachments)) as? CommandResult.Rejected)?.reason
            } } },
            status = if (activeRun != null) ChatInputStatus.Working else ChatInputStatus.Ready,
            onStop = if (activeRun != null && participant?.capabilities?.interrupt == true) {
                { scope.launch { commandError = (handle.dispatch(ConversationCommand.Interrupt(activeRun.agentId, activeRun.id)) as? CommandResult.Rejected)?.reason } }
            } else null,
            enabled = participant?.capabilities?.send == true,
            placeholder = placeholder ?: if (target == null) "Connect an agent to begin" else "Message",
            attachments = draftAttachments.map {
                ChatAttachment(it.id, it.file.name, "${it.file.length()} B", attachmentKindForFile(it.file))
            },
            onRemoveAttachment = { id -> target?.let { handle.removeAttachment(it, id) } },
            onAttachFiles = if (participant?.capabilities?.attachments == true) {
                { files -> target?.let { handle.addAttachments(it, files) }; onAttachFiles?.invoke(files) }
            } else null,
            onRequestAttach = if (participant?.capabilities?.attachments == true) onRequestAttach else null,
            filePicker = filePicker,
            slashActions = slashActions,
            mentions = participants.map { ChatMention(it.id.value, it.name) },
            onSlashAction = if (onSlashAction != null) {
                { action -> target?.let { onSlashAction(action, it) } }
            } else null,
            onMentionSelected = { mention, draftWithMention ->
                val destination = AgentId(mention.id)
                target?.let { handle.setDraft(it, "") }
                handle.setDraft(destination, draftWithMention)
                selectedAgent = destination
            },
            compactActions = compactActions,
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                .padding(horizontal = if (compactActions) 12.dp else 18.dp, vertical = if (compactActions) 6.dp else 12.dp),
            palette = palette,
            actions = {
                if (participants.size > 1) {
                    Box {
                        UiButton("To: ${participant?.name ?: "Select agent"} ▾", { targetMenuOpen = true }, palette)
                        if (targetMenuOpen) Popup(onDismissRequest = { targetMenuOpen = false }) {
                            Column(Modifier.background(palette.surface, RoundedCornerShape(10.dp)).border(1.dp, palette.border, RoundedCornerShape(10.dp)).padding(6.dp)) {
                                participants.forEach { option ->
                                    UiButton(option.name, { selectedAgent = option.id; targetMenuOpen = false; commandError = null }, palette, primary = option.id == target)
                                }
                            }
                        }
                    }
                }
            },
        ) }
    }
}

@Composable
fun AgentMessageTurn(
    turn: ConversationTurn,
    participants: List<AgentParticipant>,
    palette: AgentUiPalette,
    registry: MessagePartRegistry,
    browserPreview: @Composable ((ConversationPart.Browser) -> Unit)?,
    onOpenBrowser: ((ConversationPart.Browser) -> Unit)?,
    onOpenReview: ((ConversationPart.Diff) -> Unit)?,
    onOpenSource: ((ConversationPart.Source) -> Unit)?,
    onApproval: (String, Boolean) -> Unit,
    onQuestion: (String, String) -> Unit,
    questionSubmitting: (String) -> Boolean = { false },
    browserPresentation: ((ConversationPart.Browser) -> BrowserCardPresentation)? = null,
    browserPlayback: ((ConversationPart.Browser) -> BrowserPlaybackControls?)? = null,
    browserHandles: List<BrowserHandleBadge> = emptyList(),
    currentBrowserUrl: String? = null,
    hoveredBrowserHandleId: String? = null,
    onBrowserHandleHover: ((String?) -> Unit)? = null,
    runState: RunState? = null,
    showAuthorLabel: Boolean = true,
    toolRendererRegistry: ToolRendererRegistry? = null,
    browserCardWidthCap: Dp = 480.dp,
) {
    val isUser = turn.authorId == "user"
    val label = if (isUser) "You" else participants.firstOrNull { it.id == turn.agentId }?.name ?: turn.authorId
    val sources = turn.parts.filterIsInstance<ConversationPart.Source>()
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (!isUser && showAuthorLabel) UiText(label, palette, muted = true, size = 12)
        Box(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            if (isUser) Modifier.widthIn(max = 420.dp).background(palette.userBubble, RoundedCornerShape(24.dp))
                .padding(16.dp).testTag("agent-user-bubble")
            else Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            turn.parts.forEach { part ->
                androidx.compose.runtime.key(part.id) {
                    when (part) {
                        is ConversationPart.Text -> AgentMarkdown(part.value + if (part.streaming) " ▍" else "",
                            if (isUser) palette.copy(text = palette.userBubbleText) else palette,
                            size = if (isUser) 17 else 16,
                            citationCount = sources.size,
                            onCitation = if (onOpenSource == null) null else { index ->
                                sources.getOrNull(index - 1)?.let(onOpenSource)
                            })
                        is ConversationPart.Reasoning -> AgentReasoning(part.value, palette = palette)
                        is ConversationPart.Tool -> AgentToolCard(part.value,
                            onApproval = { onApproval(part.value.id, it) }, palette = palette,
                            rendererRegistry = toolRendererRegistry)
                        is ConversationPart.ToolGroup -> AgentToolGroup(part.value,
                            onApproval = { toolId, approved -> onApproval(toolId, approved) }, palette = palette,
                            rendererRegistry = toolRendererRegistry)
                        is ConversationPart.ToolTimeline -> AgentToolTimeline(part.calls,
                            onApproval = { toolId, approved -> onApproval(toolId, approved) },
                            active = part.active, palette = palette, rendererRegistry = toolRendererRegistry)
                        is ConversationPart.Source -> {
                            val sourceIndex = sources.indexOfFirst { it.id == part.id } + 1
                            if (onOpenSource != null) AgentSources(listOf(ChatSource(part.title, part.detail, part.url)),
                                onOpen = { onOpenSource(part) }, palette = palette, startIndex = sourceIndex)
                            else UiText(listOf("$sourceIndex ${part.title}", part.detail).filter { it.isNotBlank() }
                                .joinToString(" · "), palette, muted = true, size = 12)
                        }
                        is ConversationPart.Image -> AgentInlineImage(part.bitmap, part.alt, palette)
                        is ConversationPart.Attachment -> UiText(
                            if (part.sizeBytes > 0) "${part.name} · ${part.sizeBytes} B" else part.name,
                            if (isUser) palette.copy(text = palette.userBubbleText) else palette,
                            muted = !isUser, size = 12)
                        is ConversationPart.Browser -> BrowserEmbedCard(part, browserPreview, onOpenBrowser, palette,
                            browserPresentation?.invoke(part), browserPlayback?.invoke(part),
                            browserHandles, hoveredBrowserHandleId, onBrowserHandleHover,
                            browserCardWidthCap)
                        is ConversationPart.BrowserStep -> BrowserStepEntry(part, browserHandles, currentBrowserUrl,
                            hoveredBrowserHandleId, onBrowserHandleHover, palette)
                        is ConversationPart.Diff -> DiffReviewEntry(part, onOpenReview, palette)
                        is ConversationPart.Question -> if (part.answer != null) {
                            Column(Modifier.fillMaxWidth().background(palette.subtle, RoundedCornerShape(8.dp)).padding(10.dp)) {
                                UiText(part.prompt, palette, size = 13)
                                UiText("Answered: ${part.answer}", palette, muted = true, size = 12)
                            }
                        } else if (turn.agentId != null && turn.runId != null && participants.firstOrNull { it.id == turn.agentId }?.capabilities?.answerQuestion == true) {
                            AgentQuestionCard(part.prompt, part.options, onSubmit = { onQuestion(part.id, it) },
                                palette = palette, submitting = questionSubmitting(part.id))
                        } else UiText(part.prompt, palette, size = 13)
                        is ConversationPart.Custom -> {
                            val renderer = registry.renderer(part.kind)
                            if (renderer != null) renderer.Render(part) else {
                                Column(Modifier.fillMaxWidth().background(palette.subtle, RoundedCornerShape(8.dp)).padding(10.dp)) {
                                    UiText(part.kind, palette, size = 12)
                                    part.fields.forEach { (name, value) -> UiText("$name: $value", palette, muted = true, size = 11) }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
        if (turn.delivery == TurnDelivery.Rejected) UiText("Message was not delivered", palette.copy(text = palette.danger), size = 12)
        if (turn.delivery == TurnDelivery.Pending) UiText("Sending…", palette, muted = true, size = 12)
        if (runState == RunState.Interrupted) UiText("Interrupted", palette, muted = true, size = 12)
        if (runState == RunState.Failed) UiText("Failed", palette.copy(text = palette.danger), size = 12)
    }
}

@Composable
private fun BrowserStepEntry(
    part: ConversationPart.BrowserStep,
    handles: List<BrowserHandleBadge>,
    currentUrl: String?,
    hoveredHandleId: String?,
    onHandleHover: ((String?) -> Unit)?,
    palette: AgentUiPalette,
) {
    val target = part.elementHandle
    val canHighlight = target != null && part.url == currentUrl && handles.any { handle ->
        handle.id == target && (part.elementLabel == null || handle.label == part.elementLabel)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("chat-browser-step-${part.stepIndex}"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            UiText("Step ${part.stepIndex} · ${part.action}", palette, muted = true, size = 12)
            if (target != null) {
                val interaction = remember(part.id) { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                LaunchedEffect(hovered, canHighlight, target) {
                    if (hovered && canHighlight) onHandleHover?.invoke(target)
                    else if (hoveredHandleId == target) onHandleHover?.invoke(null)
                }
                val active = canHighlight && hoveredHandleId == target
                Box(Modifier.background(if (active) palette.accent.copy(alpha = 0.24f) else palette.subtle,
                        RoundedCornerShape(6.dp))
                    .hoverable(interaction, enabled = canHighlight)
                    .semantics {
                        contentDescription = "$target ${part.elementLabel.orEmpty()}"
                        stateDescription = if (active) "Highlighted" else if (canHighlight) "Current page target" else "Historical target"
                    }
                    .testTag("chat-browser-step-handle-${part.stepIndex}-$target")
                    .padding(horizontal = 7.dp, vertical = 4.dp)) {
                    UiText(target, palette, size = 11)
                }
            }
        }
        if (part.thoughts.isNotBlank()) UiText(part.thoughts, palette, size = 12)
        if (part.actionResult.isNotBlank()) UiText(part.actionResult, palette, muted = true, size = 12)
    }
}

@Composable
private fun BrowserEmbedCard(
    part: ConversationPart.Browser,
    preview: @Composable ((ConversationPart.Browser) -> Unit)?,
    onOpen: ((ConversationPart.Browser) -> Unit)?,
    palette: AgentUiPalette,
    presentation: BrowserCardPresentation?,
    playback: BrowserPlaybackControls?,
    handles: List<BrowserHandleBadge>,
    hoveredHandleId: String?,
    onHandleHover: ((String?) -> Unit)?,
    cardWidthCap: Dp,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardMaxWidth = minOf(cardWidthCap, maxWidth * 0.70f)
        val compactCard = cardMaxWidth < 400.dp
        val cardPadding = if (compactCard) 16.dp else 24.dp
        val cardGap = if (compactCard) 12.dp else 18.dp
        val iconSize = if (compactCard) 48.dp else 60.dp
        val headerSize = if (compactCard) 16.sp else 21.sp
        val headerLine = if (compactCard) 22.sp else 27.sp
        var detailsExpanded by remember(part.id) { mutableStateOf(false) }
        Box(Modifier.widthIn(max = cardMaxWidth).fillMaxWidth().padding(bottom = 14.dp)) {
        Column(
            Modifier.fillMaxWidth().background(palette.subtle, RoundedCornerShape(24.dp))
                .testTag("agent-browser-card").padding(cardPadding),
            verticalArrangement = Arrangement.spacedBy(cardGap),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(if (compactCard) 12.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val iconColor = if (presentation?.paused == true) palette.muted else palette.success
                val iconSurface = if (presentation?.paused == true)
                    palette.muted.copy(alpha = 0.17f) else palette.success.copy(alpha = 0.12f)
                Box(
                    Modifier.size(iconSize).background(iconSurface, RoundedCornerShape(if (compactCard) 13.dp else 16.dp))
                        .testTag("browser-card-icon"),
                    contentAlignment = Alignment.Center,
                ) { AgentIcon(AgentIconId.Globe, size = if (compactCard) 24.dp else 30.dp, color = iconColor) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(
                        "Browser",
                        style = TextStyle(color = palette.text, fontSize = headerSize, lineHeight = headerLine,
                            letterSpacing = 0.8.sp, fontWeight = FontWeight.Medium),
                    )
                    BasicText(
                        presentation?.subtitle ?: part.title,
                        modifier = Modifier.testTag("browser-card-subtitle"),
                        style = TextStyle(color = palette.text, fontSize = headerSize, lineHeight = headerLine,
                            letterSpacing = 0.8.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val viewportShape = RoundedCornerShape(11.dp)
            Box(Modifier.fillMaxWidth().clip(viewportShape).background(palette.background)
                .border(1.dp, palette.border, viewportShape).testTag("agent-browser-viewport")) {
                if (preview != null) preview(part)
                else Box(Modifier.fillMaxWidth().aspectRatio(1.6f), contentAlignment = Alignment.Center) {
                    UiText("Browser session ${part.sessionId}", palette, muted = true, size = 12)
                }
                presentation?.description?.let { description ->
                    AgentBrowserDescriptionOverlay(description,
                        Modifier.align(Alignment.BottomCenter).testTag("browser-inline-description"), palette)
                }
            }
            if (playback != null && playback.stepCount > 0)
                AgentBrowserPlaybackBar(playback, tagPrefix = "browser-inline", palette = palette)
            if (handles.isNotEmpty()) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                handles.forEach { handle ->
                    val interaction = remember(handle.id) { MutableInteractionSource() }
                    val hovered by interaction.collectIsHoveredAsState()
                    LaunchedEffect(hovered, handle.id) {
                        if (hovered) onHandleHover?.invoke(handle.id)
                        else if (hoveredHandleId == handle.id) onHandleHover?.invoke(null)
                    }
                    val active = hoveredHandleId == handle.id
                    Box(
                        Modifier.background(if (active) palette.accent.copy(alpha = 0.24f) else palette.subtle, RoundedCornerShape(6.dp))
                            .hoverable(interaction)
                            .semantics {
                                contentDescription = "${handle.id} ${handle.label}"
                                stateDescription = if (active) "Highlighted" else "Idle"
                            }
                            .testTag("browser-handle-badge-${handle.id}")
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                    ) { UiText(handle.id, palette, size = 11) }
                }
            }
            if (onOpen != null) Box(
                Modifier.fillMaxWidth().height(if (compactCard) 44.dp else 54.dp)
                    .testTag("browser-preview-action")
                    .background(palette.text.copy(alpha = 0.22f), RoundedCornerShape(27.dp))
                    .clickable(role = Role.Button) { onOpen(part) },
                contentAlignment = Alignment.Center,
            ) { UiText(presentation?.actionLabel ?: "Open preview", palette, size = if (compactCard) 16 else 20) }
            if (detailsExpanded) Column(Modifier.fillMaxWidth().testTag("browser-card-details"),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val description = presentation?.description
                if (description?.thoughts?.isNotBlank() == true)
                    UiText(description.thoughts, palette, size = 12)
                if (description?.actionResult?.isNotBlank() == true)
                    UiText(description.actionResult, palette, muted = true, size = 12)
                if (description?.thoughts.isNullOrBlank() && description?.actionResult.isNullOrBlank())
                    UiText("Page · ${part.url?.takeIf { it.isNotBlank() } ?: "No page loaded"}",
                        palette, muted = true, size = 12)
                if (playback != null && playback.stepCount > 0)
                    UiText("${playback.stepCount} recorded steps", palette, muted = true, size = 12)
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).offset(y = 14.dp).size(width = 148.dp, height = 28.dp)
                .background(palette.userBubble, RoundedCornerShape(16.dp))
                .clickable(role = Role.Button) { detailsExpanded = !detailsExpanded }
                .semantics {
                    contentDescription = if (detailsExpanded) "See less browser activity" else "See more browser activity"
                    stateDescription = if (detailsExpanded) "Expanded" else "Collapsed"
                }
                .testTag("browser-card-more"),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            UiText(if (detailsExpanded) "See less" else "See more",
                palette.copy(text = palette.userBubbleText), size = 12)
            AgentIcon(AgentIconId.ChevronDown,
                Modifier.rotate(if (detailsExpanded) 180f else 0f), size = 14.dp,
                color = palette.userBubbleText)
        }
        }
    }
}

/** The same compact playback control is used by the inline card and expanded browser pane. */
@Composable
fun AgentBrowserPlaybackBar(
    playback: BrowserPlaybackControls,
    modifier: Modifier = Modifier,
    tagPrefix: String = "browser-playback",
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    if (playback.stepCount <= 0) return
    val last = playback.stepCount - 1
    val index = (if (playback.isLive) last else playback.stepIndex).coerceIn(0, last)
    val latestScrub = rememberUpdatedState(playback.onScrub)
    Row(modifier.fillMaxWidth().height(28.dp).testTag("$tagPrefix-playback"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BrowserPlaybackButton(if (playback.isLive) "LIVE" else "HISTORY", 62.dp,
            "$tagPrefix-live", palette, enabled = true, playback.onToggleLive,
            accessibilityLabel = if (playback.isLive) "View browser history" else "Return to live browser")
        BrowserPlaybackButton("‹", 26.dp, "$tagPrefix-prev", palette,
            enabled = last > 0 && (playback.isLive || index > 0), playback.onPrevious)
        Canvas(Modifier.weight(1f).height(28.dp)
            .pointerInput(playback.stepCount) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun scrub(x: Float) {
                        if (size.width > 0) latestScrub.value(
                            ((x / size.width).coerceIn(0f, 1f) * last).roundToInt())
                    }
                    scrub(down.position.x)
                    do {
                        val event = awaitPointerEvent()
                        event.changes.firstOrNull()?.let { change ->
                            scrub(change.position.x)
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false
                else when (event.key) {
                    Key.DirectionLeft -> { playback.onPrevious(); true }
                    Key.DirectionRight -> { playback.onNext(); true }
                    else -> false
                }
            }
            .semantics {
                contentDescription = "Browser step"
                progressBarRangeInfo = ProgressBarRangeInfo(index.toFloat(), 0f..last.coerceAtLeast(1).toFloat(),
                    (last - 1).coerceAtLeast(0))
                setProgress { value -> latestScrub.value(value.roundToInt().coerceIn(0, last)); true }
            }
            .focusable()
            .testTag("$tagPrefix-scrubber")) {
            val height = 3.dp.toPx()
            val top = (size.height - height) / 2f
            val fraction = if (last == 0) 1f else index.toFloat() / last
            val radius = height / 2f
            drawRoundRect(palette.border, Offset(0f, top), Size(size.width, height),
                CornerRadius(radius, radius))
            drawRoundRect(palette.accent, Offset(0f, top), Size(size.width * fraction, height),
                CornerRadius(radius, radius))
            drawCircle(palette.accent, radius = 4.dp.toPx(),
                center = Offset(size.width * fraction, size.height / 2f))
        }
        UiText("${index + 1}/${playback.stepCount}", palette, muted = true, size = 10)
        BrowserPlaybackButton("›", 26.dp, "$tagPrefix-next", palette,
            enabled = !playback.isLive, playback.onNext)
    }
}

@Composable
private fun BrowserPlaybackButton(
    label: String,
    width: androidx.compose.ui.unit.Dp,
    tag: String,
    palette: AgentUiPalette,
    enabled: Boolean,
    onClick: () -> Unit,
    accessibilityLabel: String = label,
) {
    Box(Modifier.width(width).height(26.dp)
        .background(palette.subtle, RoundedCornerShape(7.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = accessibilityLabel }
        .testTag(tag), contentAlignment = Alignment.Center) {
        UiText(label, if (enabled) palette else palette.copy(text = palette.muted), size = 10)
    }
}

@Composable
private fun DiffReviewEntry(part: ConversationPart.Diff, onOpen: ((ConversationPart.Diff) -> Unit)?, palette: AgentUiPalette) {
    Row(Modifier.fillMaxWidth().background(palette.surface, RoundedCornerShape(10.dp)).border(1.dp, palette.border, RoundedCornerShape(10.dp)).padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            UiText(part.title, palette, size = 13)
            if (part.summary.isNotBlank()) UiText(part.summary, palette, muted = true, size = 12)
        }
        if (onOpen != null) UiButton("Review", { onOpen(part) }, palette)
    }
}
