package dev.ide.core.agent

import dev.ide.agent.AgentTool
import dev.ide.agent.ToolArgs
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.ToolSpec
import dev.ide.agent.impl.CcSwitchApiFormat
import dev.ide.agent.impl.CcSwitchCircuitBreakerSnapshot
import dev.ide.agent.impl.CcSwitchCircuitState
import dev.ide.agent.impl.CcSwitchModelMapping
import dev.ide.agent.impl.CcSwitchOperatingMode
import dev.ide.agent.impl.CcSwitchPresetCatalog
import dev.ide.agent.impl.CcSwitchProviderCategory
import dev.ide.agent.impl.CcSwitchProviderProfile
import dev.ide.agent.impl.LiteRtCornerSnapshot
import dev.ide.agent.impl.LiteRtGuestLanguage
import dev.ide.agent.impl.LiteRtHardwareDelegate
import dev.ide.agent.impl.LiteRtLmCatalog
import dev.ide.agent.impl.LiteRtLmServingManager
import dev.ide.agent.impl.LiteRtLmServingRegistry
import dev.ide.agent.impl.LiteRtModelInstallState
import dev.ide.agent.toolSchema
import dev.ide.ui.backend.UiAgentModel
import dev.ide.ui.backend.UiLiteRtCornerState
import dev.ide.ui.backend.UiLiteRtDelegate
import dev.ide.ui.backend.UiLiteRtGuestLanguage
import dev.ide.ui.backend.UiLiteRtHardwareInfo
import dev.ide.ui.backend.UiLiteRtModelEntry
import dev.ide.ui.backend.UiLiteRtModelStatus
import dev.ide.ui.backend.UiLiteRtServerStatus
import dev.ide.ui.backend.UiLlmApiFormat
import dev.ide.ui.backend.UiLlmCircuitBreakerInfo
import dev.ide.ui.backend.UiLlmCircuitState
import dev.ide.ui.backend.UiLlmLiveConfigTarget
import dev.ide.ui.backend.UiLlmModelMapping
import dev.ide.ui.backend.UiLlmProviderCategory
import dev.ide.ui.backend.UiLlmProviderPreset
import dev.ide.ui.backend.UiLlmSwitchMode
import dev.ide.ui.backend.UiLlmSwitchProviderInput
import dev.ide.ui.backend.UiLlmSwitchProviderProfile
import java.io.File
import java.nio.file.Path

internal fun CcSwitchOperatingMode.toUi(): UiLlmSwitchMode = when (this) {
    CcSwitchOperatingMode.DIRECT -> UiLlmSwitchMode.DIRECT
    CcSwitchOperatingMode.ROUTING -> UiLlmSwitchMode.ROUTING
    CcSwitchOperatingMode.AGGREGATION -> UiLlmSwitchMode.AGGREGATION
}

internal fun UiLlmSwitchMode.toDomain(): CcSwitchOperatingMode = when (this) {
    UiLlmSwitchMode.DIRECT -> CcSwitchOperatingMode.DIRECT
    UiLlmSwitchMode.ROUTING -> CcSwitchOperatingMode.ROUTING
    UiLlmSwitchMode.AGGREGATION -> CcSwitchOperatingMode.AGGREGATION
}

internal fun CcSwitchApiFormat.toUi(): UiLlmApiFormat = when (this) {
    CcSwitchApiFormat.ANTHROPIC_MESSAGES -> UiLlmApiFormat.ANTHROPIC_MESSAGES
    CcSwitchApiFormat.OPENAI_CHAT -> UiLlmApiFormat.OPENAI_CHAT
    CcSwitchApiFormat.OPENAI_RESPONSES -> UiLlmApiFormat.OPENAI_RESPONSES
    CcSwitchApiFormat.GEMINI_NATIVE -> UiLlmApiFormat.GEMINI_NATIVE
    CcSwitchApiFormat.LITERT_ON_DEVICE -> UiLlmApiFormat.LITERT_ON_DEVICE
}

internal fun UiLlmApiFormat.toDomain(): CcSwitchApiFormat = when (this) {
    UiLlmApiFormat.ANTHROPIC_MESSAGES -> CcSwitchApiFormat.ANTHROPIC_MESSAGES
    UiLlmApiFormat.OPENAI_CHAT -> CcSwitchApiFormat.OPENAI_CHAT
    UiLlmApiFormat.OPENAI_RESPONSES -> CcSwitchApiFormat.OPENAI_RESPONSES
    UiLlmApiFormat.GEMINI_NATIVE -> CcSwitchApiFormat.GEMINI_NATIVE
    UiLlmApiFormat.LITERT_ON_DEVICE -> CcSwitchApiFormat.LITERT_ON_DEVICE
}

