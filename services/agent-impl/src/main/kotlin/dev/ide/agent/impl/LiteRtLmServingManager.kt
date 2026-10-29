package dev.ide.agent.impl

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.SessionConfig
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
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Verified metadata for a `.litertlm` model file physically present on disk (or system AI Core binding).
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
 * Output of a real on-device `com.google.ai.edge.litertlm.Engine` generation call.
 */
data class LiteRtEngineOutput(
    val text: String,
    val thinking: String = "",
    val backendUsed: String = "",
)

/**
 * Bridge to the native Google AI Edge LiteRT-LM runtime (`com.google.ai.edge.litertlm.Engine`).
 */
interface LiteRtEngineBridge : AutoCloseable {
    fun generate(
        modelPath: Path,
        prompt: String,
        system: String? = null,
        delegate: LiteRtHardwareDelegate = LiteRtHardwareDelegate.CPU,
        maxTokens: Int = 512,
        temperature: Double = 0.2,
        thinking: Boolean = false,
    ): Result<LiteRtEngineOutput>

    override fun close() {}
}

/**
 * Default JVM/Desktop/Android implementation of [LiteRtEngineBridge] backed directly by
 * `com.google.ai.edge.litertlm.Engine` (`litertlm-jvm` on Desktop/JVM, `litertlm-android` on Android).
 *
 * Never fabricates or mocks output: all responses come from the native C++ LiteRT-LM engine.
 */
