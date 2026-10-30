package fragments.agentui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dev.ide.ui.concurrent.UiLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmInline
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalTime::class)
private fun currentRuntimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

@OptIn(ExperimentalUuidApi::class)
private fun randomRuntimeId(): String = Uuid.random().toString()

@JvmInline value class ConversationId(val value: String)
@JvmInline value class AgentId(val value: String)
@JvmInline value class SessionId(val value: String)
@JvmInline value class TurnId(val value: String)
@JvmInline value class RunId(val value: String)

@Immutable
data class AgentCapabilities(
    val send: Boolean = true,
    val steer: Boolean = false,
    val interrupt: Boolean = false,
    val approve: Boolean = false,
    val answerQuestion: Boolean = false,
    val attachments: Boolean = false,
)

@Immutable data class AgentParticipant(val id: AgentId, val name: String, val capabilities: AgentCapabilities)
enum class TurnDelivery { Pending, Accepted, Rejected }
enum class RunState { Working, AwaitingApproval, Complete, Interrupted, Failed }
@Immutable data class AgentRun(val id: RunId, val agentId: AgentId, val state: RunState)
data class ConversationAttachment(val id: String, val file: File)
@Immutable data class ConversationQuestionOption(val id: String, val label: String)

/** Parts are ordered within a turn. IDs remain stable while streaming updates replace content. */
@Immutable
sealed interface ConversationPart {
    val id: String

    data class Text(override val id: String, val value: String, val streaming: Boolean = false) : ConversationPart
    data class Reasoning(override val id: String, val value: String) : ConversationPart
    data class Tool(override val id: String, val value: ChatTool) : ConversationPart
    data class ToolGroup(override val id: String, val value: ChatToolGroup) : ConversationPart
    data class ToolTimeline(override val id: String, val calls: List<ChatToolTimelineCall>, val active: Boolean = true) : ConversationPart
    data class Source(override val id: String, val title: String, val detail: String = "", val url: String? = null) : ConversationPart
    data class Image(override val id: String, val alt: String, val bitmap: ImageBitmap? = null) : ConversationPart
    data class Attachment(override val id: String, val name: String, val sizeBytes: Long) : ConversationPart
    data class Browser(override val id: String, val sessionId: String, val title: String, val url: String? = null) : ConversationPart
    data class BrowserStep(
        override val id: String,
        val sessionId: String,
        val stepIndex: Int,
        val url: String,
        val action: String,
        val thoughts: String,
        val actionResult: String,
        val elementHandle: String? = null,
        val elementLabel: String? = null,
    ) : ConversationPart
    data class Diff(override val id: String, val reviewId: String, val title: String, val summary: String = "") : ConversationPart
    data class Question(override val id: String, val prompt: String, val options: List<ConversationQuestionOption>, val answer: String? = null) : ConversationPart
    data class Custom(override val id: String, val kind: String, val fields: Map<String, String>) : ConversationPart
}

@Immutable
data class ConversationTurn(
    val id: TurnId,
    val authorId: String,
    val agentId: AgentId? = null,
    val runId: RunId? = null,
    val parts: List<ConversationPart> = emptyList(),
    val delivery: TurnDelivery = TurnDelivery.Accepted,
)

@Immutable
data class ConversationSnapshot(
    val conversationId: ConversationId,
    val participants: List<AgentParticipant> = emptyList(),
    val turns: List<ConversationTurn> = emptyList(),
    val runs: List<AgentRun> = emptyList(),
    val drafts: Map<AgentId, String> = emptyMap(),
    val attachments: Map<AgentId, List<ConversationAttachment>> = emptyMap(),
    val revision: Long = 0,
)

/** Source sequence is scoped to one agent session; the coordinator assigns displaySequence. */
@Immutable
data class EventOrigin(
    val agentId: AgentId? = null,
    val sessionId: SessionId? = null,
    val sourceSequence: Long? = null,
) {
    init {
        require((sessionId == null) == (sourceSequence == null))
        require(sessionId == null || agentId != null)
    }
}

@Immutable
sealed interface ConversationChange {
    data class TurnOpened(val turn: ConversationTurn) : ConversationChange
    data class PartUpserted(val turnId: TurnId, val part: ConversationPart) : ConversationChange
    data class DeliveryChanged(val turnId: TurnId, val delivery: TurnDelivery) : ConversationChange
    data class RunChanged(val run: AgentRun) : ConversationChange
}

@Immutable
data class ConversationEvent(
    val id: String,
    val origin: EventOrigin,
    val correlationId: String?,
    val displaySequence: Long,
    val timestampEpochMillis: Long,
    val change: ConversationChange,
)

@Immutable
data class IncomingConversationEvent(
    val id: String,
    val origin: EventOrigin,
    val correlationId: String? = null,
    val change: ConversationChange,
    val timestampEpochMillis: Long = currentRuntimeMillis(),
)

