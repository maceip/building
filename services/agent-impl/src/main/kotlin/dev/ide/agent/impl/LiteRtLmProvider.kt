package dev.ide.agent.impl

import dev.ide.agent.LlmClient
import dev.ide.agent.LlmModelInfo
import dev.ide.agent.LlmProvider
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.ProviderConfig
import kotlinx.coroutines.flow.flowOf

/**
 * First-class [LlmProvider] (`id = "litert"`) backed by the on-device [LiteRtLmServingManager]
 * or its local loopback OpenAI-compatible HTTP server (`127.0.0.1:<port>/v1/chat/completions`).
 *
 * Per Law 1 & Law 2, this provider never fabricates responses when no `.litertlm` model is installed:
 * if no model artifact is verified on disk, `chat(...)` emits an explicit [LlmStreamEvent.Failed].
 */
class LiteRtLmProvider(
    private val transport: LlmTransport = OkHttpLlmTransport(),
    private val managerProvider: () -> LiteRtLmServingManager? = { null },
) : LlmProvider {

    override val id: String = ID
    override val displayName: String = "LiteRT-LM (On-Device)"

    override val models: List<LlmModelInfo> = LiteRtLmCatalog.MODELS.map { spec ->
        LlmModelInfo(
            id = spec.id,
            displayName = spec.displayName,
            supportsThinking = spec.supportsThinking,
        )
    }

    override val defaultModel: String = LiteRtLmCatalog.DEFAULT_MODEL_ID

    override fun client(config: ProviderConfig): LlmClient = LlmClient { request ->
        val customUrl = config.baseUrl?.trim()?.takeIf { it.isNotBlank() }
        val manager = managerProvider()
        when {
            manager != null && customUrl == null -> manager.streamChat(request)
            customUrl != null -> {
                OpenAiProvider(transport)
                    .client(config.copy(apiKey = config.apiKey.ifBlank { "litert-local" }, baseUrl = customUrl))
                    .chat(request)
            }
            else -> {
                flowOf(
                    LlmStreamEvent.Failed(
                        "LiteRT-LM runtime manager is not initialized or no on-device model is installed.",
                    ),
                )
            }
        }
    }

    override suspend fun listModels(config: ProviderConfig): List<LlmModelInfo> {
        val manager = managerProvider() ?: return models
        val installed = manager.installedArtifacts().mapNotNull { artifact ->
            val spec = LiteRtLmCatalog.find(artifact.modelId)
            LlmModelInfo(
                id = artifact.modelId,
                displayName = spec?.displayName ?: artifact.modelId,
                supportsThinking = spec?.supportsThinking ?: false,
            )
        }
        return installed.ifEmpty { models }
    }

    override fun preferredModel(available: List<LlmModelInfo>): String? {
        val manager = managerProvider()
        val active = manager?.activeInstalledModel()?.modelId
        if (!active.isNullOrBlank() && available.any { it.id == active }) {
            return active
        }
        return available.firstOrNull()?.id
    }

    companion object {
        const val ID: String = "litert"
    }
}
