package fragments.agentui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import fragments.components.agentelements.QuestionOption
import fragments.components.agentelements.ErrorMessage as SourceErrorMessage
import fragments.components.agentelements.QuestionTool as SourceQuestionTool
import fragments.components.assistantui.SpeakerTurn as SourceSpeakerTurn
import fragments.components.assistantui.SpeakerKind as SourceSpeakerKind
import fragments.components.assistantui.SubagentItem as SourceSubagentItem
import fragments.components.assistantui.TaskCardState as SourceTaskCardState
import fragments.components.assistantui.TriggerCategory as SourceTriggerCategory
import fragments.components.assistantui.TriggerItem as SourceTriggerItem
import fragments.components.assistantui.SpeakerIdentity as SourceSpeakerIdentity
import fragments.components.assistantui.AgentCard as SourceAgentCard
import fragments.components.assistantui.AgentSkill as SourceAgentSkill
import fragments.components.assistantui.DirectiveText as SourceDirectiveText
import fragments.components.assistantui.ShimmerLabel as SourceShimmerLabel
import fragments.components.assistantui.SwapLabel as SourceSwapLabel
import fragments.components.assistantui.SubagentList as SourceSubagentList
import fragments.components.assistantui.TaskCard as SourceTaskCard
import fragments.components.assistantui.ComposerTriggerPopover as SourceComposerTriggerPopover
import fragments.components.creance.InboxItem as SourceInboxItem
import fragments.components.creance.InboxKind as SourceInboxKind
import fragments.components.creance.InboxUrgency as SourceInboxUrgency
import fragments.components.creance.KillSwitch as SourceKillSwitch
import fragments.components.creance.NeedsYouInbox as SourceNeedsYouInbox
import fragments.components.creance.Suggestions as SourceSuggestions
import fragments.components.creance.LoadingDots as SourceLoadingDots
import fragments.components.creance.LiveRegion as SourceLiveRegion
import fragments.components.creance.VisuallyHidden as SourceVisuallyHidden
import fragments.components.signoff.ModelPricing as SourceModelPricing
import fragments.components.signoff.RunUsage as SourceRunUsage
import fragments.components.signoff.SignoffSource as SourceSignoffSource
import fragments.components.signoff.DiffReview as SourceDiffReview
import fragments.diff.ContentFileChange as SourceContentFileChange
import fragments.components.signoff.DiffReviewResult as SourceDiffReviewResult
import fragments.components.signoff.DiffReviewComment as SourceDiffReviewComment
import fragments.components.signoff.DiffCommentTarget as SourceDiffCommentTarget
import fragments.components.signoff.DiffCommentSide as SourceDiffCommentSide
import fragments.components.signoff.HunkDecision as SourceHunkDecision
import fragments.components.signoff.Img as SourceImg
import fragments.components.signoff.Markdown as SourceMarkdown
import fragments.components.signoff.ReasoningBase as SourceReasoning
import fragments.components.signoff.RunMeter as SourceRunMeter
import fragments.components.signoff.Sources as SourceSources
import fragments.components.signoff.WithLabels as SourceWithLabels
import fragments.components.signoff.LocalSignoffLabels as SourceLocalLabels
import fragments.components.signoff.MarkdownRendererProvider as SourceMarkdownRendererProvider
import fragments.components.signoff.CopyButton as SourceCopyButton
import fragments.components.signoff.Revealed as SourceRevealed
import fragments.components.signoff.Kbd as SourceKbd
import fragments.components.UiButton
import fragments.components.UiText

/** App-facing leaves. Every default palette comes from the same Fragments theme. */
@Composable
fun AgentMarkdown(
    text: String,
    palette: AgentUiPalette = AgentUiTheme.palette,
    size: Int = 14,
    renderer: (@Composable (String) -> Unit)? = null,
    citationCount: Int = 0,
    onCitation: ((Int) -> Unit)? = null,
) {
    if (renderer == null) SourceMarkdown(text, palette, size, citationCount, onCitation)
    else SourceMarkdownRendererProvider(renderer) { SourceMarkdown(text, palette, size, citationCount, onCitation) }
}

/** Use one Markdown renderer for every message in a subtree. */
@Composable
fun AgentMarkdownRenderer(renderer: (@Composable (String) -> Unit)?, content: @Composable () -> Unit) =
    SourceMarkdownRendererProvider(renderer, content)

