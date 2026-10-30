package dev.ide.android.agent

import android.content.Context
import dev.ide.android.workbench.runtime.AndroidRuntimeHost
import dev.ide.ui.backend.AgentService
import dev.ide.ui.backend.UiAgentChatState
import dev.ide.ui.backend.UiAgentConfig
import dev.ide.ui.backend.UiAgentMessage
import dev.ide.ui.backend.UiAgentModel
import dev.ide.ui.backend.UiAgentPermissionDecision
import dev.ide.ui.backend.UiAgentPermissionMode
import dev.ide.ui.backend.UiAgentPermissionRequest
import dev.ide.ui.backend.UiAgentProvider
import dev.ide.ui.backend.UiAgentRole
import dev.ide.ui.backend.UiAgentUsage
import dev.ide.ui.backend.UiLiteRtCornerState
import dev.ide.ui.backend.UiLiteRtDelegate
import dev.ide.ui.backend.UiLiteRtGuestLanguage
import dev.ide.ui.backend.UiLlmSwitchMode
import dev.ide.ui.backend.UiLlmSwitchProviderInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * [AgentService] backed by the vendored ZCode agent (upstream v3.15.1, `zcode.cjs`)
 * running in the app's bundled Node runtime. The Kotlin app is the host: it extracts
 * the bundle, writes ZCode's provider configuration from the AI settings, spawns one
 * headless turn per message (`-p ... --json`, resumed via `--resume`), and renders the
 * result into the existing chat state. ZCode owns the agent loop, memory, compaction,
 * sub-agents and dynamic workflows; Android-specific capabilities stay available to
 * the agent through the host's MCP/tools layer.
 */
