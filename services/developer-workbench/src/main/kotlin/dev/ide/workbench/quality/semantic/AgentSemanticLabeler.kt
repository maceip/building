package dev.ide.workbench.quality.semantic

import dev.ide.agent.LlmClient
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.StopReason
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect

/** Uses the existing provider-neutral client, never AgentLoop/workspace/tools or a second credential store. */
class AgentSemanticLabeler(
    override val provider: SemanticProvider,
    /** Resolve existing credentials and recheck the approved target here, not in constructors or Compose. */
    private val client: suspend () -> LlmClient,
) : SemanticLabeler {
    override suspend fun label(request: SemanticLabelRequest): String {
        currentCoroutineContext().ensureActive()
        val output = StringBuilder()
        var completed = false
        var receivedChars = 0L
        client().chat(LlmRequest(
            model = provider.model,
            system = request.systemText(),
            messages = listOf(LlmMessage.user(request.untrustedRecordsJson)),
            tools = emptyList(), maxTokens = request.maxOutputTokens,
            thinking = false, webSearch = false,
        )).collect { event ->
            currentCoroutineContext().ensureActive()
            when (event) {
                is LlmStreamEvent.TextDelta -> {
                    checkOutput(!completed)
                    receivedChars += event.text.length
                    if (receivedChars > request.maxResponseChars) throw SemanticException(SemanticFailure.LIMIT_EXCEEDED)
                    output.append(event.text)
                }
                is LlmStreamEvent.Completed -> {
                    checkOutput(!completed && event.stopReason == StopReason.END_TURN)
                    completed = true
                }
                is LlmStreamEvent.ToolCallStarted, is LlmStreamEvent.ToolCallArgsDelta,
                is LlmStreamEvent.ToolCallCompleted -> invalidOutput()
                is LlmStreamEvent.Failed -> throw SemanticProviderFailure() // Do not retain/echo provider error text.
                is LlmStreamEvent.ThinkingDelta -> {
                    receivedChars += event.text.length
                    if (receivedChars > request.maxResponseChars) throw SemanticException(SemanticFailure.LIMIT_EXCEEDED)
                }
                is LlmStreamEvent.ThinkingCompleted -> {
                    receivedChars += event.text.length + (event.signature?.length ?: 0)
                    if (receivedChars > request.maxResponseChars) throw SemanticException(SemanticFailure.LIMIT_EXCEEDED)
                }
                is LlmStreamEvent.Usage -> Unit
            }
        }
        checkOutput(completed && output.isNotEmpty())
        return output.toString()
    }
}
