package dev.ide.core.agent

import dev.ide.agent.AgentTool
import dev.ide.agent.ToolArgs
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.ToolSpec
import dev.ide.agent.impl.LiteRtCornerSnapshot
import dev.ide.agent.impl.LiteRtGuestLanguage
import dev.ide.agent.impl.LiteRtHardwareDelegate
import dev.ide.agent.impl.LiteRtLmServingManager
import dev.ide.agent.impl.LiteRtLmServingRegistry
import dev.ide.agent.impl.LiteRtModelInstallState
import dev.ide.agent.toolSchema
import dev.ide.ui.backend.UiLiteRtCornerState
import dev.ide.ui.backend.UiLiteRtDelegate
import dev.ide.ui.backend.UiLiteRtGuestLanguage
import dev.ide.ui.backend.UiLiteRtHardwareInfo
import dev.ide.ui.backend.UiLiteRtModelEntry
import dev.ide.ui.backend.UiLiteRtModelStatus
import dev.ide.ui.backend.UiLiteRtServerStatus
import java.io.File
import java.nio.file.Path

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

internal fun LiteRtCornerSnapshot.toUi(): UiLiteRtCornerState = UiLiteRtCornerState(
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
        )
    },
    activeModelId = activeModelId,
    server = UiLiteRtServerStatus(
        enabled = serverEnabled,
        running = serverRunning,
        port = serverPort,
        openAiEndpoint = openAiEndpoint,
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
)

/**
 * Built-in agent tool (`litert_corner`) allowing the IDE coding agent or MCP client to inspect the
 * Self-Serve LiteRT-LM Corner, start/stop the local loopback inference server, provision a catalog model,
 * or scaffold a guest-app local inference client into the open project.
 */
internal fun createLiteRtCornerTool(
    managerProvider: () -> LiteRtLmServingManager,
    scaffoldClientIntoWorkspace: suspend (LiteRtGuestLanguage) -> String,
): AgentTool = object : AgentTool {
    override val spec = ToolSpec(
        name = "litert_corner",
        description = "Inspect or control the Self-Serve LiteRT-LM Corner (on-device .litertlm model catalog, " +
            "NPU/GPU/CPU delegates, local loopback HTTP server on 127.0.0.1:<port>, and guest-app SDK scaffolding).",
        parameters = toolSchema {
            string(
                "action",
                "status, start_server, stop_server, download_model, activate_model, or scaffold_client",
                enum = listOf("status", "start_server", "stop_server", "download_model", "activate_model", "scaffold_client"),
            )
            string("model_id", "Catalog model ID (e.g. gemma-3-1b-it-int4) for download_model or activate_model", required = false)
            string("language", "Target guest language (kotlin, swift, node, python) for scaffold_client", required = false)
        },
    )

    override suspend fun execute(args: ToolArgs): ToolExecutionResult {
        val manager = managerProvider()
        return when (val action = args.string("action")) {
            "status" -> {
                val snap = manager.currentSnapshot()
                val installed = snap.entries.filter { it.sizeOnDiskBytes > 0L }.map { "${it.spec.id} (${it.status})" }
                ToolExecutionResult.ok(
                    "LiteRT-LM Corner: soc='${snap.hardware.socName}', delegate=${snap.selectedDelegate.id}, " +
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
            .filter { it.sizeOnDiskBytes > 0L && it.localFilePath.isNotBlank() }
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
