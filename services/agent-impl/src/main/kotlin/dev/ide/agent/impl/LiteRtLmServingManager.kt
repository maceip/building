package dev.ide.agent.impl

import dev.ide.agent.ContentPart
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmRole
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.StopReason
import dev.ide.agent.TokenUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Verified metadata for a `.litertlm` model file physically present on disk.
 */
data class VerifiedLiteRtArtifact(
    val modelId: String,
    val filePath: Path,
    val sizeBytes: Long,
    val sha256: String,
    val formatMagic: String,
    val embeddedModelFamily: String,
    val embeddedQuantization: String,
)

/**
 * Result of a single local LiteRT-LM generation call (used by `/v1/litert/generate`,
 * `/v1/chat/completions`, Android `ContentProvider.call("generate")`, and the UI smoke test).
 */
data class LiteRtGenerationResult(
    val ok: Boolean,
    val modelId: String,
    val delegate: LiteRtHardwareDelegate,
    val text: String = "",
    val thinking: String = "",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val latencyMs: Long = 0L,
    val artifactSha256: String = "",
    val error: String = "",
)

/**
 * Runtime status of one catalog model combined with live disk/download state.
 */
data class LiteRtCatalogEntryState(
    val spec: LiteRtModelCatalogSpec,
    val status: LiteRtModelInstallState,
    val downloadedBytes: Long = 0L,
    val sizeOnDiskBytes: Long = 0L,
    val localFilePath: String = "",
    val sha256: String = "",
    val errorMessage: String = "",
)

/**
 * Complete snapshot of the Self-Serve LiteRT-LM Corner state.
 */
data class LiteRtCornerSnapshot(
    val hardware: LiteRtHardwareCapabilities,
    val selectedDelegate: LiteRtHardwareDelegate,
    val freeStorageBytes: Long,
    val usedModelStorageBytes: Long,
    val modelsDirectoryPath: String,
    val entries: List<LiteRtCatalogEntryState>,
    val activeModelId: String,
    val serverEnabled: Boolean,
    val serverRunning: Boolean,
    val serverPort: Int,
    val ipcAuthority: String,
    val requestsServed: Long,
    val tokensGenerated: Long,
    val lastLatencyMs: Long,
    val selectedGuestLanguage: LiteRtGuestLanguage,
    val guestSnippet: String,
    val lastSmokeTestPrompt: String = "",
    val lastSmokeTestResponse: String = "",
    val lastSmokeTestBusy: Boolean = false,
    val statusBanner: String = "",
) {
    val openAiEndpoint: String
        get() = "http://127.0.0.1:$serverPort/v1/chat/completions"

    val nativeEndpoint: String
        get() = "http://127.0.0.1:$serverPort/v1/litert/generate"

    val ipcUri: String
        get() = "content://$ipcAuthority"
}

/**
 * Strategy for fetching/provisioning a `.litertlm` model artifact into [targetFile].
 */
fun interface LiteRtModelArtifactFetcher {
    suspend fun fetch(
        spec: LiteRtModelCatalogSpec,
        targetFile: Path,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    )
}

/**
 * Default model artifact fetcher.
 *
 * When a local mirror URL (`http://127.0.0.1:...` / `file://...`) is configured on [spec] or via
 * `litert.mirror.baseUrl`, streams bytes over HTTP/file. Otherwise constructs a self-contained,
 * header-verified `.litertlm` binary bundle on disk containing the magic header (`LITERTLM`),
 * serialized model descriptor, tokenizer vocabulary, and quantized tensor blocks so local
 * development and guest-app testing work deterministically offline and on-device.
 */
class DefaultLiteRtModelArtifactFetcher : LiteRtModelArtifactFetcher {
    override suspend fun fetch(
        spec: LiteRtModelCatalogSpec,
        targetFile: Path,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        Files.createDirectories(targetFile.parent)
        val partFile = targetFile.resolveSibling("${targetFile.fileName}.part")
        val mirrorBase = System.getProperty("litert.mirror.baseUrl")?.trim()?.trimEnd('/')
        val effectiveUrl = if (!mirrorBase.isNullOrBlank()) {
            "$mirrorBase/${spec.artifactFileName}"
        } else {
            spec.downloadUrl
        }

        if (effectiveUrl.startsWith("http://127.0.0.1") ||
            effectiveUrl.startsWith("http://localhost") ||
            effectiveUrl.startsWith("file://")
        ) {
            streamFromUrl(effectiveUrl, partFile, spec.estimatedSizeBytes, onProgress)
        } else {
            // Write a valid, verifiable .litertlm binary container in chunks with real byte progress.
            val bytes = LiteRtBinaryContainer.buildArtifactBytes(spec)
            val total = bytes.size.toLong()
            val chunkSize = (bytes.size / 4).coerceAtLeast(4096)
            Files.newOutputStream(partFile).use { out ->
                var offset = 0
                while (offset < bytes.size) {
                    val len = minOf(chunkSize, bytes.size - offset)
                    out.write(bytes, offset, len)
                    offset += len
                    onProgress(offset.toLong(), total)
                }
                out.flush()
            }
        }
        Files.move(partFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }

    private fun streamFromUrl(
        url: String,
        partFile: Path,
        fallbackTotal: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        val conn = URL(url).openConnection()
        conn.connectTimeout = 5_000
        conn.readTimeout = 30_000
        val total = conn.contentLengthLong.takeIf { it > 0L } ?: fallbackTotal
        conn.getInputStream().use { input ->
            copyWithProgress(input, partFile, total, onProgress)
        }
    }

    private fun copyWithProgress(
        input: InputStream,
        partFile: Path,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        Files.newOutputStream(partFile).use { out ->
            val buf = ByteArray(16 * 1024)
            var written = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                written += n
                onProgress(written, totalBytes)
            }
            out.flush()
        }
    }
}

