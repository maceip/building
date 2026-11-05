package fragments.components.signoff

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import fragments.agentui.AgentUiTheme
import fragments.components.ReactUiPalette
import fragments.components.ReactUiPalettes
import fragments.components.UiButton
import fragments.components.UiDivider
import fragments.components.UiInput
import fragments.components.UiText
import fragments.diff.DiffHunk
import fragments.diff.ContentDiff
import fragments.diff.AcceptedPatch
import fragments.diff.ContentFileChange
import fragments.diff.DiffLayoutMode
import fragments.diff.DiffLine
import fragments.diff.DiffLineType
import fragments.diff.DiffParser
import fragments.diff.FileDiffState
import fragments.diff.FileChangeStatus
import fragments.diff.SplitDiffRow
import fragments.diff.toSplitRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
private fun newReviewId(): String = Uuid.random().toString()

enum class HunkDecision { Accepted, Rejected }
enum class DiffFileDecision { Accepted, Rejected, Pending, Partial, Unchanged }
enum class DiffCommentTarget { File, Hunk, Lines }
enum class DiffCommentSide { New, Old }

@Immutable
data class DiffReviewComment(
    val id: String,
    val fileId: String,
    val path: String,
    val target: DiffCommentTarget,
    val text: String,
    val hunkId: String? = null,
    val side: DiffCommentSide? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val oldStartLine: Int? = null,
    val oldEndLine: Int? = null,
)

@Immutable
private data class CommentDraft(
    val fileId: String,
    val path: String,
    val target: DiffCommentTarget,
    val hunkId: String? = null,
    val side: DiffCommentSide? = null,
    val line: Int? = null,
    val editingId: String? = null,
    val endLine: Int? = line,
    val oldStartLine: Int? = null,
    val oldEndLine: Int? = null,
    val endIndex: Int? = null,
)

@Immutable
private data class ReviewSelection(val fileId: String, val hunkId: String, val anchor: Int, val head: Int) {
    val from: Int get() = minOf(anchor, head)
    val to: Int get() = maxOf(anchor, head)
}

private fun selectedLinesDraft(fileId: String, path: String, hunk: DiffHunk,
    selection: ReviewSelection): CommentDraft {
    val lines = hunk.lines.subList(selection.from, selection.to + 1)
    val newNumbers = lines.mapNotNull { it.newLineNumber }
    val oldNumbers = lines.mapNotNull { it.oldLineNumber }
    val useNew = newNumbers.isNotEmpty()
    val primary = if (useNew) newNumbers else oldNumbers
    return CommentDraft(fileId, path, DiffCommentTarget.Lines, hunk.id,
        if (useNew) DiffCommentSide.New else DiffCommentSide.Old,
        primary.minOrNull(), endLine = primary.maxOrNull(),
        oldStartLine = if (useNew) oldNumbers.minOrNull() else null,
        oldEndLine = if (useNew) oldNumbers.maxOrNull() else null,
        endIndex = selection.to)
}

private fun commentEndIndex(comment: DiffReviewComment, hunk: DiffHunk?): Int? {
    if (hunk == null) return null
    return hunk.lines.indexOfLast { line ->
        (comment.side == DiffCommentSide.New && line.newLineNumber == comment.endLine) ||
            (comment.side == DiffCommentSide.Old && line.oldLineNumber == comment.endLine) ||
            (comment.oldEndLine != null && line.oldLineNumber == comment.oldEndLine)
    }.takeIf { it >= 0 }
}

@Immutable
data class DiffReviewResult(
    val decisions: Map<String, HunkDecision>,
    val acceptedCount: Int,
    val rejectedCount: Int,
    val viewedFiles: Set<String> = emptySet(),
    val comments: List<DiffReviewComment> = emptyList(),
    val acceptedPatch: String = "",
    val pendingCount: Int = 0,
    val files: List<DiffReviewFileResult> = emptyList(),
    val rejectedHunks: List<DiffReviewRejectedHunk> = emptyList(),
)

@Immutable
data class DiffReviewFileResult(
    val path: String,
    val oldPath: String?,
    val status: FileChangeStatus,
    val binary: Boolean,
    val decision: DiffFileDecision,
    val viewed: Boolean,
    val content: String?,
    val accepted: List<String>,
    val rejected: List<String>,
    val pending: List<String>,
)

@Immutable
data class DiffReviewRejectedHunk(
    val id: String,
    val path: String,
    val header: String,
    val diff: String,
)

private fun reviewItemIds(file: FileDiffState): List<String> = when {
    file.hunks.isNotEmpty() -> file.hunks.map { it.id }
    file.isBinary || file.status != FileChangeStatus.Modified -> listOf("${file.id}:file")
    else -> emptyList()
}

private fun renderHunkLines(hunk: DiffHunk): String = buildString {
    hunk.lines.forEach { line ->
        append(when (line.type) {
            DiffLineType.Context -> ' '
            DiffLineType.Added -> '+'
            DiffLineType.Deleted -> '-'
            DiffLineType.HunkHeader -> error("Hunk headers cannot appear as diff lines")
        })
        append(line.text).append('\n')
        if (line.noNewline) append("\\ No newline at end of file\n")
    }
}