internal fun CcSwitchProviderCategory.toUi(): UiLlmProviderCategory = when (this) {
    CcSwitchProviderCategory.OFFICIAL -> UiLlmProviderCategory.OFFICIAL
    CcSwitchProviderCategory.CN_OFFICIAL -> UiLlmProviderCategory.CN_OFFICIAL
    CcSwitchProviderCategory.AGGREGATOR -> UiLlmProviderCategory.AGGREGATOR
    CcSwitchProviderCategory.THIRD_PARTY -> UiLlmProviderCategory.THIRD_PARTY
    CcSwitchProviderCategory.ON_DEVICE -> UiLlmProviderCategory.ON_DEVICE
    CcSwitchProviderCategory.CUSTOM -> UiLlmProviderCategory.CUSTOM
}

internal fun UiLlmProviderCategory.toDomain(): CcSwitchProviderCategory = when (this) {
    UiLlmProviderCategory.OFFICIAL -> CcSwitchProviderCategory.OFFICIAL
    UiLlmProviderCategory.CN_OFFICIAL -> CcSwitchProviderCategory.CN_OFFICIAL
    UiLlmProviderCategory.AGGREGATOR -> CcSwitchProviderCategory.AGGREGATOR
    UiLlmProviderCategory.THIRD_PARTY -> CcSwitchProviderCategory.THIRD_PARTY
    UiLlmProviderCategory.ON_DEVICE -> CcSwitchProviderCategory.ON_DEVICE
    UiLlmProviderCategory.CUSTOM -> CcSwitchProviderCategory.CUSTOM
}

internal fun CcSwitchCircuitState.toUi(): UiLlmCircuitState = when (this) {
    CcSwitchCircuitState.CLOSED -> UiLlmCircuitState.CLOSED
    CcSwitchCircuitState.OPEN -> UiLlmCircuitState.OPEN
    CcSwitchCircuitState.HALF_OPEN -> UiLlmCircuitState.HALF_OPEN
}

internal fun CcSwitchModelMapping.toUi(): UiLlmModelMapping = UiLlmModelMapping(
    defaultModel = defaultModel,
    haikuModel = haikuModel,
    sonnetModel = sonnetModel,
    opusModel = opusModel,
    reasoningEffort = reasoningEffort,
)

internal fun CcSwitchCircuitBreakerSnapshot.toUi(): UiLlmCircuitBreakerInfo = UiLlmCircuitBreakerInfo(
    state = state.toUi(),
    consecutiveFailures = consecutiveFailures,
    totalRequests = totalRequests,
    failedRequests = failedRequests,
    lastLatencyMs = lastLatencyMs,
    lastError = lastError,
)

internal fun LiteRtHardwareDelegate.toUi(): UiLiteRtDelegate = when (this) {
    LiteRtHardwareDelegate.CPU -> UiLiteRtDelegate.CPU
    LiteRtHardwareDelegate.GPU -> UiLiteRtDelegate.GPU
    LiteRtHardwareDelegate.NPU -> UiLiteRtDelegate.NPU
}

internal fun UiLiteRtDelegate.toDomain(): LiteRtHardwareDelegate = when (this) {
    UiLiteRtDelegate.CPU -> LiteRtHardwareDelegate.CPU
    UiLiteRtDelegate.GPU -> LiteRtHardwareDelegate.GPU
    UiLiteRtDelegate.NPU -> LiteRtHardwareDelegate.NPU
}

internal fun LiteRtModelInstallState.toUi(): UiLiteRtModelStatus = when (this) {
    LiteRtModelInstallState.NOT_INSTALLED -> UiLiteRtModelStatus.NOT_INSTALLED
    LiteRtModelInstallState.DOWNLOADING -> UiLiteRtModelStatus.DOWNLOADING
    LiteRtModelInstallState.INSTALLED -> UiLiteRtModelStatus.INSTALLED
    LiteRtModelInstallState.ACTIVE -> UiLiteRtModelStatus.ACTIVE
    LiteRtModelInstallState.ERROR -> UiLiteRtModelStatus.ERROR
}