@Composable
fun AgentReasoning(text: String, defaultExpanded: Boolean = false, palette: AgentUiPalette = AgentUiTheme.palette) =
    SourceReasoning(text, defaultExpanded, palette)

@Composable
fun AgentQuestionCard(question: String, options: List<ConversationQuestionOption>, onSubmit: (String) -> Unit,
    palette: AgentUiPalette = AgentUiTheme.palette, submitting: Boolean = false) =
    SourceQuestionTool(question, options.map { QuestionOption(it.id, it.label) }, onSubmit, palette, submitting)

/** One approval card per request, with a single-shot batch action for pending requests. */
@Composable
fun AgentApprovalBatch(
    requests: List<ChatApprovalRequest>,
    onDecide: (String, ChatApprovalChoice) -> Unit,
    modifier: Modifier = Modifier,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    require(requests.all { it.id.isNotBlank() } && requests.map { it.id }.distinct().size == requests.size) {
        "Approval request ids must be nonblank and unique"
    }
    val locallyResolved = remember { mutableStateMapOf<String, Pair<ChatApprovalRequest, ChatApprovalStatus>>() }
    SideEffect {
        val pendingById = requests.filter { it.status == ChatApprovalStatus.Pending }.associateBy { it.id }
        locallyResolved.keys.toList().forEach { id ->
            if (locallyResolved[id]?.first != pendingById[id]) locallyResolved.remove(id)
        }
    }
    fun status(request: ChatApprovalRequest): ChatApprovalStatus =
        if (request.status == ChatApprovalStatus.Pending && locallyResolved[request.id]?.first == request)
            locallyResolved[request.id]?.second ?: request.status
        else request.status
    val pending = requests.filter { status(it) == ChatApprovalStatus.Pending }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        requests.forEach { request ->
            androidx.compose.runtime.key(request.id) {
                AgentApprovalCard(
                    toolName = request.toolName,
                    description = request.description,
                    input = request.input,
                    risk = request.risk,
                    status = status(request),
                    onDecide = { choice ->
                        if (status(request) == ChatApprovalStatus.Pending) {
                            locallyResolved[request.id] = request to if (choice.decision.startsWith("allow"))
                                ChatApprovalStatus.Approved else ChatApprovalStatus.Denied
                            onDecide(request.id, choice)
                        }
                    },
                    palette = palette,
                )
            }
        }
        // Batch approval is available only when the caller explicitly classified the risk.
        val batchEligible = pending.filter { it.risk != null && it.risk != ChatRiskLevel.Critical }
        if (batchEligible.size > 1) AgentUiButton(
            if (batchEligible.size == pending.size) "Approve remaining (${batchEligible.size})"
            else "Approve noncritical (${batchEligible.size})", onClick = {
            batchEligible.forEach { request ->
                if (status(request) == ChatApprovalStatus.Pending) {
                    locallyResolved[request.id] = request to ChatApprovalStatus.Approved
                    onDecide(request.id, ChatApprovalChoice("allow-once", input = request.input))
                }
            }
        }, primary = true, palette = palette)
    }
}

@Composable
fun AgentDiffReview(
    patch: String,
    onSubmit: ((ChatDiffReviewResult) -> Unit)? = null,
    modifier: Modifier = Modifier,
    title: String = "Review changes",
    readOnly: Boolean = false,
    palette: AgentUiPalette = AgentUiTheme.palette,
    viewedFiles: Set<String>? = null,
    onViewedFilesChange: ((Set<String>) -> Unit)? = null,
    collapseViewed: Boolean = true,
    comments: List<ChatDiffReviewComment>? = null,
    onCommentsChange: ((List<ChatDiffReviewComment>) -> Unit)? = null,
    decisions: Map<String, ChatHunkDecision>? = null,
    onDecisionsChange: ((Map<String, ChatHunkDecision>) -> Unit)? = null,
    onSubmitAsync: (suspend (ChatDiffReviewResult) -> Unit)? = null,
) = SourceDiffReview(patch, onSubmit?.let { callback ->
    { result: SourceDiffReviewResult -> callback(result.toCurated()) }
}, modifier, title, readOnly, palette, viewedFiles, onViewedFilesChange, collapseViewed,
    comments?.map { it.toSource() }, onCommentsChange?.let { callback ->
        { next: List<SourceDiffReviewComment> -> callback(next.map { it.toCurated() }) }
    }, decisions?.mapValues { SourceHunkDecision.valueOf(it.value.name) },
    onDecisionsChange?.let { callback ->
        { next: Map<String, SourceHunkDecision> ->
            callback(next.mapValues { ChatHunkDecision.valueOf(it.value.name) })
        }
    }, onSubmitAsync = onSubmitAsync?.let { callback ->
        { result: SourceDiffReviewResult -> callback(result.toCurated()) }
    })

