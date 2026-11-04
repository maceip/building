package dev.ide.agent.impl

import dev.ide.agent.ContentPart
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.ProviderConfig
import dev.ide.agent.StopReason
import dev.ide.agent.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The official OpenAI endpoint runs on the Responses API: reasoning and function tools in one request (Chat
 * Completions rejects that pairing on newer reasoning models), with the encrypted reasoning carried across turns.
 */
class OpenAiResponsesTest {

    private class Recording(private val payloads: List<String> = listOf("[DONE]")) : LlmTransport {
        var url: String? = null
        var body: String? = null
        override fun sse(request: SseRequest): Flow<String> {
            url = request.url
            body = request.jsonBody
            return payloads.asFlow()
        }
    }

    private val tool = ToolSpec("read_file", "Read a file", """{"type":"object","properties":{"path":{"type":"string"}}}""")

    private fun send(transport: LlmTransport, request: LlmRequest, config: ProviderConfig = ProviderConfig("k")) =
        runBlocking { OpenAiProvider(transport).client(config).chat(request).toList() }

    @Test
    fun officialEndpointSendsReasoningAndToolsTogether() {
        val t = Recording()
        send(t, LlmRequest("gpt-6-luna", "be brief", listOf(LlmMessage.user("hi")), tools = listOf(tool), effort = "medium"))
        assertEquals("https://api.openai.com/v1/responses", t.url)
        val body = t.body!!
        assertTrue(body.contains(""""reasoning":{"effort":"medium"}"""), body)
        assertTrue(body.contains(""""include":["reasoning.encrypted_content"]"""), body)
        assertTrue(body.contains(""""instructions":"be brief""""), body)
        assertTrue(body.contains(""""store":false"""), body)
        assertTrue(body.contains(""""type":"function","name":"read_file""""), body)
        assertFalse(body.contains("reasoning_effort"), body)
    }

    @Test
    fun nonReasoningModelsGetNoReasoningParameters() {
        val t = Recording()
        send(t, LlmRequest("gpt-4o", null, listOf(LlmMessage.user("hi")), effort = "high"))
        assertFalse(t.body!!.contains("reasoning"), t.body)
    }

    @Test
    fun customBaseUrlStaysOnChatCompletions() {
        val t = Recording()
        send(t, LlmRequest("gpt-6-luna", null, listOf(LlmMessage.user("hi"))), ProviderConfig("k", baseUrl = "https://gateway.example/"))
        assertEquals("https://gateway.example/v1/chat/completions", t.url)
        // An explicit official base URL is still the official endpoint.
        send(t, LlmRequest("gpt-6-luna", null, listOf(LlmMessage.user("hi"))), ProviderConfig("k", baseUrl = "https://api.openai.com"))
        assertEquals("https://api.openai.com/v1/responses", t.url)
    }

    @Test
    fun decodesTextReasoningAndToolCall() {
        val events = send(
            Recording(
                listOf(
                    """{"type":"response.created","response":{"id":"resp_1"}}""",
                    """{"type":"response.output_item.done","output_index":0,"item":{"id":"rs_1","type":"reasoning","summary":[],"encrypted_content":"ENC"}}""",
                    """{"type":"response.output_text.delta","item_id":"msg_1","delta":"Let me look"}""",
                    """{"type":"response.output_item.added","output_index":2,"item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"read_file","arguments":""}}""",
                    """{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\"path\":"}""",
                    """{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"\"A.kt\"}"}""",
                    """{"type":"response.output_item.done","output_index":2,"item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"read_file","arguments":"{\"path\":\"A.kt\"}"}}""",
                    """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":60},"output_tokens":20}}}""",
                ),
            ),
            LlmRequest("gpt-6-luna", null, listOf(LlmMessage.user("hi")), tools = listOf(tool)),
        )
        assertEquals("Let me look", events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(listOf("""{"path":""", """"A.kt"}"""), events.filterIsInstance<LlmStreamEvent.ToolCallArgsDelta>().map { it.partialJson })
        val call = events.filterIsInstance<LlmStreamEvent.ToolCallCompleted>().single()
        assertEquals("call_1", call.id)
        assertEquals("""{"path":"A.kt"}""", call.arguments)
        assertEquals("fc_1", call.signature)
        val thinking = events.filterIsInstance<LlmStreamEvent.ThinkingCompleted>().single()
        assertEquals(ReasoningItem("rs_1", "ENC"), ReasoningItem.decode(thinking.signature))
        val usage = events.filterIsInstance<LlmStreamEvent.Usage>().single().usage
        assertEquals(40, usage.inputTokens)
        assertEquals(60, usage.cacheReadTokens)
        assertEquals(20, usage.outputTokens)
        assertEquals(StopReason.TOOL_USE, events.filterIsInstance<LlmStreamEvent.Completed>().single().stopReason)
    }

    @Test
    fun reasoningAndCallsAreReplayedOnTheNextRequest() {
        val history = listOf(
            LlmMessage.user("open A.kt"),
            LlmMessage.assistant(
                listOf(
                    ContentPart.Thinking("", ReasoningItem("rs_1", "ENC").encode()),
                    ContentPart.Thinking("anthropic thought", "anthropic-signature"),
                    ContentPart.Text("Let me look"),
                    ContentPart.ToolUse("call_1", "read_file", """{"path":"A.kt"}""", "fc_1"),
                ),
            ),
            LlmMessage.toolResult("call_1", "fun main() {}"),
        )
        val t = Recording()
        send(t, LlmRequest("gpt-6-luna", null, history, tools = listOf(tool)))
        val body = t.body!!
        val reasoning = body.indexOf(""""type":"reasoning","id":"rs_1","encrypted_content":"ENC"""")
        val message = body.indexOf(""""type":"message","role":"assistant"""")
        val call = body.indexOf(""""type":"function_call","id":"fc_1","call_id":"call_1"""")
        val output = body.indexOf(""""type":"function_call_output","call_id":"call_1","output":"fun main() {}"""")
        assertTrue(reasoning in 0 until message && message < call && call < output, body)
        assertFalse(body.contains("anthropic"), "another provider's reasoning has no OpenAI form: $body")
    }

    @Test
    fun failuresAndTruncationSurface() {
        val failed = send(
            Recording(listOf("""{"type":"response.failed","response":{"status":"failed","error":{"code":"server_error","message":"The server had an error"}}}""")),
            LlmRequest("gpt-6-luna", null, listOf(LlmMessage.user("hi"))),
        )
        assertEquals("The server had an error", failed.filterIsInstance<LlmStreamEvent.Failed>().single().message)
        assertNull(failed.filterIsInstance<LlmStreamEvent.Completed>().firstOrNull())

        val error = send(
            Recording(listOf("""{"type":"error","code":"invalid_request_error","message":"Bad input"}""")),
            LlmRequest("gpt-6-luna", null, listOf(LlmMessage.user("hi"))),
        )
        assertEquals("Bad input", error.filterIsInstance<LlmStreamEvent.Failed>().single().message)

        val cut = send(
            Recording(listOf("""{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}}""")),
            LlmRequest("gpt-6-luna", null, listOf(LlmMessage.user("hi"))),
        )
        assertEquals(StopReason.MAX_TOKENS, cut.filterIsInstance<LlmStreamEvent.Completed>().single().stopReason)
    }

    @Test
    fun gpt61SolClampsNoneAndMinimalEffortAndSupportsMax() {
        val t = Recording()
        // GPT-6.1 Sol rejects "none" and "minimal" on /v1/responses; both are clamped up to "low".
        send(t, LlmRequest("gpt-6.1-sol", null, listOf(LlmMessage.user("hi")), tools = listOf(tool), effort = "none"))
        assertTrue(t.body!!.contains(""""reasoning":{"effort":"low"}"""), t.body)

        send(t, LlmRequest("gpt-6.1-sol", null, listOf(LlmMessage.user("hi")), tools = listOf(tool), effort = "minimal"))
        assertTrue(t.body!!.contains(""""reasoning":{"effort":"low"}"""), t.body)

        // GPT-6.1 Sol supports "max" effort natively.
        send(t, LlmRequest("gpt-6.1-sol", null, listOf(LlmMessage.user("hi")), tools = listOf(tool), effort = "max"))
        assertTrue(t.body!!.contains(""""reasoning":{"effort":"max"}"""), t.body)
    }

    @Test
    fun metaMuseSparkClampsNoneAndStreamsResponses() {
        val t = Recording(
            listOf(
                """{"type":"response.reasoning_summary_text.delta","delta":"Thinking about Spark"}""",
                """{"type":"response.output_text.delta","delta":"Hello from Muse"}""",
                """{"type":"response.completed","response":{"status":"completed"}}""",
            ),
        )
        val events = runBlocking {
            MetaMuseProvider(t).client(ProviderConfig("muse-key"))
                .chat(LlmRequest("muse-spark-1.3", "sys", listOf(LlmMessage.user("hi")), tools = listOf(tool), effort = "none"))
                .toList()
        }
        assertEquals("https://api.meta.ai/v1/responses", t.url)
        // "none" is clamped to "minimal" on Muse Spark, and encrypted_content include is omitted.
        assertTrue(t.body!!.contains(""""reasoning":{"effort":"minimal"}"""), t.body)
        assertFalse(t.body!!.contains("encrypted_content"), t.body)
        assertEquals("Thinking about Spark", events.filterIsInstance<LlmStreamEvent.ThinkingDelta>().joinToString("") { it.text })
        assertEquals("Hello from Muse", events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text })
    }

    @Test
    fun xiaomiMimoRoundTripsReasoningContentAndSupportsAnthropicDialect() {
        val t = Recording(
            listOf(
                """{"choices":[{"delta":{"reasoning_content":"step 1"}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"A.kt\"}"}}]},"finish_reason":"tool_calls"}]}""",
                "[DONE]",
            ),
        )
        val events = runBlocking {
            XiaomiMimoProvider(t).client(ProviderConfig("mimo-key"))
                .chat(LlmRequest("mimo-v2.6-pro", "sys", listOf(LlmMessage.user("hi")), tools = listOf(tool), effort = "high"))
                .toList()
        }
        assertEquals("https://api.xiaomimimo.com/v1/chat/completions", t.url)
        assertTrue(t.body!!.contains(""""thinking":{"type":"enabled"}"""), t.body)
        val thought = events.filterIsInstance<LlmStreamEvent.ThinkingCompleted>().single()
        assertEquals("step 1", thought.text)

        // Second turn: the assistant message echoes reasoning_content alongside tool_calls.
        val history = listOf(
            LlmMessage.user("hi"),
            LlmMessage.assistant(
                listOf(
                    ContentPart.Thinking("step 1", null),
                    ContentPart.ToolUse("c1", "read_file", """{"path":"A.kt"}"""),
                ),
            ),
            LlmMessage.toolResult("c1", "contents"),
        )
        runBlocking {
            XiaomiMimoProvider(t).client(ProviderConfig("mimo-key"))
                .chat(LlmRequest("mimo-v2.6-pro", "sys", history, tools = listOf(tool), effort = "high"))
                .toList()
        }
        assertTrue(t.body!!.contains(""""reasoning_content":"step 1""""), t.body)

        // When configured with /anthropic, Xiaomi MiMo routes over /anthropic/v1/messages with enabled thinking.
        runBlocking {
            XiaomiMimoProvider(t).client(ProviderConfig("mimo-key", baseUrl = "https://api.xiaomimimo.com/anthropic"))
                .chat(LlmRequest("mimo-v2.6-pro", "sys", listOf(LlmMessage.user("hi")), tools = listOf(tool), effort = "high"))
                .toList()
        }
        assertEquals("https://api.xiaomimimo.com/anthropic/v1/messages", t.url)
        assertTrue(t.body!!.contains(""""thinking":{"type":"enabled","budget_tokens":16384}"""), t.body)
    }

    @Test
    fun antigravityProviderWrapsGatewayEnvelopeAndUnwrapsStream() {
        val t = Recording(
            listOf(
                """{"response":{"candidates":[{"content":{"role":"model","parts":[{"text":"Hi from Antigravity"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":4}}}""",
            ),
        )
        val events = runBlocking {
            AntigravityProvider(t).client(ProviderConfig("ya29-token|proj-42"))
                .chat(LlmRequest("gemini-3-pro-high", "sys", listOf(LlmMessage.user("hi")), tools = listOf(tool)))
                .toList()
        }
        assertTrue(t.url!!.contains("v1internal:streamGenerateContent"), t.url)
        assertTrue(t.body!!.contains(""""project":"proj-42""""), t.body)
        assertEquals("Hi from Antigravity", events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text })
    }
}

