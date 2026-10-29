package dev.ide.agent.ui

import dev.ide.ui.components.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAgentProvider
import dev.ide.agent.ui.generated.resources.Res
import dev.ide.agent.ui.generated.resources.chat_api_key
import dev.ide.agent.ui.generated.resources.chat_api_key_optional
import dev.ide.agent.ui.generated.resources.chat_base_url
import dev.ide.agent.ui.generated.resources.chat_ca_cert
import dev.ide.agent.ui.generated.resources.chat_ca_cert_hint
import dev.ide.agent.ui.generated.resources.chat_close
import dev.ide.agent.ui.generated.resources.chat_connected
import dev.ide.agent.ui.generated.resources.chat_done
import dev.ide.agent.ui.generated.resources.chat_gateway_hint
import dev.ide.agent.ui.generated.resources.chat_hide
import dev.ide.agent.ui.generated.resources.chat_model
import dev.ide.agent.ui.generated.resources.chat_providers_subtitle
import dev.ide.agent.ui.generated.resources.chat_providers_title
import dev.ide.agent.ui.generated.resources.chat_show
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.screens.AgentChip
import dev.ide.ui.screens.AgentPrimaryButton
import dev.ide.ui.theme.AgentUi
import org.jetbrains.compose.resources.stringResource

/**
 * The AI provider / key manager. An opaque dialog listing each provider (plus a first-class "Custom gateway"
 * for OpenAI-compatible endpoints) as a selectable card; the active card expands to a masked, show/hide API
 * key field (and, for the gateway, a base URL + model). Everything auto-saves through [dev.ide.ui.backend.AgentService].
 * Reached from the chat header's key button and the empty-state call to action. See docs/agentic-coding.md.
 */
@Composable
internal fun AgentProvidersSheet(backend: IdeBackend, onClose: () -> Unit) {
    var cfg by remember { mutableStateOf(backend.agent.config()) }
    val chrome = AgentUi.chrome
    CenteredDialog(visible = true, onDismiss = onClose) {
        Column(
            Modifier.widthIn(max = 460.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(AgentUi.radius.sheet))
                // Opaque: a key field is the last thing that should be legible-depending-on-what-is-behind-it.
                .background(chrome.surface)
                .border(1.dp, chrome.hairlineStrong, RoundedCornerShape(AgentUi.radius.sheet))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CaIcons.key, null, Modifier.size(18.dp), tint = chrome.accent)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(Res.string.chat_providers_title),
                    color = chrome.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButtonCa(CaIcons.close, stringResource(Res.string.chat_close), onClose, iconSize = 16, boxSize = 36)
            }
            Text(stringResource(Res.string.chat_providers_subtitle), color = chrome.textFaint, style = MaterialTheme.typography.bodySmall)
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cfg.providers.forEach { provider ->
                    ProviderCard(
                        provider = provider,
                        selected = provider.id == cfg.selectedProvider,
                        activeModel = cfg.model.ifBlank { provider.defaultModel },
                        gatewayBaseUrl = cfg.gatewayBaseUrl,
                        gatewayModel = cfg.gatewayModel,
                        gatewayCaCert = cfg.gatewayCaCert,
                        onSelect = { backend.agent.selectProvider(provider.id); cfg = backend.agent.config() },
                        onSetKey = { backend.agent.setProviderKey(provider.id, it); cfg = backend.agent.config() },
                        onSetBaseUrl = { backend.agent.setProviderBaseUrl(provider.id, it); cfg = backend.agent.config() },
                        onPickModel = { backend.agent.setModel(it); cfg = backend.agent.config() },
                        onSetGateway = { url, model, ca -> backend.agent.setGateway(url, model, ca); cfg = backend.agent.config() },
                    )
                }
            }
            AgentPrimaryButton(stringResource(Res.string.chat_done), onClose, Modifier.fillMaxWidth())
        }
    }
}