sealed interface ConversationCommand {
    data class Send(val target: AgentId, val text: String, val attachments: List<ConversationAttachment> = emptyList()) : ConversationCommand
    data class Steer(val target: AgentId, val runId: RunId, val text: String) : ConversationCommand
    data class Interrupt(val target: AgentId, val runId: RunId) : ConversationCommand
    data class ResolveApproval(val target: AgentId, val runId: RunId, val toolId: String, val approved: Boolean) : ConversationCommand
    data class RespondToQuestion(val target: AgentId, val runId: RunId, val turnId: TurnId, val questionId: String, val answer: String) : ConversationCommand
    /** A UI part is never inserted into a model-visible agent session. */
    data class PublishUiPart(val authorId: String, val part: ConversationPart, val turnId: TurnId? = null) : ConversationCommand
}

sealed interface CommandResult {
    data class Accepted(val correlationId: String) : CommandResult
    data class Rejected(val reason: String) : CommandResult
}

/** The coordinator assigns this ID before routing so adapter events can reference the request. */
data class AgentCommand(val correlationId: String, val command: ConversationCommand)

/** submit acknowledges routing, not completion; the adapter publishes subsequent events to sink. */
interface AgentTransport {
    val agentId: AgentId
    val displayName: String
    val capabilities: StateFlow<AgentCapabilities>
    suspend fun submit(command: AgentCommand, sink: ConversationEventSink): CommandResult
}

fun interface ConversationEventSink {
    suspend fun publish(event: IncomingConversationEvent): Boolean
}

interface ConversationHandle {
    val conversationId: ConversationId
    val snapshots: StateFlow<ConversationSnapshot>
    /** Live events. Subscribe before reading history() when no event gap can be tolerated. */
    val events: Flow<ConversationEvent>
    fun history(): List<ConversationEvent>
    fun setDraft(target: AgentId, value: String)
    fun addAttachments(target: AgentId, files: List<File>)
    fun removeAttachment(target: AgentId, attachmentId: String)
    suspend fun dispatch(command: ConversationCommand): CommandResult
}

