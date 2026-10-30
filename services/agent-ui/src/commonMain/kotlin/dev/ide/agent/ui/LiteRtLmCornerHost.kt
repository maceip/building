package dev.ide.agent.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.platform.log.Log
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiLiteRtCornerState
import dev.ide.ui.backend.UiLiteRtDelegate
import dev.ide.ui.backend.UiLiteRtGuestLanguage
import dev.ide.ui.backend.UiLiteRtModelEntry
import dev.ide.ui.backend.UiLiteRtModelStatus
import dev.ide.ui.backend.UiLlmApiFormat
import dev.ide.ui.backend.UiLlmCircuitState
import dev.ide.ui.backend.UiLlmLiveConfigTarget
import dev.ide.ui.backend.UiLlmSwitchMode
import dev.ide.ui.backend.UiLlmSwitchProviderInput
import dev.ide.ui.backend.UiLlmSwitchProviderProfile
import dev.ide.ui.ext.ScreenContext
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.screens.AgentChip
import dev.ide.ui.screens.AgentGhostButton
import dev.ide.ui.screens.AgentPrimaryButton
import dev.ide.ui.screens.AgentSectionLabel
import dev.ide.ui.screens.AgentSegmented
import dev.ide.ui.screens.AgentStatusDot
import dev.ide.ui.screens.AgentSurface
import dev.ide.ui.screens.AgentToggleRow
import dev.ide.ui.screens.AgentTopBar
import dev.ide.ui.theme.AgentUi
import dev.ide.ui.theme.Ca

/** Screen ID for the Self-Serve LiteRT-LM & Universal CC-Switch Corner inside the Settings & Tools Hub and Agent Settings. */
const val SCREEN_LITERT_LM_CORNER = "agent.litert.corner"

private val LiteRtCornerComponent = Log.registerComponent(
    id = "agent.litert.corner",
    owner = "agent-ui",
    description = "Self-Serve LiteRT-LM & Universal CC-Switch Corner (provider switching, cross-protocol proxy, circuit breaker failover, AI Core, NPU/GPU/CPU delegates, guest SDK)",
)

@Composable
fun LiteRtLmCornerScreen(ctx: ScreenContext) {
    LiteRtLmCornerHost(
        backend = ctx.backend,
        onBack = {
            Log.traceUiAction(LiteRtCornerComponent.id.value, "Back") { scope ->
                scope.recordEffect("back")
                ctx.back()
            }
        },
    )
}

