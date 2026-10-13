package dev.ide.core.qualitysemantic

import dev.ide.agent.LlmClient
import dev.ide.agent.LlmProviderRegistry
import dev.ide.agent.ProviderConfig
import dev.ide.agent.impl.AgentProviders
import dev.ide.agent.impl.AnthropicProvider
import dev.ide.agent.impl.GeminiProvider
import dev.ide.agent.impl.LlmTransport
import dev.ide.agent.impl.MetaMuseProvider
import dev.ide.agent.impl.OkHttpLlmTransport
import dev.ide.agent.impl.OpenAiProvider
import dev.ide.agent.impl.OpenRouterProvider
import dev.ide.agent.impl.XiaomiMimoProvider
import dev.ide.ui.backend.AgentService
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAgentConfig
import dev.ide.workbench.quality.semantic.AgentSemanticLabeler
import dev.ide.workbench.quality.semantic.SemanticException
import dev.ide.workbench.quality.semantic.SemanticFailure
import dev.ide.workbench.quality.semantic.SemanticLabeler
import dev.ide.workbench.quality.semantic.SemanticLocality
import dev.ide.workbench.quality.semantic.SemanticProvider
import java.net.URI

/** Existing provider implementations/settings only; preview reads no network and persists no credentials. */
object ConfiguredSemanticLabeler {
    // Existing registry factory over an inert transport: static provider metadata only, no client construction.
    private val metadata = AgentProviders.registry(object : LlmTransport {
        override fun sse(request: dev.ide.agent.impl.SseRequest): kotlinx.coroutines.flow.Flow<String> =
            error("Metadata registry has no transport")
    })

    fun from(backend: IdeBackend): SemanticLabeler? = from(backend.agent)
    fun from(agent: AgentService): SemanticLabeler? = from(metadata, agent::config)

    /** [config] must read the existing credential settings locally, never discover models remotely. */
    internal fun from(
        registry: LlmProviderRegistry,
        config: () -> UiAgentConfig,
        clientFactory: (String) -> LlmProviderRegistry = { origin ->
            AgentProviders.registry(SemanticOnlyTransport(OkHttpLlmTransport(SemanticEgressGuard(origin).client())))
        },
    ): SemanticLabeler? {
        val snapshot = binding(config(), registry) ?: return null
        val provider = registry.provider(snapshot.clientProviderId) ?: return null
        val target = try {
            SemanticProvider(snapshot.selectedId, provider.displayName, snapshot.model, snapshot.destination, snapshot.locality,
                maxWireRequestsPerRun = 2)
        } catch (_: IllegalArgumentException) { return null }
        // This port is deliberately ONE confirmed run. A new preview creates a fresh bounded transport.
        // Reuse by an external host can only exhaust the old two-send budget, never expand it silently.
        var activeClient: LlmClient? = null
        return AgentSemanticLabeler(target) {
            val current = config()
            if (binding(current, registry) != snapshot) throw SemanticException(SemanticFailure.PROVIDER_CHANGED)
            activeClient ?: run {
                val selected = current.providers.firstOrNull { it.id == snapshot.selectedId }
                    ?: throw SemanticException(SemanticFailure.UNAVAILABLE)
                val boundedProvider = clientFactory(snapshot.destination).provider(snapshot.clientProviderId)
                    ?: throw SemanticException(SemanticFailure.UNAVAILABLE)
                // Key is read only here and kept only by the short-lived existing client; never hashed/logged/saved.
                boundedProvider.client(ProviderConfig(selected.apiKey, snapshot.baseUrl, snapshot.caCertificatePem))
                    .also { activeClient = it }
            }
        }
    }

    private data class Binding(
        val selectedId: String,
        val clientProviderId: String,
        val model: String,
        val baseUrl: String,
        val caCertificatePem: String?,
        val destination: String,
        val locality: SemanticLocality,
    )

    private fun binding(config: UiAgentConfig, registry: LlmProviderRegistry): Binding? {
        if (!config.configured) return null
        val selected = config.providers.firstOrNull { it.id == config.selectedProvider } ?: return null
        val gateway = selected.id == "gateway"
        val clientId = if (gateway) "openai" else selected.id
        // OAuth's secondary origin and token refresh require a separately reviewed flow; do not silently permit it.
        val defaultBase = defaultBase(clientId) ?: return null
        val provider = registry.provider(clientId) ?: return null
        val model = (if (gateway) config.gatewayModel else config.model).ifBlank { if (gateway) "" else provider.defaultModel }
        if (model.isBlank() || listOf(model, selected.id, provider.displayName).any { !safePublicText(it) }) return null
        val configuredBase = (if (gateway) config.gatewayBaseUrl else selected.baseUrl).trim().takeIf { it.isNotEmpty() }
        if (gateway && configuredBase == null) return null
        val base = configuredBase ?: defaultBase
        val uri = try { URI(base) } catch (_: Exception) { return null }
        if (uri.host == null || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        // Literal loopback only. DNS names (including localhost) and LAN endpoints stay CLOUD.
        val loopback = gateway && uri.host in setOf("127.0.0.1", "[::1]", "::1")
        if (uri.scheme != "https" && !(loopback && uri.scheme == "http")) return null
        val destination = "${uri.scheme}://${uri.host}${if (uri.port >= 0) ":${uri.port}" else ""}"
        return Binding(selected.id, clientId, model, base,
            config.gatewayCaCert.takeIf { gateway && it.isNotBlank() }, destination,
            if (loopback) SemanticLocality.LOCAL else SemanticLocality.CLOUD)
    }

    private fun defaultBase(id: String): String? = when (id) {
        "anthropic" -> AnthropicProvider.DEFAULT_BASE
        "openai" -> OpenAiProvider.DEFAULT_BASE
        "gemini" -> GeminiProvider.DEFAULT_BASE
        "muse" -> MetaMuseProvider.DEFAULT_BASE
        "mimo" -> XiaomiMimoProvider.DEFAULT_BASE
        "openrouter" -> OpenRouterProvider.DEFAULT_BASE
        else -> null
    }
    private fun safePublicText(value: String): Boolean = value.length in 1..256 &&
        !Regex("(?i)(?:sk-[a-z0-9_-]{12,}|gh[pousr]_|github_pat_|glpat-|bearer\\s|(?:api[_-]?key|token|secret|password)\\s*[:=])").containsMatchIn(value) &&
        value.none { it.isISOControl() || it == '@' || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' }
}