private fun buildReviewResult(files: List<FileDiffState>, changes: List<ContentFileChange>?,
    decisions: Map<String, HunkDecision>, viewed: Set<String>, comments: List<DiffReviewComment>): DiffReviewResult {
    val results = files.mapIndexed { index, file ->
        val ids = reviewItemIds(file)
        val accepted = ids.filter { decisions[it] == HunkDecision.Accepted }
        val rejected = ids.filter { decisions[it] == HunkDecision.Rejected }
        val pending = ids.filter { it !in decisions }
        val decision = when {
            ids.isEmpty() -> DiffFileDecision.Unchanged
            accepted.size == ids.size -> DiffFileDecision.Accepted
            rejected.size == ids.size -> DiffFileDecision.Rejected
            pending.size == ids.size -> DiffFileDecision.Pending
            else -> DiffFileDecision.Partial
        }
        val change = changes?.getOrNull(index)
        val originalContent = change?.oldContent
        val proposedContent = change?.newContent
        val content = if (originalContent != null && proposedContent != null)
            AcceptedPatch.applyToContent(file, accepted.toSet(), originalContent, proposedContent)
        else null
        DiffReviewFileResult(file.newPath, file.oldPath, file.status, file.isBinary, decision,
            file.id in viewed, content, accepted, rejected, pending)
    }
    val rejectedHunks = files.flatMap { file -> file.hunks.filter { decisions[it.id] == HunkDecision.Rejected }
        .map { DiffReviewRejectedHunk(it.id, file.newPath, it.formattedHeader, renderHunkLines(it)) } }
    val acceptedIds = results.flatMap { it.accepted }.toSet()
    return DiffReviewResult(decisions, results.sumOf { it.accepted.size },
        results.sumOf { it.rejected.size }, viewed, comments,
        AcceptedPatch.format(files, acceptedIds), results.sumOf { it.pending.size }, results, rejectedHunks)
}

@Immutable
private sealed interface ReviewRow {
    val key: String
    val fileId: String
    data class FileHeader(val file: FileDiffState, override val key: String) : ReviewRow {
        override val fileId: String get() = file.id
    }
    data class HunkHeader(val hunk: DiffHunk, override val key: String, override val fileId: String) : ReviewRow
    data class UnifiedLine(val line: DiffLine, override val key: String, override val fileId: String,
        val hunkId: String, val index: Int) : ReviewRow
    data class SplitLine(val line: SplitDiffRow, override val key: String, override val fileId: String,
        val hunkId: String) : ReviewRow
    data class ContextLine(val line: DiffLine, override val key: String,
        override val fileId: String) : ReviewRow
    data class GapControl(val gap: ReviewContextGap, val hidden: Int, val last: Boolean,
        override val key: String, override val fileId: String) : ReviewRow
}

@Immutable
private data class ReviewContextGap(val fileId: String, val before: Int, val oldStart: Int,
    val oldEnd: Int, val delta: Int) {
    val key: String get() = "$fileId:$before"
    val size: Int get() = oldEnd - oldStart + 1
}

@Immutable
private data class ShownContext(val start: Int = 0, val end: Int = 0)
private const val ContextStep = 20
private enum class ContextDirection { Start, End, All }

private fun reviewContextGaps(file: FileDiffState, change: ContentFileChange?): List<ReviewContextGap> {
    val oldContent = change?.oldContent ?: return emptyList()
    if (change.newContent == null || file.isBinary || file.hunks.isEmpty()) return emptyList()
    val gaps = mutableListOf<ReviewContextGap>()
    var next = 1
    var delta = 0
    file.hunks.forEachIndexed { index, hunk ->
        val end = hunk.oldStart - 1
        if (end >= next) gaps += ReviewContextGap(file.id, index, next, end, delta)
        val oldEnd = if (hunk.oldCount == 0 && hunk.oldStart == 0) 0
            else hunk.oldStart + hunk.oldCount - 1
        val newEnd = if (hunk.newCount == 0 && hunk.newStart == 0) 0
            else hunk.newStart + hunk.newCount - 1
        next = oldEnd + 1
        delta = newEnd - oldEnd
    }
    val total = oldContent.removeSuffix("\n").let { if (it.isEmpty() && oldContent.isEmpty()) 0
        else it.split('\n').size }
    if (total >= next) gaps += ReviewContextGap(file.id, file.hunks.size, next, total, delta)
    return gaps
}

@Immutable
private data class ReviewDocument(val files: List<FileDiffState>, val rows: List<ReviewRow>,
    val gaps: Map<String, List<ReviewContextGap>>)

private fun buildRows(files: List<FileDiffState>, view: DiffLayoutMode,
    changes: List<ContentFileChange>?, shown: Map<String, ShownContext>): ReviewDocument {
    val gapsByFile = files.mapIndexed { index, file ->
        file.id to reviewContextGaps(file, changes?.getOrNull(index))
    }.toMap()
    val rows = buildList {
        files.forEachIndexed { fileIndex, file ->
            add(ReviewRow.FileHeader(file, "file:${file.id}"))
            val gaps = gapsByFile[file.id].orEmpty().associateBy { it.before }
            val oldContent = changes?.getOrNull(fileIndex)?.oldContent.orEmpty()
            val oldLines = oldContent.removeSuffix("\n").split('\n')
            fun appendGap(gap: ReviewContextGap) {
                val expanded = shown[gap.key] ?: ShownContext()
                val top = expanded.start.coerceIn(0, gap.size)
                val bottom = expanded.end.coerceIn(0, gap.size - top)
                fun addLine(number: Int) {
                    add(ReviewRow.ContextLine(DiffLine(
                        id = "${gap.key}:context_$number", type = DiffLineType.Context,
                        text = oldLines[number - 1], oldLineNumber = number,
                        newLineNumber = number + gap.delta,
                        noNewline = number == oldLines.size && !oldContent.endsWith('\n'),
                    ), "${gap.key}:context_$number", file.id))
                }
                for (number in gap.oldStart until gap.oldStart + top) addLine(number)
                val hidden = gap.size - top - bottom
                if (hidden > 0) add(ReviewRow.GapControl(gap, hidden,
                    last = gap.before == file.hunks.size, key = "${gap.key}:controls", fileId = file.id))
                for (number in gap.oldEnd - bottom + 1..gap.oldEnd) addLine(number)
            }
            file.hunks.forEachIndexed { hunkIndex, hunk ->
                gaps[hunkIndex]?.let(::appendGap)
                val prefix = "${file.id}:$hunkIndex"
                add(ReviewRow.HunkHeader(hunk, "$prefix:header", file.id))
                if (view == DiffLayoutMode.Unified) hunk.lines.forEachIndexed { lineIndex, line ->
                    add(ReviewRow.UnifiedLine(line, "$prefix:$lineIndex", file.id, hunk.id, lineIndex))
                } else hunk.toSplitRows().forEachIndexed { lineIndex, line ->
                    add(ReviewRow.SplitLine(line, "$prefix:$lineIndex", file.id, hunk.id))
                }
            }
            gaps[file.hunks.size]?.let(::appendGap)
        }
    }
    return ReviewDocument(files, rows, gapsByFile)
}

