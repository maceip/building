package dev.ide.core.reliability

import dev.ide.agent.IncidentAiConfiguration
import dev.ide.agent.IncidentAiProvider
import dev.ide.agent.LlmClient
import dev.ide.agent.LlmProviderRegistry
import dev.ide.agent.ProviderConfig
import dev.ide.agent.impl.AgentProviders
import dev.ide.agent.impl.IncidentDigests
import dev.ide.ui.backend.AgentService
import dev.ide.ui.backend.UiAgentConfig

/** Reuses AgentService's selected provider/configuration and the existing provider clients, not chat.send. */
class IncidentAgentProvider(
    private val agent: AgentService,
    private val registry: LlmProviderRegistry = AgentProviders.registry(),
) : IncidentAiProvider {
    override fun configuration(): IncidentAiConfiguration? = resolve()?.let { value ->
        IncidentAiConfiguration(value.id, "${value.selectedProvider} / ${value.model}")
    }

    override fun client(expectedConfigurationId: String): LlmClient? = resolve()?.takeIf { it.id == expectedConfigurationId }?.let {
        registry.provider(it.provider)?.client(it.config)
    }

    override fun model(expectedConfigurationId: String): String? = resolve()?.takeIf { it.id == expectedConfigurationId }?.model

    private class Resolved(
        val id: String, val selectedProvider: String, val provider: String, val model: String, val config: ProviderConfig,
    )

    private fun resolve(): Resolved? {
        val settings: UiAgentConfig = agent.config()
        if (!settings.configured) return null
        val selected = settings.providers.singleOrNull { it.id == settings.selectedProvider } ?: return null
        val providerId = if (selected.id == "gateway") "openai" else selected.id
        val provider = registry.provider(providerId) ?: return null
        val baseUrl = if (selected.id == "gateway") settings.gatewayBaseUrl.takeIf { it.isNotBlank() } else selected.baseUrl.takeIf { it.isNotBlank() }
        val model = (if (selected.id == "gateway") settings.gatewayModel else settings.model).ifBlank {
            if (selected.id == "gateway") return null else provider.defaultModel
        }
        if (selected.id == "gateway" && baseUrl == null) return null
        if (selected.id != "gateway" && selected.apiKey.isBlank()) return null
        val ca = settings.gatewayCaCert.takeIf { selected.id == "gateway" && it.isNotBlank() }
        // Consent changes when the endpoint/model/account changes. No key or base URL is sent to incident tools.
        val fingerprint = IncidentDigests.entries(mapOf("provider" to selected.id, "endpoint" to baseUrl.orEmpty(),
            "model" to model, "account" to IncidentDigests.bytes(selected.apiKey.toByteArray()),
            "ca" to IncidentDigests.bytes(ca.orEmpty().toByteArray())))
        return Resolved(fingerprint, selected.id, providerId, model, ProviderConfig(selected.apiKey, baseUrl, ca))
    }
}