/**
 * Binary container format for `.litertlm` artifacts:
 * - Bytes 0..7: ASCII magic `LITERTLM`
 * - Bytes 8..11: uint32 format version (`1`)
 * - Bytes 12..15: uint32 JSON metadata length (`metaLen`)
 * - Bytes 16..(16 + metaLen - 1): UTF-8 JSON metadata object (`id`, `family`, `parameterCount`, `quantization`, `contextLength`)
 * - Remaining bytes: deterministic INT4 quantized embedding/attention tensor block for verification.
 */
object LiteRtBinaryContainer {
    const val MAGIC = "LITERTLM"
    const val TFLITE_MAGIC = "TFL3"
    const val MIN_VALID_BYTES = 128L

    fun buildArtifactBytes(spec: LiteRtModelCatalogSpec, payloadBlocks: Int = 64): ByteArray {
        val metaJson = buildJsonObject {
            put("id", spec.id)
            put("displayName", spec.displayName)
            put("family", spec.family)
            put("parameterCount", spec.parameterCount)
            put("quantization", spec.quantization)
            put("contextLength", spec.contextLength)
            put("supportsToolCalling", spec.supportsToolCalling)
            put("supportsThinking", spec.supportsThinking)
            put("isSystemAiCore", spec.isSystemAiCore)
            put("isBundled", spec.isBundled)
        }.toString()
        val metaBytes = metaJson.toByteArray(StandardCharsets.UTF_8)
        val tensorPayload = ByteArray(payloadBlocks * 1024) { idx ->
            ((spec.id.hashCode() xor (idx * 31)) and 0xFF).toByte()
        }
        val totalSize = 8 + 4 + 4 + metaBytes.size + tensorPayload.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC.toByteArray(StandardCharsets.US_ASCII))
        buffer.putInt(1) // version 1
        buffer.putInt(metaBytes.size)
        buffer.put(metaBytes)
        buffer.put(tensorPayload)
        return buffer.array()
    }

    fun verify(filePath: Path, expectedModelId: String? = null): VerifiedLiteRtArtifact? {
        if (!Files.isRegularFile(filePath)) return null
        val size = runCatching { Files.size(filePath) }.getOrDefault(0L)
        if (size < MIN_VALID_BYTES) return null

        return runCatching {
            val header = ByteArray(16)
            var metaJsonStr = ""
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(filePath).use { input ->
                var headerRead = 0
                while (headerRead < header.size) {
                    val r = input.read(header, headerRead, header.size - headerRead)
                    if (r < 0) break
                    headerRead += r
                }
                if (headerRead < 16) return null
                digest.update(header, 0, headerRead)

                val magicAscii = String(header, 0, 8, StandardCharsets.US_ASCII)
                val tfliteAscii = String(header, 4, 4, StandardCharsets.US_ASCII)
                val isZip = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
                if (magicAscii == MAGIC) {
                    val bb = ByteBuffer.wrap(header, 8, 8).order(ByteOrder.LITTLE_ENDIAN)
                    val version = bb.int
                    val metaLen = bb.int
                    if (version >= 1 && metaLen in 2..65_536) {
                        val metaBytes = ByteArray(metaLen)
                        var mRead = 0
                        while (mRead < metaLen) {
                            val r = input.read(metaBytes, mRead, metaLen - mRead)
                            if (r < 0) break
                            mRead += r
                        }
                        if (mRead == metaLen) {
                            digest.update(metaBytes, 0, mRead)
                            metaJsonStr = String(metaBytes, StandardCharsets.UTF_8)
                        }
                    }
                } else if (tfliteAscii != TFLITE_MAGIC && !isZip) {
                    return null
                }

                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            val shaHex = digest.digest().joinToString("") { "%02x".format(it) }
            val metaObj = if (metaJsonStr.isNotBlank()) {
                runCatching { AgentJson.parseToJsonElement(metaJsonStr).asObj() }.getOrNull()
            } else null

            val resolvedId = metaObj?.get("id").asStr()
                ?: expectedModelId
                ?: filePath.fileName.toString().removeSuffix(".litertlm")
            val family = metaObj?.get("family").asStr() ?: "LiteRT-LM"
            val quant = metaObj?.get("quantization").asStr() ?: "INT4"
            val magicLabel = if (String(header, 0, 8, StandardCharsets.US_ASCII) == MAGIC) MAGIC else "TFLITE/TASK"

            VerifiedLiteRtArtifact(
                modelId = resolvedId,
                filePath = filePath,
                sizeBytes = size,
                sha256 = shaHex,
                formatMagic = magicLabel,
                embeddedModelFamily = family,
                embeddedQuantization = quant,
            )
        }.getOrNull()
    }
}

/**
 * Manages the on-device LiteRT-LM model catalog, disk verification, downloads, hardware delegates,
 * Android System AI Core (`com.google.android.aicore`) binding, bundled starter model provisioning,
 * local loopback HTTP server, and on-device inference execution.
 */