/** Small diffs appear immediately; large diffs parse off the UI thread. Rows keep stable keys. */
@Composable
fun DiffReview(
    patch: String,
    onSubmit: ((DiffReviewResult) -> Unit)? = null,
    modifier: Modifier = Modifier,
    title: String = "Review changes",
    readOnly: Boolean = false,
    palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark,
    viewedFiles: Set<String>? = null,
    onViewedFilesChange: ((Set<String>) -> Unit)? = null,
    collapseViewed: Boolean = true,
    comments: List<DiffReviewComment>? = null,
    onCommentsChange: ((List<DiffReviewComment>) -> Unit)? = null,
    decisions: Map<String, HunkDecision>? = null,
    onDecisionsChange: ((Map<String, HunkDecision>) -> Unit)? = null,
    fileChanges: List<ContentFileChange>? = null,
    onSubmitAsync: (suspend (DiffReviewResult) -> Unit)? = null,
) {
    require(onSubmit == null || onSubmitAsync == null) {
        "Choose either onSubmit or onSubmitAsync for one review."
    }
    val reviewKey: Any = fileChanges ?: patch
    val inputSize = fileChanges?.sumOf { (it.oldContent?.length ?: 0).toLong() +
        (it.newContent?.length ?: 0) + (it.patch?.length ?: 0) } ?: patch.length.toLong()
    fun parseFiles(): List<FileDiffState> =
        if (fileChanges == null) DiffParser.parse(patch) else ContentDiff.parseAll(fileChanges)
    var view by remember(reviewKey) { mutableStateOf(DiffLayoutMode.Unified) }
    var shownContext by remember(reviewKey) { mutableStateOf<Map<String, ShownContext>>(emptyMap()) }
    val parsedFiles: List<FileDiffState>? = if (inputSize <= 8_192) {
        remember(reviewKey) { parseFiles() }
    } else {
        val backgroundFiles = remember(reviewKey) { mutableStateOf<List<FileDiffState>?>(null) }
        LaunchedEffect(reviewKey) {
            backgroundFiles.value = withContext(Dispatchers.Default) { parseFiles() }
        }
        backgroundFiles.value
    }
    val document: ReviewDocument? = if (parsedFiles == null) null
    else if (inputSize <= 8_192) remember(parsedFiles, view, shownContext) {
        buildRows(parsedFiles, view, fileChanges, shownContext)
    }
    else {
        val backgroundDocument = remember(reviewKey, view, shownContext) { mutableStateOf<ReviewDocument?>(null) }
        LaunchedEffect(reviewKey, view, parsedFiles, shownContext) {
            backgroundDocument.value = withContext(Dispatchers.Default) {
                buildRows(parsedFiles, view, fileChanges, shownContext)
            }
        }
        backgroundDocument.value
    }
    var localDecisions by remember(reviewKey) { mutableStateOf(emptyMap<String, HunkDecision>()) }
    val currentDecisions = decisions ?: localDecisions
    var submitted by remember(reviewKey) { mutableStateOf(false) }
    var submitting by remember(reviewKey) { mutableStateOf(false) }
    var submissionError by remember(reviewKey) { mutableStateOf<String?>(null) }
    val submissionScope = rememberCoroutineScope()
    var localViewed by remember(reviewKey) { mutableStateOf(emptySet<String>()) }
    val currentViewed = viewedFiles ?: localViewed
    var lastViewed by remember(reviewKey) { mutableStateOf(currentViewed) }
    var unfoldedViewed by remember(reviewKey) { mutableStateOf(emptySet<String>()) }
    var localComments by remember(reviewKey) { mutableStateOf(emptyList<DiffReviewComment>()) }
    val currentComments = comments ?: localComments
    var lastComments by remember(reviewKey) { mutableStateOf(currentComments) }
    var lastDecisions by remember(reviewKey) { mutableStateOf(currentDecisions) }
    var draft by remember(reviewKey) { mutableStateOf<CommentDraft?>(null) }
    var draftText by remember(reviewKey) { mutableStateOf("") }
    var selection by remember(reviewKey) { mutableStateOf<ReviewSelection?>(null) }
    var activeItemId by remember(reviewKey) { mutableStateOf<String?>(null) }
    var focusedItemId by remember(reviewKey) { mutableStateOf<String?>(null) }
    var scrollTargetId by remember(reviewKey) { mutableStateOf<String?>(null) }
    var focusRequestVersion by remember(reviewKey) { mutableIntStateOf(0) }
    var rootFocused by remember(reviewKey) { mutableStateOf(false) }
    val rootFocusRequester = remember(reviewKey) { FocusRequester() }
    val itemFocusRequesters = remember(reviewKey) { mutableMapOf<String, FocusRequester>() }
    SideEffect {
        if (lastViewed != currentViewed) {
            unfoldedViewed = unfoldedViewed - (currentViewed - lastViewed)
            submitted = false
            lastViewed = currentViewed
        }
        if (lastComments != currentComments) {
            submitted = false
            lastComments = currentComments
        }
        if (lastDecisions != currentDecisions) {
            submitted = false
            lastDecisions = currentDecisions
        }
    }
    fun changeComments(next: List<DiffReviewComment>) {
        if (comments == null) localComments = next
        submitted = false
        onCommentsChange?.invoke(next)
    }
    fun editComment(comment: DiffReviewComment) {
        val hunk = document?.files.orEmpty().flatMap { it.hunks }.firstOrNull { it.id == comment.hunkId }
        draft = CommentDraft(comment.fileId, comment.path, comment.target, comment.hunkId,
            comment.side, comment.startLine, comment.id, comment.endLine,
            comment.oldStartLine, comment.oldEndLine, commentEndIndex(comment, hunk))
        draftText = comment.text
    }
    fun beginComment(target: CommentDraft) {
        draft = target
        draftText = ""
    }
    fun saveComment() {
        val target = draft ?: return
        val value = draftText.trim()
        if (value.isEmpty()) return
        val next = if (target.editingId != null) currentComments.map { comment ->
            if (comment.id == target.editingId) comment.copy(text = value) else comment
        } else currentComments + DiffReviewComment(
            id = newReviewId(), fileId = target.fileId, path = target.path,
            target = target.target, text = value, hunkId = target.hunkId,
            side = target.side, startLine = target.line, endLine = target.endLine,
            oldStartLine = target.oldStartLine, oldEndLine = target.oldEndLine,
        )
        changeComments(next)
        draft = null
        draftText = ""
    }
    fun toggleViewed(fileId: String) {
        val next = if (fileId in currentViewed) currentViewed - fileId else currentViewed + fileId
        if (viewedFiles == null) {
            localViewed = next
            submitted = false
        }
        if (fileId !in next) unfoldedViewed = unfoldedViewed - fileId
        onViewedFilesChange?.invoke(next)
    }
    fun toggleDecision(id: String, decision: HunkDecision) {
        val next = if (currentDecisions[id] == decision) currentDecisions - id
            else currentDecisions + (id to decision)
        if (decisions == null) localDecisions = next
        onDecisionsChange?.invoke(next)
        submitted = false
    }
    fun setDecisions(next: Map<String, HunkDecision>) {
        if (next == currentDecisions) return
        if (decisions == null) localDecisions = next
        onDecisionsChange?.invoke(next)
        submitted = false
    }
    fun decideFile(file: FileDiffState, decision: HunkDecision) {
        val ids = reviewItemIds(file)
        setDecisions(currentDecisions + ids.associateWith { decision })
    }
    fun selectLine(fileId: String, hunkId: String, index: Int, extend: Boolean) {
        activeItemId = hunkId
        val current = selection
        selection = if (extend && current?.fileId == fileId && current.hunkId == hunkId)
            current.copy(head = index)
        else ReviewSelection(fileId, hunkId, index, index)
    }
    val hunksById = remember(document) { document?.files.orEmpty().flatMap { it.hunks }.associateBy { it.id } }
    val commentEnds = remember(document, currentComments) {
        currentComments.associate { it.id to commentEndIndex(it, hunksById[it.hunkId]) }
    }
    val reviewTargets = remember(document, currentViewed, unfoldedViewed, collapseViewed) {
        document?.files.orEmpty().filter { !collapseViewed || it.id !in currentViewed || it.id in unfoldedViewed }
            .flatMap { file -> reviewItemIds(file).map { file.id to it } }
    }
    fun targetIndex(): Int = reviewTargets.indexOfFirst { it.second == activeItemId }.takeIf { it >= 0 } ?: 0
    fun moveActive(step: Int, byFile: Boolean = false) {
        if (reviewTargets.isEmpty()) return
        val index = reviewTargets.indexOfFirst { it.second == activeItemId }
        val next = if (index < 0) reviewTargets.first() else if (byFile) {
            val fileId = reviewTargets[index].first
            (if (step > 0) reviewTargets.drop(index + 1) else reviewTargets.take(index).asReversed())
                .firstOrNull { it.first != fileId } ?: reviewTargets[index]
        } else reviewTargets[(index + step).coerceIn(0, reviewTargets.lastIndex)]
        activeItemId = next.second
        scrollTargetId = next.second
        selection = null
    }
    fun showContext(gap: ReviewContextGap, direction: ContextDirection) {
        val now = shownContext[gap.key] ?: ShownContext()
        val hidden = gap.size - now.start - now.end
        if (hidden <= 0) return
        val step = if (direction == ContextDirection.All) hidden else minOf(ContextStep, hidden)
        val next = if (direction == ContextDirection.End) now.copy(end = now.end + step)
            else now.copy(start = now.start + step)
        shownContext = shownContext + (gap.key to next)
    }
    fun submitReview() {
        val files = document?.files ?: return
        if (submitted || submitting || readOnly || (onSubmit == null && onSubmitAsync == null)) return
        val result = buildReviewResult(files, fileChanges, currentDecisions, currentViewed, currentComments)
        submitted = true
        submissionError = null
        if (onSubmitAsync != null) {
            submitting = true
            submissionScope.launch {
                try {
                    onSubmitAsync(result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    submissionError = "Review submission failed. Try again."
                } finally {
                    submitting = false
                    submitted = false
                }
            }
            return
        }
        try {
            onSubmit?.invoke(result)
        } catch (error: Throwable) {
            submitted = false
            throw error
        }
    }
    val visibleRows = remember(document, currentViewed, unfoldedViewed, collapseViewed) {
        document?.rows.orEmpty().filter { row ->
            row is ReviewRow.FileHeader || !collapseViewed ||
                row.fileId !in currentViewed || row.fileId in unfoldedViewed
        }
    }
    val rowListState = rememberLazyListState()
    LaunchedEffect(scrollTargetId, focusRequestVersion, visibleRows) {
        val id = scrollTargetId ?: return@LaunchedEffect
        val rowIndex = visibleRows.indexOfFirst { row ->
            (row is ReviewRow.HunkHeader && row.hunk.id == id) ||
                (row is ReviewRow.FileHeader && "${row.file.id}:file" == id)
        }
        if (rowIndex < 0) return@LaunchedEffect
        val layout = rowListState.layoutInfo
        val visible = layout.visibleItemsInfo.firstOrNull { it.index == rowIndex }
        if (visible == null || visible.offset < layout.viewportStartOffset ||
            visible.offset + visible.size > layout.viewportEndOffset) {
            rowListState.animateScrollToItem(rowIndex)
        }
        withFrameNanos { }
        itemFocusRequesters[id]?.requestFocus()
    }
    Column(modifier.testTag("diff-review-root")
        .focusRequester(rootFocusRequester).onFocusChanged {
            rootFocused = it.hasFocus
            if (it.isFocused || !it.hasFocus) focusedItemId = null
        }.focusProperties { canFocus = false }.focusable()
        .onPreviewKeyEvent { event ->
            if (!rootFocused || event.type != KeyEventType.KeyDown || draft != null || readOnly)
                return@onPreviewKeyEvent false
            if (event.key == Key.Enter && (event.isCtrlPressed || event.isMetaPressed)) {
                submitReview(); return@onPreviewKeyEvent true
            }
            val selected = selection
            val target = reviewTargets.firstOrNull {
                it.second == (selected?.hunkId ?: focusedItemId)
            }
            val file = document?.files?.firstOrNull { it.id == target?.first }
            if (event.isAltPressed && !event.isCtrlPressed && !event.isMetaPressed && !event.isShiftPressed) {
                if (file != null && (event.key == Key.A || event.key == Key.R)) {
                    decideFile(file, if (event.key == Key.A) HunkDecision.Accepted else HunkDecision.Rejected)
                    return@onPreviewKeyEvent true
                }
                return@onPreviewKeyEvent false
            }
            if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return@onPreviewKeyEvent false
            if (event.key == Key.Escape && selected != null) {
                selection = null; return@onPreviewKeyEvent true
            }
            if (event.key == Key.DirectionDown || event.key == Key.DirectionUp) {
                val direction = if (event.key == Key.DirectionDown) 1 else -1
                val hunk = selected?.let { hunksById[it.hunkId] }
                if (selected != null && hunk != null && hunk.lines.isNotEmpty()) {
                    val head = (selected.head + direction).coerceIn(0, hunk.lines.lastIndex)
                    selection = selected.copy(anchor = if (event.isShiftPressed) selected.anchor else head, head = head)
                } else if (!event.isShiftPressed && focusedItemId != null) moveActive(direction)
                else return@onPreviewKeyEvent false
                return@onPreviewKeyEvent true
            }
            if (event.key == Key.J || event.key == Key.K) {
                if (focusedItemId == null && !event.isShiftPressed) {
                    val itemId = activeItemId ?: reviewTargets.firstOrNull()?.second
                    if (itemId != null) {
                        activeItemId = itemId
                        scrollTargetId = itemId
                        focusRequestVersion++
                    }
                } else moveActive(if (event.key == Key.J) 1 else -1, byFile = event.isShiftPressed)
                return@onPreviewKeyEvent true
            }
            if (event.isShiftPressed && (event.key == Key.A || event.key == Key.R)) {
                val decision = if (event.key == Key.A) HunkDecision.Accepted else HunkDecision.Rejected
                val allIds = document?.files.orEmpty().flatMap(::reviewItemIds)
                setDecisions(currentDecisions + allIds.associateWith { decision })
                return@onPreviewKeyEvent true
            }
            if (event.isShiftPressed || target == null) return@onPreviewKeyEvent false
            when (event.key) {
                Key.A, Key.R, Key.X, Key.U -> {
                    val next = when (event.key) {
                        Key.A -> currentDecisions + (target.second to HunkDecision.Accepted)
                        Key.R, Key.X -> currentDecisions + (target.second to HunkDecision.Rejected)
                        else -> currentDecisions - target.second
                    }
                    setDecisions(next)
                    if (event.key != Key.U) {
                        reviewTargets.drop(targetIndex() + 1).firstOrNull { it.second !in next }
                            ?.let { activeItemId = it.second; scrollTargetId = it.second }
                    }
                    true
                }
                Key.C -> {
                    if (file != null) beginComment(CommentDraft(file.id, file.newPath,
                        if (target.second.endsWith(":file")) DiffCommentTarget.File else DiffCommentTarget.Hunk,
                        target.second.takeUnless { it.endsWith(":file") }))
                    true
                }
                Key.E -> {
                    if (file != null) {
                        val hunkIndex = file.hunks.indexOfFirst { it.id == target.second }
                        if (hunkIndex >= 0) document.gaps[file.id].orEmpty().forEach { gap ->
                            when (gap.before) {
                                hunkIndex -> showContext(gap, ContextDirection.End)
                                hunkIndex + 1 -> showContext(gap, ContextDirection.Start)
                            }
                        }
                    }
                    true
                }
                Key.V -> {
                    if (file != null) {
                        val marking = file.id !in currentViewed
                        val nextFile = if (marking && collapseViewed)
                            reviewTargets.drop(targetIndex() + 1).firstOrNull { it.first != file.id }
                        else null
                        toggleViewed(file.id)
                        if (marking && collapseViewed) {
                            activeItemId = nextFile?.second
                            scrollTargetId = nextFile?.second
                        }
                    }
                    true
                }
                else -> false
            }
        }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            UiText(title, palette, size = 16, weight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                UiButton("Unified", { view = DiffLayoutMode.Unified }, palette, primary = view == DiffLayoutMode.Unified)
                UiButton("Split", { view = DiffLayoutMode.Split }, palette, primary = view == DiffLayoutMode.Split)
            }
        }
        LazyColumn(Modifier.weight(1f).testTag("diff-review-rows"), state = rowListState,
            verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (document == null) item(key = "comparing") { UiText("Comparing…", palette, muted = true) }
            items(visibleRows, key = { it.key }) { row ->
                when (row) {
                    is ReviewRow.FileHeader -> {
                        val fileItemId = "${row.file.id}:file"
                        val itemFocusRequester = remember(fileItemId) { FocusRequester() }
                        DisposableEffect(fileItemId, itemFocusRequester) {
                            itemFocusRequesters[fileItemId] = itemFocusRequester
                            onDispose {
                                if (itemFocusRequesters[fileItemId] === itemFocusRequester) {
                                    itemFocusRequesters.remove(fileItemId)
                                }
                            }
                        }
                        Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 5.dp)
                            .testTag("diff-review-item-$fileItemId")
                            .focusRequester(itemFocusRequester)
                            .onFocusChanged {
                                if (it.hasFocus) {
                                    focusedItemId = fileItemId
                                    activeItemId = fileItemId
                                } else if (focusedItemId == fileItemId) focusedItemId = null
                            }
                            .focusProperties {
                                canFocus = fileItemId in reviewItemIds(row.file) &&
                                    fileItemId == (activeItemId ?: reviewTargets.firstOrNull()?.second)
                            }.focusable(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        val viewed = row.file.id in currentViewed
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            UiText("${row.file.newPath}   +${row.file.additions} −${row.file.deletions}",
                                palette, Modifier.weight(1f).clickable {
                                    val target = reviewItemIds(row.file).firstOrNull()
                                    activeItemId = target
                                    if (target == fileItemId) itemFocusRequester.requestFocus()
                                    else {
                                        scrollTargetId = target
                                        focusRequestVersion++
                                    }
                                }, weight = FontWeight.Medium)
                            if (!readOnly) UiButton(if (viewed) "Viewed" else "Mark viewed",
                                { toggleViewed(row.file.id) }, palette,
                                modifier = Modifier.semantics { selected = viewed }, primary = viewed)
                            else if (viewed) UiText("Viewed", palette, muted = true, size = 12)
                        }
                        if (!readOnly || (collapseViewed && viewed)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                if (!readOnly && reviewItemIds(row.file).isNotEmpty()) {
                                    UiButton("Reject file", { decideFile(row.file, HunkDecision.Rejected) }, palette)
                                    UiButton("Accept file", { decideFile(row.file, HunkDecision.Accepted) }, palette)
                                }
                                if (!readOnly) UiButton("Comment file", {
                                    beginComment(CommentDraft(row.file.id, row.file.newPath, DiffCommentTarget.File))
                                }, palette)
                                if (collapseViewed && viewed) UiButton(
                                    if (row.file.id in unfoldedViewed) "Hide file" else "Show file",
                                    { unfoldedViewed = if (row.file.id in unfoldedViewed)
                                        unfoldedViewed - row.file.id else unfoldedViewed + row.file.id }, palette)
                            }
                        }
                        if (row.file.isBinary) UiText("Binary file, not shown", palette, muted = true, size = 12)
                        else if (row.file.hunks.isEmpty()) UiText("No text changes", palette, muted = true, size = 12)
                        currentComments.filter { it.fileId == row.file.id && it.target == DiffCommentTarget.File }
                            .forEach { comment ->
                                ReviewComment(comment, readOnly, palette,
                                    onEdit = { editComment(comment) },
                                    onRemove = { changeComments(currentComments.filterNot { it.id == comment.id }) })
                            }
                        if (draft?.fileId == row.file.id && draft?.target == DiffCommentTarget.File)
                            ReviewCommentEditor(draftText, { draftText = it }, palette,
                                onSave = ::saveComment, onCancel = { draft = null })
                        UiDivider(palette)
                    }
                    }
                    is ReviewRow.HunkHeader -> {
                        val itemFocusRequester = remember(row.hunk.id) { FocusRequester() }
                        DisposableEffect(row.hunk.id, itemFocusRequester) {
                            itemFocusRequesters[row.hunk.id] = itemFocusRequester
                            onDispose {
                                if (itemFocusRequesters[row.hunk.id] === itemFocusRequester) {
                                    itemFocusRequesters.remove(row.hunk.id)
                                }
                            }
                        }
                        val decision = currentDecisions[row.hunk.id]
                        val activeSelection = selection?.takeIf { it.fileId == row.fileId && it.hunkId == row.hunk.id }
                        Column(Modifier.fillMaxWidth().background(if (activeItemId == row.hunk.id)
                            palette.accent.copy(alpha = 0.08f) else Color.Transparent).padding(top = 6.dp)
                            .testTag("diff-review-item-${row.hunk.id}")
                            .focusRequester(itemFocusRequester)
                            .onFocusChanged {
                                if (it.hasFocus) {
                                    focusedItemId = row.hunk.id
                                    activeItemId = row.hunk.id
                                } else if (focusedItemId == row.hunk.id) focusedItemId = null
                            }
                            .focusProperties {
                                canFocus = row.hunk.id == (activeItemId ?: reviewTargets.firstOrNull()?.second)
                            }.focusable(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            UiText(row.hunk.formattedHeader, palette,
                                Modifier.clickable {
                                    activeItemId = row.hunk.id
                                    itemFocusRequester.requestFocus()
                                }.semantics { selected = activeItemId == row.hunk.id }
                                    .testTag("diff-review-hunk-${row.hunk.id}"),
                                muted = true, size = 12, mono = true)
                            if (!readOnly) FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                UiButton("Comment hunk", {
                                    beginComment(CommentDraft(row.fileId,
                                        document?.files?.firstOrNull { it.id == row.fileId }?.newPath ?: row.fileId,
                                        DiffCommentTarget.Hunk, row.hunk.id))
                                }, palette)
                                UiButton("Accept", { activeItemId = row.hunk.id
                                    toggleDecision(row.hunk.id, HunkDecision.Accepted) }, palette,
                                    modifier = Modifier.semantics { selected = decision == HunkDecision.Accepted },
                                    primary = decision == HunkDecision.Accepted)
                                UiButton("Reject", { activeItemId = row.hunk.id
                                    toggleDecision(row.hunk.id, HunkDecision.Rejected) }, palette,
                                    modifier = Modifier.semantics { selected = decision == HunkDecision.Rejected },
                                    primary = decision == HunkDecision.Rejected)
                            }
                            if (!readOnly && activeSelection != null) FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                itemVerticalAlignment = Alignment.CenterVertically,
                            ) {
                                UiText("${activeSelection.to - activeSelection.from + 1} ${if (activeSelection.to == activeSelection.from) "line" else "lines"} selected",
                                    palette, muted = true, size = 12)
                                UiButton("Comment selected lines", {
                                    beginComment(selectedLinesDraft(row.fileId,
                                        document?.files?.firstOrNull { it.id == row.fileId }?.newPath ?: row.fileId,
                                        row.hunk, activeSelection))
                                }, palette)
                                UiButton("Clear selection", { selection = null }, palette)
                            }
                        }
                        currentComments.filter { it.hunkId == row.hunk.id && it.target == DiffCommentTarget.Hunk }
                            .forEach { comment -> ReviewComment(comment, readOnly, palette,
                                onEdit = { editComment(comment) },
                                onRemove = { changeComments(currentComments.filterNot { it.id == comment.id }) }) }
                        if (draft?.hunkId == row.hunk.id && draft?.target == DiffCommentTarget.Hunk)
                            ReviewCommentEditor(draftText, { draftText = it }, palette,
                                onSave = ::saveComment, onCancel = { draft = null })
                    }
                    is ReviewRow.UnifiedLine -> Column {
                        val line = row.line
                        val side = if (line.newLineNumber != null) DiffCommentSide.New else DiffCommentSide.Old
                        val number = line.newLineNumber ?: line.oldLineNumber
                        DiffReviewLine(line, palette, if (readOnly || number == null) null else ({
                            beginComment(CommentDraft(row.fileId,
                                document?.files?.firstOrNull { it.id == row.fileId }?.newPath ?: row.fileId,
                                DiffCommentTarget.Lines, row.hunkId, side, number, endIndex = row.index))
                        }), onSelect = if (readOnly || number == null) null else ({ extend ->
                            selectLine(row.fileId, row.hunkId, row.index, extend)
                        }), selected = selection?.let { it.fileId == row.fileId && it.hunkId == row.hunkId && row.index in it.from..it.to } == true,
                            selectionSide = side)
                        currentComments.filter { it.hunkId == row.hunkId && it.target == DiffCommentTarget.Lines &&
                            commentEnds[it.id] == row.index }.forEach { comment ->
                            ReviewComment(comment, readOnly, palette,
                                onEdit = { editComment(comment) },
                                onRemove = { changeComments(currentComments.filterNot { it.id == comment.id }) })
                        }
                        if (draft?.hunkId == row.hunkId && draft?.target == DiffCommentTarget.Lines &&
                            draft?.endIndex == row.index)
                            ReviewCommentEditor(draftText, { draftText = it }, palette,
                                onSave = ::saveComment, onCancel = { draft = null })
                    }
                    is ReviewRow.SplitLine -> Column {
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) { row.line.left?.let { line ->
                                DiffReviewLine(line, palette, if (readOnly || line.oldLineNumber == null) null else ({
                                    beginComment(CommentDraft(row.fileId,
                                        document?.files?.firstOrNull { it.id == row.fileId }?.newPath ?: row.fileId,
                                        DiffCommentTarget.Lines, row.hunkId, DiffCommentSide.Old, line.oldLineNumber,
                                        endIndex = row.line.leftIndex))
                                }), onSelect = if (readOnly || row.line.leftIndex == null) null else ({ extend ->
                                    row.line.leftIndex?.let { index -> selectLine(row.fileId, row.hunkId, index, extend) }
                                }), selected = selection?.let { it.fileId == row.fileId && it.hunkId == row.hunkId &&
                                    row.line.leftIndex?.let { index -> index in it.from..it.to } == true } == true,
                                    selectionSide = DiffCommentSide.Old)
                            } }
                            Spacer(Modifier.width(1.dp).background(palette.border))
                            Column(Modifier.weight(1f)) { row.line.right?.let { line ->
                                DiffReviewLine(line, palette, if (readOnly || line.newLineNumber == null) null else ({
                                    beginComment(CommentDraft(row.fileId,
                                        document?.files?.firstOrNull { it.id == row.fileId }?.newPath ?: row.fileId,
                                        DiffCommentTarget.Lines, row.hunkId, DiffCommentSide.New, line.newLineNumber,
                                        endIndex = row.line.rightIndex))
                                }), onSelect = if (readOnly || row.line.rightIndex == null) null else ({ extend ->
                                    row.line.rightIndex?.let { index -> selectLine(row.fileId, row.hunkId, index, extend) }
                                }), selected = selection?.let { it.fileId == row.fileId && it.hunkId == row.hunkId &&
                                    row.line.rightIndex?.let { index -> index in it.from..it.to } == true } == true,
                                    selectionSide = DiffCommentSide.New)
                            } }
                        }
                        currentComments.filter { it.hunkId == row.hunkId && it.target == DiffCommentTarget.Lines &&
                            commentEnds[it.id]?.let { index -> index == row.line.leftIndex ||
                                index == row.line.rightIndex } == true }
                            .forEach { comment -> ReviewComment(comment, readOnly, palette,
                                onEdit = { editComment(comment) },
                                onRemove = { changeComments(currentComments.filterNot { it.id == comment.id }) }) }
                        if (draft?.hunkId == row.hunkId && draft?.target == DiffCommentTarget.Lines &&
                            draft?.endIndex?.let { index -> index == row.line.leftIndex ||
                                index == row.line.rightIndex } == true)
                            ReviewCommentEditor(draftText, { draftText = it }, palette,
                                onSave = ::saveComment, onCancel = { draft = null })
                    }
                    is ReviewRow.ContextLine -> {
                        val description = "Unchanged line old ${row.line.oldLineNumber} new ${row.line.newLineNumber}"
                        if (view == DiffLayoutMode.Unified) Box(Modifier.semantics {
                            contentDescription = description
                        }) { DiffReviewLine(row.line, palette) }
                        else Row(Modifier.fillMaxWidth().semantics { contentDescription = description }) {
                            Column(Modifier.weight(1f)) { DiffReviewLine(row.line, palette) }
                            Spacer(Modifier.width(1.dp).background(palette.border))
                            Column(Modifier.weight(1f)) { DiffReviewLine(row.line, palette) }
                        }
                    }
                    is ReviewRow.GapControl -> if (!readOnly) {
                        FlowRow(Modifier.fillMaxWidth().testTag("diff-review-gap-${row.gap.key}")
                            .padding(start = 12.dp, top = 3.dp, bottom = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            UiText("${row.hidden} unchanged lines", palette, muted = true, size = 12, mono = true)
                            if (row.hidden > ContextStep && row.gap.before > 0) UiButton(
                                "Show $ContextStep after hunk", { showContext(row.gap, ContextDirection.Start) }, palette)
                            if (row.hidden > ContextStep && !row.last) UiButton(
                                "Show $ContextStep before hunk", { showContext(row.gap, ContextDirection.End) }, palette)
                            UiButton("Show all ${row.hidden}",
                                { showContext(row.gap, ContextDirection.All) }, palette)
                        }
                    }
                }
            }
        }
        if (submissionError != null) UiText(submissionError!!, palette, size = 12)
        if (!readOnly) {
            val isMobile = AgentUiTheme.sizing.isMobile
            UiButton(
                if (document == null) "Comparing…" else if (submitting) "Submitting…" else
                    "Apply ${document.files.sumOf { file -> reviewItemIds(file).count { currentDecisions[it] == HunkDecision.Accepted } }} of ${document.files.sumOf { reviewItemIds(it).size }}",
                ::submitReview,
                palette,
                modifier = if (isMobile) Modifier.fillMaxWidth() else Modifier,
                primary = true,
                enabled = document != null && !submitted && !submitting &&
                    (onSubmit != null || onSubmitAsync != null),
            )
        }
    }
}