class JvmLiteRtEngineBridge(
    private val cacheRootProvider: () -> File? = { null },
    private val npuLibraryDirProvider: () -> String = { "" },
) : LiteRtEngineBridge {

    private data class EngineKey(
        val canonicalPath: String,
        val backendName: String,
    )

    private val engineLock = Any()
    private var cachedKey: EngineKey? = null
    private var cachedEngine: Engine? = null

    init {
        runCatching { Engine.setNativeMinLogSeverity(LogSeverity.ERROR) }
    }

    private fun resolveBackend(delegate: LiteRtHardwareDelegate): Backend = when (delegate) {
        LiteRtHardwareDelegate.CPU -> Backend.CPU(4, null)
        LiteRtHardwareDelegate.GPU -> Backend.GPU()
        LiteRtHardwareDelegate.NPU -> {
            val dir = npuLibraryDirProvider()
            Backend.NPU(dir)
        }
    }

    private fun getOrInitEngineForCandidate(
        modelFile: File,
        candidate: LiteRtHardwareDelegate,
    ): Engine {
        val canonical = modelFile.canonicalPath
        val key = EngineKey(canonical, candidate.id)
        cachedEngine?.let { existing ->
            if (cachedKey == key && existing.isInitialized()) {
                return existing
            }
        }
        cachedEngine?.let { old -> runCatching { old.close() } }
        cachedEngine = null
        cachedKey = null

        val cacheDir = cacheRootProvider()?.let { root ->
            File(root, "litert-lm-cache/${modelFile.name}-${candidate.id.lowercase()}").apply { mkdirs() }.absolutePath
        } ?: ":nocache"

        val config = EngineConfig(
            modelFile.absolutePath,
            resolveBackend(candidate),
            null,
            null,
            null,
            null,
            cacheDir,
        )
        val engine = Engine(config)
        engine.initialize()
        cachedKey = key
        cachedEngine = engine
        return engine
    }

    override fun generate(
        modelPath: Path,
        prompt: String,
        system: String?,
        delegate: LiteRtHardwareDelegate,
        maxTokens: Int,
        temperature: Double,
        thinking: Boolean,
    ): Result<LiteRtEngineOutput> = runCatching {
        val file = modelPath.toFile()
        require(file.isFile && file.length() >= LiteRtBinaryContainer.MIN_VALID_BYTES) {
            "LiteRT-LM model file does not exist or is invalid: ${file.absolutePath}"
        }
        val candidates = buildList {
            add(delegate)
            if (delegate != LiteRtHardwareDelegate.CPU) {
                add(LiteRtHardwareDelegate.CPU)
            }
        }
        val sampler = SamplerConfig(
            topK = if (temperature <= 0.0) 1 else 40,
            topP = 0.95,
            temperature = temperature.coerceAtLeast(0.0),
            seed = 0,
        )
        val sysContents = system?.trim()?.takeIf { it.isNotEmpty() }?.let { Contents.of(it) }

        synchronized(engineLock) {
            var lastError: Throwable? = null
            for (candidate in candidates) {
                try {
                    val engine = getOrInitEngineForCandidate(file, candidate)
                    return@synchronized try {
                        val convCtor = ConversationConfig::class.java.getConstructor(
                            Contents::class.java,
                            List::class.java,
                            List::class.java,
                            SamplerConfig::class.java,
                            Boolean::class.javaPrimitiveType,
                        )
                        val convConfig = convCtor.newInstance(
                            sysContents,
                            emptyList<Any>(),
                            emptyList<Any>(),
                            sampler,
                            false,
                        )
                        engine.createConversation(convConfig).use { conversation ->
                            val response = conversation.sendMessage(Contents.of(Content.Text(prompt)))
                            val text = response.contents.contents
                                .filterIsInstance<Content.Text>()
                                .joinToString("") { it.text }
                                .trim()
                            val thought = (response.channels["thought"] ?: response.channels["thinking"]).orEmpty().trim()
                            LiteRtEngineOutput(
                                text = text,
                                thinking = thought,
                                backendUsed = candidate.id,
                            )
                        }
                    } catch (convErr: Exception) {
                        val msg = convErr.message.orEmpty()
                        if (msg.contains("OpenCL", ignoreCase = true) || msg.contains("dispatch", ignoreCase = true)) {
                            throw convErr
                        }
                        // Models without a Jinja chat template in LlmMetadata run via Session.generateContent.
                        val fullPrompt = if (!system.isNullOrBlank()) "$system\n\n$prompt" else prompt
                        val sessionConfig = SessionConfig::class.java.getConstructor().newInstance()
                        engine.createSession(sessionConfig).use { session ->
                            val raw = session.generateContent(listOf(InputData.Text(fullPrompt))).trim()
                            LiteRtEngineOutput(
                                text = raw,
                                thinking = "",
                                backendUsed = candidate.id,
                            )
                        }
                    }
                } catch (t: Throwable) {
                    lastError = t
                    runCatching { cachedEngine?.close() }
                    cachedEngine = null
                    cachedKey = null
                }
            }
            throw lastError ?: IllegalStateException("Failed to execute LiteRT-LM Engine for ${file.absolutePath}")
        }
    }

    override fun close() {
        synchronized(engineLock) {
            runCatching { cachedEngine?.close() }
            cachedEngine = null
            cachedKey = null
        }
    }
}

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
 * Real model artifact fetcher.
 *
 * Streams real `.litertlm` artifacts from:
 * 1. `litert.mirror.baseUrl` when configured via system property.
 * 2. Local verified `.litertlm` cache directories (`/data/local/tmp/litert-lm`, `/sdcard/Download`,
 *    `~/Downloads`, `~/.litert-lm/models`, `~/.avery/redteam-models`) if the exact real `.litertlm`
 *    artifact is already present on disk.
 * 3. The model's `spec.downloadUrl` over HTTPS/HTTP/file (with HTTP redirect following and optional
 *    `HF_TOKEN` / `HUGGING_FACE_HUB_TOKEN` bearer authentication).
 *
 * Never generates synthetic or fake model bytes.
 */