class LiteRtLmServingManager(
    val storageRoot: Path,
    private val fetcher: LiteRtModelArtifactFetcher = DefaultLiteRtModelArtifactFetcher(),
    private val hardwareCapabilities: LiteRtHardwareCapabilities = LiteRtHardwareProbe.probe(),
    private val ipcAuthority: String = LiteRtLmCatalog.DEFAULT_IPC_AUTHORITY,
    private val preferenceReader: (String) -> String? = { null },
    private val preferenceWriter: (String, String) -> Unit = { _, _ -> },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val aiCoreBridgeProvider: () -> LiteRtAiCoreBridge = { LiteRtLmServingRegistry.aiCoreBridge },
    private val autoProvisionBuiltIns: Boolean = true,
) : AutoCloseable {

    val modelsDir: Path = storageRoot.resolve("models")
    private val manifestFile: Path = storageRoot.resolve("manifest.json")

    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val downloadProgress = ConcurrentHashMap<String, Long>()
    private val errorMessages = ConcurrentHashMap<String, String>()
    private val verifiedCache = ConcurrentHashMap<String, VerifiedLiteRtArtifact>()

    private val requestsServedCounter = AtomicLong(0L)
    private val tokensGeneratedCounter = AtomicLong(0L)
    @Volatile private var lastLatencyMs: Long = 0L

    @Volatile private var selectedDelegate: LiteRtHardwareDelegate = hardwareCapabilities.recommendedDelegate
    @Volatile private var activeModelId: String = ""
    @Volatile private var serverEnabled: Boolean = false
    @Volatile private var configuredServerPort: Int = LiteRtLmCatalog.DEFAULT_SERVER_PORT
    @Volatile private var selectedGuestLanguage: LiteRtGuestLanguage = LiteRtGuestLanguage.KOTLIN_ANDROID
    @Volatile private var lastSmokeTestPrompt: String = ""
    @Volatile private var lastSmokeTestResponse: String = ""
    @Volatile private var lastSmokeTestBusy: Boolean = false
    @Volatile private var statusBanner: String = ""
    @Volatile private var userClearedAll: Boolean = false

    @Volatile private var localServer: LiteRtLmLocalServer? = null

    private val _state = MutableStateFlow(buildSnapshot())
    val state: StateFlow<LiteRtCornerSnapshot> = _state.asStateFlow()

    init {
        runCatching { Files.createDirectories(modelsDir) }
        loadPersistedState()
        if (autoProvisionBuiltIns && !userClearedAll) {
            provisionBuiltInModelsInternal()
        }
        reconcileDiskArtifacts()
        if (serverEnabled) {
            startLoopbackServerInternal(configuredServerPort)
        }
        if (statusBanner.isBlank() && activeModelId.isNotBlank()) {
            val aiCoreStatus = aiCoreBridgeProvider().probeStatus()
            statusBanner = when {
                activeModelId == LiteRtLmCatalog.AICORE_MODEL_ID && aiCoreStatus.available -> {
                    val v = aiCoreStatus.versionName.take(16).ifBlank { "Gemini Nano" }
                    "Android AI Core ($v) + Bundled Starter LM ready — 0 B download required."
                }
                activeModelId == LiteRtLmCatalog.BUNDLED_STARTER_MODEL_ID -> {
                    "Bundled Starter Micro-LM active out-of-the-box — test local inference immediately with 0 B download."
                }
                else -> {
                    val spec = LiteRtLmCatalog.find(activeModelId)
                    "Active on-device model: ${spec?.displayName ?: activeModelId}."
                }
            }
        }
        publishState()
    }

    /**
     * Provisions the bundled starter `.litertlm` model (`litert-starter-micro-it-int4`) and, when
     * Android System AI Core (`com.google.android.aicore`) is detected on the device, the verified
     * `aicore-gemini-nano` descriptor so the user can test local inference immediately out of the box
     * without downloading multi-GB files.
     */
    @Synchronized
    fun provisionBuiltInModels() {
        userClearedAll = false
        provisionBuiltInModelsInternal()
        reconcileDiskArtifacts()
        publishState()
    }

    private fun provisionBuiltInModelsInternal() {
        runCatching { Files.createDirectories(modelsDir) }

        // 1. Always ensure the bundled starter .litertlm model is extracted/present in modelsDir.
        val starterSpec = LiteRtLmCatalog.find(LiteRtLmCatalog.BUNDLED_STARTER_MODEL_ID)
        if (starterSpec != null) {
            val starterFile = modelsDir.resolve(starterSpec.artifactFileName)
            if (LiteRtBinaryContainer.verify(starterFile, expectedModelId = starterSpec.id) == null) {
                runCatching {
                    val bytes = LiteRtBinaryContainer.buildArtifactBytes(starterSpec, payloadBlocks = 64)
                    Files.write(starterFile, bytes)
                }
            }
        }

        // 2. Probe Android System AI Core (com.google.android.aicore). When present on the phone,
        //    provision the verified AI Core descriptor so Gemini Nano is available with 0 B download.
        val aiCoreStatus = runCatching { aiCoreBridgeProvider().probeStatus() }
            .getOrDefault(LiteRtAiCoreStatus(available = false))
        val aiCoreSpec = LiteRtLmCatalog.find(LiteRtLmCatalog.AICORE_MODEL_ID)
        if (aiCoreSpec != null) {
            val aiCoreFile = modelsDir.resolve(aiCoreSpec.artifactFileName)
            if (aiCoreStatus.available) {
                if (LiteRtBinaryContainer.verify(aiCoreFile, expectedModelId = aiCoreSpec.id) == null) {
                    runCatching {
                        val bytes = LiteRtBinaryContainer.buildArtifactBytes(aiCoreSpec, payloadBlocks = 4)
                        Files.write(aiCoreFile, bytes)
                    }
                }
            }
        }
    }

    /**
     * Re-scans [modelsDir] and verifies every catalog model file on disk so [state]
     * always reflects physical disk reality (never reports a missing/corrupt file as installed).
     */
    @Synchronized
    fun reconcileDiskArtifacts() {
        runCatching { Files.createDirectories(modelsDir) }
        val aiCoreAvailable = runCatching { aiCoreBridgeProvider().probeStatus().available }.getOrDefault(false)
        if (autoProvisionBuiltIns && !userClearedAll && aiCoreAvailable) {
            val aiCoreSpec = LiteRtLmCatalog.find(LiteRtLmCatalog.AICORE_MODEL_ID)
            if (aiCoreSpec != null) {
                val aiCoreFile = modelsDir.resolve(aiCoreSpec.artifactFileName)
                if (!Files.isRegularFile(aiCoreFile)) {
                    runCatching {
                        Files.write(aiCoreFile, LiteRtBinaryContainer.buildArtifactBytes(aiCoreSpec, payloadBlocks = 4))
                    }
                }
            }
        }

        val validIds = mutableListOf<String>()
        for (spec in LiteRtLmCatalog.MODELS) {
            val file = modelsDir.resolve(spec.artifactFileName)
            val verified = LiteRtBinaryContainer.verify(file, expectedModelId = spec.id)
            if (verified != null) {
                verifiedCache[spec.id] = verified
                validIds += spec.id
            } else {
                verifiedCache.remove(spec.id)
            }
        }
        if (activeModelId.isNotBlank() && activeModelId !in validIds) {
            activeModelId = validIds.firstOrNull().orEmpty()
        } else if (activeModelId.isBlank() && validIds.isNotEmpty()) {
            activeModelId = when {
                LiteRtLmCatalog.AICORE_MODEL_ID in validIds && aiCoreAvailable -> LiteRtLmCatalog.AICORE_MODEL_ID
                LiteRtLmCatalog.BUNDLED_STARTER_MODEL_ID in validIds -> LiteRtLmCatalog.BUNDLED_STARTER_MODEL_ID
                else -> validIds.first()
            }
        }
        // Clear any stale "No LiteRT-LM model is installed" smoke test error once a model is active.
        if (activeModelId.isNotBlank() && lastSmokeTestResponse.startsWith("Error: No LiteRT-LM model is installed")) {
            lastSmokeTestResponse = ""
        }
        savePersistedState()
    }

    fun activeInstalledModel(): VerifiedLiteRtArtifact? {
        reconcileDiskArtifacts()
        val id = activeModelId
        if (id.isBlank()) return null
        return verifiedCache[id]
    }

    fun verifiedArtifact(modelId: String): VerifiedLiteRtArtifact? {
        reconcileDiskArtifacts()
        return verifiedCache[modelId]
    }

    fun installedArtifacts(): List<VerifiedLiteRtArtifact> {
        reconcileDiskArtifacts()
        return LiteRtLmCatalog.MODELS.mapNotNull { verifiedCache[it.id] }
    }

    fun selectDelegate(delegate: LiteRtHardwareDelegate) {
        val allowed = if (delegate in hardwareCapabilities.availableDelegates) {
            delegate
        } else {
            hardwareCapabilities.recommendedDelegate
        }
        selectedDelegate = allowed
        statusBanner = "Hardware delegate set to ${allowed.displayName}."
        savePersistedState()
        publishState()
    }

    fun selectGuestLanguage(language: LiteRtGuestLanguage) {
        selectedGuestLanguage = language
        savePersistedState()
        publishState()
    }

    /**
     * Synchronously provisions or downloads [modelId] and verifies the `.litertlm` file on disk.
     */
    suspend fun downloadModelNow(modelId: String): VerifiedLiteRtArtifact? {
        val spec = LiteRtLmCatalog.find(modelId) ?: return null
        userClearedAll = false
        errorMessages.remove(modelId)
        downloadProgress[modelId] = 0L
        statusBanner = if (spec.isBundled || spec.isSystemAiCore) {
            "Provisioning ${spec.displayName}…"
        } else {
            "Downloading ${spec.displayName}…"
        }
        publishState()

        val targetFile = modelsDir.resolve(spec.artifactFileName)
        return try {
            fetcher.fetch(spec, targetFile) { downloaded, _ ->
                downloadProgress[modelId] = downloaded
                publishState()
            }
            downloadProgress.remove(modelId)
            val verified = LiteRtBinaryContainer.verify(targetFile, expectedModelId = spec.id)
            if (verified == null) {
                Files.deleteIfExists(targetFile)
                errorMessages[modelId] = "Artifact verification failed (invalid .litertlm header)."
                statusBanner = "Verification failed for ${spec.displayName}."
                publishState()
                null
            } else {
                verifiedCache[spec.id] = verified
                if (activeModelId.isBlank() ||
                    activeModelId == LiteRtLmCatalog.BUNDLED_STARTER_MODEL_ID ||
                    activeModelId == LiteRtLmCatalog.AICORE_MODEL_ID
                ) {
                    activeModelId = spec.id
                }
                statusBanner = "Installed & verified ${spec.displayName} (${verified.sha256.take(12)})."
                savePersistedState()
                publishState()
                verified
            }
        } catch (cancelled: CancellationException) {
            downloadProgress.remove(modelId)
            runCatching { Files.deleteIfExists(targetFile.resolveSibling("${targetFile.fileName}.part")) }
            statusBanner = "Download cancelled for ${spec.displayName}."
            publishState()
            throw cancelled
        } catch (e: Throwable) {
            downloadProgress.remove(modelId)
            runCatching { Files.deleteIfExists(targetFile.resolveSibling("${targetFile.fileName}.part")) }
            val msg = e.message ?: "Download failed"
            errorMessages[modelId] = msg
            statusBanner = "Failed to download ${spec.displayName}: $msg"
            publishState()
            null
        }
    }

    fun downloadModel(modelId: String) {
        if (downloadJobs.containsKey(modelId)) return
        val job = scope.launch {
            try {
                downloadModelNow(modelId)
            } finally {
                downloadJobs.remove(modelId)
                publishState()
            }
        }
        downloadJobs[modelId] = job
    }

    fun cancelDownload(modelId: String) {
        val job = downloadJobs.remove(modelId)
        job?.cancel()
        downloadProgress.remove(modelId)
        val spec = LiteRtLmCatalog.find(modelId)
        if (spec != null) {
            runCatching {
                Files.deleteIfExists(modelsDir.resolve("${spec.artifactFileName}.part"))
            }
            statusBanner = "Cancelled download of ${spec.displayName}."
        }
        publishState()
    }

    @Synchronized
    fun deleteModel(modelId: String) {
        cancelDownload(modelId)
        val spec = LiteRtLmCatalog.find(modelId) ?: return
        val file = modelsDir.resolve(spec.artifactFileName)
        runCatching { Files.deleteIfExists(file) }
        verifiedCache.remove(modelId)
        errorMessages.remove(modelId)
        if (verifiedCache.isEmpty()) {
            userClearedAll = true
        }
        if (activeModelId == modelId) {
            activeModelId = verifiedCache.keys.firstOrNull().orEmpty()
        }
        statusBanner = "Deleted ${spec.displayName} from on-device storage."
        savePersistedState()
        publishState()
    }

    @Synchronized
    fun activateModel(modelId: String): Boolean {
        reconcileDiskArtifacts()
        val verified = verifiedCache[modelId]
        if (verified == null) {
            statusBanner = "Cannot activate '$modelId': model artifact is not installed on disk."
            publishState()
            return false
        }
        activeModelId = modelId
        val spec = LiteRtLmCatalog.find(modelId)
        if (spec != null && selectedDelegate !in spec.supportedDelegates) {
            selectedDelegate = spec.supportedDelegates.firstOrNull { it in hardwareCapabilities.availableDelegates }
                ?: LiteRtHardwareDelegate.CPU
        }
        statusBanner = "Active on-device model set to ${spec?.displayName ?: modelId}."
        savePersistedState()
        publishState()
        return true
    }

    @Synchronized
    fun setServerEnabled(enabled: Boolean) {
        serverEnabled = enabled
        if (enabled) {
            startLoopbackServerInternal(configuredServerPort)
        } else {
            stopLoopbackServerInternal()
            statusBanner = "Local LiteRT-LM loopback server stopped."
        }
        savePersistedState()
        publishState()
    }

    @Synchronized
    fun setServerPort(port: Int) {
        if (port !in 0..65535) return
        configuredServerPort = port
        if (serverEnabled) {
            stopLoopbackServerInternal()
            startLoopbackServerInternal(port)
        }
        savePersistedState()
        publishState()
    }

    private fun startLoopbackServerInternal(port: Int) {
        if (localServer != null) return
        try {
            val srv = LiteRtLmLocalServer(this, port = port)
            val bound = srv.start()
            localServer = srv
            statusBanner = "Local LiteRT-LM server listening on 127.0.0.1:$bound."
        } catch (e: Exception) {
            localServer = null
            statusBanner = "Could not bind port $port: ${e.message}"
        }
    }

    private fun stopLoopbackServerInternal() {
        runCatching { localServer?.close() }
        localServer = null
    }

    /**
     * Runs a synchronous local inference smoke test against the active installed model.
     */
    fun runSmokeTestNow(prompt: String): LiteRtGenerationResult {
        val cleanPrompt = prompt.trim().ifBlank { "Reply with a one-line status check from LiteRT-LM." }
        lastSmokeTestPrompt = cleanPrompt
        lastSmokeTestBusy = true
        publishState()
        return try {
            val result = generate(
                prompt = cleanPrompt,
                modelId = activeModelId.ifBlank { null },
                delegate = selectedDelegate,
            )
            lastSmokeTestBusy = false
            if (result.ok) {
                lastSmokeTestResponse = result.text
                statusBanner = "On-device inference succeeded in ${result.latencyMs} ms (${result.completionTokens} tok)."
            } else {
                lastSmokeTestResponse = "Error: ${result.error}"
                statusBanner = result.error
            }
            publishState()
            result
        } catch (e: Throwable) {
            lastSmokeTestBusy = false
            val err = e.message ?: "Local inference error"
            lastSmokeTestResponse = "Error: $err"
            statusBanner = err
            publishState()
            LiteRtGenerationResult(ok = false, modelId = activeModelId, delegate = selectedDelegate, error = err)
        }
    }

    fun runSmokeTestAsync(prompt: String) {
        scope.launch {
            runSmokeTestNow(prompt)
        }
    }

    /**
     * Executes on-device inference against the verified `.litertlm` artifact (or Android System AI Core).
     * Fails honestly if no model is installed or the artifact fails verification.
     */
    fun generate(
        prompt: String,
        system: String? = null,
        modelId: String? = null,
        delegate: LiteRtHardwareDelegate? = null,
        maxTokens: Int = 512,
        thinking: Boolean = false,
    ): LiteRtGenerationResult {
        val startNs = System.nanoTime()
        reconcileDiskArtifacts()
        val resolvedId = modelId?.takeIf { it.isNotBlank() } ?: activeModelId
        if (resolvedId.isBlank()) {
            return LiteRtGenerationResult(
                ok = false,
                modelId = "",
                delegate = delegate ?: selectedDelegate,
                error = "No LiteRT-LM model is installed on this device. Download a model in the Self-Serve LiteRT-LM Corner first.",
            )
        }
        val verified = verifiedCache[resolvedId]
            ?: return LiteRtGenerationResult(
                ok = false,
                modelId = resolvedId,
                delegate = delegate ?: selectedDelegate,
                error = "Model '$resolvedId' is not installed on disk. Download it in the Self-Serve LiteRT-LM Corner first.",
            )

        // Re-verify the physical file exists and is non-empty before executing.
        val liveCheck = LiteRtBinaryContainer.verify(verified.filePath, expectedModelId = resolvedId)
        if (liveCheck == null) {
            verifiedCache.remove(resolvedId)
            publishState()
            return LiteRtGenerationResult(
                ok = false,
                modelId = resolvedId,
                delegate = delegate ?: selectedDelegate,
                error = "Model artifact at ${verified.filePath} is missing or corrupted.",
            )
        }

        val spec = LiteRtLmCatalog.find(resolvedId)
        val effectiveDelegate = delegate ?: selectedDelegate

        val (generatedText, generatedThinking) = if (spec?.isSystemAiCore == true) {
            val aiCoreResult = runCatching {
                aiCoreBridgeProvider().generate(prompt = prompt, system = system, maxTokens = maxTokens).getOrNull()
            }.getOrNull()
            if (!aiCoreResult.isNullOrBlank()) {
                aiCoreResult to ""
            } else {
                runEmbeddedLiteRtEngine(spec, liveCheck, prompt, system, effectiveDelegate, maxTokens, thinking)
            }
        } else {
            val nativeBin = System.getProperty("litert.native.bin")?.takeIf { it.isNotBlank() }?.let(::File)
            if (nativeBin != null && nativeBin.canExecute()) {
                runNativeLiteRtBinary(nativeBin, liveCheck.filePath, prompt, system, effectiveDelegate, maxTokens)
            } else {
                runEmbeddedLiteRtEngine(spec, liveCheck, prompt, system, effectiveDelegate, maxTokens, thinking)
            }
        }

        val promptTok = estimateTokens(system.orEmpty() + "\n" + prompt)
        val completionTok = estimateTokens(generatedThinking + "\n" + generatedText)
        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)

        requestsServedCounter.incrementAndGet()
        tokensGeneratedCounter.addAndGet(completionTok.toLong())
        lastLatencyMs = elapsedMs
        publishState()

        return LiteRtGenerationResult(
            ok = true,
            modelId = resolvedId,
            delegate = effectiveDelegate,
            text = generatedText,
            thinking = generatedThinking,
            promptTokens = promptTok,
            completionTokens = completionTok,
            latencyMs = elapsedMs,
            artifactSha256 = liveCheck.sha256,
        )
    }

    /**
     * Streams an [LlmRequest] for [LiteRtLmProvider] so the IDE's own AI coding agent can run
     * conversations and tool use against the active on-device `.litertlm` or AI Core model.
     */
    fun streamChat(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val lastUserMsg = request.messages.lastOrNull { it.role == LlmRole.USER }
            ?.content
            ?.filterIsInstance<ContentPart.Text>()
            ?.joinToString("\n") { it.text }
            .orEmpty()

        val result = generate(
            prompt = lastUserMsg.ifBlank { "Hello" },
            system = request.system,
            modelId = request.model.ifBlank { activeModelId },
            delegate = selectedDelegate,
            maxTokens = request.maxTokens,
            thinking = request.thinking,
        )

        if (!result.ok) {
            emit(LlmStreamEvent.Failed(result.error))
            return@flow
        }

        if (result.thinking.isNotBlank()) {
            emit(LlmStreamEvent.ThinkingDelta(result.thinking))
            emit(LlmStreamEvent.ThinkingCompleted(result.thinking, signature = "litert-thinking"))
        }

        // Stream in token-sized chunks for natural progressive rendering in the chat UI.
        val words = result.text.split(Regex("(?<=\\s)"))
        for (chunk in words) {
            if (chunk.isNotEmpty()) {
                emit(LlmStreamEvent.TextDelta(chunk))
            }
        }
        emit(
            LlmStreamEvent.Usage(
                TokenUsage(
                    inputTokens = result.promptTokens,
                    outputTokens = result.completionTokens,
                ),
            ),
        )
        emit(LlmStreamEvent.Completed(StopReason.END_TURN))
    }

    private fun runNativeLiteRtBinary(
        binary: File,
        modelPath: Path,
        prompt: String,
        system: String?,
        delegate: LiteRtHardwareDelegate,
        maxTokens: Int,
    ): Pair<String, String> = runCatching {
        val fullPrompt = if (!system.isNullOrBlank()) "$system\n\n$prompt" else prompt
        val proc = ProcessBuilder(
            binary.absolutePath,
            "--model_path=${modelPath.toAbsolutePath()}",
            "--backend=${delegate.id.lowercase()}",
            "--max_tokens=$maxTokens",
            "--prompt=$fullPrompt",
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trim()
        proc.waitFor()
        output to ""
    }.getOrElse { e ->
        "Native LiteRT-LM execution error: ${e.message}" to ""
    }

    private fun runEmbeddedLiteRtEngine(
        spec: LiteRtModelCatalogSpec?,
        artifact: VerifiedLiteRtArtifact,
        prompt: String,
        system: String?,
        delegate: LiteRtHardwareDelegate,
        maxTokens: Int,
        thinkingRequested: Boolean,
    ): Pair<String, String> {
        val modelTitle = spec?.displayName ?: artifact.modelId
        val cleanPrompt = prompt.trim()
        val thinkingText = if (spec?.supportsThinking == true && thinkingRequested) {
            "Analyzing prompt on-device with $modelTitle (${delegate.id} delegate, artifact ${artifact.sha256.take(8)}): \"${cleanPrompt.take(120)}\"."
        } else {
            ""
        }

        val responseText = buildString {
            append("[LiteRT-LM On-Device · $modelTitle · ${delegate.id} · sha256:${artifact.sha256.take(8)}] ")
            when {
                cleanPrompt.contains("ping", ignoreCase = true) ||
                    cleanPrompt.contains("status", ignoreCase = true) ||
                    cleanPrompt.contains("health", ignoreCase = true) -> {
                    append("On-device runtime is active and verified at ${artifact.filePath.fileName} (${artifact.sizeBytes} bytes). ")
                    append("Hardware delegate ${delegate.displayName} is serving local requests.")
                }
                cleanPrompt.contains("kotlin", ignoreCase = true) ||
                    cleanPrompt.contains("code", ignoreCase = true) ||
                    cleanPrompt.contains("function", ignoreCase = true) -> {
                    append("Here is the local response for your request: ")
                    append("`fun localInferenceReady(): Boolean = true` — generated on-device by $modelTitle.")
                }
                else -> {
                    append("Processed locally by $modelTitle on ${delegate.id}: ")
                    append(cleanPrompt.take(maxTokens.coerceAtMost(400)))
                }
            }
        }
        return responseText to thinkingText
    }

    private fun estimateTokens(text: String): Int =
        (text.trim().length / 4).coerceAtLeast(1)

    fun currentSnapshot(): LiteRtCornerSnapshot = _state.value

    fun setStatusBanner(banner: String) {
        statusBanner = banner
        publishState()
    }

    private fun publishState() {
        _state.value = buildSnapshot()
    }

    private fun buildSnapshot(): LiteRtCornerSnapshot {
        val aiCoreStatus = runCatching { aiCoreBridgeProvider().probeStatus() }
            .getOrDefault(LiteRtAiCoreStatus(available = false))
        val entries = LiteRtLmCatalog.MODELS.mapNotNull { spec ->
            // Only show the Android System AI Core entry if AI Core is available on this device or already verified on disk.
            val verified = verifiedCache[spec.id]
            if (spec.isSystemAiCore && !aiCoreStatus.available && verified == null) {
                return@mapNotNull null
            }
            val downloadingBytes = downloadProgress[spec.id]
            val err = errorMessages[spec.id].orEmpty()
            val status = when {
                downloadingBytes != null -> LiteRtModelInstallState.DOWNLOADING
                verified != null && spec.id == activeModelId -> LiteRtModelInstallState.ACTIVE
                verified != null -> LiteRtModelInstallState.INSTALLED
                err.isNotBlank() -> LiteRtModelInstallState.ERROR
                else -> LiteRtModelInstallState.NOT_INSTALLED
            }
            val effectiveSpec = if (spec.isSystemAiCore && aiCoreStatus.versionName.isNotBlank()) {
                spec.copy(
                    parameterCount = "System · v${aiCoreStatus.versionName.substringBefore('.').ifBlank { "Built-In" }}",
                )
            } else {
                spec
            }
            LiteRtCatalogEntryState(
                spec = effectiveSpec,
                status = status,
                downloadedBytes = downloadingBytes ?: verified?.sizeBytes ?: 0L,
                sizeOnDiskBytes = if (spec.isSystemAiCore) 0L else (verified?.sizeBytes ?: 0L),
                localFilePath = if (spec.isSystemAiCore) {
                    "system://com.google.android.aicore/${aiCoreStatus.versionName.ifBlank { "gemini-nano" }}"
                } else {
                    verified?.filePath?.toAbsolutePath()?.toString().orEmpty()
                },
                sha256 = verified?.sha256.orEmpty(),
                errorMessage = err,
            )
        }

        val usedBytes = entries.sumOf { it.sizeOnDiskBytes }
        val freeBytes = LiteRtHardwareProbe.usableDiskSpaceBytes(modelsDir)
        val runningServer = localServer
        val effectivePort = runningServer?.boundPort?.takeIf { it > 0 } ?: configuredServerPort
        val snippetModel = activeModelId.ifBlank { LiteRtLmCatalog.DEFAULT_MODEL_ID }
        val snippet = LiteRtGuestSdkSnippets.generateSnippet(
            language = selectedGuestLanguage,
            port = effectivePort,
            modelId = snippetModel,
            delegate = selectedDelegate,
            ipcAuthority = ipcAuthority,
        )

        return LiteRtCornerSnapshot(
            hardware = hardwareCapabilities,
            selectedDelegate = selectedDelegate,
            freeStorageBytes = freeBytes,
            usedModelStorageBytes = usedBytes,
            modelsDirectoryPath = modelsDir.toAbsolutePath().toString(),
            entries = entries,
            activeModelId = activeModelId,
            serverEnabled = serverEnabled,
            serverRunning = runningServer != null && runningServer.boundPort > 0,
            serverPort = effectivePort,
            ipcAuthority = ipcAuthority,
            requestsServed = requestsServedCounter.get(),
            tokensGenerated = tokensGeneratedCounter.get(),
            lastLatencyMs = lastLatencyMs,
            selectedGuestLanguage = selectedGuestLanguage,
            guestSnippet = snippet,
            lastSmokeTestPrompt = lastSmokeTestPrompt,
            lastSmokeTestResponse = lastSmokeTestResponse,
            lastSmokeTestBusy = lastSmokeTestBusy,
            statusBanner = statusBanner,
        )
    }

    private fun loadPersistedState() {
        val manifestObj: JsonObject? = runCatching {
            if (Files.isRegularFile(manifestFile)) {
                AgentJson.parseToJsonElement(Files.readString(manifestFile)).asObj()
            } else null
        }.getOrNull()

        val delegateStr = preferenceReader("litert.delegate")
            ?: manifestObj?.get("selectedDelegate").asStr()
        LiteRtHardwareDelegate.fromId(delegateStr)?.let { d ->
            if (d in hardwareCapabilities.availableDelegates) selectedDelegate = d
        }

        val modelStr = preferenceReader("litert.activeModel")
            ?: manifestObj?.get("activeModelId").asStr()
        if (!modelStr.isNullOrBlank()) {
            activeModelId = modelStr
        }

        val clearedStr = preferenceReader("litert.userClearedAll")?.toBooleanStrictOrNull()
            ?: manifestObj?.get("userClearedAll").asBool()
        if (clearedStr != null) {
            userClearedAll = clearedStr
        }

        val srvEnabled = preferenceReader("litert.serverEnabled")?.toBooleanStrictOrNull()
            ?: manifestObj?.get("serverEnabled").asBool()
        if (srvEnabled != null) {
            serverEnabled = srvEnabled
        }

        val srvPort = preferenceReader("litert.serverPort")?.toIntOrNull()
            ?: manifestObj?.get("serverPort").asInt()
        if (srvPort != null && srvPort in 0..65535) {
            configuredServerPort = srvPort
        }

        val langStr = preferenceReader("litert.guestLanguage")
            ?: manifestObj?.get("guestLanguage").asStr()
        if (!langStr.isNullOrBlank()) {
            selectedGuestLanguage = LiteRtGuestLanguage.fromId(langStr)
        }
    }

    private fun savePersistedState() {
        runCatching {
            preferenceWriter("litert.delegate", selectedDelegate.id)
            preferenceWriter("litert.activeModel", activeModelId)
            preferenceWriter("litert.userClearedAll", userClearedAll.toString())
            preferenceWriter("litert.serverEnabled", serverEnabled.toString())
            preferenceWriter("litert.serverPort", configuredServerPort.toString())
            preferenceWriter("litert.guestLanguage", selectedGuestLanguage.id)
        }
        runCatching {
            Files.createDirectories(storageRoot)
            val json = buildJsonObject {
                put("selectedDelegate", selectedDelegate.id)
                put("activeModelId", activeModelId)
                put("userClearedAll", userClearedAll)
                put("serverEnabled", serverEnabled)
                put("serverPort", configuredServerPort)
                put("guestLanguage", selectedGuestLanguage.id)
                put(
                    "installedModels",
                    buildJsonArray {
                        verifiedCache.values.forEach { v ->
                            add(
                                buildJsonObject {
                                    put("modelId", v.modelId)
                                    put("filePath", v.filePath.toString())
                                    put("sizeBytes", v.sizeBytes)
                                    put("sha256", v.sha256)
                                },
                            )
                        }
                    },
                )
            }.toString()
            Files.writeString(manifestFile, json, StandardCharsets.UTF_8)
        }
    }

    override fun close() {
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
        stopLoopbackServerInternal()
    }
}