private fun providerSubtitle(id: String): String = when (id) {
    "antigravity" -> "Code Assist / Gemini SSE · Gemini 3.1 Pro, Claude 4.6, GPT-OSS"
    "anthropic" -> "Messages API (/v1/messages) · Claude Opus 5, Sonnet 5, Haiku 4.5"
    "openai" -> "Responses API (/v1/responses) · GPT-6.1 Sol, GPT-6 Astra, GPT-5"
    "muse" -> "Responses & Messages API · Muse Spark 1.3, Wonder, Glimmer"
    "mimo" -> "Chat & Anthropic Messages API · MiMo V2.6 Pro, Flash, UltraSpeed"
    "gemini" -> "GenerateContent SSE · Gemini 3.8 Flash, 3.1 Pro"
    "openrouter" -> "Unified Gateway · Claude, GPT-6.1 Sol, Muse, MiMo"
    "litert" -> "On-Device NPU / GPU / CPU · Gemma 3, Qwen 2.5 Coder, DeepSeek R1"
    "gateway" -> "Custom OpenAI / Responses (/v1/responses) / Messages (/v1/messages)"
    else -> ""
}

private fun providerKeyPlaceholder(id: String, defaultPlaceholder: String): String = when (id) {
    "antigravity" -> "OAuth token (ya29... or 1//...) or AIza... key"
    "anthropic" -> "sk-ant-..."
    "openai" -> "sk-..."
    "muse" -> "Meta AI key (muse-...)"
    "mimo" -> "Xiaomi MiMo API key"
    "openrouter" -> "sk-or-..."
    else -> defaultPlaceholder
}

private fun providerBaseUrlHint(id: String): String = when (id) {
    "antigravity" -> "Endpoint override (optional, default: https://cloudcode-pa.googleapis.com)"
    "anthropic" -> "Endpoint override (optional, default: https://api.anthropic.com)"
    "openai" -> "Endpoint override (optional, e.g. https://api.openai.com or .../v1/responses)"
    "muse" -> "Endpoint override (optional, default: https://api.meta.ai)"
    "mimo" -> "Endpoint override (optional, e.g. https://api.xiaomimimo.com/anthropic)"
    else -> "Endpoint override (optional)"
}