@Composable
fun AgentDiffReview(
    files: List<ChatFileChange>,
    onSubmit: ((ChatDiffReviewResult) -> Unit)? = null,
    modifier: Modifier = Modifier,
    title: String = "Review changes",
    readOnly: Boolean = false,
    palette: AgentUiPalette = AgentUiTheme.palette,
    viewedFiles: Set<String>? = null,
    onViewedFilesChange: ((Set<String>) -> Unit)? = null,
    collapseViewed: Boolean = true,
    comments: List<ChatDiffReviewComment>? = null,
    onCommentsChange: ((List<ChatDiffReviewComment>) -> Unit)? = null,
    decisions: Map<String, ChatHunkDecision>? = null,
    onDecisionsChange: ((Map<String, ChatHunkDecision>) -> Unit)? = null,
    onSubmitAsync: (suspend (ChatDiffReviewResult) -> Unit)? = null,
) = SourceDiffReview("", onSubmit?.let { callback ->
    { result: SourceDiffReviewResult -> callback(result.toCurated()) }
}, modifier, title, readOnly, palette, viewedFiles, onViewedFilesChange, collapseViewed,
    comments?.map { it.toSource() }, onCommentsChange?.let { callback ->
        { next: List<SourceDiffReviewComment> -> callback(next.map { it.toCurated() }) }
    }, decisions?.mapValues { SourceHunkDecision.valueOf(it.value.name) },
    onDecisionsChange?.let { callback ->
        { next: Map<String, SourceHunkDecision> ->
            callback(next.mapValues { ChatHunkDecision.valueOf(it.value.name) })
        }
    }, files.map { SourceContentFileChange(it.path, it.oldPath, it.oldContent, it.newContent,
        it.patch, it.language, it.binary) }, onSubmitAsync = onSubmitAsync?.let { callback ->
        { result: SourceDiffReviewResult -> callback(result.toCurated()) }
    })

private fun SourceDiffReviewResult.toCurated() = ChatDiffReviewResult(
    decisions = decisions.mapValues { ChatHunkDecision.valueOf(it.value.name) },
    acceptedCount = acceptedCount,
    rejectedCount = rejectedCount,
    viewedFiles = viewedFiles,
    comments = comments.map { it.toCurated() },
    acceptedPatch = acceptedPatch,
    pendingCount = pendingCount,
    files = files.map { file -> ChatDiffReviewFileResult(file.path, file.oldPath,
        ChatFileChangeStatus.valueOf(file.status.name), file.binary,
        ChatDiffFileDecision.valueOf(file.decision.name), file.viewed, file.content,
        file.accepted, file.rejected, file.pending) },
    rejectedHunks = rejectedHunks.map { ChatDiffRejectedHunk(it.id, it.path, it.header, it.diff) },
)

private fun SourceDiffReviewComment.toCurated() = ChatDiffReviewComment(
    id, fileId, path, ChatDiffCommentTarget.valueOf(target.name), text, hunkId,
    side?.let { ChatDiffCommentSide.valueOf(it.name) }, startLine, endLine, oldStartLine, oldEndLine,
)

private fun ChatDiffReviewComment.toSource() = SourceDiffReviewComment(
    id, fileId, path, SourceDiffCommentTarget.valueOf(target.name), text, hunkId,
    side?.let { SourceDiffCommentSide.valueOf(it.name) }, startLine, endLine, oldStartLine, oldEndLine,
)

@Composable
fun AgentRunMeter(
    usage: ChatRunUsage?,
    model: String = "",
    pricing: ChatModelPricing? = null,
    cost: Double? = null,
    ttftMs: Long? = null,
    durationMs: Long? = null,
    expanded: Boolean = false,
    live: Boolean = false,
    palette: AgentUiPalette = AgentUiTheme.palette,
) = SourceRunMeter(usage?.let { SourceRunUsage(it.inputTokens, it.outputTokens, it.cacheReadTokens, it.reasoningTokens) },
    model, pricing?.let { SourceModelPricing(it.inputPerMillion, it.outputPerMillion) },
    cost, ttftMs, durationMs, expanded, live, palette)

