package fragments.agentui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConversationRuntimeTest {
    private class RecordingTransport(override val agentId: AgentId, override val displayName: String) : AgentTransport {
        override val capabilities = MutableStateFlow(AgentCapabilities(send = true, steer = true, interrupt = true, approve = true, answerQuestion = true, attachments = true))
        val received = mutableListOf<AgentCommand>()
        var result: CommandResult = CommandResult.Accepted("queued")
        override suspend fun submit(command: AgentCommand, sink: ConversationEventSink): CommandResult {
            received += command
            return result
        }
    }

    @Test fun explicitRoutingAndAcceptedIsNotCompletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = ConversationRuntime(ConversationId("one"), scope)
        try {
            val first = RecordingTransport(AgentId("research"), "Research")
            val second = RecordingTransport(AgentId("build"), "Build")
            runtime.register(first)
            runtime.register(second)
            runtime.setDraft(second.agentId, "Run tests")
            val result = runtime.dispatch(ConversationCommand.Send(second.agentId, "Run tests"))
            assertIs<CommandResult.Accepted>(result)
            assertTrue(first.received.isEmpty())
            assertEquals(listOf<ConversationCommand>(ConversationCommand.Send(second.agentId, "Run tests")), second.received.map { it.command })
            assertEquals(result.correlationId, second.received.single().correlationId)
            assertEquals("Run tests", (runtime.snapshots.value.turns.single().parts.single() as ConversationPart.Text).value)
            assertEquals(TurnDelivery.Accepted, runtime.snapshots.value.turns.single().delivery)
            assertTrue(runtime.snapshots.value.runs.isEmpty())
            assertEquals("", runtime.snapshots.value.drafts[second.agentId])
        } finally { runtime.close(); scope.cancel() }
        Unit
    }

    @Test fun interleavedSessionsKeepArrivalOrderAndRejectDuplicates() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = ConversationRuntime(ConversationId("two"), scope)
        try {
            val a = AgentId("a")
            val b = AgentId("b")
            val first = IncomingConversationEvent("e1", EventOrigin(a, SessionId("sa"), 1), "r1", ConversationChange.TurnOpened(ConversationTurn(TurnId("a1"), "a", a)))
            val second = IncomingConversationEvent("e2", EventOrigin(b, SessionId("sb"), 1), "r2", ConversationChange.TurnOpened(ConversationTurn(TurnId("b1"), "b", b)))
            assertTrue(runtime.publish(first))
            assertTrue(runtime.publish(second))
            assertFalse(runtime.publish(first))
            assertFalse(runtime.publish(first.copy(id = "new-id")))
            assertTrue(runtime.publish(IncomingConversationEvent("e3", EventOrigin(a, SessionId("sa"), 2), "r1", ConversationChange.PartUpserted(TurnId("a1"), ConversationPart.Text("reply", "a")))))
            assertTrue(runtime.publish(IncomingConversationEvent("e4", EventOrigin(b, SessionId("sb"), 2), "r2", ConversationChange.PartUpserted(TurnId("b1"), ConversationPart.Text("reply", "b")))))
            assertEquals(listOf("a1", "b1"), runtime.snapshots.value.turns.map { it.id.value })
            assertEquals(listOf(0L, 1L, 2L, 3L), runtime.history().map { it.displaySequence })
        } finally { runtime.close(); scope.cancel() }
        Unit
    }

    @Test fun uiPartsDoNotReachAgentAndRegistrationDisposes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = ConversationRuntime(ConversationId("three"), scope)
        try {
            val agent = RecordingTransport(AgentId("one"), "One")
            val registration = runtime.register(agent)
            val result = runtime.dispatch(ConversationCommand.PublishUiPart("browser", ConversationPart.Browser("card", "session-1", "Preview")))
            assertIs<CommandResult.Accepted>(result)
            assertTrue(agent.received.isEmpty())
            assertIs<ConversationPart.Browser>(runtime.snapshots.value.turns.single().parts.single())
            registration.close()
            assertTrue(runtime.snapshots.value.participants.isEmpty())
            assertIs<CommandResult.Rejected>(runtime.dispatch(ConversationCommand.Send(agent.agentId, "hello")))
        } finally { runtime.close(); scope.cancel() }
        Unit
    }

    @Test fun rejectedSendRemainsVisibleAndKeepsDraft() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = ConversationRuntime(ConversationId("four"), scope)
        try {
            val agent = RecordingTransport(AgentId("one"), "One")
            agent.result = CommandResult.Rejected("offline")
            runtime.register(agent)
            runtime.setDraft(agent.agentId, "Try this")
            assertIs<CommandResult.Rejected>(runtime.dispatch(ConversationCommand.Send(agent.agentId, "Try this")))
            assertEquals(TurnDelivery.Rejected, runtime.snapshots.value.turns.single().delivery)
            assertEquals("Try this", runtime.snapshots.value.drafts[agent.agentId])
        } finally { runtime.close(); scope.cancel() }
        Unit
    }

    @Test fun attachmentsAndQuestionAnswersFollowTheSelectedAgent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = ConversationRuntime(ConversationId("five"), scope)
        try {
            val agent = RecordingTransport(AgentId("builder"), "Builder")
            runtime.register(agent)
            val file = File("preview.txt")
            runtime.addAttachments(agent.agentId, listOf(file))
            val attached = runtime.snapshots.value.attachments[agent.agentId].orEmpty().single()
            assertIs<CommandResult.Accepted>(runtime.dispatch(ConversationCommand.Send(agent.agentId, "", listOf(attached))))
            assertEquals(attached.id, (runtime.snapshots.value.turns.single().parts.single() as ConversationPart.Attachment).id)
            assertTrue(runtime.snapshots.value.attachments[agent.agentId].orEmpty().isEmpty())
            val turn = TurnId("question-turn")
            runtime.publish(IncomingConversationEvent("question-turn", EventOrigin(), change = ConversationChange.TurnOpened(ConversationTurn(turn, "builder", agent.agentId, RunId("question-run")))))
            runtime.publish(IncomingConversationEvent("question-part", EventOrigin(), change = ConversationChange.PartUpserted(turn, ConversationPart.Question("question", "Proceed?", listOf(ConversationQuestionOption("yes", "Yes"))))))
            assertIs<CommandResult.Accepted>(runtime.dispatch(ConversationCommand.RespondToQuestion(agent.agentId, RunId("question-run"), turn, "question", "yes")))
            val answered = runtime.snapshots.value.turns.last().parts.single() as ConversationPart.Question
            assertEquals("yes", answered.answer)
            assertIs<ConversationCommand.RespondToQuestion>(agent.received.last().command)
        } finally { runtime.close(); scope.cancel() }
        Unit
    }

    @Test fun unsupportedAttachmentsAreRejectedBeforeOpeningATurn() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = ConversationRuntime(ConversationId("attachment-capability"), scope)
        try {
            val agent = RecordingTransport(AgentId("text-only"), "Text only")
            agent.capabilities.value = agent.capabilities.value.copy(attachments = false)
            runtime.register(agent)
            runtime.addAttachments(agent.agentId, listOf(File("unsupported.txt")))
            val attachment = runtime.snapshots.value.attachments[agent.agentId].orEmpty().single()
            assertIs<CommandResult.Rejected>(runtime.dispatch(ConversationCommand.Send(agent.agentId, "Review this", listOf(attachment))))
            assertTrue(agent.received.isEmpty())
            assertTrue(runtime.snapshots.value.turns.isEmpty())
            assertEquals(listOf(attachment), runtime.snapshots.value.attachments[agent.agentId])
        } finally { runtime.close(); scope.cancel() }
        Unit
    }

    @Test fun historyRestoresOrderedTurnsAndDuplicateDetection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = ConversationRuntime(ConversationId("replay"), scope)
        val restored = ConversationRuntime(ConversationId("replay"), scope)
        try {
            val origin = EventOrigin(AgentId("a"), SessionId("session-a"), 1)
            val opened = IncomingConversationEvent("open", origin, "request", ConversationChange.TurnOpened(ConversationTurn(TurnId("turn"), "a", AgentId("a"))))
            assertTrue(first.publish(opened))
            assertTrue(first.publish(IncomingConversationEvent("part", origin.copy(sourceSequence = 2), "request", ConversationChange.PartUpserted(TurnId("turn"), ConversationPart.Text("text", "Saved answer")))))
            restored.restore(first.history())
            assertEquals(first.snapshots.value.turns, restored.snapshots.value.turns)
            assertEquals(first.history(), restored.history())
            assertFalse(restored.publish(opened))
            assertFalse(restored.publish(opened.copy(id = "different-id")))
        } finally { first.close(); restored.close(); scope.cancel() }
        Unit
    }

    @Test fun editsAndNewAttachmentsMadeDuringSendSurviveAcknowledgement() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = ConversationRuntime(ConversationId("send-race"), scope)
        val submitted = CompletableDeferred<Unit>()
        val acknowledge = CompletableDeferred<Unit>()
        val agent = object : AgentTransport {
            override val agentId = AgentId("builder")
            override val displayName = "Builder"
            override val capabilities = MutableStateFlow(AgentCapabilities(attachments = true))
            override suspend fun submit(command: AgentCommand, sink: ConversationEventSink): CommandResult {
                submitted.complete(Unit)
                acknowledge.await()
                return CommandResult.Accepted(command.correlationId)
            }
        }
        try {
            runtime.register(agent)
            runtime.setDraft(agent.agentId, "First message")
            runtime.addAttachments(agent.agentId, listOf(File("first.txt")))
            val sentAttachment = runtime.snapshots.value.attachments[agent.agentId].orEmpty().single()
            val inFlight = async { runtime.dispatch(ConversationCommand.Send(agent.agentId, "First message", listOf(sentAttachment))) }
            submitted.await()
            runtime.setDraft(agent.agentId, "Next message")
            runtime.addAttachments(agent.agentId, listOf(File("second.txt")))
            acknowledge.complete(Unit)
            assertIs<CommandResult.Accepted>(inFlight.await())
            assertEquals("Next message", runtime.snapshots.value.drafts[agent.agentId])
            assertEquals(listOf("second.txt"), runtime.snapshots.value.attachments[agent.agentId].orEmpty().map { it.file.name })
        } finally { runtime.close(); scope.cancel() }
        Unit
    }
}
