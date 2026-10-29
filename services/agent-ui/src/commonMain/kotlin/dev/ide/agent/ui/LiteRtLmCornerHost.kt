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

/** Screen ID for the Self-Serve LiteRT-LM Corner inside the Settings & Tools Hub and Agent Settings. */
const val SCREEN_LITERT_LM_CORNER = "agent.litert.corner"

private val LiteRtCornerComponent = Log.registerComponent(
    id = "agent.litert.corner",
    owner = "agent-ui",
    description = "Self-Serve LiteRT-LM Corner (model serving catalog, AI Core, NPU/GPU/CPU delegates, local loopback & IPC server, guest-app SDK)",
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
    val state: UiLiteRtCornerState = if (observedState.models.isNotEmpty() || snapshot.models.isEmpty()) {
        observedState
    } else {
        snapshot
    }

    val clipboard = LocalClipboardManager.current
    var copiedBanner by remember { mutableStateOf("") }
    var smokePrompt by remember {
        mutableStateOf(
            state.lastSmokeTestPrompt.ifBlank { "Reply with a one-line status check from LiteRT-LM." },
        )
    }

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
            subtitle = "AI Core + bundled .litertlm · NPU/GPU/CPU · Local loopback & IPC",
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
            // 3. Local Loopback & IPC Inference Bridge
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
                                "Listening on 127.0.0.1:${state.server.port} (OpenAI & Native LiteRT-LM)"
                            } else {
                                "Expose /v1/chat/completions & /v1/litert/generate on 127.0.0.1:${state.server.port}"
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