@Composable
private fun DiffReviewLine(line: DiffLine, palette: ReactUiPalette, onComment: (() -> Unit)? = null,
    onSelect: ((Boolean) -> Unit)? = null, selected: Boolean = false,
    selectionSide: DiffCommentSide = DiffCommentSide.New) {
    val sizing = AgentUiTheme.sizing
    val tint = when (line.type) {
        DiffLineType.Added -> palette.success.copy(alpha = 0.15f)
        DiffLineType.Deleted -> palette.danger.copy(alpha = 0.15f)
        else -> palette.background
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var commentFocused by remember { mutableStateOf(false) }
    val selectionFocusRequester = remember(line.id, selectionSide) { FocusRequester() }
    fun selectModifier(side: DiffCommentSide, number: Int?): Modifier {
        val base = Modifier.width(if (sizing.isMobile) 34.dp else 30.dp)
        if (onSelect == null || side != selectionSide || number == null) return base
        fun select(extend: Boolean) {
            onSelect(extend)
            selectionFocusRequester.requestFocus()
        }
        return base.pointerInput(onSelect) {
            awaitEachGesture {
                awaitFirstDown()
                val extend = currentEvent.keyboardModifiers.isShiftPressed
                val up = waitForUpOrCancellation()
                if (up != null) {
                    select(extend)
                    up.consume()
                }
            }
        }.onKeyEvent { event ->
            if (event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.Spacebar)) {
                select(event.isShiftPressed)
                true
            } else false
        }.focusRequester(selectionFocusRequester).focusable().semantics {
            role = Role.Button
            contentDescription = "Select ${if (side == DiffCommentSide.New) "new" else "old"} line $number"
            this.selected = selected
            onClick { select(false); true }
        }
    }
    Row(Modifier.fillMaxWidth().background(if (selected) palette.accent.copy(alpha = 0.19f) else tint).hoverable(interaction)
        .padding(horizontal = 6.dp, vertical = if (sizing.isMobile) 4.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        UiText("${line.oldLineNumber ?: " "}", palette, selectModifier(DiffCommentSide.Old, line.oldLineNumber),
            muted = true, mono = true, size = 12)
        UiText("${line.newLineNumber ?: " "}", palette, selectModifier(DiffCommentSide.New, line.newLineNumber),
            muted = true, mono = true, size = 12)
        UiText(line.text, palette, Modifier.weight(1f), mono = true, size = 12)
        if (line.noNewline) UiText("no newline", palette, muted = true, size = 10)
        if (onComment != null) Box(
            Modifier.size(if (sizing.isMobile) 28.dp else 22.dp)
                .alpha(if (sizing.isMobile || selected || hovered || commentFocused) 1f else 0f)
                .onFocusChanged { commentFocused = it.isFocused }
                .semantics {
                    contentDescription = "Comment on ${line.newLineNumber?.let { "new line $it" } ?: "old line ${line.oldLineNumber}"}"
                }
                .clickable(role = Role.Button, onClick = onComment),
            contentAlignment = Alignment.Center,
        ) { UiText("+", palette, size = 15, weight = FontWeight.Bold) }
    }
}

@Composable
private fun ReviewComment(
    comment: DiffReviewComment,
    readOnly: Boolean,
    palette: ReactUiPalette,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (comment.target == DiffCommentTarget.Lines && comment.startLine != null) {
            val side = if (comment.side == DiffCommentSide.Old) "Old" else "New"
            val end = comment.endLine ?: comment.startLine
            UiText("$side lines ${comment.startLine}${if (end != comment.startLine) "–$end" else ""}",
                palette, muted = true, size = 11)
        }
        UiText(comment.text, palette, size = 13)
        if (!readOnly) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            UiButton("Edit comment", onEdit, palette)
            UiButton("Delete comment", onRemove, palette)
        }
    }
}

@Composable
private fun ReviewCommentEditor(
    text: String,
    onTextChange: (String) -> Unit,
    palette: ReactUiPalette,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    var editorValue by remember { mutableStateOf(TextFieldValue(text)) }
    SideEffect {
        if (editorValue.text != text) editorValue = TextFieldValue(text, TextRange(text.length))
    }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        UiInput(editorValue, { next ->
            editorValue = next
            onTextChange(next.text)
        }, "Leave a review comment", palette,
            Modifier.fillMaxWidth().testTag("review-comment-input").onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || editorValue.composition != null) false
                else when {
                    event.key == Key.Enter && (event.isCtrlPressed || event.isMetaPressed) -> {
                        onSave(); true
                    }
                    event.key == Key.Escape -> { onCancel(); true }
                    else -> false
                }
            })
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            UiButton("Save comment", onSave, palette, primary = true, enabled = text.isNotBlank())
            UiButton("Cancel", onCancel, palette)
        }
    }
}
