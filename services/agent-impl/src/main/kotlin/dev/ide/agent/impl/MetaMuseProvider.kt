package dev.ide.agent.impl

import dev.ide.agent.LlmClient
import dev.ide.agent.LlmEffort
import dev.ide.agent.LlmModelInfo
import dev.ide.agent.LlmProvider
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.ProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

/**
 * Meta Muse (`api.meta.ai`) provider supporting the Muse Spark, Muse Wonder, and Muse Glimmer model families.
 *
 * Muse Spark (`muse-spark-1.3`, `muse-spark-1.1`) and Muse Wonder always reason (`minimal`–`xhigh`; `none` is
 * rejected by the API and clamped to `minimal`). When function tools are combined with reasoning effort,
 * Meta's platform serves the turn over `/v1/responses` (or `/v1/messages`), falling back to
 * `/v1/chat/completions` for compatible gateways.
 */
class MetaMuseProvider(private val transport: LlmTransport) : LlmProvider {
    private val chatDelegate = OpenAiProvider(transport, includeEncryptedReasoning = false)
    private val anthropicDelegate = AnthropicProvider(transport)

    override val id: String = "muse"
    override val displayName: String = "Meta Muse"
    override val models: List<LlmModelInfo> = listOf(
        LlmModelInfo("muse-spark-1.3", "Muse Spark 1.3", supportsThinking = true),
        LlmModelInfo("muse-spark-1.1", "Muse Spark 1.1", supportsThinking = true),
        LlmModelInfo("muse-wonder", "Muse Wonder", supportsThinking = true),
        LlmModelInfo("muse-glimmer", "Muse Glimmer", supportsThinking = false),
    )
    override val defaultModel: String = "muse-spark-1.3"

    override fun client(config: ProviderConfig): LlmClient {
        val cacheKey = "codeassist-muse-" + UUID.randomUUID().toString()
        return LlmClient { rawRequest ->
            val request = normalizeEffort(rawRequest)
            val rawBase = config.baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_BASE
            val base = OpenAiProvider.normalizeBase(rawBase) ?: DEFAULT_BASE
            val explicitMessages = rawBase.endsWith("/messages", ignoreCase = true) ||
                rawBase.endsWith("/anthropic", ignoreCase = true)
            val explicitChat = rawBase.endsWith("/chat/completions", ignoreCase = true)
            when {
                explicitMessages -> anthropicDelegate.client(config.copy(baseUrl = base)).chat(request)
                explicitChat -> chatDelegate.client(config.copy(baseUrl = base)).chat(request)
                else -> streamResponsesWithChatFallback(base, cacheKey, config, request)
            }
        }
    }

    override suspend fun listModels(config: ProviderConfig): List<LlmModelInfo> = runCatching {
        val base = OpenAiProvider.normalizeBase(config.baseUrl) ?: DEFAULT_BASE
        val body = transport.get("$base/v1/models", OpenAiProvider.authHeaders(config.apiKey), config.caCertificatePem)
        val data = AgentJson.parseToJsonElement(body).asObj()?.get("data").asArr() ?: return@runCatching models
        data.mapNotNull { it.asObj() }
            .mapNotNull { m ->
                val modelId = m["id"].asStr() ?: return@mapNotNull null
                LlmModelInfo(
                    modelId,
                    m["display_name"].asStr() ?: m["name"].asStr() ?: modelId,
                    supportsThinking = !modelId.contains("glimmer", ignoreCase = true),
                )
            }
            .ifEmpty { models }
    }.getOrDefault(models)

    private fun normalizeEffort(request: LlmRequest): LlmRequest {
        val effort = OpenAiResponses.effort(request.effort, request.model)
        return if (effort != request.effort) request.copy(effort = effort) else request
    }

    private fun streamResponsesWithChatFallback(
        base: String,
        cacheKey: String,
        config: ProviderConfig,
        request: LlmRequest,
    ): Flow<LlmStreamEvent> = flow {
        val headers = OpenAiProvider.authHeaders(config.apiKey) + ("content-type" to "application/json")
        val sse = SseRequest(
            url = base + OpenAiResponses.PATH,
            headers = headers,
            jsonBody = OpenAiResponses.body(request, cacheKey, includeEncryptedReasoning = false),
            caCertificatePem = config.caCertificatePem,
        )
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
            if (!emittedAny) {
                chatDelegate.client(config.copy(baseUrl = base)).chat(request).collect { emit(it) }
            } else {
                emit(LlmStreamEvent.Failed(e.message ?: "Meta Muse stream error", e))
            }
        }
    }

    companion object {
        const val DEFAULT_BASE = "https://api.meta.ai"

        fun isMuseModel(model: String): Boolean =
            model.startsWith("muse-", ignoreCase = true) || model.contains("/muse-", ignoreCase = true)
    }
}