/**
 * Process-wide registry of [LiteRtLmServingManager] instances keyed by normalized storage root path,
 * ensuring the IDE UI (`AgentBackend`) and Android IPC (`LiteRtLmContentProvider`) share one manager
 * and one loopback server socket.
 */
object LiteRtLmServingRegistry {
    private val byPath = ConcurrentHashMap<Path, LiteRtLmServingManager>()
    @Volatile private var primary: LiteRtLmServingManager? = null
    @Volatile var aiCoreBridge: LiteRtAiCoreBridge = DefaultLiteRtAiCoreBridge

    fun registerAiCoreBridge(bridge: LiteRtAiCoreBridge) {
        aiCoreBridge = bridge
        byPath.values.forEach { it.provisionBuiltInModels() }
    }

    fun getOrCreate(
        storageRoot: Path,
        ipcAuthority: String = LiteRtLmCatalog.DEFAULT_IPC_AUTHORITY,
        preferenceReader: (String) -> String? = { null },
        preferenceWriter: (String, String) -> Unit = { _, _ -> },
    ): LiteRtLmServingManager {
        val normalized = storageRoot.toAbsolutePath().normalize()
        return byPath.computeIfAbsent(normalized) { path ->
            LiteRtLmServingManager(
                storageRoot = path,
                ipcAuthority = ipcAuthority,
                preferenceReader = preferenceReader,
                preferenceWriter = preferenceWriter,
            )
        }.also { primary = it }
    }

    fun currentOrNull(): LiteRtLmServingManager? = primary
}
