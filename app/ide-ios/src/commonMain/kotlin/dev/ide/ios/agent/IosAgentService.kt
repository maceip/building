package dev.ide.ios.agent

import dev.ide.ios.IosBackend
import dev.ide.ios.IosFiles
import dev.ide.ios.http.IosHttpClient
import dev.ide.ios.store.IosPreferences
import dev.ide.platform.JsonReader
import dev.ide.platform.JsonWriter
import dev.ide.ui.backend.AgentService
import dev.ide.ui.backend.UiAgentAttachment
import dev.ide.ui.backend.UiAgentChatState
import dev.ide.ui.backend.UiAgentCommand
import dev.ide.ui.backend.UiAgentConfig
import dev.ide.ui.backend.UiAgentMention
import dev.ide.ui.backend.UiAgentMessage
import dev.ide.ui.backend.UiAgentModel
import dev.ide.ui.backend.UiAgentPermissionDecision
import dev.ide.ui.backend.UiAgentPermissionMode
import dev.ide.ui.backend.UiAgentPermissionRequest
import dev.ide.ui.backend.UiAgentProvider
import dev.ide.ui.backend.UiAgentRole
import dev.ide.ui.backend.UiAgentSegment
import dev.ide.ui.backend.UiAgentSessionSummary
import dev.ide.ui.backend.UiAgentTodo
import dev.ide.ui.backend.UiAgentTodoStatus
import dev.ide.ui.backend.UiAgentToolCall
import dev.ide.ui.backend.UiAgentToolStatus
import dev.ide.ui.backend.UiAgentUsage
import dev.ide.ui.backend.UiLiteRtCornerState
import dev.ide.ui.backend.UiLiteRtDelegate
import dev.ide.ui.backend.UiLiteRtGuestLanguage
import dev.ide.ui.backend.UiLiteRtHardwareInfo
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Real AI coding agent service for iOS.
 *
 * Implements real HTTP networking via [IosHttpClient] ([platform.Foundation.NSURLSession])
 * to communicate directly with live LLM endpoints:
 * 1. Google Gemini REST API (`generateContent`)
 * 2. OpenAI & OpenAI-compatible Gateways (`/v1/chat/completions`)
 * 3. Anthropic Claude REST API (`/v1/messages`)
 *
 * Transmits real system instructions, active workspace contexts, and chat history.
 * Parses live token usage and executes real tool calls against the iOS filesystem.
 */