@Composable
fun AgentSources(items: List<ChatSource>, onOpen: (ChatSource) -> Unit,
    palette: AgentUiPalette = AgentUiTheme.palette, startIndex: Int = 1) {
    val converted = items.map { SourceSignoffSource(it.title, it.detail, it.url) }
    SourceSources(converted, { selected -> converted.indexOfFirst { it === selected }
        .takeIf { it >= 0 }?.let { onOpen(items[it]) } }, palette, startIndex)
}

@OptIn(ExperimentalTime::class)
private fun currentInboxMillis(): Long = Clock.System.now().toEpochMilliseconds()

@Composable
fun AgentNeedsYouInbox(
    items: List<ChatInboxItem>,
    onOpen: (ChatInboxItem) -> Unit,
    modifier: Modifier = Modifier,
    now: Long = currentInboxMillis(),
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    val converted = items.map { SourceInboxItem(it.id, SourceInboxKind.valueOf(it.kind.name), it.title,
        it.agentName, it.createdAt, SourceInboxUrgency.valueOf(it.urgency.name), it.detail) }
    SourceNeedsYouInbox(converted, { selected -> items.firstOrNull { it.id == selected.id }?.let(onOpen) }, modifier, now, palette = palette)
}

@Composable
fun AgentKillSwitch(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    holdMs: Long = 1_000,
    stopped: Boolean = false,
    onResume: (() -> Unit)? = null,
    palette: AgentUiPalette = AgentUiTheme.palette,
) = SourceKillSwitch(onStop, modifier, holdMs, stopped, onResume, palette = palette)

@Composable
fun AgentSpeakerIdentity(turns: List<ChatSpeakerTurn>, modifier: Modifier = Modifier, palette: AgentUiPalette = AgentUiTheme.palette) =
    SourceSpeakerIdentity(turns.map { SourceSpeakerTurn(it.id, SourceSpeakerKind.valueOf(it.kind.name), it.name, it.text, it.detail) }, modifier, palette)

@Composable
fun AgentSubagentList(
    agents: List<ChatSubagent>,
    completedCount: Int,
    progress: List<Int>,
    showSummary: Boolean,
    summaryAgent: ChatSubagent,
    modifier: Modifier = Modifier,
    palette: AgentUiPalette = AgentUiTheme.palette,
) = SourceSubagentList(agents.map { SourceSubagentItem(it.name, it.model) }, completedCount, progress,
    showSummary, SourceSubagentItem(summaryAgent.name, summaryAgent.model), modifier, palette)

