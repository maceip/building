package fragments.diff

import androidx.compose.runtime.Immutable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
private fun newDiffId(): String = Uuid.random().toString()

@OptIn(ExperimentalTime::class)
private fun currentEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * Layout mode for diff rendering:
 * - [Unified]: Single-column unified diff with old and new line numbers.
 * - [Split]: Two-column side-by-side diff comparing base (left) with modified (right).
 */
enum class DiffLayoutMode(val label: String) {
    Unified("Unified"),
    Split("Split"),
}

/**
 * Type of line in a diff hunk.
 */
enum class DiffLineType {
    Context,
    Added,
    Deleted,
    HunkHeader,
}

/**
 * A single line in a diff hunk.
 */
@Immutable
data class DiffLine(
    val id: String = newDiffId(),
    val type: DiffLineType,
    val text: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
    val noNewline: Boolean = false,
) {
    val content: String get() = text

    constructor(
        id: String = newDiffId(),
        oldLineNumber: Int?,
        newLineNumber: Int?,
        content: String,
        type: DiffLineType,
    ) : this(
        id = id,
        type = type,
        text = content,
        oldLineNumber = oldLineNumber,
        newLineNumber = newLineNumber,
    )
}

/**
 * A hunk in a unified diff (`@@ -oldStart,oldCount +newStart,newCount @@`).
 */
data class DiffHunk(
    val id: String = newDiffId(),
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val headerText: String = "",
    val lines: List<DiffLine> = emptyList(),
    val isCollapsed: Boolean = false,
) {
    val additionsCount: Int get() = lines.count { it.type == DiffLineType.Added }
    val deletionsCount: Int get() = lines.count { it.type == DiffLineType.Deleted }

    val formattedHeader: String
        get() = "@@ -$oldStart,$oldCount +$newStart,$newCount @@${if (headerText.isNotEmpty()) " $headerText" else ""}"

    val header: String get() = formattedHeader
    val isExpanded: Boolean get() = !isCollapsed

    constructor(
        oldStart: Int,
        oldCount: Int,
        newStart: Int,
        newCount: Int,
        header: String,
        lines: List<DiffLine>,
        isExpanded: Boolean = true,
    ) : this(
        id = newDiffId(),
        oldStart = oldStart,
        oldCount = oldCount,
        newStart = newStart,
        newCount = newCount,
        headerText = if (header.startsWith("@@") && header.indexOf("@@", 2) != -1) {
            header.substring(header.indexOf("@@", 2) + 2).trim()
        } else {
            header
        },
        lines = lines,
        isCollapsed = !isExpanded,
    )
}

/**
 * A row in a side-by-side split diff.
 */
data class SplitDiffRow(
    val left: DiffLine? = null,
    val right: DiffLine? = null,
    val leftIndex: Int? = null,
    val rightIndex: Int? = null,
)

/**
 * Pure algorithm converting a list of unified diff lines into aligned split diff rows.
 * Context lines appear on both sides.
 * Consecutive deletion and addition blocks are paired row-by-row with null placeholders.
 */
fun DiffHunk.toSplitRows(): List<SplitDiffRow> {
    val rows = mutableListOf<SplitDiffRow>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.type == DiffLineType.Context) {
            rows.add(SplitDiffRow(left = line, right = line, leftIndex = i, rightIndex = i))
            i++
            continue
        }
        val dels = mutableListOf<Pair<Int, DiffLine>>()
        val adds = mutableListOf<Pair<Int, DiffLine>>()
        while (i < lines.size && lines[i].type == DiffLineType.Deleted) {
            dels.add(i to lines[i])
            i++
        }
        while (i < lines.size && lines[i].type == DiffLineType.Added) {
            adds.add(i to lines[i])
            i++
        }
        val maxLen = maxOf(dels.size, adds.size)
        for (k in 0 until maxLen) {
            val del = dels.getOrNull(k)
            val add = adds.getOrNull(k)
            rows.add(
                SplitDiffRow(
                    left = del?.second,
                    right = add?.second,
                    leftIndex = del?.first,
                    rightIndex = add?.first,
                )
            )
        }
    }
    return rows
}

/**
 * Git change status for a file.
 */
enum class FileChangeStatus(val label: String, val code: Char) {
    Modified("Modified", 'M'),
    Added("Added", 'A'),
    Deleted("Deleted", 'D'),
    Renamed("Renamed", 'R'),
}

/**
 * State representing a single changed file in a multi-file diff view.
 */
data class FileDiffState(
    val id: String,
    val oldPath: String? = null,
    val newPath: String,
    val status: FileChangeStatus = FileChangeStatus.Modified,
    val hunks: List<DiffHunk> = emptyList(),
    val isViewed: Boolean = false,
    val isExpanded: Boolean = true,
    val originalContent: String = "",
    val currentContent: String = "",
    val isBinary: Boolean = false,
) {
    val fileId: String get() = id
    val filePath: String get() = newPath
    val additions: Int get() = hunks.sumOf { it.additionsCount }
    val deletions: Int get() = hunks.sumOf { it.deletionsCount }
    val addedCount: Int get() = additions
    val removedCount: Int get() = deletions
    val totalChanges: Int get() = additions + deletions
    val path: String get() = newPath

    constructor(
        fileId: String,
        filePath: String = fileId,
        originalContent: String = "",
        currentContent: String = originalContent,
        hunks: List<DiffHunk> = emptyList(),
        isViewed: Boolean = false,
        isExpanded: Boolean = true,
        status: FileChangeStatus = FileChangeStatus.Modified,
    ) : this(
        id = fileId,
        oldPath = null,
        newPath = filePath,
        status = status,
        hunks = hunks,
        isViewed = isViewed,
        isExpanded = isExpanded,
        originalContent = originalContent,
        currentContent = currentContent,
    )
}