internal fun LiteRtGuestLanguage.toUi(): UiLiteRtGuestLanguage = when (this) {
    LiteRtGuestLanguage.KOTLIN_ANDROID -> UiLiteRtGuestLanguage.KOTLIN_ANDROID
    LiteRtGuestLanguage.SWIFT -> UiLiteRtGuestLanguage.SWIFT
    LiteRtGuestLanguage.NODE_JS -> UiLiteRtGuestLanguage.NODE_JS
    LiteRtGuestLanguage.PYTHON -> UiLiteRtGuestLanguage.PYTHON
}

internal fun UiLiteRtGuestLanguage.toDomain(): LiteRtGuestLanguage = when (this) {
    UiLiteRtGuestLanguage.KOTLIN_ANDROID -> LiteRtGuestLanguage.KOTLIN_ANDROID
    UiLiteRtGuestLanguage.SWIFT -> LiteRtGuestLanguage.SWIFT
    UiLiteRtGuestLanguage.NODE_JS -> LiteRtGuestLanguage.NODE_JS
    UiLiteRtGuestLanguage.PYTHON -> LiteRtGuestLanguage.PYTHON
}

internal fun LiteRtCornerSnapshot.toUi(): UiLiteRtCornerState {
    val hasInstalledOnDevice = entries.any {
        it.status == LiteRtModelInstallState.INSTALLED || it.status == LiteRtModelInstallState.ACTIVE
    }
    return UiLiteRtCornerState(
        hardware = UiLiteRtHardwareInfo(
            socName = hardware.socName,
            availableDelegates = hardware.availableDelegates.map { it.toUi() },
            recommendedDelegate = hardware.recommendedDelegate.toUi(),
            selectedDelegate = selectedDelegate.toUi(),
            freeStorageBytes = freeStorageBytes,
            usedModelStorageBytes = usedModelStorageBytes,
            modelsDirectoryPath = modelsDirectoryPath,
        ),
        models = entries.map { entry ->
            UiLiteRtModelEntry(
                id = entry.spec.id,
                displayName = entry.spec.displayName,
                family = entry.spec.family,
                parameterCount = entry.spec.parameterCount,
                quantization = entry.spec.quantization,
                description = entry.spec.description,
                estimatedSizeBytes = entry.spec.estimatedSizeBytes,
                downloadedBytes = entry.downloadedBytes,
                sizeOnDiskBytes = entry.sizeOnDiskBytes,
                contextLength = entry.spec.contextLength,
                supportedDelegates = entry.spec.supportedDelegates.map { it.toUi() },
                supportsToolCalling = entry.spec.supportsToolCalling,
                supportsThinking = entry.spec.supportsThinking,
                status = entry.status.toUi(),
                localFilePath = entry.localFilePath,
                sha256 = entry.sha256,
                errorMessage = entry.errorMessage,
                isSystemAiCore = entry.spec.isSystemAiCore,
                isBundled = entry.spec.isBundled,
            )
        },
        activeModelId = activeModelId,
        server = UiLiteRtServerStatus(
            enabled = serverEnabled,
            running = serverRunning,
            port = serverPort,
            openAiEndpoint = openAiEndpoint,
            anthropicEndpoint = anthropicEndpoint,
            responsesEndpoint = responsesEndpoint,
            geminiEndpoint = geminiEndpoint,
            nativeEndpoint = nativeEndpoint,
            ipcUri = ipcUri,
            requestsServed = requestsServed,
            tokensGenerated = tokensGenerated,
            lastLatencyMs = lastLatencyMs,
        ),
        selectedGuestLanguage = selectedGuestLanguage.toUi(),
        guestSnippet = guestSnippet,
        lastSmokeTestPrompt = lastSmokeTestPrompt,
        lastSmokeTestResponse = lastSmokeTestResponse,
        lastSmokeTestBusy = lastSmokeTestBusy,
        statusBanner = statusBanner,
        switchMode = switchMode.toUi(),
        activeProviderId = activeProviderId,
        autoFailoverEnabled = autoFailoverEnabled,
        circuitBreakerEnabled = circuitBreakerEnabled,
        universalProviders = providerProfiles.map { prof ->
            val cb = circuitBreakers[prof.id]?.toUi() ?: UiLlmCircuitBreakerInfo()
            UiLlmSwitchProviderProfile(
                id = prof.id,
                providerKey = prof.providerKey,
                name = prof.name,
                presetId = prof.presetId,
                category = prof.category.toUi(),
                apiFormat = prof.apiFormat.toUi(),
                baseUrl = prof.baseUrl,
                endpointCandidates = prof.endpointCandidates,
                apiKey = prof.apiKey,
                caCertificatePem = prof.caCertificatePem,
                defaultModel = prof.defaultModel,
                models = prof.models.map { UiAgentModel(it.id, it.displayName) },
                modelMapping = prof.modelMapping.toUi(),
                inFailoverQueue = prof.inFailoverQueue,
                failoverPriority = prof.failoverPriority,
                inAggregationStack = prof.inAggregationStack,
                requiresApiKey = prof.requiresApiKey,
                configured = prof.isConfigured(hasInstalledOnDevice),
                active = prof.id == activeProviderId,
                websiteUrl = prof.websiteUrl,
                notes = prof.notes,
                circuitBreaker = cb,
            )
        },
        providerPresets = CcSwitchPresetCatalog.PRESETS.map { preset ->
            UiLlmProviderPreset(
                presetId = preset.presetId,
                defaultProviderId = preset.defaultProviderId,
                name = preset.name,
                category = preset.category.toUi(),
                apiFormat = preset.apiFormat.toUi(),
                baseUrl = preset.baseUrl,
                endpointCandidates = preset.endpointCandidates,
                defaultModel = preset.defaultModel,
                models = preset.models.map { UiAgentModel(it.id, it.displayName) },
                modelMapping = preset.modelMapping.toUi(),
                description = preset.description,
                requiresApiKey = preset.requiresApiKey,
            )
        },
        aggregatedModels = aggregatedModels.map { UiAgentModel(it.id, it.displayName) },
        liveConfigTargets = liveConfigTargets.map { target ->
            UiLlmLiveConfigTarget(
                toolId = target.toolId,
                displayName = target.displayName,
                configFilePath = target.configFilePath,
                backupFilePath = target.backupFilePath,
                hasBackup = target.hasBackup,
                syncedMode = target.syncedMode,
                syncedProviderId = target.syncedProviderId,
                syncedEndpoint = target.syncedEndpoint,
                syncedModel = target.syncedModel,
                lastSyncedEpochMs = target.lastSyncedEpochMs,
            )
        },
    )
}