@Composable
fun AgentTaskCard(
    label: String,
    state: ChatTaskState,
    modifier: Modifier = Modifier,
    meta: String = "",
    elapsed: String = "",
    result: String = "",
    palette: AgentUiPalette = AgentUiTheme.palette,
    open: Boolean? = null,
    onOpenChange: ((Boolean) -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
    resultContent: (@Composable () -> Unit)? = null,
    transcript: (@Composable () -> Unit)? = null,
) = SourceTaskCard(label, SourceTaskCardState.valueOf(state.name), modifier, meta, elapsed,
    result, palette, open, onOpenChange, actions, resultContent, transcript)

@Composable
fun AgentComposerTrigger(
    categories: List<ChatTriggerCategory>,
    items: List<ChatTriggerItem>,
    onSelect: (ChatTriggerItem) -> Unit,
    modifier: Modifier = Modifier,
    palette: AgentUiPalette = AgentUiTheme.palette,
    isLoading: Boolean = false,
    backLabel: String = "Back",
    emptyCategoriesLabel: String = "No items available",
    emptyItemsLabel: String = "No matching items",
    loadingLabel: String = "Loading…",
    onDismiss: (() -> Unit)? = null,
) = SourceComposerTriggerPopover(categories.map { SourceTriggerCategory(it.id, it.label) },
    items.map { SourceTriggerItem(it.id, it.label, it.category, it.description, it.type) },
    { selected -> items.firstOrNull { it.id == selected.id && it.category == selected.category }?.let(onSelect) },
    modifier, palette, isLoading, backLabel, emptyCategoriesLabel, emptyItemsLabel, loadingLabel, onDismiss)

/** The host owns its draft; this overload returns the selected action or serialized directive. */
@Composable
fun AgentComposerTrigger(
    categories: List<ChatTriggerCategory>,
    items: List<ChatTriggerItem>,
    behavior: ChatTriggerBehavior,
    modifier: Modifier = Modifier,
    palette: AgentUiPalette = AgentUiTheme.palette,
    isLoading: Boolean = false,
    backLabel: String = "Back",
    emptyCategoriesLabel: String = "No items available",
    emptyItemsLabel: String = "No matching items",
    loadingLabel: String = "Loading…",
    onDismiss: (() -> Unit)? = null,
) = AgentComposerTrigger(categories, items, { selected ->
    when (behavior) {
        is ChatTriggerBehavior.Action -> behavior.onExecute(selected, behavior.removeOnExecute)
        is ChatTriggerBehavior.Directive -> behavior.onInserted(selected, behavior.formatter(selected))
    }
}, modifier, palette, isLoading, backLabel, emptyCategoriesLabel, emptyItemsLabel, loadingLabel, onDismiss)

@Composable
fun AgentSuggestions(items: List<String>, onSelect: (String) -> Unit, modifier: Modifier = Modifier, palette: AgentUiPalette = AgentUiTheme.palette) =
    SourceSuggestions(items, onSelect, modifier, palette)

@Composable
fun AgentImageLightbox(
    image: ImageBitmap?, description: String, onClose: () -> Unit,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    Popup(alignment = Alignment.Center, onDismissRequest = onClose,
        properties = PopupProperties(focusable = true)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(palette.background.copy(alpha = 0.96f))) {
            val ratio = image?.let { it.width.coerceAtLeast(1).toFloat() / it.height.coerceAtLeast(1) } ?: 1f
            val imageWidth = minOf(maxWidth * 0.85f, maxHeight * 0.70f * ratio, 960.dp)
            Column(
                Modifier.align(Alignment.Center),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (image != null) Image(
                    BitmapPainter(image), description,
                    Modifier.width(imageWidth).height(imageWidth / ratio),
                    contentScale = ContentScale.Fit,
                )
                UiText(description, palette)
                AgentUiButton("Close", onClose, palette = palette)
            }
        }
    }
}