/**
 * Author role for a review comment.
 */
enum class ReviewAuthorRole(val label: String, val badge: String) {
    Human("Human", "reviewer"),
    Agent("Agent", "bot"),
    System("System", "system"),
}

/**
 * In-memory patch operation targeting a line range in a specific file.
 */
data class PatchOperation(
    val id: String = newDiffId(),
    val targetFileId: String,
    val startLine: Int,
    val endLine: Int,
    val originalLines: List<String> = emptyList(),
    val replacementLines: List<String> = emptyList(),
    val description: String = "",
    val applied: Boolean = false,
)

/**
 * Comment in an inline anchored review thread.
 */
data class ReviewComment(
    val id: String = newDiffId(),
    val author: String,
    val role: ReviewAuthorRole = ReviewAuthorRole.Human,
    val content: String = "",
    val timestamp: String = "Just now",
    val patchOperation: PatchOperation? = null,
    val suggestedPatchText: String? = null,
) {
    val isAgent: Boolean get() = role == ReviewAuthorRole.Agent
    val text: String get() = content
    val suggestedPatch: PatchOperation? get() = patchOperation

    constructor(
        id: String = newDiffId(),
        author: String,
        isAgent: Boolean = false,
        content: String = "",
        timestamp: String = "Just now",
        suggestedPatch: String? = null,
    ) : this(
        id = id,
        author = author,
        role = if (isAgent) ReviewAuthorRole.Agent else ReviewAuthorRole.Human,
        content = content,
        timestamp = timestamp,
        patchOperation = null,
        suggestedPatchText = suggestedPatch,
    )

    constructor(
        author: String,
        role: ReviewAuthorRole = ReviewAuthorRole.Human,
        timestamp: String = "Just now",
        text: String = "",
        suggestedPatch: PatchOperation? = null,
    ) : this(
        id = newDiffId(),
        author = author,
        role = role,
        content = text,
        timestamp = timestamp,
        patchOperation = suggestedPatch,
        suggestedPatchText = suggestedPatch?.replacementLines?.joinToString("\n"),
    )
}

/**
 * Inline review thread anchored to a specific line range in a file.
 */
data class LineReviewThread(
    val id: String = newDiffId(),
    val fileId: String,
    val startLine: Int,
    val endLine: Int,
    val comments: List<ReviewComment> = emptyList(),
    val isResolved: Boolean = false,
    val patchApplied: Boolean = false,
    val isExpanded: Boolean = true,
    val isDraftingReply: Boolean = false,
    val replyDraftText: String = "",
) {
    val targetFileId: String get() = fileId
    val lineRangeLabel: String
        get() = if (startLine == endLine) "Line $startLine" else "Lines $startLine–$endLine"

    val hasSuggestedPatch: Boolean
        get() = comments.any { it.patchOperation != null || !it.suggestedPatchText.isNullOrBlank() }

    val activePatch: PatchOperation?
        get() = comments.mapNotNull { it.patchOperation }.firstOrNull { !it.applied }
            ?: comments.mapNotNull { it.patchOperation }.lastOrNull()

    constructor(
        targetFileId: String,
        startLine: Int,
        endLine: Int,
        comments: List<ReviewComment> = emptyList(),
        isResolved: Boolean = false,
        patchApplied: Boolean = false,
        isExpanded: Boolean = true,
        isDraftingReply: Boolean = false,
        replyDraftText: String = "",
    ) : this(
        id = newDiffId(),
        fileId = targetFileId,
        startLine = startLine,
        endLine = endLine,
        comments = comments,
        isResolved = isResolved,
        patchApplied = patchApplied,
        isExpanded = isExpanded,
        isDraftingReply = isDraftingReply,
        replyDraftText = replyDraftText,
    )
}

/**
 * Result of an in-memory buffer patch application.
 */
sealed class PatchApplyResult {
    val isSuccess: Boolean get() = this is Success

    data class Success(val modifiedLinesCount: Int) : PatchApplyResult()
    data class Conflict(val reason: String) : PatchApplyResult()
    data class Error(val message: String) : PatchApplyResult()
}

/**
 * Snapshot of file buffer content prior to mutation, enabling undo (⌘Z).
 */
data class BufferSnapshot(
    val fileId: String,
    val content: String,
    val patchId: String?,
)

// --- Artifact Canvas Models ---

/**
 * Versioned snapshot of an editable code artifact.
 */
data class ArtifactVersion(
    val versionId: String,
    val versionNumber: Int,
    val timestamp: Long = currentEpochMillis(),
    val author: String = "User",
    val summary: String = "Initial revision",
    val content: String,
)

/**
 * Text selection inside an artifact canvas.
 */
data class ArtifactSelection(
    val startIndex: Int,
    val endIndex: Int,
    val selectedText: String,
    val startLine: Int = 1,
    val endLine: Int = 1,
)

enum class VersionDiffKind {
    Unchanged,
    Added,
    Deleted,
}

data class VersionDiffLine(
    val kind: VersionDiffKind,
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
    val text: String,
)