@Composable
internal fun ProviderCard(
    provider: UiAgentProvider,
    selected: Boolean,
    activeModel: String,
    gatewayBaseUrl: String,
    gatewayModel: String,
    gatewayCaCert: String,
    onSelect: () -> Unit,
    onSetKey: (String) -> Unit,
    onSetBaseUrl: (String) -> Unit,
    onPickModel: (String) -> Unit,
    onSetGateway: (String, String, String) -> Unit,
) {
    val chrome = AgentUi.chrome
    val isGateway = provider.id == "gateway"
    val isLiteRt = provider.id == "litert"
    var key by remember(provider.id) { mutableStateOf(provider.apiKey) }
    var customBaseUrl by remember(provider.id) { mutableStateOf(oneLine(provider.baseUrl)) }
    var baseUrl by remember(provider.id) { mutableStateOf(oneLine(gatewayBaseUrl)) }
    var model by remember(provider.id) { mutableStateOf(gatewayModel) }
    var caCert by remember(provider.id) { mutableStateOf(gatewayCaCert) }
    // A gateway is often a local server that takes no key, so its URL alone makes it usable.
    val hasKey = if (isGateway) baseUrl.isNotBlank() else key.isNotBlank()
    val shape = RoundedCornerShape(AgentUi.radius.card)
    // A selected card is a tonal container, so its text and its radio dot take the container's own content
    // colour. Leaving them on onSurface is the classic pairing slip: legible in one theme, muddy in the other.
    val container = if (selected) chrome.surfaceAlt else chrome.bg
    val onContainer = if (selected) chrome.text else chrome.text
    val subtitle = providerSubtitle(provider.id)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(container, shape)
            .border(1.dp, if (selected) chrome.accent else chrome.hairline, shape)
            .semantics { this.selected = selected }
            .clickable(onClick = onSelect)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RadioDot(selected, if (selected) chrome.accent else chrome.textFaint)
            Column(Modifier.weight(1f)) {
                Text(
                    provider.displayName,
                    color = onContainer, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        color = if (selected) chrome.textMuted else chrome.textFaint,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (hasKey) {
                AgentChip(
                    label = stringResource(Res.string.chat_connected),
                    selected = true,
                )
            }
        }
        if (selected) {
            if (!isLiteRt) {
                val defaultKeyPlaceholder = stringResource(if (isGateway) Res.string.chat_api_key_optional else Res.string.chat_api_key)
                SecretField(key, providerKeyPlaceholder(provider.id, defaultKeyPlaceholder)) { key = it; onSetKey(it) }
            }
            if (!isGateway && provider.models.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    provider.models.take(4).forEach { m ->
                        val isChosen = m.id == activeModel
                        AgentChip(
                            label = m.displayName,
                            selected = isChosen,
                            onClick = { onPickModel(m.id) },
                        )
                    }
                }
                if (!isLiteRt) {
                    PlainField(customBaseUrl, providerBaseUrlHint(provider.id)) {
                        customBaseUrl = oneLine(it)
                        onSetBaseUrl(customBaseUrl)
                    }
                } else {
                    Text(
                        if (hasKey) "Verified .litertlm model active (${provider.baseUrl})" else "Manage .litertlm models in the Self-Serve LiteRT-LM Corner",
                        color = chrome.textFaint,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (isGateway) {
                // A single-line field shows only the first line of a multi-line paste, so a pasted "URL + key" block
                // looked like a clean URL while the rest still went into requests. Flatten it so all of it is visible.
                PlainField(baseUrl, stringResource(Res.string.chat_base_url)) { baseUrl = oneLine(it); onSetGateway(baseUrl, model, caCert) }
                PlainField(model, stringResource(Res.string.chat_model)) { model = it; onSetGateway(baseUrl, it, caCert) }
                PlainField(caCert, stringResource(Res.string.chat_ca_cert)) { caCert = it; onSetGateway(baseUrl, model, it) }
                Text(stringResource(Res.string.chat_ca_cert_hint), color = chrome.textFaint, style = MaterialTheme.typography.labelSmall)
                Text(stringResource(Res.string.chat_gateway_hint), color = chrome.textFaint, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private val LINE_BREAKS = Regex("\\s*[\\r\\n]+\\s*")

private fun oneLine(text: String): String = text.replace(LINE_BREAKS, " ")

@Composable
private fun RadioDot(selected: Boolean, selectedTint: Color = AgentUi.chrome.accent) {
    val chrome = AgentUi.chrome
    Box(
        Modifier.size(18.dp).clip(CircleShape)
            .border(2.dp, if (selected) selectedTint else chrome.hairlineStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(9.dp).clip(CircleShape).background(selectedTint))
    }
}

@Composable
private fun SecretField(value: String, placeholder: String, onValueChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val chrome = AgentUi.chrome
    val shape = RoundedCornerShape(AgentUi.radius.control)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(chrome.bg)
            .border(1.dp, chrome.hairlineStrong, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = chrome.textFaint, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = chrome.text),
                cursorBrush = SolidColor(chrome.accent),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            stringResource(if (visible) Res.string.chat_hide else Res.string.chat_show),
            color = chrome.accent, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(AgentUi.radius.chip))
                .clickable { visible = !visible }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun PlainField(value: String, placeholder: String, onValueChange: (String) -> Unit) {
    val chrome = AgentUi.chrome
    val shape = RoundedCornerShape(AgentUi.radius.control)
    Box(
        Modifier.fillMaxWidth().clip(shape).background(chrome.bg)
            .border(1.dp, chrome.hairlineStrong, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = chrome.textFaint, style = MaterialTheme.typography.bodyMedium)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = chrome.text),
            cursorBrush = SolidColor(chrome.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
