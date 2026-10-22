package dev.ide.agent.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.AgentService
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAgentPermissionMode
import dev.ide.ui.ext.ScreenContext
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.screens.AgentRadioRow
import dev.ide.ui.screens.AgentSectionLabel
import dev.ide.ui.screens.AgentSegmented
import dev.ide.ui.screens.AgentSurface
import dev.ide.ui.screens.AgentTopBar
import dev.ide.ui.theme.AgentUi

/**
 * Agent configuration screen wired directly to [AgentService]:
 * - Real provider selection, API keys/tokens, model chips, and custom gateway settings ([ProviderCard]).
 * - Real reasoning effort selector (`default`, `low`, `medium`, `high`).
 * - Real permission mode selector (`PLAN_ONLY`, `ASK_EACH`, `AUTO_ACCEPT`) inside a framed card.
 * - Real IDE settings card navigating to the full Settings & Tools hub.
 */
@Composable
fun AgentSettingsScreen(ctx: ScreenContext) {
    AgentSettingsHost(
        backend = ctx.backend,
        onBack = ctx::back,
        onOpenEditorPrefs = { ctx.openScreen("__hub__") },
    )
}

@Composable
fun AgentSettingsHost(
    backend: IdeBackend,
    onBack: () -> Unit = {},
    onOpenEditorPrefs: () -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
) {
    val agent = backend.agent
    var cfg by remember(agent) { mutableStateOf(agent.config()) }

    val effortValues = remember { listOf("default", "low", "medium", "high") }
    val effortLabels = remember { listOf("Default", "Low", "Medium", "High") }
    val effortIndex = effortValues.indexOf(cfg.effort.lowercase()).let { if (it >= 0) it else 0 }

    val permissionIndex = when (cfg.mode) {
        UiAgentPermissionMode.PLAN_ONLY -> 0
        UiAgentPermissionMode.AUTO_ACCEPT -> 2
        else -> 1
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AgentUi.chrome.bg)
            .verticalScroll(scrollState)
            .testTag("agent_settings_scroll"),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        AgentTopBar(title = "", onBack = onBack, backIcon = CaIcons.arrowLeft)
        Column(
            Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text(
                "Agent",
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                color = AgentUi.chrome.text,
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentSectionLabel("Provider & API key")
                cfg.providers.forEach { provider ->
                    ProviderCard(
                        provider = provider,
                        selected = provider.id == cfg.selectedProvider,
                        activeModel = cfg.model.ifBlank { provider.defaultModel },
                        gatewayBaseUrl = cfg.gatewayBaseUrl,
                        gatewayModel = cfg.gatewayModel,
                        gatewayCaCert = cfg.gatewayCaCert,
                        onSelect = {
                            if (provider.id != cfg.selectedProvider) {
                                agent.selectProvider(provider.id)
                                cfg = agent.config()
                            }
                        },
                        onSetKey = {
                            agent.setProviderKey(provider.id, it)
                            cfg = agent.config()
                        },
                        onSetBaseUrl = {
                            agent.setProviderBaseUrl(provider.id, it)
                            cfg = agent.config()
                        },
                        onPickModel = {
                            agent.setModel(it)
                            cfg = agent.config()
                        },
                        onSetGateway = { url, model, ca ->
                            agent.setGateway(url, model, ca)
                            cfg = agent.config()
                        },
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentSectionLabel("Reasoning effort")
                AgentSegmented(
                    options = effortLabels,
                    selectedIndex = effortIndex,
                    onSelect = { idx ->
                        val nextEffort = effortValues.getOrElse(idx) { "default" }
                        agent.setEffort(nextEffort)
                        cfg = agent.config()
                    },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentSectionLabel("Permissions")
                AgentSurface(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    stroke = AgentUi.chrome.hairlineStrong,
                ) {
                    val perms = listOf(
                        "Suggest only — I apply every edit",
                        "Edit and build — ask before installs",
                        "Full auto in this project",
                    )
                    perms.forEachIndexed { i, label ->
                        AgentRadioRow(
                            label = label,
                            selected = i == permissionIndex,
                            onSelect = {
                                agent.setPermissionMode(
                                    when (i) {
                                        0 -> UiAgentPermissionMode.PLAN_ONLY
                                        2 -> UiAgentPermissionMode.AUTO_ACCEPT
                                        else -> UiAgentPermissionMode.ASK_EACH
                                    },
                                )
                                cfg = agent.config()
                            },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentSectionLabel("IDE settings")
                AgentSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("agent_settings_open_ide_prefs")
                        .clickable(onClick = onOpenEditorPrefs),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    stroke = AgentUi.chrome.hairlineStrong,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Editor, keymap, code style & tools",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = AgentUi.chrome.text,
                            )
                            Text(
                                "Open full IDE settings",
                                fontSize = 12.5.sp,
                                color = AgentUi.chrome.textMuted,
                            )
                        }
                        Icon(
                            CaIcons.arrowRight,
                            contentDescription = "Open full IDE settings",
                            tint = AgentUi.chrome.textMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