internal fun UiLlmSwitchProviderInput.toDomain(
    existing: CcSwitchProviderProfile? = null,
): CcSwitchProviderProfile {
    val preset = CcSwitchPresetCatalog.findPreset(presetId) ?: CcSwitchPresetCatalog.findPreset(id)
    return CcSwitchProviderProfile(
        id = id.trim(),
        providerKey = existing?.providerKey ?: id.trim(),
        name = name.trim().ifBlank { preset?.name ?: id.trim() },
        presetId = presetId.ifBlank { preset?.presetId ?: id.trim() },
        category = category.toDomain(),
        apiFormat = apiFormat.toDomain(),
        baseUrl = baseUrl.trim().ifBlank { existing?.baseUrl ?: preset?.baseUrl.orEmpty() },
        endpointCandidates = existing?.endpointCandidates?.takeIf { it.isNotEmpty() }
            ?: preset?.endpointCandidates.orEmpty(),
        apiKey = apiKey,
        caCertificatePem = caCertificatePem,
        defaultModel = defaultModel.trim().ifBlank { existing?.defaultModel ?: preset?.defaultModel.orEmpty() },
        models = existing?.models?.takeIf { it.isNotEmpty() } ?: preset?.models.orEmpty(),
        modelMapping = CcSwitchModelMapping(
            defaultModel = defaultModel.trim(),
            haikuModel = haikuModel.trim(),
            sonnetModel = sonnetModel.trim(),
            opusModel = opusModel.trim(),
            reasoningEffort = reasoningEffort.trim(),
        ),
        inFailoverQueue = inFailoverQueue,
        failoverPriority = existing?.failoverPriority ?: 100,
        inAggregationStack = inAggregationStack,
        requiresApiKey = requiresApiKey,
        websiteUrl = existing?.websiteUrl ?: preset?.websiteUrl.orEmpty(),
        notes = existing?.notes ?: preset?.description.orEmpty(),
    )
}

/**
 * Built-in agent tool (`litert_corner`) allowing the IDE coding agent or MCP client to inspect or control
 * the Universal LLM Corner (`cc-switch` provider switcher, operating modes, local proxy, on-device `.litertlm`
 * catalog, and guest-app SDK scaffolding).
 */