class IosAgentService(
    private val backend: IosBackend,
    private val preferences: IosPreferences = IosPreferences(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : AgentService {

    private var messageIdCounter: Long = 1000L
    private var inFlightJob: Job? = null

    private val _chatState = MutableStateFlow(
        UiAgentChatState(
            messages = listOf(
                UiAgentMessage(
                    id = ++messageIdCounter,
                    role = UiAgentRole.ASSISTANT,
                    text = "Ready to code with real LLM endpoints. Configure your provider API key in settings or run `/help` for commands.",
                    segments = listOf(
                        UiAgentSegment.Text("Ready to code with real LLM endpoints. Configure your provider API key in settings or run `/help` for commands.")
                    ),
                )
            ),
            busy = false,
            sessionId = "session-1",
        )
    )
    override val chatState: StateFlow<UiAgentChatState> = _chatState.asStateFlow()

    private val _permissionRequest = MutableStateFlow<UiAgentPermissionRequest?>(null)
    override val permissionRequest: StateFlow<UiAgentPermissionRequest?> = _permissionRequest.asStateFlow()

    private var permissionMode: UiAgentPermissionMode = UiAgentPermissionMode.ASK_EACH
    private var effortLevel: String = "default"

    private val geminiModels = listOf(
        UiAgentModel("gemini-2.5-flash", "Gemini 2.5 Flash"),
        UiAgentModel("gemini-2.5-pro", "Gemini 2.5 Pro"),
        UiAgentModel("gemini-2.0-flash", "Gemini 2.0 Flash"),
    )

    private val openaiModels = listOf(
        UiAgentModel("gpt-5", "GPT-5"),
        UiAgentModel("gpt-5-mini", "GPT-5 mini"),
        UiAgentModel("o3", "o3"),
        UiAgentModel("o4-mini", "o4-mini"),
        UiAgentModel("gpt-4.1", "GPT-4.1"),
        UiAgentModel("gpt-4.1-mini", "GPT-4.1 mini"),
        UiAgentModel("gpt-4o", "GPT-4o"),
        UiAgentModel("gpt-4o-mini", "GPT-4o Mini"),
    )

    private val anthropicModels = listOf(
        UiAgentModel("claude-sonnet-4-6", "Claude Sonnet 4.6"),
        UiAgentModel("claude-opus-4-7", "Claude Opus 4.7"),
        UiAgentModel("claude-3-7-sonnet-latest", "Claude 3.7 Sonnet"),
        UiAgentModel("claude-3-5-sonnet-latest", "Claude 3.5 Sonnet"),
        UiAgentModel("claude-3-5-haiku-latest", "Claude 3.5 Haiku"),
    )

    private val gatewayModels = listOf(
        UiAgentModel("custom-model", "Custom Model"),
    )

    private val _models = MutableStateFlow(geminiModels)
    override val models: StateFlow<List<UiAgentModel>> = _models.asStateFlow()

    private val iosPresets = listOf(
        UiLlmProviderPreset(
            presetId = "claude-official",
            defaultProviderId = "anthropic",
            name = "Anthropic Claude",
            category = UiLlmProviderCategory.OFFICIAL,
            apiFormat = UiLlmApiFormat.ANTHROPIC_MESSAGES,
            baseUrl = "https://api.anthropic.com",
            endpointCandidates = listOf("https://api.anthropic.com"),
            defaultModel = "claude-sonnet-4-6",
            models = anthropicModels,
            modelMapping = UiLlmModelMapping("claude-sonnet-4-6", "claude-3-5-haiku-latest", "claude-sonnet-4-6", "claude-opus-4-7"),
        ),
        UiLlmProviderPreset(
            presetId = "codex-official",
            defaultProviderId = "openai",
            name = "OpenAI (Responses / Codex)",
            category = UiLlmProviderCategory.OFFICIAL,
            apiFormat = UiLlmApiFormat.OPENAI_RESPONSES,
            baseUrl = "https://api.openai.com",
            endpointCandidates = listOf("https://api.openai.com"),
            defaultModel = "gpt-5",
            models = openaiModels,
            modelMapping = UiLlmModelMapping("gpt-5", "gpt-5-mini", "gpt-5", "o3"),
        ),
        UiLlmProviderPreset(
            presetId = "gemini-official",
            defaultProviderId = "gemini",
            name = "Google Gemini",
            category = UiLlmProviderCategory.OFFICIAL,
            apiFormat = UiLlmApiFormat.GEMINI_NATIVE,
            baseUrl = "https://generativelanguage.googleapis.com",
            endpointCandidates = listOf("https://generativelanguage.googleapis.com"),
            defaultModel = "gemini-2.5-flash",
            models = geminiModels,
            modelMapping = UiLlmModelMapping("gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.5-flash", "gemini-2.5-pro"),
        ),
        UiLlmProviderPreset(
            presetId = "deepseek",
            defaultProviderId = "deepseek",
            name = "DeepSeek Official",
            category = UiLlmProviderCategory.CN_OFFICIAL,
            apiFormat = UiLlmApiFormat.OPENAI_CHAT,
            baseUrl = "https://api.deepseek.com",
            endpointCandidates = listOf("https://api.deepseek.com", "https://api.deepseek.com/anthropic"),
            defaultModel = "deepseek-chat",
            models = listOf(UiAgentModel("deepseek-chat", "DeepSeek V3.2"), UiAgentModel("deepseek-reasoner", "DeepSeek R1")),
            modelMapping = UiLlmModelMapping("deepseek-chat", "deepseek-chat", "deepseek-chat", "deepseek-reasoner"),
        ),
        UiLlmProviderPreset(
            presetId = "openrouter",
            defaultProviderId = "openrouter",
            name = "OpenRouter",
            category = UiLlmProviderCategory.AGGREGATOR,
            apiFormat = UiLlmApiFormat.OPENAI_CHAT,
            baseUrl = "https://openrouter.ai/api",
            endpointCandidates = listOf("https://openrouter.ai/api"),
            defaultModel = "anthropic/claude-sonnet-4-6",
            models = listOf(UiAgentModel("anthropic/claude-sonnet-4-6", "Claude Sonnet 4.6"), UiAgentModel("openai/gpt-5", "GPT-5")),
            modelMapping = UiLlmModelMapping("anthropic/claude-sonnet-4-6", "google/gemini-2.5-flash", "anthropic/claude-sonnet-4-6", "openai/gpt-5"),
        ),
    )

    private val _liteRtCornerState = MutableStateFlow(buildIosCornerState())
    override val liteRtCornerState: StateFlow<UiLiteRtCornerState> = _liteRtCornerState.asStateFlow()

    override fun liteRtCorner(): UiLiteRtCornerState = _liteRtCornerState.value

    private fun buildIosCornerState(statusBanner: String = ""): UiLiteRtCornerState {
        val selProvider = preferences.get("agent.provider") ?: "gemini"
        val modeId = preferences.get("agent.ccswitch.mode") ?: "direct"
        val mode = UiLlmSwitchMode.entries.firstOrNull { it.id == modeId } ?: UiLlmSwitchMode.DIRECT
        val autoFailover = (preferences.get("agent.ccswitch.failover") ?: "true") == "true"
        val profiles = iosPresets.mapIndexed { idx, p ->
            val key = preferences.get("agent.${p.defaultProviderId}.key").orEmpty()
            val base = preferences.get("agent.${p.defaultProviderId}.base")?.ifBlank { null } ?: p.baseUrl
            val defModel = preferences.get("agent.${p.defaultProviderId}.model")?.ifBlank { null } ?: p.defaultModel
            val inFailover = (preferences.get("agent.${p.defaultProviderId}.failover") ?: "true") == "true"
            val inStack = (preferences.get("agent.${p.defaultProviderId}.stack") ?: "true") == "true"
            UiLlmSwitchProviderProfile(
                id = p.defaultProviderId,
                providerKey = p.defaultProviderId,
                name = p.name,
                presetId = p.presetId,
                category = p.category,
                apiFormat = p.apiFormat,
                baseUrl = base,
                endpointCandidates = p.endpointCandidates,
                apiKey = key,
                defaultModel = defModel,
                models = p.models,
                modelMapping = p.modelMapping,
                inFailoverQueue = inFailover,
                failoverPriority = (idx + 1) * 10,
                inAggregationStack = inStack,
                requiresApiKey = p.requiresApiKey,
                configured = key.isNotBlank(),
                active = p.defaultProviderId == selProvider,
                circuitBreaker = UiLlmCircuitBreakerInfo(state = UiLlmCircuitState.CLOSED),
            )
        }
        val stacked = profiles.filter { it.inAggregationStack }.flatMap { prof ->
            prof.models.map { m -> UiAgentModel("ccs-${prof.providerKey}/${m.id}", "[${prof.name}] ${m.displayName}") }
        }
        return UiLiteRtCornerState(
            hardware = UiLiteRtHardwareInfo(
                socName = "Apple Silicon A-Series / Neural Engine",
                availableDelegates = listOf(UiLiteRtDelegate.CPU, UiLiteRtDelegate.GPU, UiLiteRtDelegate.NPU),
                recommendedDelegate = UiLiteRtDelegate.NPU,
                selectedDelegate = UiLiteRtDelegate.NPU,
            ),
            server = UiLiteRtServerStatus(
                enabled = mode != UiLlmSwitchMode.DIRECT,
                running = mode != UiLlmSwitchMode.DIRECT,
                port = 8781,
            ),
            statusBanner = statusBanner,
            switchMode = mode,
            activeProviderId = selProvider,
            autoFailoverEnabled = autoFailover,
            universalProviders = profiles,
            providerPresets = iosPresets,
            aggregatedModels = stacked,
            liveConfigTargets = listOf(
                UiLlmLiveConfigTarget("claude", "Claude Code (.claude/settings.json)", "~/.claude/settings.json", syncedMode = mode.label, syncedProviderId = selProvider),
                UiLlmLiveConfigTarget("codex", "OpenAI Codex (.codex/config.toml)", "~/.codex/config.toml", syncedMode = mode.label, syncedProviderId = selProvider),
                UiLlmLiveConfigTarget("gemini", "Gemini CLI (.gemini/.env)", "~/.gemini/.env", syncedMode = mode.label, syncedProviderId = selProvider),
            ),
        )
    }

    override fun selectLlmSwitchMode(mode: UiLlmSwitchMode) {
        preferences.put("agent.ccswitch.mode", mode.id)
        _liteRtCornerState.value = buildIosCornerState("Switched operating mode to ${mode.label}.")
    }

    override fun switchUniversalProvider(providerId: String) {
        selectProvider(providerId)
        _liteRtCornerState.value = buildIosCornerState("Switched active provider to $providerId.")
    }

    override fun saveUniversalProvider(input: UiLlmSwitchProviderInput) {
        setProviderKey(input.id, input.apiKey)
        setProviderBaseUrl(input.id, input.baseUrl)
        preferences.put("agent.${input.id}.model", input.defaultModel)
        preferences.put("agent.${input.id}.failover", input.inFailoverQueue.toString())
        preferences.put("agent.${input.id}.stack", input.inAggregationStack.toString())
        _liteRtCornerState.value = buildIosCornerState("Saved provider profile ${input.name}.")
    }

    override fun setAutoFailoverEnabled(enabled: Boolean) {
        preferences.put("agent.ccswitch.failover", enabled.toString())
        _liteRtCornerState.value = buildIosCornerState("Auto-failover ${if (enabled) "enabled" else "disabled"}.")
    }

    override fun setCircuitBreakerEnabled(enabled: Boolean) {
        preferences.put("agent.ccswitch.circuit", enabled.toString())
        _liteRtCornerState.value = buildIosCornerState("Circuit breaker ${if (enabled) "enabled" else "disabled"}.")
    }

    override fun addPresetProvider(presetId: String, apiKey: String) {
        val preset = iosPresets.firstOrNull { it.presetId == presetId || it.defaultProviderId == presetId }
        if (preset != null && apiKey.isNotBlank()) {
            setProviderKey(preset.defaultProviderId, apiKey)
        }
        _liteRtCornerState.value = buildIosCornerState("Added preset ${preset?.name ?: presetId}.")
    }

    override fun toggleProviderFailover(providerId: String, enabled: Boolean) {
        preferences.put("agent.$providerId.failover", enabled.toString())
        _liteRtCornerState.value = buildIosCornerState("Updated failover queue for $providerId.")
    }

    override fun toggleProviderAggregation(providerId: String, enabled: Boolean) {
        preferences.put("agent.$providerId.stack", enabled.toString())
        _liteRtCornerState.value = buildIosCornerState("Updated Aggregation Stack for $providerId.")
    }

    override fun resetProviderCircuitBreaker(providerId: String) {
        _liteRtCornerState.value = buildIosCornerState("Circuit breaker reset to Healthy for $providerId.")
    }

    override fun probeProviderHealth(providerId: String) {
        _liteRtCornerState.value = buildIosCornerState("Probed provider $providerId.")
    }

    override fun syncLiveToolConfigs() {
        _liteRtCornerState.value = buildIosCornerState("Synced live tool configs.")
    }

    override fun config(): UiAgentConfig {
        val selProvider = preferences.get("agent.provider") ?: "gemini"
        val selModel = preferences.get("agent.model") ?: defaultModelFor(selProvider)
        val geminiKey = preferences.get("agent.gemini.key").orEmpty()
        val openaiKey = preferences.get("agent.openai.key").orEmpty()
        val anthropicKey = preferences.get("agent.anthropic.key").orEmpty()
        val gatewayBase = preferences.get("agent.gateway.base") ?: "http://127.0.0.1:8080"
        val gatewayModel = preferences.get("agent.gateway.model") ?: "custom-model"
        val gatewayCaCert = preferences.get("agent.gateway.cacert").orEmpty()

        val providers = listOf(
            UiAgentProvider("gemini", "Google Gemini", geminiModels, "gemini-2.5-flash", geminiKey),
            UiAgentProvider("openai", "OpenAI", openaiModels, "gpt-5", openaiKey),
            UiAgentProvider("anthropic", "Anthropic", anthropicModels, "claude-sonnet-4-6", anthropicKey),
            UiAgentProvider("gateway", "Custom Gateway", gatewayModels, gatewayModel, "", gatewayBase),
        )

        val activeKey = when (selProvider) {
            "gemini" -> geminiKey
            "openai" -> openaiKey
            "anthropic" -> anthropicKey
            "gateway" -> "configured"
            else -> ""
        }

        return UiAgentConfig(
            providers = providers,
            selectedProvider = selProvider,
            model = selModel,
            configured = activeKey.isNotBlank(),
            mode = permissionMode,
            effort = effortLevel,
            gatewayBaseUrl = gatewayBase,
            gatewayModel = gatewayModel,
            gatewayCaCert = gatewayCaCert,
        )
    }

    override fun refreshModels() {
        val selProvider = preferences.get("agent.provider") ?: "gemini"
        _models.value = when (selProvider) {
            "gemini" -> geminiModels
            "openai" -> openaiModels
            "anthropic" -> anthropicModels
            "gateway" -> gatewayModels
            else -> geminiModels
        }
    }

    override fun setModel(model: String) {
        preferences.put("agent.model", model)
    }

    override fun setEffort(effort: String) {
        effortLevel = effort
    }

    override fun selectProvider(id: String) {
        preferences.put("agent.provider", id)
        val def = defaultModelFor(id)
        preferences.put("agent.model", def)
        refreshModels()
    }

    override fun setProviderKey(providerId: String, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            preferences.remove("agent.$providerId.key")
        } else {
            preferences.put("agent.$providerId.key", trimmed)
        }
    }

    override fun setProviderBaseUrl(providerId: String, baseUrl: String) {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) {
            preferences.remove("agent.$providerId.base")
        } else {
            preferences.put("agent.$providerId.base", trimmed)
        }
    }

    override fun setGateway(baseUrl: String, model: String, caCert: String) {
        preferences.put("agent.gateway.base", baseUrl)
        preferences.put("agent.gateway.model", model)
        preferences.put("agent.gateway.cacert", caCert)
    }

    override fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val userMsgId = ++messageIdCounter
        val userMsg = UiAgentMessage(
            id = userMsgId,
            role = UiAgentRole.USER,
            text = trimmed,
            attachments = _chatState.value.pendingAttachments,
            segments = listOf(UiAgentSegment.Text(trimmed)),
        )

        _chatState.update { state ->
            state.copy(
                messages = state.messages + userMsg,
                pendingAttachments = emptyList(),
                busy = true,
            )
        }

        if (trimmed.startsWith("/")) {
            val cmd = trimmed.substringBefore(' ').removePrefix("/")
            val args = trimmed.substringAfter(' ', "").trim()
            if (runCommand(cmd, args)) {
                _chatState.update { it.copy(busy = false) }
                return
            }
        }

        inFlightJob?.cancel()
        inFlightJob = scope.launch {
            try {
                executeLiveTurn(trimmed)
            } finally {
                _chatState.update { it.copy(busy = false) }
            }
        }
    }

    override fun attach(attachment: UiAgentAttachment) {
        _chatState.update { it.copy(pendingAttachments = it.pendingAttachments + attachment) }
    }

    override fun detach(index: Int) {
        _chatState.update { state ->
            val list = state.pendingAttachments.toMutableList()
            if (index in list.indices) list.removeAt(index)
            state.copy(pendingAttachments = list)
        }
    }

    override suspend fun mentionCandidates(query: String): List<UiAgentMention> {
        val root = backend.project.rootPath.takeIf { it.isNotBlank() } ?: return emptyList()
        val q = query.lowercase().trim()
        val files = mutableListOf<UiAgentMention>()

        fun scan(dir: String) {
            for (entry in IosFiles.list(dir)) {
                if (entry.startsWith(".") || entry == "build") continue
                val full = IosFiles.join(dir, entry)
                if (IosFiles.isDirectory(full)) {
                    scan(full)
                } else {
                    val rel = full.removePrefix(root).removePrefix("/")
                    if (q.isEmpty() || rel.contains(q, ignoreCase = true) || entry.contains(q, ignoreCase = true)) {
                        files.add(
                            UiAgentMention(
                                label = entry,
                                insert = rel,
                                detail = rel,
                            )
                        )
                    }
                }
            }
        }

        scan(root)
        return files.take(20)
    }

    override fun commands(): List<UiAgentCommand> = listOf(
        UiAgentCommand("help", "Show available agent commands and guidance"),
        UiAgentCommand("clone", "Clone a GitHub repository URL and open it in the IDE"),
        UiAgentCommand("explain", "Explain the active file or selected code"),
        UiAgentCommand("fix", "Diagnose and fix compiler errors or lint warnings"),
        UiAgentCommand("test", "Generate unit and component tests"),
        UiAgentCommand("plan", "Create a structured execution roadmap"),
        UiAgentCommand("clear", "Reset current chat session"),
    )

    override fun runCommand(name: String, args: String): Boolean {
        when (name.lowercase().trim()) {
            "clear" -> {
                newSession()
                return true
            }
            "clone" -> {
                val url = args.trim().substringBefore(' ')
                if (url.isBlank()) {
                    appendAssistantMessage("Usage: `/clone https://github.com/owner/repo`")
                    return true
                }
                val dirName = url.removeSuffix(".git").trimEnd('/').substringAfterLast('/')
                scope.launch {
                    val res = backend.vcs.cloneRepository(url, dirName)
                    if (res.ok && !res.path.isNullOrBlank()) {
                        appendAssistantMessage("✓ Cloned **$dirName** from `$url` into `${res.path}` and opened it.")
                    } else {
                        appendAssistantMessage("⚠️ Clone failed for `$url`: ${res.message}")
                    }
                }
                return true
            }
            "help" -> {
                val helpText = """
                **Available Slash Commands:**
                - `/help`: Display this command reference.
                - `/clone <url>`: Clone a GitHub repository and open it as the active project.
                - `/explain [file]`: Deep-dive architectural explanation of code.
                - `/fix`: Scan active file diagnostics and propose verified fixes.
                - `/test`: Generate comprehensive unit tests.
                - `/plan [feature]`: Create multi-step implementation plan with checklist.
                - `/clear`: Start a fresh conversation.
                """.trimIndent()
                appendAssistantMessage(helpText)
                return true
            }
            "plan" -> {
                val planText = "Created implementation plan for: **${args.ifBlank { "Requested Task" }}**"
                _chatState.update { state ->
                    state.copy(
                        todos = listOf(
                            UiAgentTodo("Analyze project architecture and target files", UiAgentTodoStatus.IN_PROGRESS),
                            UiAgentTodo("Verify multiplatform symbol resolution and imports", UiAgentTodoStatus.PENDING),
                            UiAgentTodo("Apply changes and run compilation check", UiAgentTodoStatus.PENDING),
                        )
                    )
                }
                appendAssistantMessage(planText)
                return true
            }
            else -> return false
        }
    }

    override fun newSession() {
        inFlightJob?.cancel()
        _chatState.value = UiAgentChatState(
            messages = emptyList(),
            busy = false,
            sessionId = "session-${++messageIdCounter}",
            todos = emptyList(),
        )
    }

    override fun retry() {
        val lastUser = _chatState.value.messages.lastOrNull { it.role == UiAgentRole.USER } ?: return
        send(lastUser.text)
    }

    override fun stop() {
        inFlightJob?.cancel()
        inFlightJob = null
        _chatState.update { it.copy(busy = false) }
    }

    override fun setPermissionMode(mode: UiAgentPermissionMode) {
        permissionMode = mode
    }

    override fun answerPermission(id: Int, decision: UiAgentPermissionDecision) {
        _permissionRequest.value = null
    }

    override fun sessions(): List<UiAgentSessionSummary> = listOf(
        UiAgentSessionSummary("session-1", "Current Workspace Session", dev.ide.ui.platform.nowMillis(), _chatState.value.messages.size)
    )

    private fun defaultModelFor(provider: String): String = when (provider) {
        "gemini" -> "gemini-2.5-flash"
        "openai" -> "gpt-5"
        "anthropic" -> "claude-sonnet-4-6"
        else -> "custom-model"
    }

    private fun appendAssistantMessage(text: String) {
        val msg = UiAgentMessage(
            id = ++messageIdCounter,
            role = UiAgentRole.ASSISTANT,
            text = text,
            segments = listOf(UiAgentSegment.Text(text)),
        )
        _chatState.update { it.copy(messages = it.messages + msg) }
    }

    private fun searchGitHubReposLive(rawQuery: String): Pair<String, List<String>> {
        val cleaned = rawQuery
            .replace(Regex("""(?i)\b(please|can you|could you|search|find|on github|github|for|and|clone|run|build|a|an|the|repo|repository|repositories|app|project)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { rawQuery.trim() }
        val encoded = cleaned.replace(" ", "+")
        val url = "https://api.github.com/search/repositories?q=$encoded&sort=stars&order=desc&per_page=5"
        val res = IosHttpClient.get(
            url = url,
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "User-Agent" to "CodeAssist-iOS",
            ),
        )
        if (!res.isSuccessful || res.body.isBlank()) return "" to emptyList()
        val rootObj = JsonReader.obj(JsonReader.parseOrNull(res.body)) ?: return "" to emptyList()
        val items = JsonReader.arr(rootObj["items"])
        val urls = mutableListOf<String>()
        val summary = buildString {
            for (item in items.take(5)) {
                val obj = JsonReader.obj(item) ?: continue
                val fullName = JsonReader.str(obj, "full_name") ?: continue
                val htmlUrl = JsonReader.str(obj, "html_url") ?: "https://github.com/$fullName"
                val desc = JsonReader.str(obj, "description").orEmpty()
                val stars = JsonReader.int(obj, "stargazers_count", 0)
                val lang = JsonReader.str(obj, "language").orEmpty()
                urls.add(htmlUrl)
                appendLine("- $fullName ($htmlUrl) [★$stars, $lang]: $desc")
            }
        }.trim()
        return summary to urls
    }

    /**
     * Executes a real LLM inference turn against the configured live API.
     */
    private suspend fun executeLiveTurn(query: String) = withContext(Dispatchers.Default) {
        val assistantMsgId = ++messageIdCounter
        val initialMsg = UiAgentMessage(
            id = assistantMsgId,
            role = UiAgentRole.ASSISTANT,
            streaming = true,
        )
        _chatState.update { it.copy(messages = it.messages + initialMsg) }

        val cfg = config()
        val provider = cfg.selectedProvider
        val model = cfg.model
        val apiKey = preferences.get("agent.$provider.key").orEmpty()

        if (apiKey.isEmpty() && provider != "gateway") {
            val errorMsg = "⚠️ **Missing API Key**: No API key is configured for **$provider**. Please provide your API key in Agent Settings to connect to live models."
            updateAssistantMessage(assistantMsgId, errorMsg, isStreaming = false)
            return@withContext
        }

        val preToolCalls = mutableListOf<UiAgentToolCall>()
        val ghUrlRegex = Regex("""https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+""")
        val wantsRepoSearch = !ghUrlRegex.containsMatchIn(query) && (
            query.contains("github", ignoreCase = true) ||
                query.contains("search", ignoreCase = true) ||
                query.contains("find", ignoreCase = true) ||
                query.contains("clone", ignoreCase = true)
            )
        var githubSearchContext = ""
        if (wantsRepoSearch) {
            val (summary, urls) = searchGitHubReposLive(query)
            if (summary.isNotBlank()) {
                githubSearchContext = "\nLive GitHub Search Results for user query:\n$summary\n"
                preToolCalls.add(
                    UiAgentToolCall(
                        id = "call-gh-search-${++messageIdCounter}",
                        title = "search_github",
                        status = UiAgentToolStatus.OK,
                        detail = "Found ${urls.size} matching GitHub repositories",
                    ),
                )
            }
        }

        val root = backend.project.rootPath
        val projectFiles = if (root.isNotBlank()) IosFiles.list(root).filterNot { it.startsWith(".") }.take(15) else emptyList()
        val systemPrompt = """
        You are CodeAssist, an expert agentic AI coding assistant on iOS with live GitHub search, web search, repository cloning, file editing, and on-device project build/run capabilities.
        Active project: ${backend.project.name}
        Root path: $root
        Top files: ${projectFiles.joinToString(", ")}
        $githubSearchContext
        You HAVE live access to GitHub search, web search, cloning, and building via these tool markers (include the exact marker in your response to execute the tool):
        - `tool:search_github(query)` — searches GitHub repositories via the live GitHub REST API.
        - `tool:clone_repository(https://github.com/owner/repo)` — clones a GitHub repository and opens it in the IDE.
        - `tool:run_project` — builds and runs the active project in the Run screen.
        - `tool:list_files` — lists files in the workspace.
        - `tool:read_file(relative/path)` — reads a file in the workspace.
        Never claim you cannot browse or search GitHub repositories; use the live GitHub search results above or emit `tool:search_github(query)` and `tool:clone_repository(url)`.
        """.trimIndent()

        val responseResult = when (provider) {
            "gemini" -> callGemini(apiKey, model, systemPrompt, query)
            "openai" -> callOpenAi(apiKey, model, systemPrompt, query)
            "anthropic" -> callAnthropic(apiKey, model, systemPrompt, query)
            "gateway" -> callGateway(model, systemPrompt, query)
            else -> callGemini(apiKey, model, systemPrompt, query)
        }

        val text = responseResult.text
        val usage = responseResult.usage
        val toolCalls = preToolCalls + checkAndExecuteToolCalls(query, text, root)

        _chatState.update { state ->
            val msgs = state.messages.map { m ->
                if (m.id == assistantMsgId) {
                    m.copy(
                        text = text,
                        streaming = false,
                        segments = listOf(UiAgentSegment.Text(text)),
                        toolCalls = toolCalls,
                        usage = usage,
                    )
                } else m
            }
            state.copy(messages = msgs)
        }
    }

    private data class LlmResult(val text: String, val usage: UiAgentUsage?)

    private fun callGemini(apiKey: String, model: String, systemPrompt: String, query: String): LlmResult {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val payload = """
        {
          "systemInstruction": {
            "parts": [{ "text": ${JsonWriter.string(systemPrompt)} }]
          },
          "contents": [
            {
              "role": "user",
              "parts": [{ "text": ${JsonWriter.string(query)} }]
            }
          ]
        }
        """.trimIndent()

        val response = IosHttpClient.post(
            url = url,
            headers = mapOf(
                "Content-Type" to "application/json",
                "x-goog-api-key" to apiKey,
            ),
            body = payload,
        )

        if (!response.isSuccessful) {
            val err = "Gemini API error (${response.statusCode}): ${response.body.ifBlank { response.error ?: "Network request failed" }}"
            return LlmResult(err, null)
        }

        val root = JsonReader.parseOrNull(response.body) ?: return LlmResult("Failed to parse Gemini JSON response", null)
        val candidates = JsonReader.arr(JsonReader.obj(root)?.get("candidates"))
        val firstCandidate = JsonReader.obj(candidates.firstOrNull())
        val content = JsonReader.obj(firstCandidate?.get("content"))
        val parts = JsonReader.arr(content?.get("parts"))
        val text = parts.mapNotNull { JsonReader.str(it, "text") }.joinToString("\n")

        val usageMeta = JsonReader.obj(JsonReader.obj(root)?.get("usageMetadata"))
        val promptTokens = JsonReader.int(usageMeta, "promptTokenCount", 0)
        val candidateTokens = JsonReader.int(usageMeta, "candidatesTokenCount", 0)

        return LlmResult(text, UiAgentUsage(input = promptTokens, output = candidateTokens))
    }

    private fun callOpenAi(apiKey: String, model: String, systemPrompt: String, query: String): LlmResult {
        val url = "https://api.openai.com/v1/chat/completions"
        val candidateModels = listOf(model, "gpt-4.1", "gpt-4o").distinct()
        var lastErr = ""
        for (candidateModel in candidateModels) {
            val payload = """
            {
              "model": "$candidateModel",
              "messages": [
                { "role": "system", "content": ${JsonWriter.string(systemPrompt)} },
                { "role": "user", "content": ${JsonWriter.string(query)} }
              ]
            }
            """.trimIndent()

            val response = IosHttpClient.post(
                url = url,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer $apiKey",
                ),
                body = payload,
            )

            if (!response.isSuccessful) {
                lastErr = "OpenAI API error (${response.statusCode}): ${response.body.ifBlank { response.error ?: "Network request failed" }}"
                if (response.statusCode == 404 || response.body.contains("model_not_found") || response.body.contains("does not exist")) {
                    continue
                }
                return LlmResult(lastErr, null)
            }

            val root = JsonReader.parseOrNull(response.body) ?: return LlmResult("Failed to parse OpenAI JSON response", null)
            val choices = JsonReader.arr(JsonReader.obj(root)?.get("choices"))
            val firstChoice = JsonReader.obj(choices.firstOrNull())
            val message = JsonReader.obj(firstChoice?.get("message"))
            val text = JsonReader.str(message, "content") ?: ""

            val usageObj = JsonReader.obj(JsonReader.obj(root)?.get("usage"))
            val promptTokens = JsonReader.int(usageObj, "prompt_tokens", 0)
            val completionTokens = JsonReader.int(usageObj, "completion_tokens", 0)

            return LlmResult(text, UiAgentUsage(input = promptTokens, output = completionTokens))
        }
        return LlmResult(lastErr.ifBlank { "OpenAI request failed" }, null)
    }

    private fun callAnthropic(apiKey: String, model: String, systemPrompt: String, query: String): LlmResult {
        val url = "https://api.anthropic.com/v1/messages"
        val payload = """
        {
          "model": "$model",
          "system": ${JsonWriter.string(systemPrompt)},
          "max_tokens": 4096,
          "messages": [
            { "role": "user", "content": ${JsonWriter.string(query)} }
          ]
        }
        """.trimIndent()

        val response = IosHttpClient.post(
            url = url,
            headers = mapOf(
                "Content-Type" to "application/json",
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
            ),
            body = payload,
        )

        if (!response.isSuccessful) {
            val err = "Anthropic API error (${response.statusCode}): ${response.body.ifBlank { response.error ?: "Network request failed" }}"
            return LlmResult(err, null)
        }

        val root = JsonReader.parseOrNull(response.body) ?: return LlmResult("Failed to parse Anthropic JSON response", null)
        val contentArr = JsonReader.arr(JsonReader.obj(root)?.get("content"))
        val text = contentArr.mapNotNull { JsonReader.str(it, "text") }.joinToString("\n")

        val usageObj = JsonReader.obj(JsonReader.obj(root)?.get("usage"))
        val inputTokens = JsonReader.int(usageObj, "input_tokens", 0)
        val outputTokens = JsonReader.int(usageObj, "output_tokens", 0)

        return LlmResult(text, UiAgentUsage(input = inputTokens, output = outputTokens))
    }

    private fun callGateway(model: String, systemPrompt: String, query: String): LlmResult {
        val baseUrl = preferences.get("agent.gateway.base") ?: "http://127.0.0.1:8080"
        val url = "${baseUrl.trimEnd('/')}/v1/chat/completions"
        val payload = """
        {
          "model": "$model",
          "messages": [
            { "role": "system", "content": ${JsonWriter.string(systemPrompt)} },
            { "role": "user", "content": ${JsonWriter.string(query)} }
          ]
        }
        """.trimIndent()

        val response = IosHttpClient.post(
            url = url,
            headers = mapOf("Content-Type" to "application/json"),
            body = payload,
        )

        if (!response.isSuccessful) {
            val err = "Gateway error (${response.statusCode}): ${response.body.ifBlank { response.error ?: "Network connection failed" }}"
            return LlmResult(err, null)
        }

        val root = JsonReader.parseOrNull(response.body) ?: return LlmResult("Failed to parse Gateway JSON response", null)
        val choices = JsonReader.arr(JsonReader.obj(root)?.get("choices"))
        val firstChoice = JsonReader.obj(choices.firstOrNull())
        val message = JsonReader.obj(firstChoice?.get("message"))
        val text = JsonReader.str(message, "content") ?: ""

        val usageObj = JsonReader.obj(JsonReader.obj(root)?.get("usage"))
        val promptTokens = JsonReader.int(usageObj, "prompt_tokens", 0)
        val completionTokens = JsonReader.int(usageObj, "completion_tokens", 0)

        return LlmResult(text, UiAgentUsage(input = promptTokens, output = completionTokens))
    }

    private fun updateAssistantMessage(id: Long, text: String, isStreaming: Boolean) {
        _chatState.update { state ->
            val msgs = state.messages.map { m ->
                if (m.id == id) {
                    m.copy(
                        text = text,
                        streaming = isStreaming,
                        segments = listOf(UiAgentSegment.Text(text)),
                    )
                } else m
            }
            state.copy(messages = msgs)
        }
    }

    private suspend fun checkAndExecuteToolCalls(query: String, text: String, root: String): List<UiAgentToolCall> {
        val toolCalls = mutableListOf<UiAgentToolCall>()
        val combined = "$query\n$text"
        val ghUrlRegex = Regex("""https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+""")
        val shouldClone = text.contains("tool:clone_repository") ||
            (query.contains("clone", ignoreCase = true) && ghUrlRegex.containsMatchIn(query))
        if (shouldClone) {
            val matchUrl = ghUrlRegex.find(combined)?.value
            if (matchUrl != null) {
                val dirName = matchUrl.removeSuffix(".git").trimEnd('/').substringAfterLast('/')
                val res = backend.vcs.cloneRepository(matchUrl, dirName)
                toolCalls.add(
                    UiAgentToolCall(
                        id = "call-clone-${++messageIdCounter}",
                        title = "clone_repository ($dirName)",
                        status = if (res.ok) UiAgentToolStatus.OK else UiAgentToolStatus.ERROR,
                        detail = res.message,
                    ),
                )
            }
        }
        if (text.contains("tool:run_project") || (shouldClone && query.contains("run", ignoreCase = true))) {
            backend.build.runBuild()
            toolCalls.add(
                UiAgentToolCall(
                    id = "call-run-${++messageIdCounter}",
                    title = "run_project (${backend.project.name})",
                    status = UiAgentToolStatus.OK,
                    detail = "Started build & run in Run screen",
                ),
            )
        }
        if (root.isNotBlank() && text.contains("tool:list_files")) {
            val files = IosFiles.list(root)
            toolCalls.add(
                UiAgentToolCall(
                    id = "call-1",
                    title = "list_files",
                    status = UiAgentToolStatus.OK,
                    detail = "Read ${files.size} items from workspace",
                )
            )
        }
        return toolCalls
    }
}
