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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import dev.ide.agent.LlmClient
import dev.ide.agent.LlmModelInfo
import dev.ide.agent.ProviderConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
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
    val switchMode: CcSwitchOperatingMode = CcSwitchOperatingMode.DIRECT,
    val activeProviderId: String = LiteRtLmCatalog.PROVIDER_ID,
    val autoFailoverEnabled: Boolean = true,
    val circuitBreakerEnabled: Boolean = true,
    val providerProfiles: List<CcSwitchProviderProfile> = emptyList(),
    val circuitBreakers: Map<String, CcSwitchCircuitBreakerSnapshot> = emptyMap(),
    val aggregatedModels: List<LlmModelInfo> = emptyList(),
    val liveConfigTargets: List<CcSwitchLiveConfigTargetStatus> = emptyList(),
) {
    val openAiEndpoint: String
        get() = "http://127.0.0.1:$serverPort/v1/chat/completions"

    val anthropicEndpoint: String
        get() = "http://127.0.0.1:$serverPort/v1/messages"

    val responsesEndpoint: String
        get() = "http://127.0.0.1:$serverPort/v1/responses"

    val geminiEndpoint: String
        get() = "http://127.0.0.1:$serverPort/v1beta/models"

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
    private val transport: LlmTransport = OkHttpLlmTransport(),
    private val cliConfigRootProvider: () -> Path = { storageRoot.resolve("cli-configs") },
) : AutoCloseable {

    val modelsDir: Path = storageRoot.resolve("models")
    private val manifestFile: Path = storageRoot.resolve("manifest.json")
    private val liveConfigSyncer = CcSwitchLiveConfigSyncer(storageRoot, cliConfigRootProvider)

    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val downloadProgress = ConcurrentHashMap<String, Long>()
    private val errorMessages = ConcurrentHashMap<String, String>()
    private val verifiedCache = ConcurrentHashMap<String, VerifiedLiteRtArtifact>()
    private val circuitBreakers = ConcurrentHashMap<String, CcSwitchCircuitBreaker>()
    private val providerProfilesMap = LinkedHashMap<String, CcSwitchProviderProfile>()

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

    @Volatile private var switchMode: CcSwitchOperatingMode = CcSwitchOperatingMode.DIRECT
    @Volatile private var activeProviderId: String = LiteRtLmCatalog.PROVIDER_ID
    @Volatile private var autoFailoverEnabled: Boolean = true
    @Volatile private var circuitBreakerEnabled: Boolean = true

    @Volatile private var localServer: LiteRtLmLocalServer? = null

    private val _state = MutableStateFlow(
        run {
            seedDefaultProviderProfiles()
            buildSnapshot()
        },
    )
    val state: StateFlow<LiteRtCornerSnapshot> = _state.asStateFlow()

    init {
        runCatching { Files.createDirectories(modelsDir) }
        seedDefaultProviderProfiles()
        loadPersistedState()
        reconcileDiskArtifacts()
        if (serverEnabled || switchMode != CcSwitchOperatingMode.DIRECT) {
            serverEnabled = true
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

    @Synchronized
    private fun seedDefaultProviderProfiles() {
        if (providerProfilesMap.isNotEmpty()) return
        CcSwitchPresetCatalog.PRESETS.forEachIndexed { idx, preset ->
            val inFailover = preset.defaultProviderId in setOf("anthropic", "openai", "gemini", LiteRtLmCatalog.PROVIDER_ID)
            val inStack = preset.defaultProviderId in setOf("anthropic", "openai", "gemini", "deepseek", LiteRtLmCatalog.PROVIDER_ID)
            val profile = CcSwitchPresetCatalog.toInitialProfile(
                preset = preset,
                priority = (idx + 1) * 10,
                inFailover = inFailover,
                inStack = inStack,
            )
            providerProfilesMap[profile.id] = profile
            circuitBreakers.computeIfAbsent(profile.id) { CcSwitchCircuitBreaker() }
        }
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
     * Runs a synchronous inference smoke test against the active provider (on-device LiteRT-LM when
     * `activeProviderId == "litert"` in Direct mode, or through the universal `cc-switch` router
     * when a cloud/gateway provider or Routing/Aggregation mode is active).
     */
    fun runSmokeTestNow(prompt: String): LiteRtGenerationResult {
        val cleanPrompt = prompt.trim().ifBlank { "Reply with a one-line status check from LiteRT-LM." }
        lastSmokeTestPrompt = cleanPrompt
        lastSmokeTestBusy = true
        publishState()
        return try {
            val useOnDeviceDirect = switchMode == CcSwitchOperatingMode.DIRECT &&
                (activeProviderId == LiteRtLmCatalog.PROVIDER_ID || activeProviderId.isBlank())
            if (useOnDeviceDirect) {
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
            } else {
                val activeProf = activeProviderProfile()
                val reqModel = if (switchMode == CcSwitchOperatingMode.AGGREGATION) {
                    CcSwitchStackCodec.encodeAggregatedModel(activeProf.providerKey, activeProf.defaultModel)
                } else {
                    activeProf.defaultModel
                }
                val routed = routeTurn(
                    CcSwitchProtocolBridge.ParsedInboundRequest(
                        inboundFormat = activeProf.apiFormat,
                        requestedModel = reqModel,
                        stream = false,
                        llmRequest = LlmRequest(
                            model = reqModel,
                            system = "You are a concise verification probe.",
                            messages = listOf(LlmMessage.user(cleanPrompt)),
                            maxTokens = 256,
                            thinking = false,
                        ),
                    ),
                )
                lastSmokeTestBusy = false
                if (routed.ok) {
                    lastSmokeTestResponse = routed.text
                    statusBanner = "Routed via ${routed.providerName} (${routed.resolvedModel}) in ${routed.latencyMs} ms."
                } else {
                    lastSmokeTestResponse = "Error: ${routed.error}"
                    statusBanner = routed.error
                }
                publishState()
                LiteRtGenerationResult(
                    ok = routed.ok,
                    modelId = routed.resolvedModel,
                    delegate = selectedDelegate,
                    text = routed.text,
                    thinking = routed.thinking,
                    promptTokens = routed.usage.promptTokens,
                    completionTokens = routed.usage.outputTokens,
                    latencyMs = routed.latencyMs,
                    error = routed.error,
                )
            }
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

    // -------------------------------------------------------------------------
    // Universal CC-Switch Provider Management, Routing, Failover & Aggregation
    // -------------------------------------------------------------------------

    @Synchronized
    fun activeProviderProfile(): CcSwitchProviderProfile {
        seedDefaultProviderProfiles()
        val existing = providerProfilesMap[activeProviderId]
            ?: providerProfilesMap[LiteRtLmCatalog.PROVIDER_ID]
            ?: providerProfilesMap.values.first()
        return if (existing.id == LiteRtLmCatalog.PROVIDER_ID && activeModelId.isNotBlank()) {
            existing.copy(defaultModel = activeModelId)
        } else {
            existing
        }
    }

    @Synchronized
    fun providerProfile(providerId: String): CcSwitchProviderProfile? {
        seedDefaultProviderProfiles()
        val p = providerProfilesMap[providerId] ?: return null
        return if (p.id == LiteRtLmCatalog.PROVIDER_ID && activeModelId.isNotBlank()) {
            p.copy(defaultModel = activeModelId)
        } else {
            p
        }
    }

    @Synchronized
    fun selectSwitchMode(mode: CcSwitchOperatingMode) {
        switchMode = mode
        if (mode != CcSwitchOperatingMode.DIRECT && !serverEnabled) {
            serverEnabled = true
            startLoopbackServerInternal(configuredServerPort)
        }
        val active = activeProviderProfile()
        val effectivePort = localServer?.boundPort?.takeIf { it > 0 } ?: configuredServerPort
        runCatching { liveConfigSyncer.syncAll(mode, active, effectivePort) }
        statusBanner = when (mode) {
            CcSwitchOperatingMode.DIRECT ->
                "Switched to Direct mode (${active.name} · ${active.defaultModel})."
            CcSwitchOperatingMode.ROUTING ->
                "Routing mode active on 127.0.0.1:$effectivePort (Primary: ${active.name}, Failover: ${if (autoFailoverEnabled) "ON" else "OFF"})."
            CcSwitchOperatingMode.AGGREGATION ->
                "Aggregation (Stack) mode active on 127.0.0.1:$effectivePort (${computeAggregatedModels().size} stacked models)."
        }
        savePersistedState()
        publishState()
    }

    @Synchronized
    fun switchUniversalProvider(providerId: String): CcSwitchProviderProfile? {
        seedDefaultProviderProfiles()
        val target = providerProfilesMap[providerId] ?: run {
            val preset = CcSwitchPresetCatalog.findPreset(providerId) ?: return null
            val created = CcSwitchPresetCatalog.toInitialProfile(
                preset = preset,
                priority = (providerProfilesMap.size + 1) * 10,
                inFailover = true,
                inStack = true,
            )
            providerProfilesMap[created.id] = created
            circuitBreakers.computeIfAbsent(created.id) { CcSwitchCircuitBreaker() }
            created
        }
        activeProviderId = target.id
        if (target.id == LiteRtLmCatalog.PROVIDER_ID && activeModelId.isNotBlank()) {
            val updated = target.copy(defaultModel = activeModelId)
            providerProfilesMap[target.id] = updated
        }
        val effective = activeProviderProfile()
        val effectivePort = localServer?.boundPort?.takeIf { it > 0 } ?: configuredServerPort
        runCatching { liveConfigSyncer.syncAll(switchMode, effective, effectivePort) }
        statusBanner = "Switched active provider to ${effective.name} (${effective.defaultModel}) [${switchMode.displayName}]."
        savePersistedState()
        publishState()
        return effective
    }

    @Synchronized
    fun upsertProviderProfile(profile: CcSwitchProviderProfile): CcSwitchProviderProfile {
        seedDefaultProviderProfiles()
        val cleanId = profile.id.trim().ifBlank {
            CcSwitchStackCodec.deriveProviderKey("", profile.name, providerProfilesMap.keys)
        }
        val takenKeys = providerProfilesMap.values
            .filter { it.id != cleanId }
            .map { it.providerKey }
            .toSet()
        val validKey = if (CcSwitchStackCodec.isValidProviderKey(profile.providerKey) && profile.providerKey !in takenKeys) {
            profile.providerKey
        } else {
            CcSwitchStackCodec.deriveProviderKey(cleanId, profile.name, takenKeys)
        }
        val updatedModels = if (profile.models.isNotEmpty()) {
            if (profile.defaultModel.isNotBlank() && profile.models.none { it.id == profile.defaultModel }) {
                listOf(LlmModelInfo(profile.defaultModel, profile.defaultModel, supportsThinking = true)) + profile.models
            } else {
                profile.models
            }
        } else if (profile.defaultModel.isNotBlank()) {
            listOf(LlmModelInfo(profile.defaultModel, profile.defaultModel, supportsThinking = true))
        } else {
            emptyList()
        }
        val normalized = profile.copy(
            id = cleanId,
            providerKey = validKey,
            name = profile.name.trim().ifBlank { cleanId },
            baseUrl = profile.baseUrl.trim(),
            defaultModel = profile.defaultModel.trim(),
            models = updatedModels,
        )
        providerProfilesMap[cleanId] = normalized
        circuitBreakers.computeIfAbsent(cleanId) { CcSwitchCircuitBreaker() }
        statusBanner = "Saved provider profile ${normalized.name} (${normalized.apiFormat.displayName})."
        savePersistedState()
        publishState()
        return normalized
    }

    @Synchronized
    fun addPresetProvider(
        presetId: String,
        apiKey: String = "",
        baseUrl: String = "",
        defaultModel: String = "",
    ): CcSwitchProviderProfile? {
        seedDefaultProviderProfiles()
        val preset = CcSwitchPresetCatalog.findPreset(presetId) ?: return null
        val existing = providerProfilesMap[preset.defaultProviderId]
        val baseProfile = existing ?: CcSwitchPresetCatalog.toInitialProfile(
            preset = preset,
            priority = (providerProfilesMap.size + 1) * 10,
            inFailover = true,
            inStack = true,
        )
        val merged = baseProfile.copy(
            apiKey = apiKey.ifBlank { baseProfile.apiKey },
            baseUrl = baseUrl.ifBlank { baseProfile.baseUrl },
            defaultModel = defaultModel.ifBlank { baseProfile.defaultModel },
        )
        return upsertProviderProfile(merged)
    }

    @Synchronized
    fun deleteProviderProfile(providerId: String): Boolean {
        seedDefaultProviderProfiles()
        val target = providerProfilesMap[providerId] ?: return false
        if (providerId == activeProviderId || providerId == LiteRtLmCatalog.PROVIDER_ID) {
            statusBanner = "Cannot delete active or built-in on-device provider (${target.name}). Switch active provider first."
            publishState()
            return false
        }
        providerProfilesMap.remove(providerId)
        circuitBreakers.remove(providerId)
        statusBanner = "Removed provider profile ${target.name}."
        savePersistedState()
        publishState()
        return true
    }

    @Synchronized
    fun setAutoFailoverEnabled(enabled: Boolean) {
        autoFailoverEnabled = enabled
        statusBanner = "Automatic failover queue ${if (enabled) "enabled" else "disabled"}."
        savePersistedState()
        publishState()
    }

    @Synchronized
    fun setCircuitBreakerEnabled(enabled: Boolean) {
        circuitBreakerEnabled = enabled
        statusBanner = "Circuit breaker protection ${if (enabled) "enabled" else "disabled"}."
        savePersistedState()
        publishState()
    }

    @Synchronized
    fun toggleProviderFailover(providerId: String, enabled: Boolean) {
        seedDefaultProviderProfiles()
        val current = providerProfilesMap[providerId] ?: return
        providerProfilesMap[providerId] = current.copy(inFailoverQueue = enabled)
        statusBanner = "${current.name} ${if (enabled) "added to" else "removed from"} failover queue."
        savePersistedState()
        publishState()
    }

    @Synchronized
    fun moveProviderInFailoverQueue(providerId: String, delta: Int) {
        seedDefaultProviderProfiles()
        val queue = providerProfilesMap.values
            .filter { it.inFailoverQueue }
            .sortedBy { it.failoverPriority }
            .toMutableList()
        val idx = queue.indexOfFirst { it.id == providerId }
        if (idx < 0) return
        val newIdx = (idx + delta).coerceIn(0, queue.lastIndex)
        if (newIdx == idx) return
        val item = queue.removeAt(idx)
        queue.add(newIdx, item)
        queue.forEachIndexed { i, prof ->
            providerProfilesMap[prof.id] = prof.copy(failoverPriority = (i + 1) * 10)
        }
        statusBanner = "Updated failover priority for ${item.name} to P${newIdx + 1}."
        savePersistedState()
        publishState()
    }

    @Synchronized
    fun toggleProviderAggregation(providerId: String, enabled: Boolean) {
        seedDefaultProviderProfiles()
        val current = providerProfilesMap[providerId] ?: return
        providerProfilesMap[providerId] = current.copy(inAggregationStack = enabled)
        statusBanner = "${current.name} ${if (enabled) "added to" else "removed from"} Aggregation Stack."
        savePersistedState()
        publishState()
    }

    @Synchronized
    fun resetCircuitBreaker(providerId: String) {
        val cb = circuitBreakers.computeIfAbsent(providerId) { CcSwitchCircuitBreaker() }
        cb.reset()
        val name = providerProfilesMap[providerId]?.name ?: providerId
        statusBanner = "Circuit breaker reset to Closed (Healthy) for $name."
        publishState()
    }

    @Synchronized
    fun syncLiveToolConfigs(): List<CcSwitchLiveConfigTargetStatus> {
        val active = activeProviderProfile()
        val effectivePort = localServer?.boundPort?.takeIf { it > 0 } ?: configuredServerPort
        val targets = liveConfigSyncer.syncAll(switchMode, active, effectivePort)
        statusBanner = "Synced live CLI configs (Claude Code, Codex, Gemini CLI) in ${switchMode.displayName} mode."
        publishState()
        return targets
    }

    fun probeProviderHealthAsync(providerId: String) {
        scope.launch {
            probeProviderHealthNow(providerId)
        }
    }

    fun probeProviderHealthNow(providerId: String): CcSwitchRoutedTurnResult {
        val prof = providerProfile(providerId)
            ?: return CcSwitchRoutedTurnResult(
                ok = false,
                providerId = providerId,
                providerName = providerId,
                resolvedModel = "",
                apiFormat = CcSwitchApiFormat.OPENAI_CHAT,
                error = "Unknown provider '$providerId'.",
            )
        val result = executeSingleProviderTurn(
            profile = prof,
            resolvedModel = prof.defaultModel,
            request = LlmRequest(
                model = prof.defaultModel,
                system = "Reply with OK.",
                messages = listOf(LlmMessage.user("Ping")),
                maxTokens = 32,
                thinking = false,
            ),
            failoversAttempted = 0,
        )
        statusBanner = if (result.ok) {
            "Health check passed for ${prof.name} (${result.latencyMs} ms)."
        } else {
            "Health check failed for ${prof.name}: ${result.error}"
        }
        publishState()
        return result
    }

    @Synchronized
    fun computeAggregatedModels(): List<LlmModelInfo> {
        seedDefaultProviderProfiles()
        val out = mutableListOf<LlmModelInfo>()
        providerProfilesMap.values
            .filter { it.inAggregationStack }
            .forEach { prof ->
                val models = if (prof.id == LiteRtLmCatalog.PROVIDER_ID) {
                    val installed = verifiedCache.keys.mapNotNull { id ->
                        val spec = LiteRtLmCatalog.find(id)
                        LlmModelInfo(id, spec?.displayName ?: id, supportsThinking = spec?.supportsThinking ?: false)
                    }
                    installed.ifEmpty { prof.models.take(2) }
                } else {
                    prof.models.ifEmpty {
                        listOf(LlmModelInfo(prof.defaultModel, prof.defaultModel, supportsThinking = true))
                    }
                }
                models.forEach { m ->
                    val encoded = CcSwitchStackCodec.encodeAggregatedModel(prof.providerKey, m.id)
                    out += LlmModelInfo(
                        id = encoded,
                        displayName = "[${prof.name}] ${m.displayName}",
                        supportsThinking = m.supportsThinking,
                    )
                }
            }
        return out
    }

    /**
     * Routes an inbound request through the universal `cc-switch` control plane:
     * - In [CcSwitchOperatingMode.AGGREGATION]: resolves `ccs-<key>/<model>` (or `ccs-claude-<key>--<model>`)
     *   against stack-enabled providers; rejects malformed or unresolvable stack prefixes explicitly.
     * - In [CcSwitchOperatingMode.ROUTING] / [CcSwitchOperatingMode.DIRECT]: routes to the active provider
     *   first, and when [autoFailoverEnabled] is true, iterates through healthy failover queue candidates
     *   (`P1 -> P2 -> ...`) using each provider's [CcSwitchCircuitBreaker] and [CcSwitchModelMapping].
     */
    fun routeTurn(
        inbound: CcSwitchProtocolBridge.ParsedInboundRequest,
        forceProviderId: String? = null,
    ): CcSwitchRoutedTurnResult {
        seedDefaultProviderProfiles()
        val requestedModel = inbound.requestedModel.trim()

        if (forceProviderId != null) {
            val forced = providerProfile(forceProviderId)
                ?: return CcSwitchRoutedTurnResult(
                    ok = false,
                    providerId = forceProviderId,
                    providerName = forceProviderId,
                    resolvedModel = requestedModel,
                    apiFormat = inbound.inboundFormat,
                    error = "Provider '$forceProviderId' is not registered.",
                )
            val mappedModel = resolveTargetModelForProfile(forced, requestedModel, isPrimary = true)
            return executeSingleProviderTurn(forced, mappedModel, inbound.llmRequest.copy(model = mappedModel), 0)
        }

        val decodedStack = CcSwitchStackCodec.decodeAggregatedModel(requestedModel)
        if (switchMode == CcSwitchOperatingMode.AGGREGATION || decodedStack != null || CcSwitchStackCodec.hasStackPrefix(requestedModel)) {
            if (decodedStack != null) {
                val (providerKey, upstreamModel) = decodedStack
                val stackTarget = synchronized(this) {
                    providerProfilesMap.values.firstOrNull {
                        (it.providerKey.equals(providerKey, ignoreCase = true) || it.id.equals(providerKey, ignoreCase = true)) &&
                            (it.inAggregationStack || switchMode != CcSwitchOperatingMode.AGGREGATION)
                    }
                }
                if (stackTarget == null) {
                    val err = "Aggregation Stack error: provider '$providerKey' is not registered or not enabled in the Aggregation Stack."
                    statusBanner = err
                    publishState()
                    return CcSwitchRoutedTurnResult(
                        ok = false,
                        providerId = providerKey,
                        providerName = providerKey,
                        resolvedModel = upstreamModel,
                        apiFormat = inbound.inboundFormat,
                        error = err,
                    )
                }
                val effectiveTarget = if (stackTarget.id == LiteRtLmCatalog.PROVIDER_ID && activeModelId.isNotBlank()) {
                    stackTarget.copy(defaultModel = activeModelId)
                } else {
                    stackTarget
                }
                return executeSingleProviderTurn(
                    profile = effectiveTarget,
                    resolvedModel = upstreamModel,
                    request = inbound.llmRequest.copy(model = upstreamModel),
                    failoversAttempted = 0,
                )
            } else if (CcSwitchStackCodec.hasStackPrefix(requestedModel)) {
                val err = "Aggregation Stack error: malformed stack model identifier '$requestedModel' (expected ccs-<provider>/<model>)."
                statusBanner = err
                publishState()
                return CcSwitchRoutedTurnResult(
                    ok = false,
                    providerId = "stack",
                    providerName = "Aggregation Stack",
                    resolvedModel = requestedModel,
                    apiFormat = inbound.inboundFormat,
                    error = err,
                )
            }
        }

        val candidates = buildRoutingCandidates()
        var lastFailure: CcSwitchRoutedTurnResult? = null
        var failovers = 0

        for ((index, candidate) in candidates.withIndex()) {
            val cb = circuitBreakers.computeIfAbsent(candidate.id) { CcSwitchCircuitBreaker() }
            val perm = cb.allowRequest(circuitBreakerEnabled)
            if (!perm.allowed) {
                if (candidates.size > 1) {
                    failovers++
                    continue
                }
                val err = "Circuit breaker is OPEN for ${candidate.name}. Reset the circuit breaker or enable failover."
                return CcSwitchRoutedTurnResult(
                    ok = false,
                    providerId = candidate.id,
                    providerName = candidate.name,
                    resolvedModel = candidate.defaultModel,
                    apiFormat = candidate.apiFormat,
                    failoversAttempted = failovers,
                    error = err,
                )
            }

            val mappedModel = resolveTargetModelForProfile(
                profile = candidate,
                requestedModel = requestedModel,
                isPrimary = index == 0,
            )
            val effectiveRequest = inbound.llmRequest.copy(
                model = mappedModel,
                effort = candidate.modelMapping.reasoningEffort.ifBlank { inbound.llmRequest.effort },
            )
            val outcome = executeSingleProviderTurn(
                profile = candidate,
                resolvedModel = mappedModel,
                request = effectiveRequest,
                failoversAttempted = failovers,
                existingPermit = perm,
            )
            if (outcome.ok) {
                if (failovers > 0) {
                    statusBanner = "Auto-failover succeeded on ${candidate.name} ($mappedModel) after $failovers failed attempt(s)."
                    publishState()
                }
                return outcome
            }
            lastFailure = outcome
            if (!autoFailoverEnabled || index == candidates.lastIndex) {
                break
            }
            failovers++
        }

        return lastFailure ?: CcSwitchRoutedTurnResult(
            ok = false,
            providerId = activeProviderId,
            providerName = activeProviderProfile().name,
            resolvedModel = requestedModel,
            apiFormat = inbound.inboundFormat,
            failoversAttempted = failovers,
            error = "No healthy provider available to serve request.",
        )
    }

    /**
     * Streams a turn through the universal `cc-switch` router for `AgentBackend` when `ROUTING`
     * or `AGGREGATION` mode is active, or for custom universal providers.
     */
    fun streamUniversalChat(request: LlmRequest, forceProviderId: String? = null): Flow<LlmStreamEvent> = flow {
        val inbound = CcSwitchProtocolBridge.ParsedInboundRequest(
            inboundFormat = activeProviderProfile().apiFormat,
            requestedModel = request.model,
            stream = true,
            llmRequest = request,
        )
        val result = routeTurn(inbound, forceProviderId = forceProviderId)
        if (!result.ok) {
            emit(LlmStreamEvent.Failed(result.error))
            return@flow
        }
        if (result.thinking.isNotBlank()) {
            emit(LlmStreamEvent.ThinkingDelta(result.thinking))
            emit(LlmStreamEvent.ThinkingCompleted(result.thinking, signature = "cc-switch-thinking"))
        }
        if (result.text.isNotEmpty()) {
            emit(LlmStreamEvent.TextDelta(result.text))
        }
        result.toolCalls.forEach { tc ->
            emit(LlmStreamEvent.ToolCallStarted(tc.id, tc.name))
            emit(LlmStreamEvent.ToolCallCompleted(tc.id, tc.name, tc.arguments, tc.signature))
        }
        emit(LlmStreamEvent.Usage(result.usage))
        emit(LlmStreamEvent.Completed(result.stopReason))
    }

    @Synchronized
    private fun buildRoutingCandidates(): List<CcSwitchProviderProfile> {
        val primary = activeProviderProfile()
        if (!autoFailoverEnabled) {
            return listOf(primary)
        }
        val hasOnDevice = verifiedCache.isNotEmpty()
        val queue = providerProfilesMap.values
            .filter { it.id != primary.id && it.inFailoverQueue && it.isConfigured(hasOnDevice) }
            .sortedBy { it.failoverPriority }
            .map { prof ->
                if (prof.id == LiteRtLmCatalog.PROVIDER_ID && activeModelId.isNotBlank()) {
                    prof.copy(defaultModel = activeModelId)
                } else {
                    prof
                }
            }
        return listOf(primary) + queue
    }

    private fun resolveTargetModelForProfile(
        profile: CcSwitchProviderProfile,
        requestedModel: String,
        isPrimary: Boolean,
    ): String {
        val cleaned = CcSwitchModelMapping.strip1mSuffix(requestedModel.trim())
        if (profile.id == LiteRtLmCatalog.PROVIDER_ID || profile.apiFormat == CcSwitchApiFormat.LITERT_ON_DEVICE) {
            if (cleaned.isNotBlank() && (verifiedCache.containsKey(cleaned) || LiteRtLmCatalog.find(cleaned) != null)) {
                return cleaned
            }
            return activeModelId.ifBlank { profile.defaultModel.ifBlank { LiteRtLmCatalog.DEFAULT_MODEL_ID } }
        }
        val mapped = profile.modelMapping.resolveModel(cleaned)
        if (!isPrimary) {
            val matchesProfileCatalog = profile.models.any { it.id.equals(mapped, ignoreCase = true) }
            return if (matchesProfileCatalog) mapped else profile.defaultModel.ifBlank { mapped }
        }
        if (cleaned.isNotBlank() && profile.models.any { it.id.equals(cleaned, ignoreCase = true) }) {
            return cleaned
        }
        return mapped.ifBlank { profile.defaultModel.ifBlank { cleaned } }
    }

    private fun executeSingleProviderTurn(
        profile: CcSwitchProviderProfile,
        resolvedModel: String,
        request: LlmRequest,
        failoversAttempted: Int,
        existingPermit: CcSwitchAcquirePermission? = null,
    ): CcSwitchRoutedTurnResult {
        val cb = circuitBreakers.computeIfAbsent(profile.id) { CcSwitchCircuitBreaker() }
        val perm = existingPermit ?: cb.allowRequest(circuitBreakerEnabled)
        if (!perm.allowed) {
            return CcSwitchRoutedTurnResult(
                ok = false,
                providerId = profile.id,
                providerName = profile.name,
                resolvedModel = resolvedModel,
                apiFormat = profile.apiFormat,
                failoversAttempted = failoversAttempted,
                error = "Circuit breaker is OPEN for ${profile.name}.",
            )
        }

        val startNs = System.nanoTime()
        return try {
            if (profile.apiFormat == CcSwitchApiFormat.LITERT_ON_DEVICE || profile.id == LiteRtLmCatalog.PROVIDER_ID) {
                val userPrompt = request.messages.lastOrNull { it.role == LlmRole.USER }
                    ?.content
                    ?.filterIsInstance<ContentPart.Text>()
                    ?.joinToString("\n") { it.text }
                    .orEmpty()
                    .ifBlank { "Hello" }
                val gen = generate(
                    prompt = userPrompt,
                    system = request.system,
                    modelId = resolvedModel.ifBlank { activeModelId },
                    delegate = selectedDelegate,
                    maxTokens = request.maxTokens,
                    thinking = request.thinking,
                )
                val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                if (!gen.ok) {
                    cb.recordFailure(perm.usedHalfOpenPermit, gen.error, elapsedMs, circuitBreakerEnabled)
                    publishState()
                    return CcSwitchRoutedTurnResult(
                        ok = false,
                        providerId = profile.id,
                        providerName = profile.name,
                        resolvedModel = gen.modelId.ifBlank { resolvedModel },
                        apiFormat = profile.apiFormat,
                        latencyMs = elapsedMs,
                        failoversAttempted = failoversAttempted,
                        error = gen.error,
                    )
                }
                cb.recordSuccess(perm.usedHalfOpenPermit, gen.latencyMs, circuitBreakerEnabled)
                publishState()
                return CcSwitchRoutedTurnResult(
                    ok = true,
                    providerId = profile.id,
                    providerName = profile.name,
                    resolvedModel = gen.modelId,
                    apiFormat = profile.apiFormat,
                    text = gen.text,
                    thinking = gen.thinking,
                    stopReason = StopReason.END_TURN,
                    usage = TokenUsage(inputTokens = gen.promptTokens, outputTokens = gen.completionTokens),
                    latencyMs = gen.latencyMs,
                    failoversAttempted = failoversAttempted,
                )
            }

            if (profile.requiresApiKey && profile.apiKey.isBlank()) {
                val err = "Provider ${profile.name} requires an API key. Configure one in the Universal LLM Corner."
                cb.recordFailure(perm.usedHalfOpenPermit, err, 1L, circuitBreakerEnabled)
                publishState()
                return CcSwitchRoutedTurnResult(
                    ok = false,
                    providerId = profile.id,
                    providerName = profile.name,
                    resolvedModel = resolvedModel,
                    apiFormat = profile.apiFormat,
                    failoversAttempted = failoversAttempted,
                    error = err,
                )
            }

            val llmClient = buildUpstreamLlmClient(profile)
            val textBuilder = StringBuilder()
            val thinkingBuilder = StringBuilder()
            val toolCalls = mutableListOf<ContentPart.ToolUse>()
            var usage = TokenUsage()
            var stopReason = StopReason.END_TURN
            var streamError: String? = null

            runBlocking {
                llmClient.chat(request.copy(model = resolvedModel)).collect { event ->
                    when (event) {
                        is LlmStreamEvent.TextDelta -> textBuilder.append(event.text)
                        is LlmStreamEvent.ThinkingDelta -> thinkingBuilder.append(event.text)
                        is LlmStreamEvent.ThinkingCompleted -> {
                            if (thinkingBuilder.isEmpty() && event.text.isNotEmpty()) {
                                thinkingBuilder.append(event.text)
                            }
                        }
                        is LlmStreamEvent.ToolCallCompleted -> {
                            toolCalls += ContentPart.ToolUse(event.id, event.name, event.arguments, event.signature)
                        }
                        is LlmStreamEvent.Usage -> usage = event.usage
                        is LlmStreamEvent.Completed -> stopReason = event.stopReason
                        is LlmStreamEvent.Failed -> streamError = event.message
                        else -> Unit
                    }
                }
            }

            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            if (streamError != null) {
                cb.recordFailure(perm.usedHalfOpenPermit, streamError!!, elapsedMs, circuitBreakerEnabled)
                publishState()
                return CcSwitchRoutedTurnResult(
                    ok = false,
                    providerId = profile.id,
                    providerName = profile.name,
                    resolvedModel = resolvedModel,
                    apiFormat = profile.apiFormat,
                    latencyMs = elapsedMs,
                    failoversAttempted = failoversAttempted,
                    error = streamError!!,
                )
            }

            cb.recordSuccess(perm.usedHalfOpenPermit, elapsedMs, circuitBreakerEnabled)
            requestsServedCounter.incrementAndGet()
            tokensGeneratedCounter.addAndGet(usage.outputTokens.toLong().coerceAtLeast(1L))
            lastLatencyMs = elapsedMs
            publishState()

            CcSwitchRoutedTurnResult(
                ok = true,
                providerId = profile.id,
                providerName = profile.name,
                resolvedModel = resolvedModel,
                apiFormat = profile.apiFormat,
                text = textBuilder.toString(),
                thinking = thinkingBuilder.toString(),
                toolCalls = toolCalls,
                stopReason = if (toolCalls.isNotEmpty()) StopReason.TOOL_USE else stopReason,
                usage = usage,
                latencyMs = elapsedMs,
                failoversAttempted = failoversAttempted,
            )
        } catch (t: Throwable) {
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            val err = t.message ?: "Upstream provider execution failed"
            cb.recordFailure(perm.usedHalfOpenPermit, err, elapsedMs, circuitBreakerEnabled)
            publishState()
            CcSwitchRoutedTurnResult(
                ok = false,
                providerId = profile.id,
                providerName = profile.name,
                resolvedModel = resolvedModel,
                apiFormat = profile.apiFormat,
                latencyMs = elapsedMs,
                failoversAttempted = failoversAttempted,
                error = err,
            )
        }
    }

    fun buildUpstreamLlmClient(profile: CcSwitchProviderProfile): LlmClient {
        val rawBase = profile.baseUrl.trim()
        val cfg = ProviderConfig(
            apiKey = profile.apiKey,
            baseUrl = rawBase.ifBlank { null },
            caCertificatePem = profile.caCertificatePem.ifBlank { null },
        )
        return when (profile.apiFormat) {
            CcSwitchApiFormat.LITERT_ON_DEVICE -> LlmClient { req -> streamChat(req) }
            CcSwitchApiFormat.ANTHROPIC_MESSAGES -> AnthropicProvider(transport).client(cfg)
            CcSwitchApiFormat.GEMINI_NATIVE -> {
                if (profile.id == "antigravity" || rawBase.contains("cloudcode-pa.googleapis.com", ignoreCase = true)) {
                    AntigravityProvider(transport).client(cfg)
                } else {
                    GeminiProvider(transport).client(cfg)
                }
            }
            CcSwitchApiFormat.OPENAI_RESPONSES -> {
                when {
                    profile.id == "muse" || rawBase.contains("meta.ai", ignoreCase = true) ->
                        MetaMuseProvider(transport).client(cfg)
                    else -> {
                        val responsesUrl = if (rawBase.endsWith("/responses", ignoreCase = true)) {
                            rawBase
                        } else {
                            "${OpenAiProvider.normalizeBase(rawBase) ?: OpenAiProvider.DEFAULT_BASE}/v1/responses"
                        }
                        OpenAiProvider(transport, includeEncryptedReasoning = false)
                            .client(cfg.copy(baseUrl = responsesUrl))
                    }
                }
            }
            CcSwitchApiFormat.OPENAI_CHAT -> {
                when {
                    profile.id == "openrouter" || rawBase.contains("openrouter.ai", ignoreCase = true) ->
                        OpenRouterProvider(transport).client(cfg)
                    profile.id == "mimo" || rawBase.contains("xiaomimimo.com", ignoreCase = true) ->
                        XiaomiMimoProvider(transport).client(cfg)
                    else ->
                        OpenAiProvider(transport, includeEncryptedReasoning = false).client(cfg)
                }
            }
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
        val profilesSnapshot = synchronized(this) {
            seedDefaultProviderProfiles()
            providerProfilesMap.values.map { p ->
                if (p.id == LiteRtLmCatalog.PROVIDER_ID && activeModelId.isNotBlank()) {
                    p.copy(defaultModel = activeModelId)
                } else {
                    p
                }
            }
        }
        val breakersSnapshot = profilesSnapshot.associate { p ->
            val cb = circuitBreakers.computeIfAbsent(p.id) { CcSwitchCircuitBreaker() }
            p.id to cb.snapshot()
        }
        val activeProf = profilesSnapshot.firstOrNull { it.id == activeProviderId }
            ?: profilesSnapshot.firstOrNull { it.id == LiteRtLmCatalog.PROVIDER_ID }
            ?: profilesSnapshot.first()
        val liveTargets = runCatching {
            liveConfigSyncer.inspectTargets(switchMode, activeProf, effectivePort)
        }.getOrDefault(emptyList())

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
            switchMode = switchMode,
            activeProviderId = activeProf.id,
            autoFailoverEnabled = autoFailoverEnabled,
            circuitBreakerEnabled = circuitBreakerEnabled,
            providerProfiles = profilesSnapshot,
            circuitBreakers = breakersSnapshot,
            aggregatedModels = computeAggregatedModels(),
            liveConfigTargets = liveTargets,
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

        val modeStr = preferenceReader("ccswitch.mode")
            ?: manifestObj?.get("switchMode").asStr()
        if (!modeStr.isNullOrBlank()) {
            switchMode = CcSwitchOperatingMode.fromId(modeStr)
        }

        val providerStr = preferenceReader("ccswitch.activeProvider")
            ?: manifestObj?.get("activeProviderId").asStr()
        if (!providerStr.isNullOrBlank()) {
            activeProviderId = providerStr
        }

        val failoverBool = preferenceReader("ccswitch.autoFailover")?.toBooleanStrictOrNull()
            ?: manifestObj?.get("autoFailoverEnabled").asBool()
        if (failoverBool != null) {
            autoFailoverEnabled = failoverBool
        }

        val cbBool = preferenceReader("ccswitch.circuitBreaker")?.toBooleanStrictOrNull()
            ?: manifestObj?.get("circuitBreakerEnabled").asBool()
        if (cbBool != null) {
            circuitBreakerEnabled = cbBool
        }

        manifestObj?.get("providerProfiles")?.asArr()?.forEach { el ->
            val obj = el.asObj() ?: return@forEach
            val id = obj["id"].asStr()?.takeIf { it.isNotBlank() } ?: return@forEach
            val existing = providerProfilesMap[id]
            val preset = CcSwitchPresetCatalog.findPreset(obj["presetId"].asStr() ?: id)
            val defaultModels = existing?.models ?: preset?.models ?: emptyList()
            val parsedModels = obj["models"].asArr()?.mapNotNull { mEl ->
                val mObj = mEl.asObj() ?: return@mapNotNull null
                val mId = mObj["id"].asStr() ?: return@mapNotNull null
                LlmModelInfo(
                    id = mId,
                    displayName = mObj["displayName"].asStr() ?: mId,
                    supportsThinking = mObj["supportsThinking"].asBool() ?: true,
                )
            }?.takeIf { it.isNotEmpty() } ?: defaultModels
            val mapObj = obj["modelMapping"].asObj()
            val mapping = if (mapObj != null) {
                CcSwitchModelMapping(
                    defaultModel = mapObj["defaultModel"].asStr().orEmpty(),
                    haikuModel = mapObj["haikuModel"].asStr().orEmpty(),
                    sonnetModel = mapObj["sonnetModel"].asStr().orEmpty(),
                    opusModel = mapObj["opusModel"].asStr().orEmpty(),
                    reasoningEffort = mapObj["reasoningEffort"].asStr().orEmpty(),
                )
            } else {
                existing?.modelMapping ?: preset?.modelMapping ?: CcSwitchModelMapping()
            }
            val loaded = CcSwitchProviderProfile(
                id = id,
                providerKey = obj["providerKey"].asStr()?.ifBlank { null } ?: existing?.providerKey ?: id,
                name = obj["name"].asStr()?.ifBlank { null } ?: existing?.name ?: id,
                presetId = obj["presetId"].asStr()?.ifBlank { null } ?: existing?.presetId ?: id,
                category = CcSwitchProviderCategory.fromId(obj["category"].asStr() ?: existing?.category?.id),
                apiFormat = CcSwitchApiFormat.fromId(obj["apiFormat"].asStr() ?: existing?.apiFormat?.id),
                baseUrl = obj["baseUrl"].asStr() ?: existing?.baseUrl.orEmpty(),
                endpointCandidates = obj["endpointCandidates"].asArr()?.mapNotNull { it.asStr() }
                    ?.takeIf { it.isNotEmpty() } ?: existing?.endpointCandidates.orEmpty(),
                apiKey = obj["apiKey"].asStr() ?: existing?.apiKey.orEmpty(),
                caCertificatePem = obj["caCertificatePem"].asStr() ?: existing?.caCertificatePem.orEmpty(),
                defaultModel = obj["defaultModel"].asStr()?.ifBlank { null } ?: existing?.defaultModel.orEmpty(),
                models = parsedModels,
                modelMapping = mapping,
                inFailoverQueue = obj["inFailoverQueue"].asBool() ?: existing?.inFailoverQueue ?: false,
                failoverPriority = obj["failoverPriority"].asInt() ?: existing?.failoverPriority ?: 100,
                inAggregationStack = obj["inAggregationStack"].asBool() ?: existing?.inAggregationStack ?: false,
                requiresApiKey = obj["requiresApiKey"].asBool() ?: existing?.requiresApiKey ?: true,
                websiteUrl = obj["websiteUrl"].asStr() ?: existing?.websiteUrl.orEmpty(),
                notes = obj["notes"].asStr() ?: existing?.notes.orEmpty(),
            )
            providerProfilesMap[id] = loaded
            circuitBreakers.computeIfAbsent(id) { CcSwitchCircuitBreaker() }
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
            preferenceWriter("ccswitch.mode", switchMode.id)
            preferenceWriter("ccswitch.activeProvider", activeProviderId)
            preferenceWriter("ccswitch.autoFailover", autoFailoverEnabled.toString())
            preferenceWriter("ccswitch.circuitBreaker", circuitBreakerEnabled.toString())
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
                put("switchMode", switchMode.id)
                put("activeProviderId", activeProviderId)
                put("autoFailoverEnabled", autoFailoverEnabled)
                put("circuitBreakerEnabled", circuitBreakerEnabled)
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
                put(
                    "providerProfiles",
                    buildJsonArray {
                        providerProfilesMap.values.forEach { p ->
                            add(
                                buildJsonObject {
                                    put("id", p.id)
                                    put("providerKey", p.providerKey)
                                    put("name", p.name)
                                    put("presetId", p.presetId)
                                    put("category", p.category.id)
                                    put("apiFormat", p.apiFormat.id)
                                    put("baseUrl", p.baseUrl)
                                    put(
                                        "endpointCandidates",
                                        buildJsonArray { p.endpointCandidates.forEach { add(it) } },
                                    )
                                    put("apiKey", p.apiKey)
                                    put("caCertificatePem", p.caCertificatePem)
                                    put("defaultModel", p.defaultModel)
                                    put(
                                        "models",
                                        buildJsonArray {
                                            p.models.forEach { m ->
                                                add(
                                                    buildJsonObject {
                                                        put("id", m.id)
                                                        put("displayName", m.displayName)
                                                        put("supportsThinking", m.supportsThinking)
                                                    },
                                                )
                                            }
                                        },
                                    )
                                    put(
                                        "modelMapping",
                                        buildJsonObject {
                                            put("defaultModel", p.modelMapping.defaultModel)
                                            put("haikuModel", p.modelMapping.haikuModel)
                                            put("sonnetModel", p.modelMapping.sonnetModel)
                                            put("opusModel", p.modelMapping.opusModel)
                                            put("reasoningEffort", p.modelMapping.reasoningEffort)
                                        },
                                    )
                                    put("inFailoverQueue", p.inFailoverQueue)
                                    put("failoverPriority", p.failoverPriority)
                                    put("inAggregationStack", p.inAggregationStack)
                                    put("requiresApiKey", p.requiresApiKey)
                                    put("websiteUrl", p.websiteUrl)
                                    put("notes", p.notes)
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