internal fun createLiteRtCornerTool(
    managerProvider: () -> LiteRtLmServingManager,
    scaffoldClientIntoWorkspace: suspend (LiteRtGuestLanguage) -> String,
    onSwitchProviderInHost: (String) -> Unit = {},
): AgentTool = object : AgentTool {
    override val spec = ToolSpec(
        name = "litert_corner",
        description = "Inspect or control the Universal LLM Corner & CC-Switch Hub (provider switching across " +
            "Anthropic/OpenAI/Gemini/DeepSeek/Kimi/GLM/MiniMax/Qwen/MiMo/Muse/Grok/OpenRouter/Ollama/LiteRT, " +
            "Direct/Routing/Aggregation modes, failover & circuit breakers, on-device .litertlm models, and guest SDKs).",
        parameters = toolSchema {
            string(
                "action",
                "status, start_server, stop_server, download_model, activate_model, scaffold_client, switch_provider, set_switch_mode, or sync_cli_configs",
                enum = listOf(
                    "status",
                    "start_server",
                    "stop_server",
                    "download_model",
                    "activate_model",
                    "scaffold_client",
                    "switch_provider",
                    "set_switch_mode",
                    "sync_cli_configs",
                ),
            )
            string("model_id", "Catalog model ID (e.g. gemma-3-1b-it-int4) for download_model or activate_model", required = false)
            string("provider_id", "Provider ID (e.g. anthropic, openai, gemini, deepseek, kimi, zhipu-glm, litert) for switch_provider", required = false)
            string("mode", "Switch operating mode (direct, routing, aggregation) for set_switch_mode", required = false)
            string("language", "Target guest language (kotlin, swift, node, python) for scaffold_client", required = false)
        },
    )

    override suspend fun execute(args: ToolArgs): ToolExecutionResult {
        val manager = managerProvider()
        return when (val action = args.string("action")) {
            "status" -> {
                val snap = manager.currentSnapshot()
                val installed = snap.entries
                    .filter { it.status == LiteRtModelInstallState.INSTALLED || it.status == LiteRtModelInstallState.ACTIVE }
                    .map { "${it.spec.id} (${it.status})" }
                ToolExecutionResult.ok(
                    "LiteRT-LM Corner: mode=${snap.switchMode.id}, activeProvider='${snap.activeProviderId}', " +
                        "soc='${snap.hardware.socName}', delegate=${snap.selectedDelegate.id}, " +
                        "activeModel='${snap.activeModelId.ifBlank { "none" }}', installed=${installed.ifEmpty { listOf("none") }}, " +
                        "serverRunning=${snap.serverRunning} (${snap.openAiEndpoint}).",
                )
            }
            "start_server" -> {
                manager.setServerEnabled(true)
                val snap = manager.currentSnapshot()
                ToolExecutionResult.ok("LiteRT-LM loopback server started on 127.0.0.1:${snap.serverPort}.")
            }
            "stop_server" -> {
                manager.setServerEnabled(false)
                ToolExecutionResult.ok("LiteRT-LM loopback server stopped.")
            }
            "download_model" -> {
                val id = args.optString("model_id")?.trim().orEmpty()
                if (id.isBlank()) return ToolExecutionResult.error("Missing 'model_id' for download_model.")
                val verified = manager.downloadModelNow(id)
                    ?: return ToolExecutionResult.error("Failed to download or verify model '$id'.")
                ToolExecutionResult.ok("Installed & verified $id (${verified.sizeBytes} bytes, sha256=${verified.sha256.take(12)}).")
            }
            "activate_model" -> {
                val id = args.optString("model_id")?.trim().orEmpty()
                if (id.isBlank()) return ToolExecutionResult.error("Missing 'model_id' for activate_model.")
                if (!manager.activateModel(id)) {
                    return ToolExecutionResult.error("Model '$id' is not installed on disk.")
                }
                ToolExecutionResult.ok("Activated on-device model '$id'.")
            }
            "scaffold_client" -> {
                val lang = LiteRtGuestLanguage.fromId(args.optString("language"))
                val path = scaffoldClientIntoWorkspace(lang)
                ToolExecutionResult.ok("Scaffolded LiteRT-LM guest client (${lang.displayName}) at $path.")
            }
            "switch_provider" -> {
                val providerId = args.optString("provider_id")?.trim().orEmpty()
                if (providerId.isBlank()) return ToolExecutionResult.error("Missing 'provider_id' for switch_provider.")
                val prof = manager.switchUniversalProvider(providerId)
                    ?: return ToolExecutionResult.error("Unknown provider '$providerId'.")
                onSwitchProviderInHost(prof.id)
                ToolExecutionResult.ok("Switched active provider to ${prof.name} (${prof.defaultModel}) in ${manager.currentSnapshot().switchMode.displayName} mode.")
            }
            "set_switch_mode" -> {
                val mode = CcSwitchOperatingMode.fromId(args.optString("mode"))
                manager.selectSwitchMode(mode)
                ToolExecutionResult.ok("Set Universal LLM Corner operating mode to ${mode.displayName}.")
            }
            "sync_cli_configs" -> {
                val targets = manager.syncLiveToolConfigs()
                ToolExecutionResult.ok("Synced ${targets.size} CLI tool configurations: ${targets.joinToString { it.displayName }}.")
            }
            else -> ToolExecutionResult.error("Unknown litert_corner action '$action'.")
        }
    }
}

