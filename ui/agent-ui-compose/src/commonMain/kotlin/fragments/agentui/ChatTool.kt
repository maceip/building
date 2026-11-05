package fragments.agentui

import androidx.compose.runtime.Immutable

enum class ChatToolStatus { Running, Completed, Error, NeedsApproval }

@Immutable
data class ChatPlanStep(val label: String, val completed: Boolean = false)
enum class ChatTodoStatus { Pending, InProgress, Completed }
@Immutable data class ChatTodoItem(
    val content: String, val status: ChatTodoStatus = ChatTodoStatus.Pending,
    val activeForm: String? = null,
)
@Immutable data class ChatPlanDocument(
    val title: String, val summary: String = "", val fileId: String? = null,
    val approved: Boolean = false, val approveLabel: String = "Approve",
)
@Immutable data class ChatSearchResult(
    val source: String, val title: String, val date: String = "",
)

/** Kit-owned tool model. Source conversions stay behind the renderer adapter. */
@Immutable
sealed interface ChatTool {
    val id: String
    val status: ChatToolStatus

    data class Bash(override val id: String, val command: String, val output: String = "", val exitCode: Int? = null, override val status: ChatToolStatus = ChatToolStatus.Completed) : ChatTool
    data class Edit(override val id: String, val file: String, val diff: List<String>, override val status: ChatToolStatus = ChatToolStatus.Completed) : ChatTool
    data class Search(override val id: String, val query: String, val results: List<String>, override val status: ChatToolStatus = ChatToolStatus.Completed,
        val richResults: List<ChatSearchResult>? = null, val defaultOpen: Boolean = false) : ChatTool
    data class Todo(override val id: String, val items: List<String>, override val status: ChatToolStatus = ChatToolStatus.Completed,
        val todoItems: List<ChatTodoItem>? = null) : ChatTool
    data class Plan(override val id: String, val steps: List<ChatPlanStep>, override val status: ChatToolStatus = ChatToolStatus.Completed,
        val document: ChatPlanDocument? = null) : ChatTool
    data class Thinking(override val id: String, val text: String, override val status: ChatToolStatus = ChatToolStatus.Running) : ChatTool
    data class Mcp(override val id: String, val server: String, val action: String, val result: String, override val status: ChatToolStatus = ChatToolStatus.Completed) : ChatTool
    data class Generic(override val id: String, val name: String, val detail: String,
        override val status: ChatToolStatus = ChatToolStatus.Completed,
        val kind: String = name,
        val fields: Map<String, String> = emptyMap()) : ChatTool
}