class ZCodeAgentService(
    context: Context,
    private val workspaceRoot: () -> Path?,
    /** Reads the app's existing `settings.ai.*` preferences so keys/models configured for
     *  the previous engine carry over on first run. */
    private val legacyPreference: (String) -> String? = { null },
    /** Bound port of the host workspace MCP server (Kotlin-native tools), or null. */
    private val mcpPort: () -> Int? = { null },
    /** Optional host Kotlin [AgentService] delegate for Self-Serve LiteRT-LM Corner operations. */
    private val hostAgent: (() -> AgentService)? = null,
) : AgentService {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicLong(0)

    private val _chatState = MutableStateFlow(UiAgentChatState())
    override val chatState: StateFlow<UiAgentChatState> = _chatState.asStateFlow()
    private val _permissionRequest = MutableStateFlow<UiAgentPermissionRequest?>(null)
    override val permissionRequest: StateFlow<UiAgentPermissionRequest?> = _permissionRequest.asStateFlow()
    private val _models = MutableStateFlow<List<UiAgentModel>>(emptyList())
    override val models: StateFlow<List<UiAgentModel>> = _models.asStateFlow()
    override val liteRtCornerState: StateFlow<UiLiteRtCornerState>
        get() = hostAgent?.invoke()?.liteRtCornerState ?: MutableStateFlow(UiLiteRtCornerState())

    override fun liteRtCorner(): UiLiteRtCornerState =
        hostAgent?.invoke()?.liteRtCorner() ?: UiLiteRtCornerState()

    override fun selectLiteRtDelegate(delegate: UiLiteRtDelegate) {
        hostAgent?.invoke()?.selectLiteRtDelegate(delegate)
    }

    override fun downloadLiteRtModel(modelId: String) {
        hostAgent?.invoke()?.downloadLiteRtModel(modelId)
    }

    override fun cancelLiteRtDownload(modelId: String) {
        hostAgent?.invoke()?.cancelLiteRtDownload(modelId)
    }

    override fun deleteLiteRtModel(modelId: String) {
        hostAgent?.invoke()?.deleteLiteRtModel(modelId)
    }

    override fun activateLiteRtModel(modelId: String) {
        hostAgent?.invoke()?.activateLiteRtModel(modelId)
    }

    override fun useLiteRtInIdeAgent(modelId: String) {
        val host = hostAgent?.invoke()
        if (host != null) {
            host.useLiteRtInIdeAgent(modelId)
            val snap = host.liteRtCorner()
            setGateway(snap.server.openAiEndpoint.removeSuffix("/chat/completions"), modelId, "")
        }
    }

    override fun setLiteRtServerEnabled(enabled: Boolean) {
        hostAgent?.invoke()?.setLiteRtServerEnabled(enabled)
    }

    override fun setLiteRtServerPort(port: Int) {
        hostAgent?.invoke()?.setLiteRtServerPort(port)
    }

    override fun selectLiteRtGuestLanguage(language: UiLiteRtGuestLanguage) {
        hostAgent?.invoke()?.selectLiteRtGuestLanguage(language)
    }

    override fun runLiteRtSmokeTest(prompt: String) {
        hostAgent?.invoke()?.runLiteRtSmokeTest(prompt)
    }

    override fun scaffoldLiteRtGuestClient(language: UiLiteRtGuestLanguage): String =
        hostAgent?.invoke()?.scaffoldLiteRtGuestClient(language).orEmpty()

    override fun selectLlmSwitchMode(mode: UiLlmSwitchMode) {
        val host = hostAgent?.invoke() ?: return
        host.selectLlmSwitchMode(mode)
        val snap = host.liteRtCorner()
        if (mode != UiLlmSwitchMode.DIRECT && snap.server.running) {
            setGateway(snap.server.openAiEndpoint.removeSuffix("/chat/completions"), snap.activeModelId.ifBlank { modelId() }, "")
        }
    }

    override fun switchUniversalProvider(providerId: String) {
        val host = hostAgent?.invoke()
        if (host != null) {
            host.switchUniversalProvider(providerId)
            val snap = host.liteRtCorner()
            val active = snap.universalProviders.firstOrNull { it.id == snap.activeProviderId }
            if (active != null) {
                prefs.edit()
                    .putString(KEY_PROVIDER, active.id)
                    .putString(KEY_MODEL, active.defaultModel)
                    .putString(KEY_API_KEY, active.apiKey.ifBlank { "LOCAL_PROVIDER" })
                    .putString(KEY_BASE_URL, active.baseUrl)
                    .apply()
                writeProviderConfig()
                refreshModels()
            }
        } else {
            selectProvider(providerId)
        }
    }

    override fun saveUniversalProvider(input: UiLlmSwitchProviderInput) {
        hostAgent?.invoke()?.saveUniversalProvider(input)
        if (input.id == providerId()) {
            prefs.edit()
                .putString(KEY_MODEL, input.defaultModel)
                .putString(KEY_API_KEY, input.apiKey)
                .putString(KEY_BASE_URL, input.baseUrl)
                .apply()
            writeProviderConfig()
            refreshModels()
        }
    }

    override fun deleteUniversalProvider(providerId: String) {
        hostAgent?.invoke()?.deleteUniversalProvider(providerId)
    }

    override fun addPresetProvider(presetId: String, apiKey: String) {
        hostAgent?.invoke()?.addPresetProvider(presetId, apiKey)
    }

    override fun setAutoFailoverEnabled(enabled: Boolean) {
        hostAgent?.invoke()?.setAutoFailoverEnabled(enabled)
    }

    override fun setCircuitBreakerEnabled(enabled: Boolean) {
        hostAgent?.invoke()?.setCircuitBreakerEnabled(enabled)
    }

    override fun toggleProviderFailover(providerId: String, enabled: Boolean) {
        hostAgent?.invoke()?.toggleProviderFailover(providerId, enabled)
    }

    override fun moveProviderInFailoverQueue(providerId: String, delta: Int) {
        hostAgent?.invoke()?.moveProviderInFailoverQueue(providerId, delta)
    }

    override fun toggleProviderAggregation(providerId: String, enabled: Boolean) {
        hostAgent?.invoke()?.toggleProviderAggregation(providerId, enabled)
    }

    override fun resetProviderCircuitBreaker(providerId: String) {
        hostAgent?.invoke()?.resetProviderCircuitBreaker(providerId)
    }

    override fun probeProviderHealth(providerId: String) {
        hostAgent?.invoke()?.probeProviderHealth(providerId)
    }

    override fun syncLiveToolConfigs() {
        hostAgent?.invoke()?.syncLiveToolConfigs()
    }

    @Volatile private var job: Job? = null
    @Volatile private var sessionId: String? = prefs.getString(KEY_SESSION, null)
    @Volatile private var lastPrompt: String? = null

    init {
        // First run: carry over the provider/key/model the user already configured
        // for the previous agent engine, so switching engines loses nothing.
        if (!prefs.contains(KEY_API_KEY)) {
            val provider = legacyPreference("provider") ?: "openai"
            val keyField = when (provider) {
                "antigravity" -> "antigravityKey"
                "openai" -> "openaiKey"
                "muse" -> "museKey"
                "mimo" -> "mimoKey"
                "gateway" -> "gatewayKey"
                else -> "${provider}Key"
            }
            val edit = prefs.edit().putString(KEY_PROVIDER, provider)
            legacyPreference(keyField)?.let { edit.putString(KEY_API_KEY, it) }
            (legacyPreference("model.$provider") ?: legacyPreference("model"))?.let {
                edit.putString(KEY_MODEL, it)
            }
            legacyPreference("gatewayBaseUrl")?.let { edit.putString(KEY_BASE_URL, it) }
            edit.apply()
            writeProviderConfig()
        }
        refreshModels()
    }

    // --- configuration (persisted; written into ZCode's provider_config.json) ---

    private fun providerId(): String = prefs.getString(KEY_PROVIDER, "openai") ?: "openai"
    private fun modelId(): String = prefs.getString(KEY_MODEL, "") ?: ""
    private fun apiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""
    private fun mode(): UiAgentPermissionMode =
        runCatching { UiAgentPermissionMode.valueOf(prefs.getString(KEY_MODE, "AUTO_ACCEPT") ?: "AUTO_ACCEPT") }
            .getOrDefault(UiAgentPermissionMode.AUTO_ACCEPT)

    override fun config(): UiAgentConfig {
        val provider = UiAgentProvider(
            id = providerId(),
            displayName = providerId().replaceFirstChar { it.uppercase() },
            models = _models.value,
            defaultModel = modelId(),
            apiKey = apiKey(),
        )
        return UiAgentConfig(
            providers = listOf(provider),
            selectedProvider = providerId(),
            model = modelId(),
            configured = apiKey().isNotBlank() && modelId().isNotBlank(),
            mode = mode(),
            effort = prefs.getString(KEY_EFFORT, "default") ?: "default",
        )
    }

    override fun refreshModels() {
        _models.value = listOfNotNull(modelId().takeIf { it.isNotBlank() }?.let { UiAgentModel(it, it) })
    }

    override fun setModel(model: String) {
        prefs.edit().putString(KEY_MODEL, model).apply()
        writeProviderConfig()
        refreshModels()
    }

    override fun setEffort(effort: String) {
        prefs.edit().putString(KEY_EFFORT, effort).apply()
    }

    override fun selectProvider(id: String) {
        prefs.edit().putString(KEY_PROVIDER, id).apply()
        writeProviderConfig()
    }

    override fun setProviderKey(providerId: String, key: String) {
        prefs.edit().putString(KEY_PROVIDER, providerId).putString(KEY_API_KEY, key).apply()
        writeProviderConfig()
    }

    override fun setGateway(baseUrl: String, model: String, caCert: String) {
        prefs.edit()
            .putString(KEY_PROVIDER, "gateway")
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_MODEL, model)
            .apply()
        writeProviderConfig()
    }

    override fun setPermissionMode(mode: UiAgentPermissionMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    override fun answerPermission(id: Int, decision: UiAgentPermissionDecision) {
        // Headless turns cannot surface per-call prompts; the ZCode permission mode
        // (plan/edit/yolo) is the gate. Nothing is pending by construction.
        _permissionRequest.value = null
    }

    // --- chat ---

    override fun send(text: String) {
        if (text.isBlank() || _chatState.value.busy) return
        lastPrompt = text
        val user = UiAgentMessage(id = ids.incrementAndGet(), role = UiAgentRole.USER, text = text)
        _chatState.update { it.copy(messages = it.messages + user, busy = true) }
        job = scope.launch { runTurn(text) }
    }

    override fun retry() {
        val prompt = lastPrompt ?: return
        if (_chatState.value.busy) return
        _chatState.update { it.copy(busy = true) }
        job = scope.launch { runTurn(prompt) }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _chatState.update { it.copy(busy = false) }
    }

    override fun newSession() {
        stop()
        sessionId = null
        prefs.edit().remove(KEY_SESSION).apply()
        _chatState.value = UiAgentChatState()
    }

    private suspend fun runTurn(prompt: String) {
        try {
            val result = executeTurn(prompt)
            sessionId = result.sessionId ?: sessionId
            sessionId?.let { prefs.edit().putString(KEY_SESSION, it).apply() }
            val assistant = UiAgentMessage(
                id = ids.incrementAndGet(),
                role = UiAgentRole.ASSISTANT,
                text = result.response,
                usage = result.usage,
            )
            _chatState.update {
                it.copy(messages = it.messages + assistant, busy = false, sessionId = sessionId)
            }
        } catch (e: Exception) {
            val error = UiAgentMessage(
                id = ids.incrementAndGet(),
                role = UiAgentRole.ASSISTANT,
                text = e.message ?: "ZCode agent failed.",
                isError = true,
                canRetry = true,
            )
            _chatState.update { it.copy(messages = it.messages + error, busy = false) }
        }
    }

    private data class TurnResult(val response: String, val sessionId: String?, val usage: UiAgentUsage?)

    private suspend fun executeTurn(prompt: String): TurnResult = withContext(Dispatchers.IO) {
        val bundleDir = extractBundle()
        writeProviderConfig()
        val host = AndroidRuntimeHost(appContext)
        val installation = host.prepare()
        val node = installation.node ?: error("Bundled Node runtime is unavailable.")
        val workDir = workspaceRoot() ?: appContext.filesDir.toPath().resolve("zcode-workspace")
        Files.createDirectories(workDir)
        writeWorkspaceMcpConfig(workDir)
        val args = buildList {
            add(node.toString())
            add(bundleDir.resolve("zcode.cjs").toString())
            add("-p"); add(prompt)
            add("--json")
            add("--cwd"); add(workDir.toString())
            add("--mode"); add(
                when (mode()) {
                    UiAgentPermissionMode.ASK_EACH -> "edit"
                    UiAgentPermissionMode.AUTO_ACCEPT -> "yolo"
                    UiAgentPermissionMode.PLAN_ONLY -> "plan"
                },
            )
            add("--workflow-mode"); add("onDemand")
            sessionId?.let { add("--resume"); add(it) }
        }
        val child = host.start(args, workDir, installation.environment)
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread { child.stdout.bufferedReader().forEachLine { stdout.appendLine(it) } }
        val errThread = Thread { child.stderr.bufferedReader().forEachLine { stderr.appendLine(it) } }
        outThread.start(); errThread.start()
        val exit = child.awaitExit()
        outThread.join(5_000); errThread.join(5_000)
        val json = extractJsonObject(stdout.toString())
            ?: error("ZCode agent failed (exit $exit): ${stderr.toString().trim().ifBlank { stdout.toString().trim().take(500) }}")
        val response = json.optString("response")
        if (response.isBlank()) error("ZCode agent returned an empty response.")
        val usageObj = json.optJSONObject("usage")
        TurnResult(
            response = response,
            sessionId = json.optString("sessionId").ifBlank { null },
            usage = usageObj?.let {
                UiAgentUsage(
                    input = it.optInt("inputTokens"),
                    output = it.optInt("outputTokens"),
                    cacheRead = it.optInt("cacheReadTokens"),
                    cacheWrite = it.optInt("cacheWriteTokens"),
                )
            },
        )
    }

    /** The headless CLI prints a banner line before the JSON payload; take the last JSON object. */
    private fun extractJsonObject(output: String): JSONObject? {
        val start = output.lastIndexOf("\n{").takeIf { it >= 0 }?.plus(1) ?: output.indexOf('{')
        if (start < 0) return null
        return runCatching { JSONObject(output.substring(start)) }.getOrNull()
    }

    private fun bundleDir(): File = File(appContext.filesDir, "zcode-agent")

    private fun extractBundle(): Path {
        val dir = bundleDir()
        val versionFile = File(dir, "VERSION")
        if (versionFile.readTextOrNull() != BUNDLE_VERSION) {
            dir.deleteRecursively()
            dir.mkdirs()
            appContext.assets.open("zcode/zcode.cjs").use { input ->
                File(dir, "zcode.cjs").outputStream().use { input.copyTo(it) }
            }
            File(dir, "provider").mkdirs()
            appContext.assets.open("zcode/provider/zcode-builtin.json").use { input ->
                File(dir, "provider/zcode-builtin.json").outputStream().use { input.copyTo(it) }
            }
            versionFile.writeText(BUNDLE_VERSION)
        }
        return dir.toPath()
    }

    /**
     * Point the workspace's ZCode config at the host MCP server so the agent can call
     * Kotlin-native tools (clone_repository, run_project, semantic navigation, build).
     * Existing user config in `.zcode/config.json` is preserved; only the `building`
     * MCP entry is managed.
     */
    private fun writeWorkspaceMcpConfig(workDir: Path) {
        val port = mcpPort() ?: return
        runCatching {
            val configFile = workDir.resolve(".zcode/config.json")
            Files.createDirectories(configFile.parent)
            val root = if (Files.isRegularFile(configFile)) {
                runCatching { JSONObject(Files.readString(configFile)) }.getOrDefault(JSONObject())
            } else {
                JSONObject()
            }
            val mcp = root.optJSONObject("mcp") ?: JSONObject().also { root.put("mcp", it) }
            val servers = mcp.optJSONObject("servers") ?: JSONObject().also { mcp.put("servers", it) }
            servers.put(
                "building",
                JSONObject().put("type", "http").put("url", "http://127.0.0.1:$port/mcp"),
            )
            Files.writeString(configFile, root.toString())
        }
    }

    /** Write ZCode's provider registry from the AI settings the user entered in the app. */
    private fun writeProviderConfig() {
        if (apiKey().isBlank()) return
        val home = File(appContext.filesDir, "developer-runtime/home")
        val v2 = File(home, ".zcode/v2")
        v2.mkdirs()
        val isGateway = providerId() == "gateway"
        val provider = JSONObject()
            .put("providerId", if (isGateway) "gateway" else providerId())
            .put("providerName", if (isGateway) "Custom gateway" else providerId())
            .put(
                "config", JSONObject()
                    .put("group", "standard-personal")
                    .put("access", JSONObject().put("type", "api-key").put("apiKey", apiKey()))
                    .put("personalModelIds", JSONArray())
                    .put("modelOrder", JSONArray().put(modelId())),
            )
        if (isGateway) {
            provider.getJSONObject("config").put(
                "api",
                JSONObject()
                    .put("type", "openai-responses")
                    .put("baseUrl", prefs.getString(KEY_BASE_URL, "") ?: ""),
            )
        } else {
            provider.put("templateId", providerId())
        }
        val root = JSONObject()
            .put("schemaVersion", 1)
            .put(
                "config", JSONObject()
                    .put("providerOrder", JSONArray().put(provider.getString("providerId")))
                    .put(
                        "providerConfigRules",
                        JSONObject().put("providerRules", JSONArray().put(provider)),
                    )
                    .put(
                        "modelConfigRules",
                        JSONObject().put("providerModelRules", JSONArray()).put("manualProviderModelRules", JSONArray()),
                    ),
            )
        File(v2, "provider_config.json").writeText(root.toString())
    }

    private fun File.readTextOrNull(): String? = runCatching { if (isFile) readText() else null }.getOrNull()

    private companion object {
        const val PREFS = "zcode-agent"
        const val BUNDLE_VERSION = "0.17.0-v3.15.1"
        const val KEY_PROVIDER = "provider"
        const val KEY_MODEL = "model"
        const val KEY_API_KEY = "apiKey"
        const val KEY_BASE_URL = "baseUrl"
        const val KEY_MODE = "mode"
        const val KEY_EFFORT = "effort"
        const val KEY_SESSION = "sessionId"
    }
}
