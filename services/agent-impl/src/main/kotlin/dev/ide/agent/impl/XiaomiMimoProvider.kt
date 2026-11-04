package dev.ide.agent.impl

import dev.ide.agent.LlmClient
import dev.ide.agent.LlmModelInfo
import dev.ide.agent.LlmProvider
import dev.ide.agent.ProviderConfig

/**
 * Xiaomi MiMo (`api.xiaomimimo.com`) provider supporting `mimo-v2.6-pro`, `mimo-v2.6-flash`,
 * `mimo-v2.6-pro-ultraspeed`, and `mimo-v2.5-pro`.
 *
 * Speaks Xiaomi's OpenAI-compatible `/v1/chat/completions` dialect with `thinking: {"type": "enabled"|"disabled"}`
 * and full `reasoning_content` round-tripping across multi-turn tool calls, and also supports Xiaomi's
 * Anthropic-compatible `/anthropic/v1/messages` endpoint when configured or when a gateway requests `/v1/messages`.
 */
class XiaomiMimoProvider(private val transport: LlmTransport) : LlmProvider {
    private val openai = OpenAiProvider(
        transport = transport,
        explicitPromptCaching = false,
        includeEncryptedReasoning = false,
        extraThinkingField = true,
    )
    private val anthropic = AnthropicProvider(transport)

    override val id: String = "mimo"
    override val displayName: String = "Xiaomi MiMo"
    override val models: List<LlmModelInfo> = listOf(
        LlmModelInfo("mimo-v2.6-pro", "MiMo V2.6 Pro", supportsThinking = true),
        LlmModelInfo("mimo-v2.6-flash", "MiMo V2.6 Flash", supportsThinking = true),
        LlmModelInfo("mimo-v2.6-pro-ultraspeed", "MiMo V2.6 Pro UltraSpeed", supportsThinking = true),
        LlmModelInfo("mimo-v2.5-pro", "MiMo V2.5 Pro", supportsThinking = true),
    )
    override val defaultModel: String = "mimo-v2.6-pro"

    override fun client(config: ProviderConfig): LlmClient {
        val rawBase = config.baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_BASE
        val usesAnthropic = rawBase.contains("/anthropic", ignoreCase = true) ||
            rawBase.endsWith("/messages", ignoreCase = true)
        return if (usesAnthropic) {
            val anthropicBase = AnthropicProvider.normalizeBase(rawBase) ?: "$DEFAULT_BASE/anthropic"
            anthropic.client(config.copy(baseUrl = anthropicBase))
        } else {
            val openAiBase = OpenAiProvider.normalizeBase(rawBase) ?: DEFAULT_BASE
            openai.client(config.copy(baseUrl = openAiBase))
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
                    supportsThinking = true,
                )
            }
            .ifEmpty { models }
    }.getOrDefault(models)

    companion object {
        const val DEFAULT_BASE = "https://api.xiaomimimo.com"

        fun isMimoModel(model: String): Boolean =
            model.startsWith("mimo-", ignoreCase = true) || model.contains("/mimo-", ignoreCase = true)
    }
}