/** An in-memory coordinator for previews and embedders; persistence belongs to the host. */
class ConversationRuntime(
    override val conversationId: ConversationId,
    private val scope: CoroutineScope,
) : ConversationHandle, ConversationEventSink, AutoCloseable {
    private val mutex = Mutex()
    private val registrationLock = UiLock()
    private val stateLock = UiLock()
    private val recordedEventsLock = UiLock()
    private val transports = mutableMapOf<AgentId, AgentTransport>()
    private val capabilityJobs = mutableMapOf<AgentId, Job>()
    private val recordedEvents = mutableListOf<ConversationEvent>()
    private val seenIds = mutableSetOf<String>()
    private val seenSourceSequences = mutableSetOf<Triple<AgentId, SessionId, Long>>()
    private val state = MutableStateFlow(ConversationSnapshot(conversationId))
    private val liveEvents = MutableSharedFlow<ConversationEvent>(extraBufferCapacity = 64)
    @Volatile private var closed = false
    override val snapshots: StateFlow<ConversationSnapshot> = state.asStateFlow()
    override val events: Flow<ConversationEvent> = liveEvents.asSharedFlow()

    fun register(transport: AgentTransport): AutoCloseable {
        synchronized(registrationLock) {
            check(!closed) { "Conversation is closed" }
            require(transport.agentId !in transports) { "Agent already registered: ${transport.agentId.value}" }
            transports[transport.agentId] = transport
            updateParticipant(transport.agentId, transport.displayName, transport.capabilities.value)
            capabilityJobs[transport.agentId] = scope.launch {
                transport.capabilities.collect { capabilities ->
                    synchronized(registrationLock) {
                        if (transports[transport.agentId] === transport) {
                            updateParticipant(transport.agentId, transport.displayName, capabilities)
                        }
                    }
                }
            }
        }
        return AutoCloseable {
            synchronized(registrationLock) {
                if (transports[transport.agentId] === transport) {
                    transports.remove(transport.agentId)
                    capabilityJobs.remove(transport.agentId)?.cancel()
                    synchronized(stateLock) {
                        state.update { current -> current.copy(
                            participants = current.participants.filterNot { it.id == transport.agentId },
                            revision = current.revision + 1,
                        ) }
                    }
                }
            }
        }
    }

    private fun updateParticipant(id: AgentId, name: String, capabilities: AgentCapabilities) {
        synchronized(stateLock) {
            val current = state.value
            val participant = AgentParticipant(id, name, capabilities)
            val index = current.participants.indexOfFirst { it.id == id }
            val next = if (index < 0) current.participants + participant else current.participants.toMutableList().also { it[index] = participant }
            if (next != current.participants) state.value = current.copy(participants = next, revision = current.revision + 1)
        }
    }

    override fun history(): List<ConversationEvent> = synchronized(recordedEventsLock) { recordedEvents.toList() }

    /** Restore the host's durable event history before routing new commands. No live events fire. */
    suspend fun restore(history: List<ConversationEvent>) = mutex.withLock {
        require(!closed) { "Conversation is closed" }
        synchronized(stateLock) {
            require(recordedEvents.isEmpty()) { "Restore must happen before new events" }
            var projected = state.value
            val ids = mutableSetOf<String>()
            val sequences = mutableSetOf<Triple<AgentId, SessionId, Long>>()
            history.forEachIndexed { index, event ->
                require(event.displaySequence == index.toLong()) { "Display sequence must be contiguous" }
                require(ids.add(event.id)) { "Duplicate event ID: ${event.id}" }
                val origin = event.origin
                if (origin.agentId != null && origin.sessionId != null && origin.sourceSequence != null) {
                    require(sequences.add(Triple(origin.agentId, origin.sessionId, origin.sourceSequence))) { "Duplicate source sequence" }
                }
                projected = requireNotNull(reduce(projected, event.change)) { "Invalid event at display sequence $index" }
            }
            synchronized(recordedEventsLock) { recordedEvents.addAll(history) }
            seenIds.addAll(ids)
            seenSourceSequences.addAll(sequences)
            state.value = projected.copy(revision = projected.revision + history.size)
        }
    }

    override fun setDraft(target: AgentId, value: String) {
        if (closed) return
        synchronized(stateLock) {
            val current = state.value
            if (current.drafts[target] != value) {
                state.value = current.copy(drafts = current.drafts + (target to value), revision = current.revision + 1)
            }
        }
    }

    override fun addAttachments(target: AgentId, files: List<File>) {
        if (closed || files.isEmpty()) return
        synchronized(stateLock) {
            val current = state.value
            val prior = current.attachments[target].orEmpty()
            val added = files.map { ConversationAttachment(newId(), it) }
            state.value = current.copy(attachments = current.attachments + (target to (prior + added)), revision = current.revision + 1)
        }
    }

    override fun removeAttachment(target: AgentId, attachmentId: String) {
        if (closed) return
        synchronized(stateLock) {
            val current = state.value
            val next = current.attachments[target].orEmpty().filterNot { it.id == attachmentId }
            state.value = current.copy(attachments = current.attachments + (target to next), revision = current.revision + 1)
        }
    }

    override suspend fun dispatch(command: ConversationCommand): CommandResult {
        if (closed) return CommandResult.Rejected("Conversation is closed")
        if (command is ConversationCommand.PublishUiPart) {
            val turnId = command.turnId ?: TurnId(newId())
            if (command.turnId == null) {
                publish(IncomingConversationEvent(newId(), EventOrigin(), null, ConversationChange.TurnOpened(ConversationTurn(turnId, command.authorId))))
            }
            val accepted = publish(IncomingConversationEvent(newId(), EventOrigin(), null, ConversationChange.PartUpserted(turnId, command.part)))
            return if (accepted) CommandResult.Accepted(turnId.value) else CommandResult.Rejected("Unknown turn")
        }
        val target = when (command) {
            is ConversationCommand.Send -> command.target
            is ConversationCommand.Steer -> command.target
            is ConversationCommand.Interrupt -> command.target
            is ConversationCommand.ResolveApproval -> command.target
            is ConversationCommand.RespondToQuestion -> command.target
            is ConversationCommand.PublishUiPart -> error("handled above")
        }
        val transport = synchronized(registrationLock) { transports[target] }
            ?: return CommandResult.Rejected("Agent ${target.value} is unavailable")
        val caps = transport.capabilities.value
        val supported = when (command) {
            is ConversationCommand.Send -> caps.send && (command.text.isNotBlank() || command.attachments.isNotEmpty()) &&
                (command.attachments.isEmpty() || caps.attachments)
            is ConversationCommand.Steer -> caps.steer && command.text.isNotBlank()
            is ConversationCommand.Interrupt -> caps.interrupt
            is ConversationCommand.ResolveApproval -> caps.approve
            is ConversationCommand.RespondToQuestion -> caps.answerQuestion && command.answer.isNotBlank()
            is ConversationCommand.PublishUiPart -> true
        }
        if (!supported) return CommandResult.Rejected("Agent ${target.value} cannot perform this action")
        val correlationId = newId()
        val userTurn = if (command is ConversationCommand.Send) TurnId(newId()) else null
        val submittedDraft = if (command is ConversationCommand.Send) snapshots.value.drafts[target] else null
        if (userTurn != null) {
            publish(IncomingConversationEvent(newId(), EventOrigin(), correlationId,
                change = ConversationChange.TurnOpened(ConversationTurn(userTurn, "user", target, delivery = TurnDelivery.Pending))))
            val send = command as ConversationCommand.Send
            if (send.text.isNotBlank()) publish(IncomingConversationEvent(newId(), EventOrigin(), correlationId,
                change = ConversationChange.PartUpserted(userTurn, ConversationPart.Text("text", send.text))))
            send.attachments.forEach { attachment ->
                publish(IncomingConversationEvent(newId(), EventOrigin(), correlationId,
                    change = ConversationChange.PartUpserted(userTurn, ConversationPart.Attachment(attachment.id, attachment.file.name, attachment.file.length()))))
            }
        }
        val result = try { transport.submit(AgentCommand(correlationId, command), this) }
        catch (failure: Exception) { CommandResult.Rejected(failure.message ?: "Agent transport failed") }
        if (userTurn != null) {
            publish(IncomingConversationEvent(newId(), EventOrigin(), correlationId,
                change = ConversationChange.DeliveryChanged(userTurn, if (result is CommandResult.Accepted) TurnDelivery.Accepted else TurnDelivery.Rejected)))
            if (result is CommandResult.Accepted) synchronized(stateLock) {
                val current = state.value
                val sentIds = (command as ConversationCommand.Send).attachments.mapTo(mutableSetOf()) { it.id }
                val remaining = current.attachments[target].orEmpty().filterNot { it.id in sentIds }
                val drafts = if (current.drafts[target] == submittedDraft) current.drafts + (target to "") else current.drafts
                state.value = current.copy(
                    drafts = drafts,
                    attachments = current.attachments + (target to remaining),
                    revision = current.revision + 1,
                )
            }
        }
        if (command is ConversationCommand.RespondToQuestion && result is CommandResult.Accepted) {
            val question = snapshots.value.turns.firstOrNull { it.id == command.turnId }?.parts
                ?.filterIsInstance<ConversationPart.Question>()?.firstOrNull { it.id == command.questionId }
            if (question != null) publish(IncomingConversationEvent(newId(), EventOrigin(), correlationId,
                change = ConversationChange.PartUpserted(command.turnId, question.copy(answer = command.answer))))
        }
        return if (result is CommandResult.Accepted) CommandResult.Accepted(correlationId) else result
    }

    override suspend fun publish(event: IncomingConversationEvent): Boolean = mutex.withLock {
        if (closed || event.id in seenIds) return@withLock false
        val origin = event.origin
        val sourceKey = if (origin.agentId != null && origin.sessionId != null && origin.sourceSequence != null)
            Triple(origin.agentId, origin.sessionId, origin.sourceSequence) else null
        if (sourceKey != null && sourceKey in seenSourceSequences) return@withLock false
        val recorded = synchronized(stateLock) {
            val current = state.value
            val updated = reduce(current, event.change) ?: return@synchronized null
            val next = ConversationEvent(event.id, origin, event.correlationId, recordedEvents.size.toLong(), event.timestampEpochMillis, event.change)
            seenIds += event.id
            if (sourceKey != null) seenSourceSequences += sourceKey
            synchronized(recordedEventsLock) { recordedEvents += next }
            state.value = updated.copy(revision = current.revision + 1)
            next
        } ?: return@withLock false
        liveEvents.emit(recorded)
        true
    }

    private fun reduce(current: ConversationSnapshot, change: ConversationChange): ConversationSnapshot? = when (change) {
        is ConversationChange.TurnOpened -> if (current.turns.any { it.id == change.turn.id }) null else
            current.copy(turns = current.turns + change.turn)
        is ConversationChange.PartUpserted -> {
            val index = current.turns.indexOfFirst { it.id == change.turnId }
            if (index < 0) null else {
                val turn = current.turns[index]
                val partIndex = turn.parts.indexOfFirst { it.id == change.part.id }
                val parts = if (partIndex < 0) turn.parts + change.part else turn.parts.toMutableList().also { it[partIndex] = change.part }
                current.copy(turns = current.turns.toMutableList().also { it[index] = turn.copy(parts = parts) })
            }
        }
        is ConversationChange.DeliveryChanged -> {
            val index = current.turns.indexOfFirst { it.id == change.turnId }
            if (index < 0) null else current.copy(turns = current.turns.toMutableList().also { it[index] = it[index].copy(delivery = change.delivery) })
        }
        is ConversationChange.RunChanged -> {
            val index = current.runs.indexOfFirst { it.id == change.run.id }
            val runs = if (index < 0) current.runs + change.run else current.runs.toMutableList().also { it[index] = change.run }
            current.copy(runs = runs)
        }
    }

    override fun close() {
        synchronized(registrationLock) {
            if (!closed) {
                closed = true
                transports.clear()
                capabilityJobs.values.forEach { it.cancel() }
                capabilityJobs.clear()
            }
        }
    }

    private fun newId(): String = randomRuntimeId()
}