@Composable
fun LiteRtLmCornerHost(
    backend: IdeBackend,
    onBack: () -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
) {
    val agent = backend.agent
    val observedState by agent.liteRtCornerState.collectAsState()
    var snapshot by remember(agent) { mutableStateOf(agent.liteRtCorner()) }
    // Keep snapshot in sync with StateFlow emissions and immediate actions.
    val state: UiLiteRtCornerState = if (
        observedState.models.isNotEmpty() ||
        observedState.universalProviders.isNotEmpty() ||
        (snapshot.models.isEmpty() && snapshot.universalProviders.isEmpty())
    ) {
        observedState
    } else {
        snapshot
    }

    val clipboard = LocalClipboardManager.current
    var copiedBanner by remember { mutableStateOf("") }
    var expandedProviderId by remember { mutableStateOf("") }
    var smokePrompt by remember {
        mutableStateOf(
            state.lastSmokeTestPrompt.ifBlank { "Reply with a one-line status check from LiteRT-LM." },
        )
    }

    val switchModes = remember {
        listOf(UiLlmSwitchMode.DIRECT, UiLlmSwitchMode.ROUTING, UiLlmSwitchMode.AGGREGATION)
    }
    val switchModeLabels = remember {
        listOf("Direct", "Routing Proxy", "Aggregation Stack")
    }
    val selectedSwitchModeIndex = switchModes.indexOf(state.switchMode).coerceAtLeast(0)

    val delegateValues = remember { listOf(UiLiteRtDelegate.CPU, UiLiteRtDelegate.GPU, UiLiteRtDelegate.NPU) }
    val delegateLabels = remember { listOf("CPU", "GPU", "NPU") }
    val selectedDelegateIndex = delegateValues.indexOf(state.hardware.selectedDelegate).coerceAtLeast(0)

    val guestLanguages = remember {
        listOf(
            UiLiteRtGuestLanguage.KOTLIN_ANDROID,
            UiLiteRtGuestLanguage.SWIFT,
            UiLiteRtGuestLanguage.NODE_JS,
            UiLiteRtGuestLanguage.PYTHON,
        )
    }
    val guestLanguageLabels = remember {
        listOf("Kotlin", "Swift", "Node.js", "Python")
    }
    val selectedGuestIndex = guestLanguages.indexOf(state.selectedGuestLanguage).coerceAtLeast(0)

    fun refreshLocalSnapshot() {
        snapshot = agent.liteRtCorner()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AgentUi.chrome.bg)
            .verticalScroll(scrollState)
            .testTag("litert_corner_scroll"),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        AgentTopBar(
            title = "Self-Serve LiteRT-LM Corner",
            subtitle = "Universal CC-Switch Provider Hub · AI Core + .litertlm · Cross-Protocol Proxy & Failover",
            onBack = onBack,
            backIcon = CaIcons.arrowLeft,
        )

        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val activeBanner = copiedBanner.ifBlank { state.statusBanner }
            if (activeBanner.isNotBlank()) {
                AgentSurface(
                    modifier = Modifier.fillMaxWidth().testTag("litert_status_banner"),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    stroke = AgentUi.chrome.accent,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AgentStatusDot(active = true)
                        Text(
                            activeBanner,
                            fontSize = 13.sp,
                            color = AgentUi.chrome.text,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // -----------------------------------------------------------------------------------------
            // 0. Universal CC-Switch Control Plane (Operating Mode, Failover, Universal Providers)
            // -----------------------------------------------------------------------------------------
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentSectionLabel("Universal CC-Switch Provider Control Plane")
                AgentSurface(
                    modifier = Modifier.fillMaxWidth().testTag("ccswitch_control_plane_card"),
                    contentPadding = PaddingValues(16.dp),
                    stroke = AgentUi.chrome.hairlineStrong,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "Universal Provider Switcher & Proxy",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AgentUi.chrome.text,
                                )
                                Text(
                                    state.switchMode.summary,
                                    fontSize = 12.5.sp,
                                    color = AgentUi.chrome.textMuted,
                                )
                            }
                            AgentChip(
                                label = "Active: ${state.activeProviderId}",
                                selected = true,
                                leading = { AgentStatusDot(active = true) },
                                onClick = null,
                            )
                        }

                        AgentSegmented(
                            options = switchModeLabels,
                            selectedIndex = selectedSwitchModeIndex,
                            onSelect = { idx ->
                                val nextMode = switchModes.getOrElse(idx) { UiLlmSwitchMode.DIRECT }
                                Log.traceUiAction(LiteRtCornerComponent.id.value, "SelectSwitchMode:${nextMode.name}") {
                                    agent.selectLlmSwitchMode(nextMode)
                                    Log.recordStateMutation(LiteRtCornerComponent.id.value, "switchMode", nextMode.name)
                                    refreshLocalSnapshot()
                                }
                            },
                        )

                        AgentToggleRow(
                            title = "Automatic Failover & Circuit Breaker",
                            subtitle = "Automatically failover across P1 -> P2 queue and trip circuit breaker on upstream errors",
                            checked = state.autoFailoverEnabled,
                            onCheckedChange = { enabled ->
                                Log.traceUiAction(LiteRtCornerComponent.id.value, "SetAutoFailover:$enabled") {
                                    agent.setAutoFailoverEnabled(enabled)
                                    agent.setCircuitBreakerEnabled(enabled)
                                    Log.recordStateMutation(LiteRtCornerComponent.id.value, "autoFailoverEnabled", enabled.toString())
                                    refreshLocalSnapshot()
                                }
                            },
                        )

                        val existingPresetIds = state.universalProviders.map { it.presetId }.toSet() +
                            state.universalProviders.map { it.id }.toSet()
                        val unaddedPresets = state.providerPresets.filter {
                            it.presetId !in existingPresetIds && it.defaultProviderId !in existingPresetIds
                        }
                        if (unaddedPresets.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "One-Tap Provider Presets (${unaddedPresets.size} available)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = AgentUi.chrome.textMuted,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    unaddedPresets.forEach { preset ->
                                        AgentGhostButton(
                                            label = "+ ${preset.name}",
                                            onClick = {
                                                Log.traceUiAction(LiteRtCornerComponent.id.value, "AddPreset:${preset.presetId}") {
                                                    agent.addPresetProvider(preset.presetId)
                                                    Log.recordStateMutation(LiteRtCornerComponent.id.value, "presetAdded", preset.presetId)
                                                    refreshLocalSnapshot()
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                state.universalProviders.forEach { provider ->
                    UniversalProviderCard(
                        provider = provider,
                        expanded = expandedProviderId == provider.id,
                        onToggleExpand = {
                            val next = if (expandedProviderId == provider.id) "" else provider.id
                            Log.traceUiAction(LiteRtCornerComponent.id.value, "ToggleProviderConfig:${provider.id}") {
                                expandedProviderId = next
                                copiedBanner = if (next.isNotBlank()) {
                                    "Editing ${provider.name} configuration"
                                } else {
                                    "Closed ${provider.name} configuration"
                                }
                                Log.recordStateMutation(LiteRtCornerComponent.id.value, "expandedProviderId", next)
                            }
                        },
                        onSwitchActive = {
                            Log.traceUiAction(LiteRtCornerComponent.id.value, "SwitchUniversalProvider:${provider.id}") {
                                agent.switchUniversalProvider(provider.id)
                                Log.recordStateMutation(LiteRtCornerComponent.id.value, "activeProviderId", provider.id)
                                refreshLocalSnapshot()
                            }
                        },
                        onSaveProvider = { input ->
                            Log.traceUiAction(LiteRtCornerComponent.id.value, "SaveUniversalProvider:${input.id}") {
                                agent.saveUniversalProvider(input)
                                expandedProviderId = ""
                                Log.recordStateMutation(LiteRtCornerComponent.id.value, "savedProvider", input.id)
                                refreshLocalSnapshot()
                            }
                        },
                        onDeleteProvider = {
                            Log.traceUiAction(LiteRtCornerComponent.id.value, "DeleteUniversalProvider:${provider.id}") {
                                agent.deleteUniversalProvider(provider.id)
                                if (expandedProviderId == provider.id) expandedProviderId = ""
                                Log.recordStateMutation(LiteRtCornerComponent.id.value, "deletedProvider", provider.id)
                                refreshLocalSnapshot()
                            }
                        },
                        onToggleFailover = {
                            val next = !provider.inFailoverQueue
                            Log.traceUiAction(LiteRtCornerComponent.id.value, "ToggleFailover:${provider.id}:$next") {
                                agent.toggleProviderFailover(provider.id, next)
                                Log.recordStateMutation(LiteRtCornerComponent.id.value, "failover.${provider.id}", next.toString())
                                refreshLocalSnapshot()
                            }
                        },
                        onToggleAggregation = {
                            val next = !provider.inAggregationStack
                            Log.traceUiAction(LiteRtCornerComponent.id.value, "ToggleAggregation:${provider.id}:$next") {
                                agent.toggleProviderAggregation(provider.id, next)
                                Log.recordStateMutation(LiteRtCornerComponent.id.value, "stack.${provider.id}", next.toString())
                                refreshLocalSnapshot()
                            }
                        },
                        onProbeHealth = {
                            Log.traceUiAction(LiteRtCornerComponent.id.value, "ProbeProviderHealth:${provider.id}") {
                                agent.probeProviderHealth(provider.id)
                                Log.recordStateMutation(LiteRtCornerComponent.id.value, "probe.${provider.id}", "probed")
                                refreshLocalSnapshot()
                            }
                        },
                        onResetCircuit = {
                            Log.traceUiAction(LiteRtCornerComponent.id.value, "ResetCircuitBreaker:${provider.id}") {
                                agent.resetProviderCircuitBreaker(provider.id)
                                Log.recordStateMutation(LiteRtCornerComponent.id.value, "circuit.${provider.id}", "reset")
                                refreshLocalSnapshot()
                            }
                        },
                    )
                }

                if (state.aggregatedModels.isNotEmpty()) {
                    AgentSurface(
                        modifier = Modifier.fillMaxWidth().testTag("ccswitch_aggregation_stack_card"),
                        contentPadding = PaddingValues(16.dp),
                        stroke = AgentUi.chrome.hairlineStrong,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Unified Aggregation Model Stack (ccs-<provider>/<model>)",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AgentUi.chrome.text,
                                )
                                AgentChip(
                                    label = "${state.aggregatedModels.size} stacked models",
                                    selected = state.switchMode == UiLlmSwitchMode.AGGREGATION,
                                    onClick = null,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                state.aggregatedModels.take(8).forEach { stackedModel ->
                                    AgentGhostButton(
                                        label = "Use Stack ${stackedModel.id}",
                                        onClick = {
                                            Log.traceUiAction(LiteRtCornerComponent.id.value, "UseStackedModel:${stackedModel.id}") {
                                                agent.setModel(stackedModel.id)
                                                copiedBanner = "Routed IDE Agent to stacked model ${stackedModel.id}"
                                                Log.recordStateMutation(LiteRtCornerComponent.id.value, "model", stackedModel.id)
                                                refreshLocalSnapshot()
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.liveConfigTargets.isNotEmpty()) {
                    LiveCliSyncCard(
                        targets = state.liveConfigTargets,
                        onSyncConfigs = {
                            Log.traceUiAction(LiteRtCornerComponent.id.value, "SyncLiveToolConfigs") {
                                agent.syncLiveToolConfigs()
                                Log.recordStateMutation(LiteRtCornerComponent.id.value, "liveConfigSync", "synced")
                                refreshLocalSnapshot()
                            }
                        },
                    )
                }
            }

            // -----------------------------------------------------------------------------------------
            // 1. Hardware Acceleration & On-Device Storage
            // -----------------------------------------------------------------------------------------
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentSectionLabel("Hardware Acceleration & Storage")
                AgentSurface(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    stroke = AgentUi.chrome.hairlineStrong,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    state.hardware.socName.ifBlank { "On-Device Accelerator" },
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AgentUi.chrome.text,
                                )
                                Text(
                                    "Recommended: ${state.hardware.recommendedDelegate.name} · " +
                                        "Used: ${formatBytes(state.hardware.usedModelStorageBytes)} · " +
                                        "Free: ${formatBytes(state.hardware.freeStorageBytes)}",
                                    fontSize = 12.5.sp,
                                    color = AgentUi.chrome.textMuted,
                                )
                            }
                            AgentChip(
                                label = "${state.hardware.selectedDelegate.name} active",
                                selected = true,
                                onClick = null,
                            )
                        }

                        AgentSegmented(
                            options = delegateLabels,
                            selectedIndex = selectedDelegateIndex,
                            onSelect = { idx ->
                                val nextDelegate = delegateValues.getOrElse(idx) { UiLiteRtDelegate.CPU }
                                Log.traceUiAction(LiteRtCornerComponent.id.value, "SelectDelegate:${nextDelegate.name}") {
                                    agent.selectLiteRtDelegate(nextDelegate)
                                    Log.recordStateMutation(LiteRtCornerComponent.id.value, "selectedDelegate", nextDelegate.name)
                                    refreshLocalSnapshot()
                                }
                            },
                        )

                        if (state.hardware.modelsDirectoryPath.isNotBlank()) {
                            Text(
                                "Storage path: ${state.hardware.modelsDirectoryPath}",
                                fontSize = 11.5.sp,
                                color = AgentUi.chrome.textFaint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // -----------------------------------------------------------------------------------------
            // 2. Self-Serve On-Device .litertlm Model Catalog
            // -----------------------------------------------------------------------------------------
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentSectionLabel("On-Device AI Core & .litertlm Model Catalog")
                state.models.forEach { model ->
                    LiteRtModelCard(
                        model = model,
                        onDownload = {
                            Log.traceUiAction(LiteRtCornerComponent.id.value, "DownloadModel:${model.id}") {
                                agent.downloadLiteRtModel(model.id)
                                Log.recordStateMutation(LiteRtCornerComponent.id.value, "download.${model.id}", "started")
                                refreshLocalSnapshot()
                            }
                        },
                        onCancel = {
                            Log.traceUiAction(LiteRtCornerComponent.id.value, "CancelDownload:${model.id}") {
                                agent.cancelLiteRtDownload(model.id)
                                Log.recordStateMutation(LiteRtCornerComponent.id.value, "download.${model.id}", "cancelled")
                                refreshLocalSnapshot()
                            }
                        },
                        onActivate = {
                            Log.traceUiAction(LiteRtCornerComponent.id.value, "ActivateModel:${model.id}") {
                                agent.activateLiteRtModel(model.id)
                                Log.recordStateMutation(LiteRtCornerComponent.id.value, "activeModelId", model.id)
                                refreshLocalSnapshot()
                            }
                        },
                        onUseInIdeAgent = {
                            Log.traceUiAction(LiteRtCornerComponent.id.value, "UseInIdeAgent:${model.id}") {
                                agent.useLiteRtInIdeAgent(model.id)
                                Log.recordStateMutation(LiteRtCornerComponent.id.value, "ideAgentProvider", "litert:${model.id}")
                                refreshLocalSnapshot()
                            }
                        },
                        onDelete = {
                            Log.traceUiAction(LiteRtCornerComponent.id.value, "DeleteModel:${model.id}") {
                                agent.deleteLiteRtModel(model.id)
                                Log.recordStateMutation(LiteRtCornerComponent.id.value, "delete.${model.id}", "deleted")
                                refreshLocalSnapshot()
                            }
                        },
                    )
                }
            }

            // -----------------------------------------------------------------------------------------
            // 3. Local Loopback & IPC Inference Bridge (Multi-Protocol CC-Switch Proxy + LiteRT-LM)
            // -----------------------------------------------------------------------------------------
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentSectionLabel("Local Loopback & IPC Inference Bridge")
                AgentSurface(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    stroke = AgentUi.chrome.hairlineStrong,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AgentToggleRow(
                            title = "Local Loopback HTTP Server",
                            subtitle = if (state.server.running) {
                                "Listening on 127.0.0.1:${state.server.port} (Anthropic, OpenAI, Gemini & LiteRT-LM)"
                            } else {
                                "Expose /v1/messages, /v1/chat/completions, /v1/responses, /v1beta/models & /v1/litert/generate on 127.0.0.1:${state.server.port}"
                            },
                            checked = state.server.enabled,
                            onCheckedChange = { enabled ->
                                Log.traceUiAction(LiteRtCornerComponent.id.value, "SetLoopbackServer:$enabled") {
                                    agent.setLiteRtServerEnabled(enabled)
                                    Log.recordStateMutation(LiteRtCornerComponent.id.value, "serverEnabled", enabled.toString())
                                    refreshLocalSnapshot()
                                }
                            },
                        )

                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "Requests served: ${state.server.requestsServed} · " +
                                    "Tokens generated: ${state.server.tokensGenerated} · " +
                                    "Last latency: ${state.server.lastLatencyMs} ms",
                                fontSize = 12.5.sp,
                                color = AgentUi.chrome.textMuted,
                            )

                            EndpointRow(
                                label = "OpenAI API",
                                value = state.server.openAiEndpoint,
                                copyButtonLabel = "Copy OpenAI URL",
                                onCopy = {
                                    Log.traceUiAction(LiteRtCornerComponent.id.value, "CopyOpenAiUrl") {
                                        runCatching { clipboard?.setText(AnnotatedString(state.server.openAiEndpoint)) }
                                        copiedBanner = "Copied OpenAI endpoint: ${state.server.openAiEndpoint}"
                                        Log.recordStateMutation(LiteRtCornerComponent.id.value, "clipboard", state.server.openAiEndpoint)
                                    }
                                },
                            )

                            EndpointRow(
                                label = "Anthropic Messages API",
                                value = state.server.anthropicEndpoint,
                                copyButtonLabel = "Copy Anthropic URL",
                                onCopy = {
                                    Log.traceUiAction(LiteRtCornerComponent.id.value, "CopyAnthropicUrl") {
                                        runCatching { clipboard?.setText(AnnotatedString(state.server.anthropicEndpoint)) }
                                        copiedBanner = "Copied Anthropic Messages endpoint: ${state.server.anthropicEndpoint}"
                                        Log.recordStateMutation(LiteRtCornerComponent.id.value, "clipboard", state.server.anthropicEndpoint)
                                    }
                                },
                            )

                            EndpointRow(
                                label = "OpenAI Responses API",
                                value = state.server.responsesEndpoint,
                                copyButtonLabel = "Copy Responses URL",
                                onCopy = {
                                    Log.traceUiAction(LiteRtCornerComponent.id.value, "CopyResponsesUrl") {
                                        runCatching { clipboard?.setText(AnnotatedString(state.server.responsesEndpoint)) }
                                        copiedBanner = "Copied OpenAI Responses endpoint: ${state.server.responsesEndpoint}"
                                        Log.recordStateMutation(LiteRtCornerComponent.id.value, "clipboard", state.server.responsesEndpoint)
                                    }
                                },
                            )

                            EndpointRow(
                                label = "Gemini Native API",
                                value = state.server.geminiEndpoint,
                                copyButtonLabel = "Copy Gemini URL",
                                onCopy = {
                                    Log.traceUiAction(LiteRtCornerComponent.id.value, "CopyGeminiUrl") {
                                        runCatching { clipboard?.setText(AnnotatedString(state.server.geminiEndpoint)) }
                                        copiedBanner = "Copied Gemini Native endpoint: ${state.server.geminiEndpoint}"
                                        Log.recordStateMutation(LiteRtCornerComponent.id.value, "clipboard", state.server.geminiEndpoint)
                                    }
                                },
                            )

                            EndpointRow(
                                label = "LiteRT Native",
                                value = state.server.nativeEndpoint,
                                copyButtonLabel = "Copy Native URL",
                                onCopy = {
                                    Log.traceUiAction(LiteRtCornerComponent.id.value, "CopyNativeUrl") {
                                        runCatching { clipboard?.setText(AnnotatedString(state.server.nativeEndpoint)) }
                                        copiedBanner = "Copied Native LiteRT endpoint: ${state.server.nativeEndpoint}"
                                        Log.recordStateMutation(LiteRtCornerComponent.id.value, "clipboard", state.server.nativeEndpoint)
                                    }
                                },
                            )

                            EndpointRow(
                                label = "Android IPC",
                                value = state.server.ipcUri,
                                copyButtonLabel = "Copy IPC URI",
                                onCopy = {
                                    Log.traceUiAction(LiteRtCornerComponent.id.value, "CopyIpcUri") {
                                        runCatching { clipboard?.setText(AnnotatedString(state.server.ipcUri)) }
                                        copiedBanner = "Copied Android ContentProvider URI: ${state.server.ipcUri}"
                                        Log.recordStateMutation(LiteRtCornerComponent.id.value, "clipboard", state.server.ipcUri)
                                    }
                                },
                            )

                            // Interactive local smoke test bar
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AgentUi.chrome.bg)
                                    .border(1.dp, AgentUi.chrome.hairline, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "On-Device Inference Smoke Test",
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AgentUi.chrome.text,
                                    )
                                    AgentChip(
                                        label = if (state.activeModelId.isNotBlank()) {
                                            "${state.activeModelId} · ${state.hardware.selectedDelegate.name}"
                                        } else {
                                            "No active model"
                                        },
                                        selected = state.activeModelId.isNotBlank(),
                                        onClick = null,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(AgentUi.chrome.surface)
                                        .border(1.dp, AgentUi.chrome.hairlineStrong, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                ) {
                                    BasicTextField(
                                        value = smokePrompt,
                                        onValueChange = { smokePrompt = it },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodySmall.copy(color = AgentUi.chrome.text),
                                        cursorBrush = SolidColor(AgentUi.chrome.accent),
                                        modifier = Modifier.fillMaxWidth().testTag("litert_smoke_prompt_input"),
                                    )
                                }
                                AgentPrimaryButton(
                                    label = if (state.lastSmokeTestBusy) "Running smoke test…" else "Run local smoke test",
                                    onClick = {
                                        Log.traceUiAction(LiteRtCornerComponent.id.value, "RunSmokeTest") {
                                            agent.runLiteRtSmokeTest(smokePrompt)
                                            Log.recordStateMutation(LiteRtCornerComponent.id.value, "smokeTest", smokePrompt)
                                            refreshLocalSnapshot()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (state.lastSmokeTestResponse.isNotBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(AgentUi.chrome.surfaceAlt)
                                            .padding(10.dp)
                                            .testTag("litert_smoke_response"),
                                    ) {
                                        Text(
                                            state.lastSmokeTestResponse,
                                            style = Ca.type.codeSmall,
                                            color = AgentUi.chrome.text,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // -----------------------------------------------------------------------------------------
            // 4. Guest-App Local Inference Building Blocks (SDK & Project Scaffolder)
            // -----------------------------------------------------------------------------------------
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentSectionLabel("Guest-App Local Inference Building Blocks")
                AgentSurface(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    stroke = AgentUi.chrome.hairlineStrong,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Drop-in client code for apps you build on this phone to call the local LiteRT-LM " +
                                "loopback server or zero-copy Android ContentProvider IPC.",
                            fontSize = 13.sp,
                            color = AgentUi.chrome.textMuted,
                        )

                        AgentSegmented(
                            options = guestLanguageLabels,
                            selectedIndex = selectedGuestIndex,
                            onSelect = { idx ->
                                val lang = guestLanguages.getOrElse(idx) { UiLiteRtGuestLanguage.KOTLIN_ANDROID }
                                Log.traceUiAction(LiteRtCornerComponent.id.value, "SelectGuestLanguage:${lang.name}") {
                                    agent.selectLiteRtGuestLanguage(lang)
                                    Log.recordStateMutation(LiteRtCornerComponent.id.value, "guestLanguage", lang.name)
                                    refreshLocalSnapshot()
                                }
                            },
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 260.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AgentUi.chrome.bg)
                                .border(1.dp, AgentUi.chrome.hairline, RoundedCornerShape(10.dp))
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp)
                                .testTag("litert_guest_snippet_box"),
                        ) {
                            Text(
                                state.guestSnippet,
                                style = Ca.type.codeSmall,
                                color = AgentUi.chrome.text,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AgentGhostButton(
                                label = "Copy SDK snippet",
                                onClick = {
                                    Log.traceUiAction(LiteRtCornerComponent.id.value, "CopyGuestSnippet:${state.selectedGuestLanguage.name}") {
                                        runCatching { clipboard?.setText(AnnotatedString(state.guestSnippet)) }
                                        copiedBanner = "Copied ${state.selectedGuestLanguage.name} guest SDK snippet to clipboard."
                                        Log.recordStateMutation(
                                            LiteRtCornerComponent.id.value,
                                            "copiedSnippet",
                                            state.selectedGuestLanguage.name,
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            AgentPrimaryButton(
                                label = "Add client to project",
                                onClick = {
                                    Log.traceUiAction(LiteRtCornerComponent.id.value, "ScaffoldGuestClient:${state.selectedGuestLanguage.name}") {
                                        val path = agent.scaffoldLiteRtGuestClient(state.selectedGuestLanguage)
                                        copiedBanner = "Added client to project: $path"
                                        Log.recordStateMutation(LiteRtCornerComponent.id.value, "scaffoldedClient", path)
                                        refreshLocalSnapshot()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun UniversalProviderCard(
    provider: UiLlmSwitchProviderProfile,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSwitchActive: () -> Unit,
    onSaveProvider: (UiLlmSwitchProviderInput) -> Unit,
    onDeleteProvider: () -> Unit,
    onToggleFailover: () -> Unit,
    onToggleAggregation: () -> Unit,
    onProbeHealth: () -> Unit,
    onResetCircuit: () -> Unit,
) {
    val chrome = AgentUi.chrome
    var editBaseUrl by remember(provider.id, provider.baseUrl) { mutableStateOf(provider.baseUrl) }
    var editApiKey by remember(provider.id, provider.apiKey) { mutableStateOf(provider.apiKey) }
    var editDefaultModel by remember(provider.id, provider.defaultModel) { mutableStateOf(provider.defaultModel) }
    var editHaikuModel by remember(provider.id, provider.modelMapping.haikuModel) {
        mutableStateOf(provider.modelMapping.haikuModel)
    }
    var editSonnetModel by remember(provider.id, provider.modelMapping.sonnetModel) {
        mutableStateOf(provider.modelMapping.sonnetModel)
    }
    var editOpusModel by remember(provider.id, provider.modelMapping.opusModel) {
        mutableStateOf(provider.modelMapping.opusModel)
    }
    var editFormat by remember(provider.id, provider.apiFormat) { mutableStateOf(provider.apiFormat) }

    val statusLabel = when {
        provider.active -> "Active · ${provider.circuitBreaker.state.label}"
        provider.configured -> "Ready · ${provider.circuitBreaker.state.label}"
        else -> "Unconfigured · ${provider.circuitBreaker.state.label}"
    }

    AgentSurface(
        modifier = Modifier.fillMaxWidth().testTag("ccswitch_provider_card_${provider.id}"),
        contentPadding = PaddingValues(16.dp),
        fill = if (provider.active) chrome.surfaceAlt else chrome.surface,
        stroke = if (provider.active) chrome.accent else chrome.hairlineStrong,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        provider.name,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = chrome.text,
                    )
                    Text(
                        "${provider.category.label} · ${provider.apiFormat.label} · ${provider.defaultModel.ifBlank { "default" }}",
                        fontSize = 12.sp,
                        color = chrome.textMuted,
                    )
                }
                AgentChip(
                    label = statusLabel,
                    selected = provider.active,
                    leading = if (provider.configured || provider.active) {
                        { AgentStatusDot(active = provider.active) }
                    } else {
                        null
                    },
                    onClick = null,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentChip(
                    label = "Failover P${provider.failoverPriority}: ${if (provider.inFailoverQueue) "Enabled" else "Off"}",
                    selected = provider.inFailoverQueue,
                    onClick = null,
                )
                AgentChip(
                    label = "Stack: ${if (provider.inAggregationStack) "ccs-${provider.providerKey}" else "Off"}",
                    selected = provider.inAggregationStack,
                    onClick = null,
                )
                if (provider.circuitBreaker.totalRequests > 0L || provider.circuitBreaker.lastLatencyMs > 0L) {
                    AgentChip(
                        label = "${provider.circuitBreaker.totalRequests} req · ${provider.circuitBreaker.lastLatencyMs} ms",
                        selected = false,
                        onClick = null,
                    )
                }
            }

            if (provider.baseUrl.isNotBlank()) {
                Text(
                    "Endpoint: ${provider.baseUrl}",
                    fontSize = 11.5.sp,
                    color = chrome.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (provider.circuitBreaker.lastError.isNotBlank()) {
                Text(
                    "Circuit alert: ${provider.circuitBreaker.lastError}",
                    fontSize = 12.sp,
                    color = chrome.danger,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!provider.active) {
                    AgentPrimaryButton(
                        label = "Switch to ${provider.name}",
                        onClick = onSwitchActive,
                        modifier = Modifier.weight(1f),
                    )
                }
                AgentGhostButton(
                    label = if (expanded) "Close ${provider.name} Config" else "Configure ${provider.name}",
                    onClick = onToggleExpand,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentGhostButton(
                    label = "Failover ${provider.name}: ${if (provider.inFailoverQueue) "ON" else "OFF"}",
                    onClick = onToggleFailover,
                )
                AgentGhostButton(
                    label = "Stack ${provider.name}: ${if (provider.inAggregationStack) "ON" else "OFF"}",
                    onClick = onToggleAggregation,
                )
                AgentGhostButton(
                    label = "Probe ${provider.name}",
                    onClick = onProbeHealth,
                )
                if (provider.circuitBreaker.state != UiLlmCircuitState.CLOSED || provider.circuitBreaker.consecutiveFailures > 0) {
                    AgentGhostButton(
                        label = "Reset Circuit (${provider.name})",
                        onClick = onResetCircuit,
                    )
                }
            }

            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(chrome.bg)
                        .border(1.dp, chrome.hairline, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val formatOptions = UiLlmApiFormat.entries
                    AgentSegmented(
                        options = formatOptions.map { it.label },
                        selectedIndex = formatOptions.indexOf(editFormat).coerceAtLeast(0),
                        onSelect = { idx ->
                            val picked = formatOptions.getOrElse(idx) { UiLlmApiFormat.OPENAI_CHAT }
                            Log.traceUiAction(LiteRtCornerComponent.id.value, "SelectProviderApiFormat:${provider.id}:${picked.name}") {
                                editFormat = picked
                                Log.recordStateMutation(LiteRtCornerComponent.id.value, "apiFormat.${provider.id}", picked.name)
                            }
                        },
                    )

                    ProviderTextField(
                        label = "Base URL",
                        value = editBaseUrl,
                        onValueChange = { editBaseUrl = it },
                        testTag = "ccswitch_baseurl_${provider.id}",
                    )

                    if (provider.endpointCandidates.size > 1) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            provider.endpointCandidates.forEach { candidateUrl ->
                                AgentGhostButton(
                                    label = "Endpoint ${candidateUrl.removePrefix("https://").removePrefix("http://")}",
                                    onClick = {
                                        Log.traceUiAction(LiteRtCornerComponent.id.value, "PickEndpointCandidate:${provider.id}") {
                                            editBaseUrl = candidateUrl
                                            Log.recordStateMutation(LiteRtCornerComponent.id.value, "candidateUrl.${provider.id}", candidateUrl)
                                        }
                                    },
                                )
                            }
                        }
                    }

                    if (provider.requiresApiKey) {
                        ProviderTextField(
                            label = "API Key / Bearer Token",
                            value = editApiKey,
                            onValueChange = { editApiKey = it },
                            testTag = "ccswitch_apikey_${provider.id}",
                        )
                    }

                    ProviderTextField(
                        label = "Default Model",
                        value = editDefaultModel,
                        onValueChange = { editDefaultModel = it },
                        testTag = "ccswitch_model_${provider.id}",
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            ProviderTextField(
                                label = "Haiku Tier",
                                value = editHaikuModel,
                                onValueChange = { editHaikuModel = it },
                                testTag = "ccswitch_haiku_${provider.id}",
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            ProviderTextField(
                                label = "Sonnet Tier",
                                value = editSonnetModel,
                                onValueChange = { editSonnetModel = it },
                                testTag = "ccswitch_sonnet_${provider.id}",
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            ProviderTextField(
                                label = "Opus Tier",
                                value = editOpusModel,
                                onValueChange = { editOpusModel = it },
                                testTag = "ccswitch_opus_${provider.id}",
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AgentPrimaryButton(
                            label = "Save ${provider.name}",
                            onClick = {
                                onSaveProvider(
                                    UiLlmSwitchProviderInput(
                                        id = provider.id,
                                        name = provider.name,
                                        presetId = provider.presetId,
                                        category = provider.category,
                                        apiFormat = editFormat,
                                        baseUrl = editBaseUrl.trim(),
                                        apiKey = editApiKey.trim(),
                                        caCertificatePem = provider.caCertificatePem,
                                        defaultModel = editDefaultModel.trim(),
                                        haikuModel = editHaikuModel.trim(),
                                        sonnetModel = editSonnetModel.trim(),
                                        opusModel = editOpusModel.trim(),
                                        reasoningEffort = provider.modelMapping.reasoningEffort,
                                        inFailoverQueue = provider.inFailoverQueue,
                                        inAggregationStack = provider.inAggregationStack,
                                        requiresApiKey = provider.requiresApiKey,
                                    ),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (!provider.active && !provider.id.equals("litert", ignoreCase = true)) {
                            AgentGhostButton(
                                label = "Remove ${provider.name}",
                                onClick = onDeleteProvider,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    testTag: String,
) {
    val chrome = AgentUi.chrome
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 11.5.sp, color = chrome.textFaint, fontWeight = FontWeight.Medium)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(chrome.surface)
                .border(1.dp, chrome.hairlineStrong, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = chrome.text),
                cursorBrush = SolidColor(chrome.accent),
                modifier = Modifier.fillMaxWidth().testTag(testTag),
            )
        }
    }
}

@Composable
private fun LiveCliSyncCard(
    targets: List<UiLlmLiveConfigTarget>,
    onSyncConfigs: () -> Unit,
) {
    val chrome = AgentUi.chrome
    AgentSurface(
        modifier = Modifier.fillMaxWidth().testTag("ccswitch_live_cli_sync_card"),
        contentPadding = PaddingValues(16.dp),
        stroke = chrome.hairlineStrong,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Multi-Tool CLI Live Config Sync",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = chrome.text,
                    )
                    Text(
                        "Non-destructively sync active provider or local proxy to Claude Code, Codex & Gemini CLI with first-write backup",
                        fontSize = 12.sp,
                        color = chrome.textMuted,
                    )
                }
                AgentChip(
                    label = "${targets.size} CLI targets",
                    selected = targets.any { it.lastSyncedEpochMs > 0L },
                    onClick = null,
                )
            }

            targets.forEach { target ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(chrome.bg)
                        .border(1.dp, chrome.hairline, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "${target.displayName} (${target.syncedMode} · ${target.syncedProviderId.ifBlank { "default" }})",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = chrome.text,
                        )
                        Text(
                            target.configFilePath,
                            fontSize = 11.sp,
                            color = chrome.textFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    AgentChip(
                        label = if (target.hasBackup) "Backup guarded" else "Ready to sync",
                        selected = target.hasBackup,
                        onClick = null,
                    )
                }
            }

            AgentPrimaryButton(
                label = "Sync CLI Configs (.claude / .codex / .gemini)",
                onClick = onSyncConfigs,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LiteRtModelCard(
    model: UiLiteRtModelEntry,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onActivate: () -> Unit,
    onUseInIdeAgent: () -> Unit,
    onDelete: () -> Unit,
) {
    val chrome = AgentUi.chrome
    val isActive = model.status == UiLiteRtModelStatus.ACTIVE
    val isInstalled = model.status == UiLiteRtModelStatus.INSTALLED || isActive
    val statusText = when {
        model.isSystemAiCore && isActive -> "Active · System AI Core"
        model.isSystemAiCore && isInstalled -> "Built-in · 0 B download"
        model.isBundled && isActive -> "Active · Bundled in APK"
        model.isBundled && isInstalled -> "Bundled · Ready"
        model.status == UiLiteRtModelStatus.NOT_INSTALLED -> "Not installed · ${formatBytes(model.estimatedSizeBytes)}"
        model.status == UiLiteRtModelStatus.DOWNLOADING -> "Downloading · ${formatBytes(model.downloadedBytes)}"
        model.status == UiLiteRtModelStatus.INSTALLED -> "Installed · ${formatBytes(model.sizeOnDiskBytes)}"
        model.status == UiLiteRtModelStatus.ACTIVE -> "Active on-device · ${formatBytes(model.sizeOnDiskBytes)}"
        else -> "Verification failed"
    }

    AgentSurface(
        modifier = Modifier.fillMaxWidth().testTag("litert_model_card_${model.id}"),
        contentPadding = PaddingValues(16.dp),
        fill = if (isActive) chrome.surfaceAlt else chrome.surface,
        stroke = if (isActive) chrome.accent else chrome.hairlineStrong,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        model.displayName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = chrome.text,
                    )
                    Text(
                        "${model.family} · ${model.parameterCount} · ${model.quantization} · ${model.contextLength / 1024}K ctx",
                        fontSize = 12.sp,
                        color = chrome.textMuted,
                    )
                }
                AgentChip(
                    label = statusText,
                    selected = isActive,
                    leading = if (isInstalled) {
                        { AgentStatusDot(active = isActive) }
                    } else {
                        null
                    },
                    onClick = null,
                )
            }

            Text(
                model.description,
                fontSize = 13.sp,
                color = chrome.textMuted,
            )

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (model.isSystemAiCore) {
                    AgentChip(label = "System Built-In · 0 B", selected = true, onClick = null)
                } else if (model.isBundled) {
                    AgentChip(label = "Bundled in APK", selected = true, onClick = null)
                }
                AgentChip(
                    label = "Delegates: ${model.supportedDelegates.joinToString("/") { it.name }}",
                    selected = false,
                    onClick = null,
                )
                if (model.supportsToolCalling) {
                    AgentChip(label = "Tool calling", selected = false, onClick = null)
                }
                if (model.supportsThinking) {
                    AgentChip(label = "Reasoning", selected = false, onClick = null)
                }
            }

            if (isInstalled && model.sha256.isNotBlank()) {
                val locationLabel = if (model.isSystemAiCore) {
                    "Bound to ${model.localFilePath}"
                } else {
                    "Verified .litertlm · sha256:${model.sha256.take(16)}… · ${model.localFilePath}"
                }
                Text(
                    locationLabel,
                    fontSize = 11.5.sp,
                    color = chrome.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (model.errorMessage.isNotBlank()) {
                Text(
                    model.errorMessage,
                    fontSize = 12.sp,
                    color = chrome.danger,
                )
            }

            when (model.status) {
                UiLiteRtModelStatus.NOT_INSTALLED, UiLiteRtModelStatus.ERROR -> {
                    AgentPrimaryButton(
                        label = "Download ${model.displayName}",
                        onClick = onDownload,
                    )
                }
                UiLiteRtModelStatus.DOWNLOADING -> {
                    AgentGhostButton(
                        label = "Cancel download (${model.id})",
                        onClick = onCancel,
                    )
                }
                UiLiteRtModelStatus.INSTALLED -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AgentPrimaryButton(
                                label = "Activate ${model.displayName}",
                                onClick = onActivate,
                                modifier = Modifier.weight(1f),
                            )
                            AgentGhostButton(
                                label = "Delete ${model.displayName}",
                                onClick = onDelete,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        AgentGhostButton(
                            label = "Use in IDE Agent (${model.displayName})",
                            onClick = onUseInIdeAgent,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                UiLiteRtModelStatus.ACTIVE -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AgentPrimaryButton(
                            label = "Use in IDE Agent (${model.displayName})",
                            onClick = onUseInIdeAgent,
                            modifier = Modifier.weight(1f),
                        )
                        AgentGhostButton(
                            label = "Delete ${model.displayName}",
                            onClick = onDelete,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EndpointRow(
    label: String,
    value: String,
    copyButtonLabel: String,
    onCopy: () -> Unit,
) {
    val chrome = AgentUi.chrome
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(chrome.bg)
            .border(1.dp, chrome.hairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, fontSize = 11.5.sp, color = chrome.textFaint, fontWeight = FontWeight.Medium)
            AgentGhostButton(
                label = copyButtonLabel,
                onClick = onCopy,
            )
        }
        Text(
            value,
            style = Ca.type.codeSmall,
            color = chrome.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> "${((bytes / gb) * 10.0).toLong() / 10.0} GB"
        bytes >= mb -> "${((bytes / mb) * 10.0).toLong() / 10.0} MB"
        bytes >= kb -> "${(bytes / kb).toLong()} KB"
        else -> "$bytes B"
    }
}