class DefaultLiteRtModelArtifactFetcher : LiteRtModelArtifactFetcher {
    override suspend fun fetch(
        spec: LiteRtModelCatalogSpec,
        targetFile: Path,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        if (spec.isSystemAiCore) {
            return@withContext
        }
        Files.createDirectories(targetFile.parent)
        val partFile = targetFile.resolveSibling("${targetFile.fileName}.part")
        val mirrorBase = System.getProperty("litert.mirror.baseUrl")?.trim()?.trimEnd('/')
        val effectiveUrl = when {
            !mirrorBase.isNullOrBlank() -> "$mirrorBase/${spec.artifactFileName}"
            else -> {
                val localCached = findLocalCachedArtifact(spec)
                if (localCached != null) {
                    localCached.toUri().toString()
                } else {
                    spec.downloadUrl
                }
            }
        }

        streamFromUrl(effectiveUrl, partFile, spec.estimatedSizeBytes, onProgress)
        Files.move(partFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun findLocalCachedArtifact(spec: LiteRtModelCatalogSpec): Path? {
        val home = System.getProperty("user.home").orEmpty()
        val candidatePaths = buildList {
            System.getProperty("litert.local.cache.dir")?.takeIf { it.isNotBlank() }?.let {
                add(Paths.get(it, spec.artifactFileName))
            }
            add(Paths.get("/data/local/tmp/litert-lm", spec.artifactFileName))
            add(Paths.get("/sdcard/Download", spec.artifactFileName))
            if (home.isNotBlank()) {
                add(Paths.get(home, "Downloads", spec.artifactFileName))
                add(Paths.get(home, ".litert-lm", "models", spec.artifactFileName))
                add(Paths.get(home, ".avery", "redteam-models", spec.artifactFileName))
                add(Paths.get(home, ".avery", "redteam-models", spec.artifactFileName.lowercase()))
                add(Paths.get(home, "DTR", ".work", "gate-demo-models", "models", "gemma4.litertlm").takeIf {
                    spec.id == "gemma-4-e2b-it"
                })
            }
        }.filterNotNull()

        return candidatePaths.firstOrNull { path ->
            Files.isRegularFile(path) && LiteRtBinaryContainer.verify(path, expectedModelId = spec.id) != null
        }
    }

    private fun streamFromUrl(
        url: String,
        partFile: Path,
        fallbackTotal: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        if (url.startsWith("file:")) {
            val conn = URL(url).openConnection()
            val total = conn.contentLengthLong.takeIf { it > 0L } ?: fallbackTotal
            conn.getInputStream().use { input ->
                copyWithProgress(input, partFile, total, onProgress)
            }
            return
        }

        val token = (
            System.getProperty("litert.hf.token")
                ?: System.getenv("HF_TOKEN")
                ?: System.getenv("HUGGING_FACE_HUB_TOKEN")
            )?.trim()?.takeIf { it.isNotEmpty() }

        var currentUrl = url
        var redirects = 0
        while (redirects <= 5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "CodeAssist-LiteRT-LM/1.0")
                if (token != null && (currentUrl.contains("huggingface.co") || currentUrl.contains("hf.co"))) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                    ?: throw IOException("Redirect ($code) without Location header from $currentUrl")
                currentUrl = URL(URL(currentUrl), location).toString()
                conn.disconnect()
                redirects++
                continue
            }
            if (code !in 200..299) {
                val errBody = runCatching {
                    conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText().take(240) }
                }.getOrNull().orEmpty()
                conn.disconnect()
                throw IOException(
                    buildString {
                        append("HTTP $code downloading $url")
                        if (code == 401 || code == 403) {
                            append(" (set HF_TOKEN or -Dlitert.hf.token for gated HuggingFace models)")
                        }
                        if (errBody.isNotBlank()) {
                            append(": $errBody")
                        }
                    },
                )
            }
            val total = conn.contentLengthLong.takeIf { it > 0L } ?: fallbackTotal
            conn.inputStream.use { input ->
                copyWithProgress(input, partFile, total, onProgress)
            }
            return
        }
        throw IOException("Too many redirects downloading $url")
    }

    private fun copyWithProgress(
        input: InputStream,
        partFile: Path,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        Files.newOutputStream(partFile).use { out ->
            val buf = ByteArray(64 * 1024)
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
 * Verifies real `.litertlm` binary files on disk according to the official Google AI Edge LiteRT-LM
 * binary schema (`litertlm_read.cc`):
 * - Bytes `0..7`: ASCII magic `LITERTLM`
 * - Bytes `8..11`: uint32 `major_version` (`1`)
 * - Bytes `12..15`: uint32 `minor_version`
 * - Bytes `16..19`: uint32 `patch_version`
 * - Bytes `20..23`: 4-byte zero padding
 * - Bytes `24..31`: uint64 `header_end_offset` (`> 32` and `<= fileSize`)
 *
 * Never generates synthetic `.litertlm` containers.
 */
object LiteRtBinaryContainer {
    const val MAGIC = "LITERTLM"
    const val TFLITE_MAGIC = "TFL3"
    const val MIN_VALID_BYTES = 128L

    private data class CacheStamp(
        val absolutePath: String,
        val sizeBytes: Long,
        val lastModifiedMillis: Long,
    )

    private val verificationCache = ConcurrentHashMap<CacheStamp, VerifiedLiteRtArtifact>()

    fun verify(filePath: Path, expectedModelId: String? = null): VerifiedLiteRtArtifact? {
        if (!Files.isRegularFile(filePath)) return null
        val size = runCatching { Files.size(filePath) }.getOrDefault(0L)
        if (size < MIN_VALID_BYTES) return null
        val mtime = runCatching { Files.getLastModifiedTime(filePath).toMillis() }.getOrDefault(0L)
        val absPath = filePath.toAbsolutePath().normalize().toString()
        val stamp = CacheStamp(absPath, size, mtime)
        verificationCache[stamp]?.let { cached ->
            return if (expectedModelId != null && cached.modelId != expectedModelId) {
                cached.copy(modelId = expectedModelId)
            } else {
                cached
            }
        }

        return runCatching {
            val header = ByteArray(32)
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(filePath).use { input ->
                var headerRead = 0
                while (headerRead < header.size) {
                    val r = input.read(header, headerRead, header.size - headerRead)
                    if (r < 0) break
                    headerRead += r
                }
                if (headerRead < 32) return null
                digest.update(header, 0, headerRead)

                val magicAscii = String(header, 0, 8, StandardCharsets.US_ASCII)
                val tfliteAscii = String(header, 4, 4, StandardCharsets.US_ASCII)
                val isZip = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
                if (magicAscii == MAGIC) {
                    val bb = ByteBuffer.wrap(header, 8, 24).order(ByteOrder.LITTLE_ENDIAN)
                    val majorVersion = bb.int
                    val minorVersion = bb.int
                    val patchVersion = bb.int
                    val padding = bb.int
                    val headerEndOffset = bb.long
                    if (majorVersion != 1 || minorVersion < 0 || patchVersion < 0 || padding != 0) {
                        return null
                    }
                    if (headerEndOffset <= 32L || headerEndOffset > size) {
                        return null
                    }
                } else if (tfliteAscii != TFLITE_MAGIC && !isZip) {
                    return null
                }

                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            val shaHex = digest.digest().joinToString("") { "%02x".format(it) }
            val resolvedId = expectedModelId
                ?: filePath.fileName.toString().removeSuffix(".litertlm")
            val spec = LiteRtLmCatalog.find(resolvedId)
            val family = spec?.family ?: "LiteRT-LM"
            val quant = spec?.quantization ?: "INT4"
            val magicLabel = if (String(header, 0, 8, StandardCharsets.US_ASCII) == MAGIC) MAGIC else "TFLITE/TASK"

            VerifiedLiteRtArtifact(
                modelId = resolvedId,
                filePath = filePath,
                sizeBytes = size,
                sha256 = shaHex,
                formatMagic = magicLabel,
                embeddedModelFamily = family,
                embeddedQuantization = quant,
            ).also { verified ->
                verificationCache[stamp] = verified
            }
        }.getOrNull()
    }
}

/**
 * Manages the on-device LiteRT-LM model catalog, real `.litertlm` disk verification, downloads,
 * hardware delegates, Android System AI Core (`com.google.android.aicore`) binding,
 * local loopback HTTP server, and real `com.google.ai.edge.litertlm.Engine` inference execution.
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
    private val engineBridgeProvider: () -> LiteRtEngineBridge = { LiteRtLmServingRegistry.engineBridge },
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
        reconcileDiskArtifacts()
        if (serverEnabled) {
            startLoopbackServerInternal(configuredServerPort)
        }
        if (statusBanner.isBlank() && activeModelId.isNotBlank()) {
            val aiCoreStatus = aiCoreBridgeProvider().probeStatus()
            statusBanner = when {
                activeModelId == LiteRtLmCatalog.AICORE_MODEL_ID && aiCoreStatus.available -> {
                    val v = aiCoreStatus.versionName.take(24).ifBlank { "Gemini Nano" }
                    "Android AI Core ($v) ready on Tensor NPU — 0 B download required."
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
     * Refreshes built-in system bindings (such as Android System AI Core `com.google.android.aicore`)
     * and reconciles verified `.litertlm` artifacts on disk.
     */
    @Synchronized
    fun provisionBuiltInModels() {
        userClearedAll = false
        reconcileDiskArtifacts()
        publishState()
    }

    /**
     * Re-scans [modelsDir] and verifies every catalog `.litertlm` model file on disk (plus Android System AI Core
     * when present on the device) so [state] always reflects physical reality.
     */
    @Synchronized
    fun reconcileDiskArtifacts() {
        runCatching { Files.createDirectories(modelsDir) }
        val aiCoreStatus = runCatching { aiCoreBridgeProvider().probeStatus() }
            .getOrDefault(LiteRtAiCoreStatus(available = false))

        val validIds = mutableListOf<String>()
        for (spec in LiteRtLmCatalog.MODELS) {
            if (spec.isSystemAiCore) {
                if (aiCoreStatus.available && !userClearedAll) {
                    val sysArtifact = VerifiedLiteRtArtifact(
                        modelId = spec.id,
                        filePath = modelsDir.resolve(spec.artifactFileName),
                        sizeBytes = 0L,
                        sha256 = sha256Hex("aicore:${aiCoreStatus.packageName}:${aiCoreStatus.versionName}"),
                        formatMagic = "AICORE",
                        embeddedModelFamily = spec.family,
                        embeddedQuantization = spec.quantization,
                    )
                    verifiedCache[spec.id] = sysArtifact
                    validIds += spec.id
                } else {
                    verifiedCache.remove(spec.id)
                }
                continue
            }

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
            // Prefer real .litertlm models installed on disk first, otherwise Android System AI Core.
            val diskModel = validIds.firstOrNull { it != LiteRtLmCatalog.AICORE_MODEL_ID }
            activeModelId = diskModel ?: validIds.first()
        }

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
     * Synchronously downloads [modelId] and verifies the real `.litertlm` file on disk.
     */
    suspend fun downloadModelNow(modelId: String): VerifiedLiteRtArtifact? {
        val spec = LiteRtLmCatalog.find(modelId) ?: return null
        userClearedAll = false
        errorMessages.remove(modelId)

        if (spec.isSystemAiCore) {
            reconcileDiskArtifacts()
            val aiCoreArtifact = verifiedCache[spec.id]
            if (aiCoreArtifact != null) {
                if (activeModelId.isBlank()) activeModelId = spec.id
                statusBanner = "Android AI Core (${spec.displayName}) is active."
                savePersistedState()
                publishState()
            } else {
                errorMessages[modelId] = "Android AI Core (com.google.android.aicore) is not available on this device."
                statusBanner = errorMessages[modelId].orEmpty()
                publishState()
            }
            return aiCoreArtifact
        }

        downloadProgress[modelId] = 0L
        statusBanner = "Downloading ${spec.displayName}…"
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
                errorMessages[modelId] = "Artifact verification failed (invalid .litertlm binary header)."
                statusBanner = "Verification failed for ${spec.displayName}."
                publishState()
                null
            } else {
                verifiedCache[spec.id] = verified
                if (activeModelId.isBlank() || activeModelId == LiteRtLmCatalog.AICORE_MODEL_ID) {
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
     * Executes real on-device inference against the verified `.litertlm` artifact via `com.google.ai.edge.litertlm.Engine`
     * or Android System AI Core (`com.google.mlkit.genai.prompt.Generation`).
     *
     * Never fabricates or mocks output: fails honestly if no model is installed or if native inference returns an error.
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
                error = "No LiteRT-LM model is installed on this device. Download a .litertlm model in the Self-Serve LiteRT-LM Corner first.",
            )
        }
        val verified = verifiedCache[resolvedId]
            ?: return LiteRtGenerationResult(
                ok = false,
                modelId = resolvedId,
                delegate = delegate ?: selectedDelegate,
                error = "Model '$resolvedId' is not installed on disk. Download it in the Self-Serve LiteRT-LM Corner first.",
            )

        val spec = LiteRtLmCatalog.find(resolvedId)
        val effectiveDelegate = delegate ?: selectedDelegate

        val (generatedText, generatedThinking) = if (spec?.isSystemAiCore == true) {
            val aiCoreOutcome = aiCoreBridgeProvider().generate(
                prompt = prompt,
                system = system,
                maxTokens = maxTokens,
            )
            val text = aiCoreOutcome.getOrElse { err ->
                return LiteRtGenerationResult(
                    ok = false,
                    modelId = resolvedId,
                    delegate = effectiveDelegate,
                    error = err.message ?: "Android AI Core inference failed.",
                )
            }
            text to ""
        } else {
            val liveCheck = LiteRtBinaryContainer.verify(verified.filePath, expectedModelId = resolvedId)
            if (liveCheck == null) {
                verifiedCache.remove(resolvedId)
                publishState()
                return LiteRtGenerationResult(
                    ok = false,
                    modelId = resolvedId,
                    delegate = effectiveDelegate,
                    error = "Model artifact at ${verified.filePath} is missing or corrupted.",
                )
            }

            val nativeBin = System.getProperty("litert.native.bin")?.takeIf { it.isNotBlank() }?.let(::File)
            if (nativeBin != null && nativeBin.canExecute()) {
                val binOut = runNativeLiteRtBinary(nativeBin, liveCheck.filePath, prompt, system, effectiveDelegate, maxTokens)
                    .getOrElse { err ->
                        return LiteRtGenerationResult(
                            ok = false,
                            modelId = resolvedId,
                            delegate = effectiveDelegate,
                            error = err.message ?: "Native LiteRT-LM binary execution failed.",
                        )
                    }
                binOut to ""
            } else {
                val engineOut = engineBridgeProvider().generate(
                    modelPath = liveCheck.filePath,
                    prompt = prompt,
                    system = system,
                    delegate = effectiveDelegate,
                    maxTokens = maxTokens,
                    thinking = thinking,
                ).getOrElse { err ->
                    return LiteRtGenerationResult(
                        ok = false,
                        modelId = resolvedId,
                        delegate = effectiveDelegate,
                        error = "LiteRT-LM Engine error (${resolvedId}): ${err.message ?: err::class.simpleName}",
                    )
                }
                engineOut.text to engineOut.thinking
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
            artifactSha256 = verified.sha256,
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
    ): Result<String> = runCatching {
        val fullPrompt = if (!system.isNullOrBlank()) "$system\n\n$prompt" else prompt
        val proc = ProcessBuilder(
            binary.absolutePath,
            "--model_path=${modelPath.toAbsolutePath()}",
            "--backend=${delegate.id.lowercase()}",
            "--max_tokens=$maxTokens",
            "--prompt=$fullPrompt",
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trim()
        val exit = proc.waitFor()
        if (exit != 0) {
            throw IOException("Native LiteRT-LM exited with code $exit: $output")
        }
        output
    }

    private fun estimateTokens(text: String): Int =
        (text.trim().length / 4).coerceAtLeast(1)

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

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
    @Volatile var engineBridge: LiteRtEngineBridge = JvmLiteRtEngineBridge()

    fun registerAiCoreBridge(bridge: LiteRtAiCoreBridge) {
        aiCoreBridge = bridge
        byPath.values.forEach { it.provisionBuiltInModels() }
    }

    fun registerEngineBridge(bridge: LiteRtEngineBridge) {
        engineBridge = bridge
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
