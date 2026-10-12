package dev.ide.agent.impl

import dev.ide.agent.ContentPart
import dev.ide.agent.LlmClient
import dev.ide.agent.LlmEffort
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmModelInfo
import dev.ide.agent.LlmProvider
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmRole
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.ProviderConfig
import dev.ide.agent.StopReason
import dev.ide.agent.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The OpenAI provider. The official endpoint is driven through the Responses API ([OpenAiResponses]); a
 * configurable base URL makes the same adapter serve OpenAI-compatible gateways (OpenRouter, Ollama, LocalAI, and
 * similar) over Chat Completions, which is what they implement, with the `max_tokens` they generally take.
 *
 * [explicitPromptCaching] switches on the breakpoint markers that gateways fronting Anthropic models require:
 * OpenAI caches long prefixes by itself, but a Claude model reached through a gateway caches nothing unless
 * the request carries `cache_control` — see [OpenRouterProvider].
 */
class OpenAiProvider(
    private val transport: LlmTransport,
    private val explicitPromptCaching: Boolean = false,
    private val includeEncryptedReasoning: Boolean = true,
    private val extraThinkingField: Boolean = false,
) : LlmProvider {
    override val id: String = "openai"
    override val displayName: String = "OpenAI"
    override val models: List<LlmModelInfo> = listOf(
        LlmModelInfo("gpt-6.1-sol", "GPT-6.1 Sol", supportsThinking = true),
        LlmModelInfo("gpt-6-astra", "GPT-6 Astra", supportsThinking = true),
        LlmModelInfo("gpt-5", "GPT-5", supportsThinking = true),
        LlmModelInfo("gpt-5-mini", "GPT-5 mini", supportsThinking = true),
        LlmModelInfo("gpt-4.1", "GPT-4.1"),
        LlmModelInfo("gpt-4o", "GPT-4o"),
    )
    override val defaultModel: String = "gpt-6.1-sol"

    override fun client(config: ProviderConfig): LlmClient {
        // One key per client, i.e. per conversation. OpenAI routes same-key requests to the same cache, which
        // is what lifts the automatic prefix cache's hit rate; it is a routing hint only, never an identifier.
        val cacheKey = "codeassist-" + java.util.UUID.randomUUID().toString()
        return LlmClient { request ->
            val rawBase = config.baseUrl?.trim().orEmpty()
            val base = normalizeBase(config.baseUrl) ?: DEFAULT_BASE
            val official = base == DEFAULT_BASE
            val headers = authHeaders(config.apiKey) + ("content-type" to "application/json")
            val explicitMessages = rawBase.endsWith("/messages", ignoreCase = true) ||
                rawBase.endsWith("/anthropic", ignoreCase = true) ||
                rawBase.contains("/anthropic/", ignoreCase = true)
            val explicitResponses = rawBase.endsWith("/responses", ignoreCase = true)
            when {
                explicitMessages -> {
                    val anthropicBase = if (rawBase.endsWith("/anthropic", ignoreCase = true)) rawBase.trimEnd('/') else base
                    AnthropicProvider(transport).client(config.copy(baseUrl = anthropicBase)).chat(request)
                }
                official || explicitResponses -> {
                    // OpenAI itself (or an explicit /v1/responses endpoint) speaks the Responses API, which
                    // takes reasoning and function tools together.
                    val sse = SseRequest(
                        url = base + OpenAiResponses.PATH,
                        headers = headers,
                        jsonBody = OpenAiResponses.body(request, cacheKey, includeEncryptedReasoning),
                        caCertificatePem = config.caCertificatePem,
                    )
                    val fallbackSse = if (includeEncryptedReasoning) {
                        SseRequest(
                            url = base + OpenAiResponses.PATH,
                            headers = headers,
                            jsonBody = OpenAiResponses.body(request, cacheKey, includeEncryptedReasoning = false),
                            caCertificatePem = config.caCertificatePem,
                        )
                    } else null
                    streamResponses(sse, fallbackSse)
                }
                else -> {
                    // Compatible gateways default to Chat Completions, but if the gateway rejects function tools
                    // + high reasoning on /v1/chat/completions saying /v1/responses or /v1/messages is required,
                    // fall back automatically before any token is emitted.
                    val sse = SseRequest(
                        url = "$base/v1/chat/completions",
                        headers = headers,
                        jsonBody = buildBody(request),
                        caCertificatePem = config.caCertificatePem,
                    )
                    streamWithGatewayFallback(sse, base, headers, cacheKey, config, request)
                }
            }
        }
    }

    override suspend fun listModels(config: ProviderConfig): List<LlmModelInfo> = runCatching {
        val base = normalizeBase(config.baseUrl) ?: DEFAULT_BASE
        val official = base == DEFAULT_BASE
        val body = transport.get("$base/v1/models", authHeaders(config.apiKey), config.caCertificatePem)
        val data = AgentJson.parseToJsonElement(body).asObj()?.get("data").asArr() ?: return@runCatching models
        // OpenAI's own list mixes in embedding, audio and image models; a gateway's list is what it serves.
        data.mapNotNull { it.asObj()?.get("id").asStr() }
            .filter { !official || it.startsWith("gpt") || it.startsWith("o1") || it.startsWith("o3") || it.startsWith("o4") || it.startsWith("chatgpt") }
            .sorted()
            .map { LlmModelInfo(it, it) }
            .ifEmpty { models }
    }.getOrDefault(models)

    private fun streamWithGatewayFallback(
        sse: SseRequest,
        base: String,
        headers: Map<String, String>,
        cacheKey: String,
        config: ProviderConfig,
        request: LlmRequest,
    ): Flow<LlmStreamEvent> = flow {
        val decoder = OpenAiStreamDecoder()
        var emittedAny = false
        try {
            transport.sse(sse).collect { data ->
                decoder.decode(data).forEach {
                    emittedAny = true
                    emit(it)
                }
            }
            if (!decoder.completed) decoder.finish().forEach { emit(it) }
        } catch (e: Throwable) {
            val msg = (e.message ?: "").lowercase()
            val needsMessages = !emittedAny && (msg.contains("/v1/messages") || msg.contains("use messages"))
            val needsResponses = !emittedAny && (
                msg.contains("/v1/responses") ||
                    msg.contains("responses api") ||
                    (msg.contains("reasoning_effort") && msg.contains("chat completions"))
                )
            when {
                needsMessages -> {
                    AnthropicProvider(transport).client(config.copy(baseUrl = base)).chat(request).collect { emit(it) }
                }
                needsResponses -> {
                    val respSse = SseRequest(
                        url = base + OpenAiResponses.PATH,
                        headers = headers,
                        jsonBody = OpenAiResponses.body(request, cacheKey, includeEncryptedReasoning = false),
                        caCertificatePem = config.caCertificatePem,
                    )
                    streamResponses(respSse, null).collect { emit(it) }
                }
                else -> emit(LlmStreamEvent.Failed(e.message ?: "OpenAI stream error", e))
            }
        }
    }

    private fun streamResponses(sse: SseRequest, fallbackWithoutEncrypted: SseRequest? = null): Flow<LlmStreamEvent> = flow {
        val decoder = OpenAiResponsesDecoder()
        var emittedAny = false
        try {
            transport.sse(sse).collect { data ->
                decoder.decode(data).forEach {
                    emittedAny = true
                    emit(it)
                }
            }
            if (!decoder.completed) decoder.finish(null, null).forEach { emit(it) }
        } catch (e: Throwable) {
            val msg = (e.message ?: "").lowercase()
            if (!emittedAny && fallbackWithoutEncrypted != null &&
                (msg.contains("encrypted_content") || msg.contains("include"))
            ) {
                val retryDecoder = OpenAiResponsesDecoder()
                try {
                    transport.sse(fallbackWithoutEncrypted).collect { data ->
                        retryDecoder.decode(data).forEach { emit(it) }
                    }
                    if (!retryDecoder.completed) retryDecoder.finish(null, null).forEach { emit(it) }
                } catch (e2: Throwable) {
                    emit(LlmStreamEvent.Failed(e2.message ?: "OpenAI stream error", e2))
                }
            } else {
                emit(LlmStreamEvent.Failed(e.message ?: "OpenAI stream error", e))
            }
        }
    }

    private fun buildBody(request: LlmRequest): String = buildJsonObject {
        put("model", request.model)
        put("stream", true)
        put("max_tokens", request.maxTokens)
        // A reasoning model applies a default reasoning effort even when none is sent; on chat completions
        // that default plus function tools is rejected, so forwarding "none" is how a tool-using agent runs
        // against such a model. Non-reasoning models ignore the field. Sent only when explicitly requested.
        reasoningEffort(request.effort, request.model)?.let { put("reasoning_effort", it) }
        if (extraThinkingField) {
            val enabled = request.thinking && request.effort != LlmEffort.NONE && request.effort != LlmEffort.MINIMAL
            put("thinking", buildJsonObject { put("type", if (enabled) "enabled" else "disabled") })
        }
        put("stream_options", buildJsonObject { put("include_usage", true) })
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                request.tools.forEach { spec ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", spec.name)
                            put("description", spec.description)
                            put("parameters", AgentJson.parseToJsonElement(spec.parameters))
                        })
                    })
                }
            })
        }
        put("messages", messages(request.system, request.messages, cacheable(request.model)))
    }.toString()

    /** Chat completions tops out at "high"; the levels above it are clamped rather than rejected. Mandatory
     *  reasoning models (GPT-6.1 Sol, GPT-6 Astra, o-series) also clamp "none"/"minimal" up to "low". */
    private fun reasoningEffort(effort: String?, model: String = ""): String? {
        val raw = effort?.takeIf { it.isNotBlank() } ?: return null
        if (OpenAiResponses.requiresReasoning(model) && (raw == LlmEffort.NONE || raw == LlmEffort.MINIMAL)) {
            return LlmEffort.LOW
        }
        return when (raw) {
            LlmEffort.XHIGH, LlmEffort.MAX -> LlmEffort.HIGH
            else -> raw
        }
    }

    /** Whether to emit explicit cache breakpoints for this model (a gateway fronting an Anthropic model). */
    private fun cacheable(model: String): Boolean =
        explicitPromptCaching && ANTHROPIC_MODEL_MARKERS.any { model.contains(it, ignoreCase = true) }

    private fun messages(system: String?, messages: List<LlmMessage>, cacheable: Boolean): JsonArray = buildJsonArray {
        // The system prompt and the tool set are the big shared prefix, so that is where the one breakpoint
        // goes; on this dialect the tools ride alongside it and are covered by the same marker.
        system?.takeIf { it.isNotBlank() }?.let {
            add(buildJsonObject {
                put("role", "system")
                if (cacheable) put("content", cachedTextContent(it)) else put("content", it)
            })
        }
        // A `tool` message is text-only on this dialect, so images a tool produced are held back and sent in a
        // user message right after the run of tool results they belong to.
        val toolImages = ArrayList<ContentPart.Image>()
        fun flushToolImages() {
            if (toolImages.isEmpty()) return
            add(buildJsonObject {
                put("role", "user")
                put("content", userContent(listOf(ContentPart.Text("Images returned by the tool calls above:")) + toolImages))
            })
            toolImages.clear()
        }
        messages.forEach { m ->
            if (m.role != LlmRole.TOOL) flushToolImages()
            when (m.role) {
                LlmRole.SYSTEM -> add(buildJsonObject { put("role", "system"); put("content", plainText(m.content)) })
                LlmRole.USER -> add(buildJsonObject {
                    put("role", "user")
                    if (m.content.any { it is ContentPart.Image }) put("content", userContent(m.content))
                    else put("content", plainText(m.content))
                })
                LlmRole.ASSISTANT -> add(assistantMessage(m.content))
                LlmRole.TOOL -> m.content.forEach { part ->
                    if (part is ContentPart.ToolResultPart) {
                        add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", part.toolCallId)
                            put("content", part.content)
                        })
                        toolImages += part.images
                    }
                }
            }
        }
        flushToolImages()
    }

    /** The content-array form, needed as soon as a user turn carries an image. */
    private fun userContent(parts: List<ContentPart>): JsonArray = buildJsonArray {
        parts.forEach { p ->
            when (p) {
                is ContentPart.Text -> add(buildJsonObject { put("type", "text"); put("text", p.text) })
                is ContentPart.Image -> add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", "data:${p.mediaType};base64,${p.data}") })
                })
                else -> Unit
            }
        }
    }

    private fun assistantMessage(parts: List<ContentPart>) = buildJsonObject {
        put("role", "assistant")
        val thinking = parts.filterIsInstance<ContentPart.Thinking>().joinToString("") { it.text }
        val text = parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        val toolUses = parts.filterIsInstance<ContentPart.ToolUse>()
        // Reasoning-capable Chat Completions providers (Xiaomi MiMo, DeepSeek, OpenRouter) require
        // `reasoning_content` to be echoed back on assistant turns that invoke tools.
        if (thinking.isNotEmpty()) put("reasoning_content", thinking)
        if (text.isNotEmpty()) put("content", text)
        if (toolUses.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                toolUses.forEach { tu ->
                    add(buildJsonObject {
                        put("id", tu.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tu.name)
                            put("arguments", tu.arguments.ifBlank { "{}" })
                        })
                    })
                }
            })
        }
        if (text.isEmpty() && toolUses.isEmpty()) put("content", "")
    }

    private fun plainText(parts: List<ContentPart>): String =
        parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }

    /** A single text part carrying a cache breakpoint, the content-array form gateways expect. */
    private fun cachedTextContent(text: String): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("type", "text")
            put("text", text)
            put("cache_control", buildJsonObject { put("type", "ephemeral") })
        })
    }

    companion object {
        const val DEFAULT_BASE = "https://api.openai.com"

        private val URL_TOKEN = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        private val ENDPOINT_SUFFIXES = listOf("/chat/completions", "/completions", "/responses", "/messages", "/models")

        /**
         * The server root that `/v1/...` paths are appended to, from what a user typed or pasted. Gateways document
         * their base URL in the OpenAI SDK's form, which already ends in `/v1` (Ollama's is
         * `http://localhost:11434/v1`), and some users paste a full endpoint; both would otherwise double up into
         * `/v1/v1/...`. A paste that carries more than the URL (a "Base URL: ... / API Key: ..." block) keeps only
         * the first URL in it: OkHttp silently drops line breaks, so the rest used to end up in the request path.
         */
        fun normalizeBase(raw: String?): String? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            var base = URL_TOKEN.find(text)?.value ?: text.substringBefore('\n').trim().substringBefore(' ')
            base = base.trimEnd('/')
            ENDPOINT_SUFFIXES.firstOrNull { base.endsWith(it, ignoreCase = true) }?.let { base = base.dropLast(it.length).trimEnd('/') }
            if (base.endsWith("/v1", ignoreCase = true)) base = base.dropLast(3)
            return base.takeIf { it.isNotEmpty() }
        }

        /** A local server (Ollama, LM Studio, llama.cpp) needs no key; an empty bearer is not sent at all. */
        internal fun authHeaders(apiKey: String): Map<String, String> =
            apiKey.trim().takeIf { it.isNotEmpty() }?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

        /** Model-id fragments that identify an Anthropic model behind an OpenAI-dialect gateway. */
        val ANTHROPIC_MODEL_MARKERS = listOf("anthropic/", "claude")
    }
}