data class LiteRtIpcModelRow(
    val id: String,
    val displayName: String,
    val family: String,
    val parameterCount: String,
    val quantization: String,
    val sizeBytes: Long,
    val sha256: String,
    val localFilePath: String,
    val active: Boolean,
)

data class LiteRtIpcGenerateResult(
    val ok: Boolean,
    val modelId: String,
    val delegateId: String,
    val text: String,
    val thinking: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val latencyMs: Long,
    val error: String,
)

/**
 * Host bridge exposing the on-device LiteRT-LM runtime to Android IPC surfaces (such as
 * `LiteRtLmContentProvider`) so guest apps built on the phone can query installed `.litertlm`
 * models, open read-only file descriptors (`mmap`), or invoke local inference via `ContentResolver.call`.
 */
object LiteRtIpcBridge {
    private fun managerFor(storageRoot: Path): LiteRtLmServingManager =
        LiteRtLmServingRegistry.getOrCreate(storageRoot = storageRoot.resolve("litert-lm"))

    fun listInstalledModels(storageRoot: Path): List<LiteRtIpcModelRow> {
        val snap = managerFor(storageRoot).currentSnapshot()
        return snap.entries
            .filter {
                (it.status == LiteRtModelInstallState.INSTALLED || it.status == LiteRtModelInstallState.ACTIVE) &&
                    it.localFilePath.isNotBlank()
            }
            .map { entry ->
                LiteRtIpcModelRow(
                    id = entry.spec.id,
                    displayName = entry.spec.displayName,
                    family = entry.spec.family,
                    parameterCount = entry.spec.parameterCount,
                    quantization = entry.spec.quantization,
                    sizeBytes = entry.sizeOnDiskBytes,
                    sha256 = entry.sha256,
                    localFilePath = entry.localFilePath,
                    active = entry.spec.id == snap.activeModelId,
                )
            }
    }

    fun statusSnapshot(storageRoot: Path): UiLiteRtCornerState =
        managerFor(storageRoot).currentSnapshot().toUi()

    fun resolveVerifiedModelFile(storageRoot: Path, modelId: String): File? {
        val manager = managerFor(storageRoot)
        val verified = if (modelId.isBlank() || modelId == "active") {
            manager.activeInstalledModel()
        } else {
            manager.verifiedArtifact(modelId)
        } ?: return null
        val file = verified.filePath.toFile()
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    fun generate(
        storageRoot: Path,
        prompt: String,
        system: String? = null,
        modelId: String? = null,
        delegateId: String? = null,
        maxTokens: Int = 512,
    ): LiteRtIpcGenerateResult {
        val manager = managerFor(storageRoot)
        val delegate = delegateId?.takeIf { it.isNotBlank() }?.let(LiteRtHardwareDelegate::fromId)
        val res = manager.generate(
            prompt = prompt,
            system = system,
            modelId = modelId,
            delegate = delegate,
            maxTokens = maxTokens,
        )
        return LiteRtIpcGenerateResult(
            ok = res.ok,
            modelId = res.modelId,
            delegateId = res.delegate.id,
            text = res.text,
            thinking = res.thinking,
            promptTokens = res.promptTokens,
            completionTokens = res.completionTokens,
            latencyMs = res.latencyMs,
            error = res.error,
        )
    }
}
