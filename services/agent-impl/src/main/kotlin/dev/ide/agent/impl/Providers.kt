package dev.ide.agent.impl

import dev.ide.agent.LlmProvider
import dev.ide.agent.LlmProviderRegistry
import dev.ide.agent.SimpleLlmProviderRegistry

/** Assembles the built-in providers over a transport (OkHttp by default). Plugins may add more providers. */
object AgentProviders {
    fun defaults(
        transport: LlmTransport = OkHttpLlmTransport(),
        liteRtManagerProvider: (() -> LiteRtLmServingManager?)? = null,
    ): List<LlmProvider> = buildList {
        add(AntigravityProvider(transport))
        add(AnthropicProvider(transport))
        add(OpenAiProvider(transport))
        add(MetaMuseProvider(transport))
        add(XiaomiMimoProvider(transport))
        add(GeminiProvider(transport))
        add(OpenRouterProvider(transport))
        if (liteRtManagerProvider != null) {
            add(LiteRtLmProvider(transport, liteRtManagerProvider))
        }
    }

    fun registry(
        transport: LlmTransport = OkHttpLlmTransport(),
        liteRtManagerProvider: (() -> LiteRtLmServingManager?)? = null,
    ): LlmProviderRegistry = SimpleLlmProviderRegistry(defaults(transport, liteRtManagerProvider))
}