/** A bounded message image that opens its own lightbox when selected. */
@Composable
fun AgentInlineImage(image: ImageBitmap?, description: String, palette: AgentUiPalette = AgentUiTheme.palette) {
    if (image == null) {
        SourceImg(null, description, palette)
        return
    }
    var expanded by remember(image) { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val ratio = image.width.coerceAtLeast(1).toFloat() / image.height.coerceAtLeast(1)
    Box(
        Modifier.widthIn(max = 480.dp).heightIn(max = 320.dp).aspectRatio(ratio)
            .clip(shape).border(1.dp, palette.border, shape)
            .clickable(role = Role.Button) { expanded = true }
            .semantics { contentDescription = "Open image: $description" },
    ) {
        Image(BitmapPainter(image), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
    if (expanded) AgentImageLightbox(image, description, onClose = { expanded = false }, palette = palette)
}

@Composable
fun AgentErrorMessage(message: String, onRetry: (() -> Unit)? = null, palette: AgentUiPalette = AgentUiTheme.palette) =
    SourceErrorMessage(message, onRetry, palette)

@Immutable data class AgentIdentitySkill(val name: String, val description: String)

@Composable
fun AgentIdentityCard(
    name: String, description: String, provider: String, version: String,
    model: String, endpoint: String, skills: List<AgentIdentitySkill>, connected: Boolean,
    onConnect: (() -> Unit)? = null, modifier: Modifier = Modifier,
    palette: AgentUiPalette = AgentUiTheme.palette,
) = SourceAgentCard(name, description, provider, version, model, endpoint,
    skills.map { SourceAgentSkill(it.name, it.description) }, connected, onConnect, modifier, palette)

@Composable fun AgentUiButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, palette: AgentUiPalette = AgentUiTheme.palette) =
    UiButton(label, onClick, palette, modifier, primary)

@Composable fun AgentLoadingIndicator(text: String? = null, modifier: Modifier = Modifier, palette: AgentUiPalette = AgentUiTheme.palette) {
    Box(modifier.semantics { contentDescription = text ?: "Loading";
        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        if (text == null) SourceLoadingDots(palette = palette) else SourceShimmerLabel(text, palette = palette)
    }
}

@Composable fun AgentLiveRegion(message: String) = SourceLiveRegion(message)
@Composable fun AgentVisuallyHidden(label: String) = SourceVisuallyHidden(label)
@Composable fun AgentUiLabels(labels: Map<String, String>, content: @Composable () -> Unit) = SourceWithLabels(labels, content)
@Composable fun AgentUiLabel(key: String, fallback: String): String = SourceLocalLabels.current[key] ?: fallback
@Composable fun AgentKeyboardHint(label: String, palette: AgentUiPalette = AgentUiTheme.palette) = SourceKbd(label, palette)
@Composable fun AgentCopyButton(text: String, label: String = "Copy", palette: AgentUiPalette = AgentUiTheme.palette) = SourceCopyButton(text, label, palette)
@Composable fun AgentRevealedText(text: String, blanks: Boolean = false, modifier: Modifier = Modifier,
    palette: AgentUiPalette = AgentUiTheme.palette) = SourceRevealed(text, blanks, modifier, palette)
@Composable fun AgentDirectiveText(text: String, palette: AgentUiPalette = AgentUiTheme.palette) = SourceDirectiveText(text, palette = palette)
@Composable fun AgentSwapLabel(active: Int, first: String, second: String, palette: AgentUiPalette = AgentUiTheme.palette) = SourceSwapLabel(active, first, second, palette)

/**
 * Persistent dual-purpose action rows for every chat window (both full-screen and half-screen):
 * - Row 1 ([navigationActions]): "Where you want to go" — navigation/destination actions such as opening the most recent project.
 * - Row 2 ([taskActions]): "What you want done" — execution/task actions such as rebuilding the project or submitting to the Google Play Store.
 */
@Composable
fun AgentDualActionRows(
    navigationActions: List<ChatQuickAction>,
    taskActions: List<ChatQuickAction>,
    modifier: Modifier = Modifier,
    navigationTitle: String = "Where you want to go",
    taskTitle: String = "What you want done",
    compact: Boolean = false,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    if (navigationActions.isEmpty() && taskActions.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("agent-dual-action-rows"),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
    ) {
        if (navigationActions.isNotEmpty()) {
            AgentQuickActionRow(
                title = navigationTitle,
                actions = navigationActions,
                rowTag = "agent-action-row-navigation",
                titleTag = "agent-action-row-navigation-title",
                compact = compact,
                palette = palette,
            )
        }
        if (taskActions.isNotEmpty()) {
            AgentQuickActionRow(
                title = taskTitle,
                actions = taskActions,
                rowTag = "agent-action-row-task",
                titleTag = "agent-action-row-task-title",
                compact = compact,
                palette = palette,
            )
        }
    }
}

@Composable
private fun AgentQuickActionRow(
    title: String,
    actions: List<ChatQuickAction>,
    rowTag: String,
    titleTag: String,
    compact: Boolean,
    palette: AgentUiPalette,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(rowTag),
        verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 5.dp),
    ) {
        if (title.isNotBlank()) {
            UiText(
                title,
                palette,
                modifier = Modifier.testTag(titleTag),
                muted = true,
                size = if (compact) 10 else 11,
                weight = FontWeight.SemiBold,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEach { action ->
                val shape = RoundedCornerShape(if (compact) 8.dp else 10.dp)
                val bgColor = when {
                    !action.enabled -> palette.subtle.copy(alpha = 0.45f)
                    action.primary -> palette.accent.copy(alpha = 0.16f)
                    else -> palette.surface
                }
                val borderColor = when {
                    !action.enabled -> palette.border.copy(alpha = 0.45f)
                    action.primary -> palette.accent
                    else -> palette.border
                }
                val textColor = when {
                    !action.enabled -> palette.muted
                    action.primary -> palette.accent
                    else -> palette.text
                }
                Row(
                    modifier = Modifier
                        .clip(shape)
                        .background(bgColor, shape)
                        .border(1.dp, borderColor, shape)
                        .clickable(
                            enabled = action.enabled,
                            role = Role.Button,
                            onClick = action.onClick,
                        )
                        .semantics { contentDescription = action.label }
                        .testTag("agent-quick-action-${action.id}")
                        .padding(
                            horizontal = if (compact) 10.dp else 12.dp,
                            vertical = if (compact) 6.dp else 8.dp,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UiText(
                        action.label,
                        palette.copy(text = textColor),
                        size = if (compact) 12 else 13,
                        weight = if (action.primary) FontWeight.SemiBold else FontWeight.Medium,
                    )
                    if (action.subtitle.isNotBlank() && !compact) {
                        UiText(
                            action.subtitle,
                            palette,
                            muted = true,
                            size = 11,
                        )
                    }
                }
            }
        }
    }
}