/** Decoder for OpenAI Chat Completions SSE. Tool-call fragments accumulate by index and flush at end. */
internal class OpenAiStreamDecoder {
    private class Call {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
        var started = false
    }

    private val calls = LinkedHashMap<Int, Call>()
    private val reasoning = StringBuilder()
    private var inputTokens = 0
    private var outputTokens = 0
    private var cacheReadTokens = 0
    private var stopReason: StopReason = StopReason.END_TURN
    var completed: Boolean = false
        private set

    fun decode(data: String): List<LlmStreamEvent> {
        if (data.trim() == "[DONE]") return finish()
        val json = runCatching { AgentJson.parseToJsonElement(data).asObj() }.getOrNull() ?: return emptyList()
        val out = ArrayList<LlmStreamEvent>(2)

        json["usage"].asObj()?.let { usage ->
            usage["prompt_tokens"].asInt()?.let { inputTokens = it }
            usage["completion_tokens"].asInt()?.let { outputTokens = it }
            // This dialect counts cached tokens INSIDE prompt_tokens; the neutral model keeps the uncached
            // remainder in inputTokens, so subtract the cached share rather than double-counting it.
            usage["prompt_tokens_details"].asObj()?.get("cached_tokens").asInt()?.let { cacheReadTokens = it }
        }

        val choice = json["choices"].asArr()?.firstOrNull().asObj() ?: return out
        choice["finish_reason"].asStr()?.let { stopReason = mapStop(it) }

        val delta = choice["delta"].asObj() ?: return out
        val thought = delta["reasoning_content"].asStr() ?: delta["reasoning"].asStr()
        thought?.let {
            if (it.isNotEmpty()) {
                reasoning.append(it)
                out += LlmStreamEvent.ThinkingDelta(it)
            }
        }
        delta["content"].asStr()?.let { if (it.isNotEmpty()) out += LlmStreamEvent.TextDelta(it) }

        delta["tool_calls"].asArr()?.forEach { tcElement ->
            val tc = tcElement.asObj() ?: return@forEach
            val index = tc["index"].asInt() ?: 0
            val call = calls.getOrPut(index) { Call() }
            tc["id"].asStr()?.let { call.id = it }
            val fn = tc["function"].asObj()
            fn?.get("name").asStr()?.let { call.name = it }
            if (!call.started && call.id.isNotEmpty()) {
                call.started = true
                out += LlmStreamEvent.ToolCallStarted(call.id, call.name)
            }
            fn?.get("arguments").asStr()?.let { frag ->
                call.args.append(frag)
                if (call.id.isNotEmpty()) out += LlmStreamEvent.ToolCallArgsDelta(call.id, frag)
            }
        }
        return out
    }

    /** Emits the assembled tool calls, usage, and completion once the stream ends. */
    fun finish(): List<LlmStreamEvent> {
        if (completed) return emptyList()
        completed = true
        val out = ArrayList<LlmStreamEvent>()
        if (reasoning.isNotEmpty()) {
            out += LlmStreamEvent.ThinkingCompleted(reasoning.toString(), null)
        }
        calls.values.forEach { c ->
            if (c.id.isNotEmpty()) out += LlmStreamEvent.ToolCallCompleted(c.id, c.name, c.args.toString())
        }
        out += LlmStreamEvent.Usage(
            TokenUsage((inputTokens - cacheReadTokens).coerceAtLeast(0), outputTokens, cacheReadTokens),
        )
        out += LlmStreamEvent.Completed(stopReason)
        return out
    }

    private fun mapStop(s: String): StopReason = when (s) {
        "stop" -> StopReason.END_TURN
        "length" -> StopReason.MAX_TOKENS
        "tool_calls", "function_call" -> StopReason.TOOL_USE
        "content_filter" -> StopReason.REFUSAL
        else -> StopReason.END_TURN
    }
}
