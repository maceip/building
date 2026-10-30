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
import dev.ide.platform.log.Log
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
private val AgentSettingsComponent = Log.registerComponent(
    id = "agent.settings",
    owner = "agent-ui",
    description = "Agent settings screen (provider, API key, model, effort, permissions, IDE prefs)",
)

@Composable
fun AgentSettingsScreen(ctx: ScreenContext) {
    AgentSettingsHost(
        backend = ctx.backend,
        onBack = ctx::back,
        onOpenEditorPrefs = {
            Log.traceUiAction(AgentSettingsComponent.id.value, "OpenIdeSettings") { scope ->
                scope.recordEffect("openScreen:__hub__")
                ctx.openScreen("__hub__")
            }
        },
        onOpenLiteRtCorner = {
            Log.traceUiAction(AgentSettingsComponent.id.value, "OpenLiteRtCorner") { scope ->
                scope.recordEffect("openScreen:$SCREEN_LITERT_LM_CORNER")
                ctx.openScreen(SCREEN_LITERT_LM_CORNER)
            }
        },
    )
}

@Composable
fun AgentSettingsHost(
    backend: IdeBackend,
    onBack: () -> Unit = {},
    onOpenEditorPrefs: () -> Unit = {},
    onOpenLiteRtCorner: () -> Unit = {},
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
                                Log.traceUiAction(AgentSettingsComponent.id.value, "SelectProvider:${provider.id}") {
                                    agent.selectProvider(provider.id)
                                    Log.recordStateMutation(AgentSettingsComponent.id.value, "selectedProvider", provider.id)
                                    cfg = agent.config()
                                }
                            }
                        },
                        onSetKey = { keyVal ->
                            Log.traceUiAction(AgentSettingsComponent.id.value, "SetProviderKey:${provider.id}") {
                                agent.setProviderKey(provider.id, keyVal)
                                Log.recordStateMutation(AgentSettingsComponent.id.value, "key.${provider.id}", if (keyVal.isBlank()) "empty" else "set")
                                cfg = agent.config()
                            }
                        },
                        onSetBaseUrl = { urlVal ->
                            Log.traceUiAction(AgentSettingsComponent.id.value, "SetProviderBaseUrl:${provider.id}") {
                                agent.setProviderBaseUrl(provider.id, urlVal)
                                Log.recordStateMutation(AgentSettingsComponent.id.value, "baseUrl.${provider.id}", urlVal)
                                cfg = agent.config()
                            }
                        },
                        onPickModel = { modelVal ->
                            Log.traceUiAction(AgentSettingsComponent.id.value, "PickModel:$modelVal") {
                                agent.setModel(modelVal)
                                Log.recordStateMutation(AgentSettingsComponent.id.value, "model", modelVal)
                                cfg = agent.config()
                            }
                        },
                        onSetGateway = { url, model, ca ->
                            Log.traceUiAction(AgentSettingsComponent.id.value, "SetGateway") {
                                agent.setGateway(url, model, ca)
                                Log.recordStateMutation(AgentSettingsComponent.id.value, "gateway", "$url|$model")
                                cfg = agent.config()
                            }
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
                        Log.traceUiAction(AgentSettingsComponent.id.value, "SetEffort:$nextEffort") {
                            agent.setEffort(nextEffort)
                            Log.recordStateMutation(AgentSettingsComponent.id.value, "effort", nextEffort)
                            cfg = agent.config()
                        }
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
                                val nextMode = when (i) {
                                    0 -> UiAgentPermissionMode.PLAN_ONLY
                                    2 -> UiAgentPermissionMode.AUTO_ACCEPT
                                    else -> UiAgentPermissionMode.ASK_EACH
                                }
                                Log.traceUiAction(AgentSettingsComponent.id.value, "SetPermissionMode:$nextMode") {
                                    agent.setPermissionMode(nextMode)
                                    Log.recordStateMutation(AgentSettingsComponent.id.value, "permissionMode", nextMode.name)
                                    cfg = agent.config()
                                }
                            },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentSectionLabel("LiteRT-LM Corner")
                AgentSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("agent_settings_open_litert_corner")
                        .clickable(onClick = onOpenLiteRtCorner),
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
                                "Self-Serve LiteRT-LM Corner",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = AgentUi.chrome.text,
                            )
                            Text(
                                "Local .litertlm catalog, NPU/GPU delegates, loopback server & guest SDK",
                                fontSize = 12.5.sp,
                                color = AgentUi.chrome.textMuted,
                            )
                        }
                        Icon(
                            CaIcons.arrowRight,
                            contentDescription = "Open Self-Serve LiteRT-LM Corner",
                            tint = AgentUi.chrome.textMuted,
                            modifier = Modifier.size(18.dp),
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

