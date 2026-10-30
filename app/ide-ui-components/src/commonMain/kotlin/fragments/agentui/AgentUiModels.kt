package fragments.agentui

import androidx.compose.runtime.Immutable

enum class ChatApprovalStatus { Pending, Approved, Denied }
enum class ChatRiskLevel { Low, Medium, High, Critical }
@Immutable data class ChatApprovalChoice(
    val decision: String,
    val reason: String? = null,
    val input: String? = null,
    /** Patterns for a session or permanent rule; null means any arguments. */
    val args: Map<String, String>? = null,
)
@Immutable data class ChatApprovalRequest(
    val id: String,
    val toolName: String,
    val description: String = "",
    val input: String = "",
    val risk: ChatRiskLevel? = null,
    val status: ChatApprovalStatus = ChatApprovalStatus.Pending,
)

@Immutable data class ChatRunUsage(
    val inputTokens: Int? = null, val outputTokens: Int? = null,
    val cacheReadTokens: Int? = null, val reasoningTokens: Int? = null,
)
@Immutable data class ChatModelPricing(val inputPerMillion: Double, val outputPerMillion: Double)
@Immutable data class ChatSource(val title: String, val detail: String = "", val url: String? = null)

enum class ChatHunkDecision { Accepted, Rejected }
enum class ChatDiffFileDecision { Accepted, Rejected, Pending, Partial, Unchanged }
enum class ChatFileChangeStatus { Modified, Added, Deleted, Renamed }
@Immutable data class ChatFileChange(
    val path: String,
    val oldPath: String? = null,
    val oldContent: String? = null,
    val newContent: String? = null,
    val patch: String? = null,
    val language: String? = null,
    val binary: Boolean? = null,
)
enum class ChatDiffCommentTarget { File, Hunk, Lines }
enum class ChatDiffCommentSide { New, Old }
@Immutable data class ChatDiffReviewComment(
    val id: String, val fileId: String, val path: String,
    val target: ChatDiffCommentTarget, val text: String,
    val hunkId: String? = null, val side: ChatDiffCommentSide? = null,
    val startLine: Int? = null, val endLine: Int? = null,
    val oldStartLine: Int? = null, val oldEndLine: Int? = null,
)
@Immutable data class ChatDiffReviewResult(
    val decisions: Map<String, ChatHunkDecision>, val acceptedCount: Int, val rejectedCount: Int,
    val viewedFiles: Set<String> = emptySet(),
    val comments: List<ChatDiffReviewComment> = emptyList(),
    val acceptedPatch: String = "",
    val pendingCount: Int = 0,
    val files: List<ChatDiffReviewFileResult> = emptyList(),
    val rejectedHunks: List<ChatDiffRejectedHunk> = emptyList(),
)

@Immutable data class ChatDiffReviewFileResult(
    val path: String,
    val oldPath: String?,
    val status: ChatFileChangeStatus,
    val binary: Boolean,
    val decision: ChatDiffFileDecision,
    val viewed: Boolean,
    val content: String?,
    val accepted: List<String>,
    val rejected: List<String>,
    val pending: List<String>,
)

@Immutable data class ChatDiffRejectedHunk(
    val id: String,
    val path: String,
    val header: String,
    val diff: String,
)

enum class ChatInboxKind { Approval, Question, FailedRun, Review }
enum class ChatInboxUrgency { High, Normal, Low }
@Immutable data class ChatInboxItem(
    val id: String, val kind: ChatInboxKind, val title: String, val agentName: String,
    val createdAt: Long, val urgency: ChatInboxUrgency, val detail: String = "",
)

enum class ChatSpeakerKind { User, Agent, Subagent, Tool }
@Immutable data class ChatSpeakerTurn(
    val id: String, val kind: ChatSpeakerKind, val name: String,
    val text: String, val detail: String = "",
)
@Immutable data class ChatSubagent(val name: String, val model: String)
enum class ChatTaskState { Working, Waiting, Done, Failed, Cancelled }
@Immutable data class ChatTriggerCategory(val id: String, val label: String)
@Immutable data class ChatTriggerItem(
    val id: String, val label: String, val category: String, val description: String = "",
    val type: String = "agent",
)

sealed interface ChatTriggerBehavior {
    class Action(
        val removeOnExecute: Boolean = false,
        val onExecute: (ChatTriggerItem, Boolean) -> Unit,
    ) : ChatTriggerBehavior

    class Directive(
        val formatter: (ChatTriggerItem) -> String = ::serializeChatTriggerDirective,
        val onInserted: (ChatTriggerItem, String) -> Unit,
    ) : ChatTriggerBehavior
}

private fun quoteDirectiveValue(value: String): String = buildString {
    append('"')
    value.forEach { character -> when (character) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (character.code < 0x20) append("\\u" + character.code.toString(16).padStart(4, '0')) else append(character)
    } }
    append('"')
}

fun serializeChatTriggerDirective(item: ChatTriggerItem): String {
    if (']' in item.label || (item.id != item.label && '}' in item.id)) {
        val name = if (item.id == item.label) "" else " name=${quoteDirectiveValue(item.id)}"
        return ":${item.type}{label=${quoteDirectiveValue(item.label)}$name}"
    }
    val name = if (item.id == item.label) "" else "{name=${item.id}}"
    return ":${item.type}[${item.label}]$name"
}

/**
 * Classifies the two persistent quick-action rows displayed in every chat window:
 * - [Navigation]: Row 1 — "Where you want to go" (e.g., "Open <Project X>", "All projects", "Agent settings").
 * - [Task]: Row 2 — "What you want done" (e.g., "Rebuild project", "Submit to the Google Play Store for inclusion").
 */
enum class ChatActionPurpose {
    Navigation,
    Task,
}

@Immutable
data class ChatQuickAction(
    val id: String,
    val label: String,
    val purpose: ChatActionPurpose,
    val subtitle: String = "",
    val primary: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)
