package dev.ide.agent.impl

import dev.ide.agent.ContentPart
import dev.ide.agent.LlmClient
import dev.ide.agent.LlmEffort
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmModelInfo
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmRole
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.ProviderConfig
import dev.ide.agent.StopReason
import dev.ide.agent.TokenUsage
import dev.ide.agent.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Operating modes modeled directly after `farion1231/cc-switch`:
 * - [DIRECT]: Zero proxy overhead; requests go straight to the active provider's native endpoint.
 * - [ROUTING]: Requests traverse the local loopback router (`127.0.0.1:<port>`) with cross-protocol
 *   format translation, model tier mapping, circuit breaker health tracking, and automatic failover.
 * - [AGGREGATION]: Combines models from multiple stack-enabled providers under `ccs-<key>/<model>`
 *   (and `ccs-claude-<key>--<model>`) identifiers on the local router.
 */
enum class CcSwitchOperatingMode(val id: String, val displayName: String, val summary: String) {
    DIRECT(
        id = "direct",
        displayName = "Direct",
        summary = "Direct connection to the active provider endpoint (zero local proxy overhead).",
    ),
    ROUTING(
        id = "routing",
        displayName = "Routing",
        summary = "Local proxy on 127.0.0.1 with cross-protocol translation, circuit breaker, and auto-failover.",
    ),
    AGGREGATION(
        id = "aggregation",
        displayName = "Aggregation",
        summary = "Unified model stack (ccs-<provider>/<model>) across all stack-enabled providers.",
    ),
    ;

    companion object {
        fun fromId(raw: String?): CcSwitchOperatingMode =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) || it.name.equals(raw?.trim(), ignoreCase = true) }
                ?: DIRECT
    }
}

/**
 * Upstream wire protocol format for a provider profile in the Universal LLM Corner.
 */
enum class CcSwitchApiFormat(val id: String, val displayName: String) {
    ANTHROPIC_MESSAGES("anthropic", "Anthropic Messages (/v1/messages)"),
    OPENAI_CHAT("openai_chat", "OpenAI Chat Completions (/v1/chat/completions)"),
    OPENAI_RESPONSES("openai_responses", "OpenAI Responses (/v1/responses)"),
    GEMINI_NATIVE("gemini_native", "Gemini Native (/v1beta/models)"),
    LITERT_ON_DEVICE("litert_on_device", "LiteRT-LM On-Device (.litertlm / AI Core)"),
    ;

    companion object {
        fun fromId(raw: String?): CcSwitchApiFormat =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) || it.name.equals(raw?.trim(), ignoreCase = true) }
                ?: OPENAI_CHAT
    }
}

/**
 * Provider preset / profile category matching `cc-switch`.
 */
enum class CcSwitchProviderCategory(val id: String, val displayName: String) {
    OFFICIAL("official", "Official"),
    CN_OFFICIAL("cn_official", "Model Labs"),
    AGGREGATOR("aggregator", "Aggregator"),
    THIRD_PARTY("third_party", "Third-Party"),
    ON_DEVICE("on_device", "On-Device / Local"),
    CUSTOM("custom", "Custom"),
    ;

    companion object {
        fun fromId(raw: String?): CcSwitchProviderCategory =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) || it.name.equals(raw?.trim(), ignoreCase = true) }
                ?: CUSTOM
    }
}

/**
 * Tier-based model mapping per provider (`haiku`, `sonnet`, `opus`, `default`, and optional `reasoningEffort`),
 * ported from `cc-switch`'s `src-tauri/src/proxy/model_mapper.rs`.
 */
data class CcSwitchModelMapping(
    val defaultModel: String = "",
    val haikuModel: String = "",
    val sonnetModel: String = "",
    val opusModel: String = "",
    val reasoningEffort: String = "",
) {
    val hasMappings: Boolean
        get() = defaultModel.isNotBlank() ||
            haikuModel.isNotBlank() ||
            sonnetModel.isNotBlank() ||
            opusModel.isNotBlank() ||
            reasoningEffort.isNotBlank()

    fun resolveModel(requestedModel: String): String {
        val cleaned = strip1mSuffix(requestedModel.trim())
        val lower = cleaned.lowercase()
        if (lower.contains("haiku") && haikuModel.isNotBlank()) return haikuModel.trim()
        if (lower.contains("sonnet") && sonnetModel.isNotBlank()) return sonnetModel.trim()
        if (lower.contains("opus") && opusModel.isNotBlank()) return opusModel.trim()
        if (defaultModel.isNotBlank() && (cleaned.isBlank() || lower == "default" || lower.startsWith("claude-"))) {
            return defaultModel.trim()
        }
        return cleaned.ifBlank { defaultModel.trim() }
    }

    companion object {
        fun strip1mSuffix(model: String): String {
            val trimmed = model.trim()
            return if (trimmed.length > 4 && trimmed.endsWith("[1m]", ignoreCase = true)) {
                trimmed.dropLast(4).trim()
            } else {
                trimmed
            }
        }
    }
}

/**
 * Circuit breaker states ported from `cc-switch`'s `src-tauri/src/proxy/circuit_breaker.rs`.
 */
enum class CcSwitchCircuitState(val id: String, val displayName: String) {
    CLOSED("closed", "Closed (Healthy)"),
    OPEN("open", "Open (Tripped)"),
    HALF_OPEN("half_open", "Half-Open (Probing)"),
}

data class CcSwitchCircuitBreakerConfig(
    val failureThreshold: Int = 4,
    val successThreshold: Int = 2,
    val timeoutSeconds: Long = 60L,
    val errorRateThreshold: Double = 0.6,
    val minRequests: Int = 10,
)

data class CcSwitchAcquirePermission(
    val allowed: Boolean,
    val usedHalfOpenPermit: Boolean,
)

data class CcSwitchCircuitBreakerSnapshot(
    val state: CcSwitchCircuitState = CcSwitchCircuitState.CLOSED,
    val consecutiveFailures: Int = 0,
    val consecutiveSuccesses: Int = 0,
    val totalRequests: Long = 0L,
    val failedRequests: Long = 0L,
    val lastLatencyMs: Long = 0L,
    val lastError: String = "",
    val lastOpenedAtEpochMs: Long = 0L,
)

/**
 * Thread-safe circuit breaker implementation matching `cc-switch` (`Closed -> Open -> HalfOpen -> Closed`).
 */
class CcSwitchCircuitBreaker(
    @Volatile var config: CcSwitchCircuitBreakerConfig = CcSwitchCircuitBreakerConfig(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile private var state: CcSwitchCircuitState = CcSwitchCircuitState.CLOSED
    private val consecutiveFailures = AtomicInteger(0)
    private val consecutiveSuccesses = AtomicInteger(0)
    private val totalRequests = AtomicLong(0L)
    private val failedRequests = AtomicLong(0L)
    private val halfOpenRequests = AtomicInteger(0)
    @Volatile private var lastOpenedAtMs: Long = 0L
    @Volatile private var lastLatencyMs: Long = 0L
    @Volatile private var lastError: String = ""

    @Synchronized
    fun allowRequest(enabled: Boolean = true): CcSwitchAcquirePermission {
        if (!enabled) {
            return CcSwitchAcquirePermission(allowed = true, usedHalfOpenPermit = false)
        }
        return when (state) {
            CcSwitchCircuitState.CLOSED -> CcSwitchAcquirePermission(allowed = true, usedHalfOpenPermit = false)
            CcSwitchCircuitState.OPEN -> {
                val elapsedSec = ((clockMs() - lastOpenedAtMs).coerceAtLeast(0L)) / 1_000L
                if (elapsedSec >= config.timeoutSeconds) {
                    state = CcSwitchCircuitState.HALF_OPEN
                    consecutiveSuccesses.set(0)
                    halfOpenRequests.set(1)
                    CcSwitchAcquirePermission(allowed = true, usedHalfOpenPermit = true)
                } else {
                    CcSwitchAcquirePermission(allowed = false, usedHalfOpenPermit = false)
                }
            }
            CcSwitchCircuitState.HALF_OPEN -> {
                if (halfOpenRequests.compareAndSet(0, 1)) {
                    CcSwitchAcquirePermission(allowed = true, usedHalfOpenPermit = true)
                } else {
                    CcSwitchAcquirePermission(allowed = false, usedHalfOpenPermit = false)
                }
            }
        }
    }

    @Synchronized
    fun releaseHalfOpenPermit() {
        halfOpenRequests.set(0)
    }

    @Synchronized
    fun recordSuccess(usedHalfOpenPermit: Boolean, latencyMs: Long = 0L, enabled: Boolean = true) {
        if (usedHalfOpenPermit) {
            halfOpenRequests.set(0)
        }
        if (latencyMs > 0L) {
            lastLatencyMs = latencyMs
        }
        lastError = ""
        if (!enabled) {
            state = CcSwitchCircuitState.CLOSED
            consecutiveFailures.set(0)
            consecutiveSuccesses.set(0)
            return
        }
        totalRequests.incrementAndGet()
        consecutiveFailures.set(0)

        when (state) {
            CcSwitchCircuitState.HALF_OPEN -> {
                val successes = consecutiveSuccesses.incrementAndGet()
                if (successes >= config.successThreshold) {
                    state = CcSwitchCircuitState.CLOSED
                    consecutiveSuccesses.set(0)
                    consecutiveFailures.set(0)
                    halfOpenRequests.set(0)
                }
            }
            CcSwitchCircuitState.OPEN -> {
                state = CcSwitchCircuitState.CLOSED
                consecutiveSuccesses.set(0)
                consecutiveFailures.set(0)
                halfOpenRequests.set(0)
            }
            CcSwitchCircuitState.CLOSED -> Unit
        }
    }

    @Synchronized
    fun recordFailure(usedHalfOpenPermit: Boolean, errorMessage: String, latencyMs: Long = 0L, enabled: Boolean = true) {
        if (usedHalfOpenPermit) {
            halfOpenRequests.set(0)
        }
        if (latencyMs > 0L) {
            lastLatencyMs = latencyMs
        }
        lastError = errorMessage.take(240)
        if (!enabled) {
            state = CcSwitchCircuitState.CLOSED
            consecutiveFailures.set(0)
            consecutiveSuccesses.set(0)
            return
        }
        totalRequests.incrementAndGet()
        failedRequests.incrementAndGet()
        val failures = consecutiveFailures.incrementAndGet()
        consecutiveSuccesses.set(0)

        when (state) {
            CcSwitchCircuitState.HALF_OPEN -> {
                state = CcSwitchCircuitState.OPEN
                lastOpenedAtMs = clockMs()
                halfOpenRequests.set(0)
            }
            CcSwitchCircuitState.CLOSED -> {
                if (shouldTrip(failures)) {
                    state = CcSwitchCircuitState.OPEN
                    lastOpenedAtMs = clockMs()
                    halfOpenRequests.set(0)
                }
            }
            CcSwitchCircuitState.OPEN -> Unit
        }
    }

    @Synchronized
    fun reset() {
        state = CcSwitchCircuitState.CLOSED
        consecutiveFailures.set(0)
        consecutiveSuccesses.set(0)
        totalRequests.set(0L)
        failedRequests.set(0L)
        halfOpenRequests.set(0)
        lastError = ""
    }

    @Synchronized
    fun snapshot(): CcSwitchCircuitBreakerSnapshot {
        if (state == CcSwitchCircuitState.OPEN) {
            val elapsedSec = ((clockMs() - lastOpenedAtMs).coerceAtLeast(0L)) / 1_000L
            if (elapsedSec >= config.timeoutSeconds) {
                state = CcSwitchCircuitState.HALF_OPEN
                consecutiveSuccesses.set(0)
                halfOpenRequests.set(0)
            }
        }
        return CcSwitchCircuitBreakerSnapshot(
            state = state,
            consecutiveFailures = consecutiveFailures.get(),
            consecutiveSuccesses = consecutiveSuccesses.get(),
            totalRequests = totalRequests.get(),
            failedRequests = failedRequests.get(),
            lastLatencyMs = lastLatencyMs,
            lastError = lastError,
            lastOpenedAtEpochMs = lastOpenedAtMs,
        )
    }

    private fun shouldTrip(failures: Int): Boolean {
        if (failures >= config.failureThreshold) return true
        val total = totalRequests.get()
        val failed = failedRequests.get()
        if (total >= config.minRequests && total > 0L) {
            val rate = failed.toDouble() / total.toDouble()
            if (rate >= config.errorRateThreshold) return true
        }
        return false
    }
}

/**
 * Stack / Aggregation mode codec ported from `cc-switch`'s `src-tauri/src/mode/stack.rs`.
 */
object CcSwitchStackCodec {
    const val STACK_PREFIX = "ccs-"
    const val CLAUDE_ENCODE_PREFIX = "ccs-claude-"
    const val CLAUDE_ENCODE_SEP = "--"

    fun encodeAggregatedModel(providerKey: String, upstreamModel: String): String =
        "$STACK_PREFIX$providerKey/$upstreamModel"

    fun encodeClaudeCodeModel(providerKey: String, upstreamModel: String): String =
        "$CLAUDE_ENCODE_PREFIX$providerKey$CLAUDE_ENCODE_SEP$upstreamModel"

    fun hasStackPrefix(raw: String): Boolean {
        val trimmed = raw.trim().removePrefix("models/")
        return trimmed.startsWith(STACK_PREFIX)
    }

    fun decodeAggregatedModel(raw: String): Pair<String, String>? {
        val trimmed = raw.trim().removePrefix("models/")
        val claudeRest = trimmed.removePrefix(CLAUDE_ENCODE_PREFIX)
        if (claudeRest != trimmed) {
            val sepIdx = claudeRest.indexOf(CLAUDE_ENCODE_SEP)
            if (sepIdx <= 0) return null
            val key = claudeRest.substring(0, sepIdx)
            val model = claudeRest.substring(sepIdx + CLAUDE_ENCODE_SEP.length)
            if (!isValidProviderKey(key) || model.isBlank()) return null
            return key to model
        }

        val rest = trimmed.removePrefix(STACK_PREFIX)
        if (rest == trimmed) return null
        val slashIdx = rest.indexOf('/')
        if (slashIdx <= 0) return null
        val key = rest.substring(0, slashIdx)
        val model = rest.substring(slashIdx + 1)
        if (!isValidProviderKey(key) || model.isBlank()) return null
        return key to model
    }

    fun isValidProviderKey(key: String): Boolean =
        key.isNotBlank() && key.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }

    fun deriveProviderKey(id: String, name: String, taken: Set<String> = emptySet()): String {
        val slugFromName = slugify(name)
        val base = when {
            slugFromName.isNotBlank() -> slugFromName
            else -> slugify(id).ifBlank { "provider" }
        }
        if (base !in taken) return base
        var idx = 2
        while ("$base-$idx" in taken) idx++
        return "$base-$idx"
    }

    private fun slugify(raw: String): String {
        val sb = StringBuilder()
        var prevDash = false
        for (ch in raw.lowercase()) {
            when {
                ch in 'a'..'z' || ch in '0'..'9' -> {
                    sb.append(ch)
                    prevDash = false
                }
                !prevDash && sb.isNotEmpty() -> {
                    sb.append('-')
                    prevDash = true
                }
            }
        }
        return sb.toString().trim('-')
    }
}

/**
 * Built-in provider preset definition modeled after `cc-switch`'s `universalProviderPresets.ts`,
 * `claudeProviderPresets.ts`, `codexProviderPresets.ts`, and `geminiProviderPresets.ts`.
 */
data class CcSwitchProviderPreset(
    val presetId: String,
    val defaultProviderId: String,
    val name: String,
    val category: CcSwitchProviderCategory,
    val apiFormat: CcSwitchApiFormat,
    val baseUrl: String,
    val endpointCandidates: List<String>,
    val defaultModel: String,
    val models: List<LlmModelInfo>,
    val modelMapping: CcSwitchModelMapping,
    val websiteUrl: String = "",
    val description: String = "",
    val requiresApiKey: Boolean = true,
)

/**
 * Persisted universal provider profile in the LLM Corner.
 */
data class CcSwitchProviderProfile(
    val id: String,
    val providerKey: String,
    val name: String,
    val presetId: String,
    val category: CcSwitchProviderCategory,
    val apiFormat: CcSwitchApiFormat,
    val baseUrl: String,
    val endpointCandidates: List<String> = emptyList(),
    val apiKey: String = "",
    val caCertificatePem: String = "",
    val defaultModel: String,
    val models: List<LlmModelInfo> = emptyList(),
    val modelMapping: CcSwitchModelMapping = CcSwitchModelMapping(),
    val inFailoverQueue: Boolean = false,
    val failoverPriority: Int = 100,
    val inAggregationStack: Boolean = false,
    val requiresApiKey: Boolean = true,
    val websiteUrl: String = "",
    val notes: String = "",
) {
    fun isConfigured(hasInstalledOnDeviceModel: Boolean = false): Boolean = when {
        apiFormat == CcSwitchApiFormat.LITERT_ON_DEVICE || id == LiteRtLmCatalog.PROVIDER_ID -> hasInstalledOnDeviceModel
        !requiresApiKey -> baseUrl.isNotBlank() && defaultModel.isNotBlank()
        else -> apiKey.isNotBlank() && defaultModel.isNotBlank()
    }
}

/**
 * Complete preset catalog derived from `farion1231/cc-switch` + existing IDE providers.
 */
object CcSwitchPresetCatalog {
    val PRESETS: List<CcSwitchProviderPreset> = listOf(
        CcSwitchProviderPreset(
            presetId = "claude-official",
            defaultProviderId = "anthropic",
            name = "Anthropic Claude",
            category = CcSwitchProviderCategory.OFFICIAL,
            apiFormat = CcSwitchApiFormat.ANTHROPIC_MESSAGES,
            baseUrl = "https://api.anthropic.com",
            endpointCandidates = listOf("https://api.anthropic.com"),
            defaultModel = "claude-opus-5",
            models = listOf(
                LlmModelInfo("claude-opus-5", "Claude Opus 5", supportsThinking = true),
                LlmModelInfo("claude-opus-4-8", "Claude Opus 4.8", supportsThinking = true),
                LlmModelInfo("claude-sonnet-5", "Claude Sonnet 5", supportsThinking = true),
                LlmModelInfo("claude-haiku-4-5", "Claude Haiku 4.5", supportsThinking = false),
            ),
            modelMapping = CcSwitchModelMapping(
                defaultModel = "claude-opus-5",
                haikuModel = "claude-haiku-4-5",
                sonnetModel = "claude-sonnet-5",
                opusModel = "claude-opus-5",
            ),
            websiteUrl = "https://console.anthropic.com",
            description = "Official Anthropic Messages API with adaptive thinking & prompt caching.",
        ),
        CcSwitchProviderPreset(
            presetId = "codex-official",
            defaultProviderId = "openai",
            name = "OpenAI (Responses / Codex)",
            category = CcSwitchProviderCategory.OFFICIAL,
            apiFormat = CcSwitchApiFormat.OPENAI_RESPONSES,
            baseUrl = "https://api.openai.com",
            endpointCandidates = listOf("https://api.openai.com", "https://api.openai.com/v1"),
            defaultModel = "gpt-6.1-sol",
            models = listOf(
                LlmModelInfo("gpt-6.1-sol", "GPT-6.1 Sol", supportsThinking = true),
                LlmModelInfo("gpt-6-astra", "GPT-6 Astra", supportsThinking = true),
                LlmModelInfo("gpt-5", "GPT-5", supportsThinking = true),
                LlmModelInfo("gpt-5-mini", "GPT-5 mini", supportsThinking = true),
                LlmModelInfo("gpt-4.1", "GPT-4.1", supportsThinking = false),
                LlmModelInfo("gpt-4o", "GPT-4o", supportsThinking = false),
            ),
            modelMapping = CcSwitchModelMapping(
                defaultModel = "gpt-6.1-sol",
                haikuModel = "gpt-5-mini",
                sonnetModel = "gpt-5",
                opusModel = "gpt-6.1-sol",
            ),
            websiteUrl = "https://platform.openai.com",
            description = "Official OpenAI Responses & Chat Completions API.",
        ),
        CcSwitchProviderPreset(
            presetId = "gemini-official",
            defaultProviderId = "gemini",
            name = "Google Gemini",
            category = CcSwitchProviderCategory.OFFICIAL,
            apiFormat = CcSwitchApiFormat.GEMINI_NATIVE,
            baseUrl = "https://generativelanguage.googleapis.com",
            endpointCandidates = listOf("https://generativelanguage.googleapis.com"),
            defaultModel = "gemini-3.8-flash",
            models = listOf(
                LlmModelInfo("gemini-3.8-flash", "Gemini 3.8 Flash", supportsThinking = true),
                LlmModelInfo("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", supportsThinking = true),
                LlmModelInfo("gemini-3.1-pro-preview", "Gemini 3.1 Pro", supportsThinking = true),
                LlmModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash", supportsThinking = true),
            ),
            modelMapping = CcSwitchModelMapping(
                defaultModel = "gemini-3.8-flash",
                haikuModel = "gemini-3.5-flash-lite",
                sonnetModel = "gemini-3.8-flash",
                opusModel = "gemini-3.1-pro-preview",
            ),
            websiteUrl = "https://aistudio.google.com",
            description = "Official Google Gemini generateContent API with context caching.",
        ),
        CcSwitchProviderPreset(
            presetId = "antigravity-official",
            defaultProviderId = "antigravity",
            name = "Antigravity",
            category = CcSwitchProviderCategory.OFFICIAL,
            apiFormat = CcSwitchApiFormat.GEMINI_NATIVE,
            baseUrl = "https://cloudcode-pa.googleapis.com",
            endpointCandidates = listOf("https://cloudcode-pa.googleapis.com"),
            defaultModel = "gemini-3-pro-high",
            models = listOf(
                LlmModelInfo("gemini-3.1-pro-high", "Gemini 3.1 Pro (high)", supportsThinking = true),
                LlmModelInfo("gemini-3-pro-high", "Gemini 3 Pro (high)", supportsThinking = true),
                LlmModelInfo("gemini-3-pro-low", "Gemini 3 Pro (low)", supportsThinking = true),
                LlmModelInfo("gemini-3-flash", "Gemini 3 Flash", supportsThinking = true),
                LlmModelInfo("claude-opus-4-6-thinking", "Claude Opus 4.6 (thinking)", supportsThinking = true),
                LlmModelInfo("claude-sonnet-4-6", "Claude Sonnet 4.6", supportsThinking = false),
                LlmModelInfo("gpt-oss-120b-medium", "GPT-OSS 120B", supportsThinking = false),
            ),
            modelMapping = CcSwitchModelMapping(
                defaultModel = "gemini-3-pro-high",
                haikuModel = "gemini-3-flash",
                sonnetModel = "claude-sonnet-4-6",
                opusModel = "claude-opus-4-6-thinking",
            ),
            websiteUrl = "https://cloud.google.com",
            description = "Google Code Assist unified gateway for Gemini 3, Claude, and GPT-OSS.",
        ),
        CcSwitchProviderPreset(
            presetId = "litert-on-device",
            defaultProviderId = LiteRtLmCatalog.PROVIDER_ID,
            name = "LiteRT-LM (On-Device)",
            category = CcSwitchProviderCategory.ON_DEVICE,
            apiFormat = CcSwitchApiFormat.LITERT_ON_DEVICE,
            baseUrl = "local://litert-lm",
            endpointCandidates = listOf("local://litert-lm", "http://127.0.0.1:8781/v1"),
            defaultModel = LiteRtLmCatalog.DEFAULT_MODEL_ID,
            models = LiteRtLmCatalog.MODELS.map {
                LlmModelInfo(it.id, "${it.displayName} (${it.quantization})", supportsThinking = it.supportsThinking)
            },
            modelMapping = CcSwitchModelMapping(
                defaultModel = LiteRtLmCatalog.DEFAULT_MODEL_ID,
                haikuModel = LiteRtLmCatalog.DEFAULT_MODEL_ID,
                sonnetModel = "gemma-4-e2b-it",
                opusModel = "deepseek-r1-distill-qwen-1.5b-int4",
            ),
            websiteUrl = "https://ai.google.dev/edge/litert",
            description = "Zero-cloud local inference via verified .litertlm artifacts & Android System AI Core.",
            requiresApiKey = false,
        ),
        CcSwitchProviderPreset(
            presetId = "openrouter",
            defaultProviderId = "openrouter",
            name = "OpenRouter",
            category = CcSwitchProviderCategory.AGGREGATOR,
            apiFormat = CcSwitchApiFormat.OPENAI_CHAT,
            baseUrl = "https://openrouter.ai/api",
            endpointCandidates = listOf("https://openrouter.ai/api", "https://openrouter.ai/api/v1"),
            defaultModel = "anthropic/claude-opus-5",
            models = listOf(
                LlmModelInfo("anthropic/claude-opus-5", "Claude Opus 5", supportsThinking = true),
                LlmModelInfo("anthropic/claude-sonnet-5", "Claude Sonnet 5", supportsThinking = true),
                LlmModelInfo("openai/gpt-6.1-sol", "GPT-6.1 Sol", supportsThinking = true),
                LlmModelInfo("openai/gpt-5", "GPT-5", supportsThinking = true),
                LlmModelInfo("deepseek/deepseek-r1", "DeepSeek R1", supportsThinking = true),
                LlmModelInfo("google/gemini-2.5-flash", "Gemini 2.5 Flash", supportsThinking = true),
            ),
            modelMapping = CcSwitchModelMapping(
                defaultModel = "anthropic/claude-opus-5",
                haikuModel = "google/gemini-2.5-flash",
                sonnetModel = "anthropic/claude-sonnet-5",
                opusModel = "anthropic/claude-opus-5",
            ),
            websiteUrl = "https://openrouter.ai",
            description = "Multi-vendor OpenAI/Anthropic aggregator with explicit prompt caching.",
        ),
        CcSwitchProviderPreset(
            presetId = "deepseek",
            defaultProviderId = "deepseek",
            name = "DeepSeek Official",
            category = CcSwitchProviderCategory.CN_OFFICIAL,
            apiFormat = CcSwitchApiFormat.OPENAI_CHAT,
            baseUrl = "https://api.deepseek.com",
            endpointCandidates = listOf("https://api.deepseek.com", "https://api.deepseek.com/anthropic"),
            defaultModel = "deepseek-chat",
            models = listOf(
                LlmModelInfo("deepseek-chat", "DeepSeek V3.2 Chat", supportsThinking = false),
                LlmModelInfo("deepseek-reasoner", "DeepSeek R1 Reasoner", supportsThinking = true),
            ),
            modelMapping = CcSwitchModelMapping(
                defaultModel = "deepseek-chat",
                haikuModel = "deepseek-chat",
                sonnetModel = "deepseek-chat",
                opusModel = "deepseek-reasoner",
            ),
            websiteUrl = "https://platform.deepseek.com",
            description = "DeepSeek official API (supports both OpenAI Chat and /anthropic Messages).",
        ),
        CcSwitchProviderPreset(
            presetId = "kimi",
            defaultProviderId = "kimi",
            name = "Kimi (Moonshot AI)",
            category = CcSwitchProviderCategory.CN_OFFICIAL,
            apiFormat = CcSwitchApiFormat.OPENAI_CHAT,
            baseUrl = "https://api.moonshot.cn/v1",
            endpointCandidates = listOf(
                "https://api.moonshot.cn/v1",
                "https://api.moonshot.cn/anthropic",
                "https://api.kimi.com/coding",
            ),
            defaultModel = "kimi-k2.5",
            models = listOf(
                LlmModelInfo("kimi-k2.5", "Kimi K2.5", supportsThinking = true),
                LlmModelInfo("kimi-k2-thinking", "Kimi K2 Thinking", supportsThinking = true),
                LlmModelInfo("kimi-for-coding", "Kimi For Coding", supportsThinking = true),
            ),
            modelMapping = CcSwitchModelMapping(
                defaultModel = "kimi-k2.5",
                haikuModel = "kimi-k2.5",
                sonnetModel = "kimi-k2.5",
                opusModel = "kimi-k2-thinking",
            ),
            websiteUrl = "https://platform.moonshot.cn",
            description = "Moonshot Kimi K2.5 and Kimi For Coding endpoints.",
        ),
        CcSwitchProviderPreset(
            presetId = "zhipu-glm",
            defaultProviderId = "zhipu-glm",
            name = "Zhipu GLM / Z.ai",
            category = CcSwitchProviderCategory.CN_OFFICIAL,
            apiFormat = CcSwitchApiFormat.OPENAI_CHAT,
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            endpointCandidates = listOf(
                "https://open.bigmodel.cn/api/paas/v4",
                "https://open.bigmodel.cn/api/anthropic",
                "https://api.z.ai/api/anthropic",
            ),
            defaultModel = "glm-5",
            models = listOf(
                LlmModelInfo("glm-5", "GLM-5", supportsThinking = true),
                LlmModelInfo("glm-4.7", "GLM-4.7", supportsThinking = true),
                LlmModelInfo("glm-4.5-air", "GLM-4.5 Air", supportsThinking = false),
            ),
            modelMapping = CcSwitchModelMapping(
                defaultModel = "glm-5",
                haikuModel = "glm-4.5-air",
                sonnetModel = "glm-4.7",
                opusModel = "glm-5",
            ),
            websiteUrl = "https://open.bigmodel.cn",
            description = "Zhipu AI BigModel & Z.ai Coding Plan endpoints.",
        ),
        CcSwitchProviderPreset(
            presetId = "minimax",
            defaultProviderId = "minimax",
            name = "MiniMax",
            category = CcSwitchProviderCategory.CN_OFFICIAL,
            apiFormat = CcSwitchApiFormat.OPENAI_CHAT,
            baseUrl = "https://api.minimaxi.com/v1",
            endpointCandidates = listOf(
                "https://api.minimaxi.com/v1",
                "https://api.minimaxi.com/anthropic",
                "https://api.minimax.io/anthropic",
            ),
            defaultModel = "MiniMax-M2.7",
            models = listOf(
                LlmModelInfo("MiniMax-M2.7", "MiniMax M2.7", supportsThinking = true),
                LlmModelInfo("MiniMax-M2.5", "MiniMax M2.5", supportsThinking = true),
                LlmModelInfo("MiniMax-M2.1", "MiniMax M2.1", supportsThinking = false),
            ),
            modelMapping = CcSwitchModelMapping(
                defaultModel = "MiniMax-M2.7",
                haikuModel = "MiniMax-M2.1",
                sonnetModel = "MiniMax-M2.5",
                opusModel = "MiniMax-M2.7",
            ),
            websiteUrl = "https://platform.minimaxi.com",
            description = "MiniMax M2.7 / M2.5 coding & reasoning endpoints.",
        ),
        CcSwitchProviderPreset(
            presetId = "qwen-bailian",
            defaultProviderId = "qwen-bailian",
            name = "Alibaba Qwen (Bailian)",
            category = CcSwitchProviderCategory.CN_OFFICIAL,
            apiFormat = CcSwitchApiFormat.OPENAI_CHAT,
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            endpointCandidates = listOf(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "https://dashscope.aliyuncs.com/apps/anthropic",
            ),
            defaultModel = "qwen3-coder-plus",
            models = listOf(
                LlmModelInfo("qwen3-coder-plus", "Qwen3 Coder Plus", supportsThinking = true),
                LlmModelInfo("qwen3.5-plus", "Qwen3.5 Plus", supportsThinking = true),
                LlmModelInfo("qwen3-max", "Qwen3 Max", supportsThinking = true),
            ),
            modelMapping = CcSwitchModelMapping(
                defaultModel = "qwen3-coder-plus",
                haikuModel = "qwen3.5-plus",
                sonnetModel = "qwen3-coder-plus",
                opusModel = "qwen3-max",
            ),
            websiteUrl = "https://bailian.console.aliyun.com",
            description = "Alibaba Cloud DashScope / Model Studio Qwen endpoints.",
        ),
        CcSwitchProviderPreset(
            presetId = "xiaomi-mimo",
            defaultProviderId = "mimo",
            name = "Xiaomi MiMo",
            category = CcSwitchProviderCategory.CN_OFFICIAL,
            apiFormat = CcSwitchApiFormat.OPENAI_CHAT,
            baseUrl = "https://api.xiaomimimo.com",
            endpointCandidates = listOf(
                "https://api.xiaomimimo.com",
                "https://api.xiaomimimo.com/anthropic",
            ),
            defaultModel = "mimo-v2.6-pro",
            models = listOf(
                LlmModelInfo("mimo-v2.6-pro", "MiMo V2.6 Pro", supportsThinking = true),
                LlmModelInfo("mimo-v2.6-flash", "MiMo V2.6 Flash", supportsThinking = true),
                LlmModelInfo("mimo-v2.6-pro-ultraspeed", "MiMo V2.6 Pro UltraSpeed", supportsThinking = true),
                LlmModelInfo("mimo-v2.5-pro", "MiMo V2.5 Pro", supportsThinking = true),
            ),
            modelMapping = CcSwitchModelMapping(
                defaultModel = "mimo-v2.6-pro",
                haikuModel = "mimo-v2.6-flash",
                sonnetModel = "mimo-v2.6-pro",
                opusModel = "mimo-v2.6-pro-ultraspeed",
            ),
            websiteUrl = "https://platform.xiaomimimo.com",
            description = "Xiaomi MiMo V2.6 reasoning & coding API.",
        ),
        CcSwitchProviderPreset(
            presetId = "meta-muse",
            defaultProviderId = "muse",
            name = "Meta Muse",
            category = CcSwitchProviderCategory.OFFICIAL,
            apiFormat = CcSwitchApiFormat.OPENAI_RESPONSES,
            baseUrl = "https://api.meta.ai",
            endpointCandidates = listOf("https://api.meta.ai"),
            defaultModel = "muse-spark-1.3",
            models = listOf(
                LlmModelInfo("muse-spark-1.3", "Muse Spark 1.3", supportsThinking = true),
                LlmModelInfo("muse-spark-1.1", "Muse Spark 1.1", supportsThinking = true),
                LlmModelInfo("muse-wonder", "Muse Wonder", supportsThinking = true),
                LlmModelInfo("muse-glimmer", "Muse Glimmer", supportsThinking = false),
            ),
            modelMapping = CcSwitchModelMapping(
                defaultModel = "muse-spark-1.3",
                haikuModel = "muse-glimmer",
                sonnetModel = "muse-spark-1.3",
                opusModel = "muse-wonder",
            ),
            websiteUrl = "https://ai.meta.com",
            description = "Meta Muse Spark, Wonder, and Glimmer models.",
        ),
        CcSwitchProviderPreset(
            presetId = "grok-official",
            defaultProviderId = "grok",
            name = "xAI Grok",
            category = CcSwitchProviderCategory.OFFICIAL,
            apiFormat = CcSwitchApiFormat.OPENAI_CHAT,
            baseUrl = "https://api.x.ai/v1",
            endpointCandidates = listOf("https://api.x.ai/v1"),
            defaultModel = "grok-4",
            models = listOf(
                LlmModelInfo("grok-4", "Grok 4", supportsThinking = true),
                LlmModelInfo("grok-3", "Grok 3", supportsThinking = true),
                LlmModelInfo("grok-3-mini", "Grok 3 Mini", supportsThinking = true),
            ),
            modelMapping = CcSwitchModelMapping(
                defaultModel = "grok-4",
                haikuModel = "grok-3-mini",
                sonnetModel = "grok-3",
                opusModel = "grok-4",
            ),
            websiteUrl = "https://console.x.ai",
            description = "Official xAI Grok API.",
        ),
        CcSwitchProviderPreset(
            presetId = "siliconflow",
            defaultProviderId = "siliconflow",
            name = "SiliconFlow",
            category = CcSwitchProviderCategory.AGGREGATOR,
            apiFormat = CcSwitchApiFormat.OPENAI_CHAT,
            baseUrl = "https://api.siliconflow.cn/v1",
            endpointCandidates = listOf("https://api.siliconflow.cn/v1", "https://api.siliconflow.com/v1"),
            defaultModel = "Pro/deepseek-ai/DeepSeek-V3",
            models = listOf(
                LlmModelInfo("Pro/deepseek-ai/DeepSeek-V3", "DeepSeek V3 Pro", supportsThinking = false),
                LlmModelInfo("Pro/deepseek-ai/DeepSeek-R1", "DeepSeek R1 Pro", supportsThinking = true),
                LlmModelInfo("Qwen/Qwen3-Coder-480B-A35B-Instruct", "Qwen3 Coder 480B", supportsThinking = true),
            ),
            modelMapping = CcSwitchModelMapping(
                defaultModel = "Pro/deepseek-ai/DeepSeek-V3",
                haikuModel = "Pro/deepseek-ai/DeepSeek-V3",
                sonnetModel = "Qwen/Qwen3-Coder-480B-A35B-Instruct",
                opusModel = "Pro/deepseek-ai/DeepSeek-R1",
            ),
            websiteUrl = "https://siliconflow.cn",
            description = "SiliconFlow high-throughput cloud inference relay.",
        ),
        CcSwitchProviderPreset(
            presetId = "newapi-gateway",
            defaultProviderId = "gateway",
            name = "NewAPI / Custom Gateway",
            category = CcSwitchProviderCategory.AGGREGATOR,
            apiFormat = CcSwitchApiFormat.OPENAI_CHAT,
            baseUrl = "http://127.0.0.1:3000",
            endpointCandidates = listOf("http://127.0.0.1:3000", "http://127.0.0.1:4000"),
            defaultModel = "claude-sonnet-5",
            models = listOf(
                LlmModelInfo("claude-sonnet-5", "Claude Sonnet 5", supportsThinking = true),
                LlmModelInfo("gpt-5", "GPT-5", supportsThinking = true),
                LlmModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash", supportsThinking = true),
            ),
            modelMapping = CcSwitchModelMapping(
                defaultModel = "claude-sonnet-5",
                haikuModel = "gemini-2.5-flash",
                sonnetModel = "claude-sonnet-5",
                opusModel = "gpt-5",
            ),
            websiteUrl = "https://github.com/Calcium-Ion/new-api",
            description = "Self-hosted NewAPI, OneAPI, LiteLLM, or custom OpenAI/Anthropic gateway.",
        ),
        CcSwitchProviderPreset(
            presetId = "ollama-local",
            defaultProviderId = "ollama",
            name = "Ollama / LM Studio (Local)",
            category = CcSwitchProviderCategory.ON_DEVICE,
            apiFormat = CcSwitchApiFormat.OPENAI_CHAT,
            baseUrl = "http://127.0.0.1:11434/v1",
            endpointCandidates = listOf("http://127.0.0.1:11434/v1", "http://127.0.0.1:1234/v1", "http://127.0.0.1:8080/v1"),
            defaultModel = "qwen2.5-coder:7b",
            models = listOf(
                LlmModelInfo("qwen2.5-coder:7b", "Qwen 2.5 Coder 7B", supportsThinking = false),
                LlmModelInfo("deepseek-r1:8b", "DeepSeek R1 8B", supportsThinking = true),
                LlmModelInfo("llama3.3:8b", "Llama 3.3 8B", supportsThinking = false),
            ),
            modelMapping = CcSwitchModelMapping(
                defaultModel = "qwen2.5-coder:7b",
                haikuModel = "qwen2.5-coder:7b",
                sonnetModel = "qwen2.5-coder:7b",
                opusModel = "deepseek-r1:8b",
            ),
            websiteUrl = "https://ollama.com",
            description = "Local Ollama, LM Studio, or llama.cpp OpenAI-compatible server.",
            requiresApiKey = false,
        ),
    )

    fun findPreset(idOrPresetId: String): CcSwitchProviderPreset? =
        PRESETS.firstOrNull {
            it.presetId.equals(idOrPresetId, ignoreCase = true) ||
                it.defaultProviderId.equals(idOrPresetId, ignoreCase = true)
        }

    fun toInitialProfile(
        preset: CcSwitchProviderPreset,
        priority: Int,
        inFailover: Boolean = false,
        inStack: Boolean = false,
    ): CcSwitchProviderProfile = CcSwitchProviderProfile(
        id = preset.defaultProviderId,
        providerKey = preset.defaultProviderId,
        name = preset.name,
        presetId = preset.presetId,
        category = preset.category,
        apiFormat = preset.apiFormat,
        baseUrl = preset.baseUrl,
        endpointCandidates = preset.endpointCandidates,
        apiKey = "",
        defaultModel = preset.defaultModel,
        models = preset.models,
        modelMapping = preset.modelMapping,
        inFailoverQueue = inFailover,
        failoverPriority = priority,
        inAggregationStack = inStack,
        requiresApiKey = preset.requiresApiKey,
        websiteUrl = preset.websiteUrl,
        notes = preset.description,
    )
}

/**
 * Result of executing a turn through the universal `cc-switch` router.
 */
data class CcSwitchRoutedTurnResult(
    val ok: Boolean,
    val providerId: String,
    val providerName: String,
    val resolvedModel: String,
    val apiFormat: CcSwitchApiFormat,
    val text: String = "",
    val thinking: String = "",
    val toolCalls: List<ContentPart.ToolUse> = emptyList(),
    val stopReason: StopReason = StopReason.END_TURN,
    val usage: TokenUsage = TokenUsage(),
    val latencyMs: Long = 0L,
    val failoversAttempted: Int = 0,
    val error: String = "",
)

/**
 * Universal protocol bridge and format converter ported from `cc-switch`'s
 * `src-tauri/src/proxy/providers/transform.rs` and `handlers.rs`.
 *
 * Parses inbound requests in:
 * - Anthropic Messages (`/v1/messages`, `/claude/v1/messages`)
 * - OpenAI Chat Completions (`/v1/chat/completions`, `/chat/completions`)
 * - OpenAI Responses (`/v1/responses`, `/responses`)
 * - Gemini Native (`/v1beta/models/{model}:generateContent` & `:streamGenerateContent`)
 *
 * Executes them against ANY configured upstream provider (Anthropic, OpenAI Chat, OpenAI Responses,
 * Gemini Native, Antigravity, Xiaomi MiMo, Meta Muse, OpenRouter, or On-Device LiteRT-LM),
 * and encodes the response back into the caller's requested wire format (both JSON and SSE).
 */
object CcSwitchProtocolBridge {
    private val BILLING_HEADER_PREFIX = "x-anthropic-billing-header:"

    data class ParsedInboundRequest(
        val inboundFormat: CcSwitchApiFormat,
        val requestedModel: String,
        val stream: Boolean,
        val llmRequest: LlmRequest,
    )

    fun parseAnthropicMessagesRequest(bodyJson: String): ParsedInboundRequest {
        val root = AgentJson.parseToJsonElement(bodyJson).asObj()
            ?: throw IllegalArgumentException("Invalid JSON object for /v1/messages")
        val model = root["model"].asStr()?.trim().orEmpty().ifBlank { "claude-sonnet-5" }
        val stream = root["stream"].asBool() ?: false
        val maxTokens = (root["max_tokens"].asInt() ?: 4096).coerceIn(1, 65536)

        val systemText = when (val sys = root["system"]) {
            is JsonPrimitive -> stripBillingHeader(sys.asStr().orEmpty())
            is JsonArray -> sys.mapNotNull { block ->
                val obj = block.asObj() ?: return@mapNotNull null
                val text = obj["text"].asStr() ?: return@mapNotNull null
                if (text.trimStart().startsWith(BILLING_HEADER_PREFIX, ignoreCase = true)) null else text
            }.joinToString("\n\n").trim()
            else -> ""
        }.ifBlank { null }

        val thinkingObj = root["thinking"].asObj()
        val thinkingType = thinkingObj?.get("type").asStr()?.lowercase()
        val thinkingEnabled = thinkingType == "enabled" || thinkingType == "adaptive"
        val thinkingBudget = thinkingObj?.get("budget_tokens").asInt()
        val effort = root["output_config"].asObj()?.get("effort").asStr()
            ?: if (thinkingEnabled) LlmEffort.MEDIUM else null

        val tools = root["tools"].asArr()?.mapNotNull { toolEl ->
            val obj = toolEl.asObj() ?: return@mapNotNull null
            val name = obj["name"].asStr()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val schema = obj["input_schema"]?.toString() ?: """{"type":"object","properties":{}}"""
            ToolSpec(
                name = name,
                description = obj["description"].asStr().orEmpty(),
                parameters = schema,
            )
        }.orEmpty()

        val messages = mutableListOf<LlmMessage>()
        root["messages"].asArr()?.forEach { msgEl ->
            val msgObj = msgEl.asObj() ?: return@forEach
            val roleStr = msgObj["role"].asStr()?.lowercase().orEmpty()
            val contentEl = msgObj["content"]
            when (roleStr) {
                "user" -> {
                    val parts = parseAnthropicContentParts(contentEl)
                    val toolResults = parts.filterIsInstance<ContentPart.ToolResultPart>()
                    val otherParts = parts.filterNot { it is ContentPart.ToolResultPart }
                    if (toolResults.isNotEmpty()) {
                        messages += LlmMessage(LlmRole.TOOL, toolResults)
                    }
                    if (otherParts.isNotEmpty()) {
                        messages += LlmMessage(LlmRole.USER, otherParts)
                    }
                }
                "assistant" -> {
                    val parts = parseAnthropicContentParts(contentEl)
                    if (parts.isNotEmpty()) {
                        messages += LlmMessage(LlmRole.ASSISTANT, parts)
                    }
                }
                "system" -> {
                    val parts = parseAnthropicContentParts(contentEl).filterIsInstance<ContentPart.Text>()
                    if (parts.isNotEmpty()) {
                        messages += LlmMessage(LlmRole.SYSTEM, parts)
                    }
                }
            }
        }
        if (messages.isEmpty()) {
            messages += LlmMessage.user("Hello")
        }

        return ParsedInboundRequest(
            inboundFormat = CcSwitchApiFormat.ANTHROPIC_MESSAGES,
            requestedModel = model,
            stream = stream,
            llmRequest = LlmRequest(
                model = model,
                system = systemText,
                messages = messages,
                tools = tools,
                maxTokens = maxTokens,
                thinking = thinkingEnabled,
                thinkingBudget = thinkingBudget,
                effort = effort,
            ),
        )
    }

    private fun parseAnthropicContentParts(contentEl: JsonElement?): List<ContentPart> {
        if (contentEl is JsonPrimitive) {
            val text = contentEl.asStr().orEmpty()
            return if (text.isNotEmpty()) listOf(ContentPart.Text(text)) else emptyList()
        }
        val arr = contentEl.asArr() ?: return emptyList()
        val out = mutableListOf<ContentPart>()
        for (item in arr) {
            val obj = item.asObj() ?: continue
            when (obj["type"].asStr()) {
                "text" -> obj["text"].asStr()?.let { out += ContentPart.Text(it) }
                "thinking" -> {
                    val thought = obj["thinking"].asStr().orEmpty()
                    val sig = obj["signature"].asStr()
                    if (thought.isNotEmpty()) out += ContentPart.Thinking(thought, sig)
                }
                "tool_use" -> {
                    val id = obj["id"].asStr() ?: "call_${UUID.randomUUID().toString().take(8)}"
                    val name = obj["name"].asStr() ?: "tool"
                    val input = obj["input"]?.toString() ?: "{}"
                    out += ContentPart.ToolUse(id, name, input)
                }
                "tool_result" -> {
                    val toolUseId = obj["tool_use_id"].asStr() ?: "call"
                    val isErr = obj["is_error"].asBool() ?: false
                    val c = obj["content"]
                    val textContent = when (c) {
                        is JsonPrimitive -> c.asStr().orEmpty()
                        is JsonArray -> c.mapNotNull { it.asObj()?.get("text").asStr() }.joinToString("\n")
                        else -> c?.toString().orEmpty()
                    }
                    out += ContentPart.ToolResultPart(toolUseId, textContent, isError = isErr)
                }
            }
        }
        return out
    }

    fun parseOpenAiChatRequest(bodyJson: String): ParsedInboundRequest {
        val root = AgentJson.parseToJsonElement(bodyJson).asObj()
            ?: throw IllegalArgumentException("Invalid JSON object for /v1/chat/completions")
        val model = root["model"].asStr()?.trim().orEmpty().ifBlank { "gpt-5" }
        val stream = root["stream"].asBool() ?: false
        val maxTokens = (root["max_tokens"].asInt() ?: root["max_completion_tokens"].asInt() ?: 4096).coerceIn(1, 65536)
        val reasoningEffort = root["reasoning_effort"].asStr()
        val thinkingType = root["thinking"].asObj()?.get("type").asStr()
        val thinking = thinkingType == "enabled" || (!reasoningEffort.isNullOrBlank() && reasoningEffort != "none")

        val tools = root["tools"].asArr()?.mapNotNull { toolEl ->
            val obj = toolEl.asObj() ?: return@mapNotNull null
            val fn = obj["function"].asObj() ?: obj
            val name = fn["name"].asStr()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val params = fn["parameters"]?.toString() ?: """{"type":"object","properties":{}}"""
            ToolSpec(
                name = name,
                description = fn["description"].asStr().orEmpty(),
                parameters = params,
            )
        }.orEmpty()

        val systemParts = mutableListOf<String>()
        val messages = mutableListOf<LlmMessage>()
        root["messages"].asArr()?.forEach { msgEl ->
            val obj = msgEl.asObj() ?: return@forEach
            val role = obj["role"].asStr()?.lowercase().orEmpty()
            val text = extractOpenAiContentText(obj["content"])
            when (role) {
                "system", "developer" -> {
                    if (text.isNotBlank()) systemParts += stripBillingHeader(text)
                }
                "user" -> {
                    messages += LlmMessage.user(text)
                }
                "assistant" -> {
                    val parts = mutableListOf<ContentPart>()
                    val reasoning = obj["reasoning_content"].asStr() ?: obj["reasoning"].asStr()
                    if (!reasoning.isNullOrBlank()) parts += ContentPart.Thinking(reasoning)
                    if (text.isNotBlank()) parts += ContentPart.Text(text)
                    obj["tool_calls"].asArr()?.forEach { tcEl ->
                        val tc = tcEl.asObj() ?: return@forEach
                        val id = tc["id"].asStr() ?: "call_${UUID.randomUUID().toString().take(8)}"
                        val fn = tc["function"].asObj()
                        val name = fn?.get("name").asStr() ?: "tool"
                        val args = fn?.get("arguments").asStr() ?: "{}"
                        parts += ContentPart.ToolUse(id, name, args)
                    }
                    if (parts.isNotEmpty()) messages += LlmMessage.assistant(parts)
                }
                "tool" -> {
                    val callId = obj["tool_call_id"].asStr() ?: "call"
                    messages += LlmMessage.toolResult(callId, text, isError = false)
                }
            }
        }
        val singlePrompt = root["prompt"].asStr()
        if (messages.isEmpty() && !singlePrompt.isNullOrBlank()) {
            messages += LlmMessage.user(singlePrompt)
        }
        if (messages.isEmpty()) {
            messages += LlmMessage.user("Hello")
        }

        return ParsedInboundRequest(
            inboundFormat = CcSwitchApiFormat.OPENAI_CHAT,
            requestedModel = model,
            stream = stream,
            llmRequest = LlmRequest(
                model = model,
                system = systemParts.joinToString("\n\n").trim().ifBlank { null },
                messages = messages,
                tools = tools,
                maxTokens = maxTokens,
                thinking = thinking,
                effort = reasoningEffort,
            ),
        )
    }

    fun parseOpenAiResponsesRequest(bodyJson: String): ParsedInboundRequest {
        val root = AgentJson.parseToJsonElement(bodyJson).asObj()
            ?: throw IllegalArgumentException("Invalid JSON object for /v1/responses")
        val model = root["model"].asStr()?.trim().orEmpty().ifBlank { "gpt-6.1-sol" }
        val stream = root["stream"].asBool() ?: false
        val maxTokens = (root["max_output_tokens"].asInt() ?: root["max_tokens"].asInt() ?: 4096).coerceIn(1, 65536)
        val instructions = root["instructions"].asStr()?.let(::stripBillingHeader)?.ifBlank { null }
        val effort = root["reasoning"].asObj()?.get("effort").asStr()

        val tools = root["tools"].asArr()?.mapNotNull { toolEl ->
            val obj = toolEl.asObj() ?: return@mapNotNull null
            val fn = obj["function"].asObj() ?: obj
            val name = fn["name"].asStr()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val params = fn["parameters"]?.toString() ?: """{"type":"object","properties":{}}"""
            ToolSpec(
                name = name,
                description = fn["description"].asStr().orEmpty(),
                parameters = params,
            )
        }.orEmpty()

        val messages = mutableListOf<LlmMessage>()
        val inputEl = root["input"]
        when (inputEl) {
            is JsonPrimitive -> {
                val txt = inputEl.asStr().orEmpty()
                if (txt.isNotBlank()) messages += LlmMessage.user(txt)
            }
            is JsonArray -> {
                inputEl.forEach { itemEl ->
                    val obj = itemEl.asObj() ?: return@forEach
                    val type = obj["type"].asStr().orEmpty()
                    val role = obj["role"].asStr()?.lowercase().orEmpty()
                    when {
                        type == "function_call_output" -> {
                            val callId = obj["call_id"].asStr() ?: "call"
                            val output = obj["output"].asStr().orEmpty()
                            messages += LlmMessage.toolResult(callId, output, isError = false)
                        }
                        type == "function_call" -> {
                            val callId = obj["call_id"].asStr() ?: obj["id"].asStr() ?: "call"
                            val name = obj["name"].asStr() ?: "tool"
                            val args = obj["arguments"].asStr() ?: "{}"
                            messages += LlmMessage.assistant(listOf(ContentPart.ToolUse(callId, name, args)))
                        }
                        role == "user" -> {
                            messages += LlmMessage.user(extractOpenAiContentText(obj["content"]))
                        }
                        role == "assistant" -> {
                            val txt = extractOpenAiContentText(obj["content"])
                            if (txt.isNotBlank()) messages += LlmMessage.assistant(listOf(ContentPart.Text(txt)))
                        }
                        role == "developer" || role == "system" -> {
                            val txt = extractOpenAiContentText(obj["content"])
                            if (txt.isNotBlank()) messages += LlmMessage(LlmRole.SYSTEM, listOf(ContentPart.Text(txt)))
                        }
                    }
                }
            }
            else -> Unit
        }
        if (messages.isEmpty()) {
            messages += LlmMessage.user("Hello")
        }

        return ParsedInboundRequest(
            inboundFormat = CcSwitchApiFormat.OPENAI_RESPONSES,
            requestedModel = model,
            stream = stream,
            llmRequest = LlmRequest(
                model = model,
                system = instructions,
                messages = messages,
                tools = tools,
                maxTokens = maxTokens,
                thinking = effort != "none",
                effort = effort,
            ),
        )
    }

    fun parseGeminiNativeRequest(path: String, query: String?, bodyJson: String): ParsedInboundRequest {
        val root = AgentJson.parseToJsonElement(bodyJson).asObj()
            ?: throw IllegalArgumentException("Invalid JSON object for Gemini generateContent")
        val modelFromPath = path
            .substringAfter("/models/", "gemini-3.8-flash")
            .substringBefore(':')
            .ifBlank { "gemini-3.8-flash" }
        val stream = path.contains(":streamGenerateContent") || (query?.contains("alt=sse") == true)
        val systemObj = (root["system_instruction"] ?: root["systemInstruction"]).asObj()
        val systemText = systemObj?.get("parts").asArr()
            ?.mapNotNull { it.asObj()?.get("text").asStr() }
            ?.joinToString("\n")
            ?.ifBlank { null }

        val genCfg = root["generationConfig"].asObj()
        val maxTokens = (genCfg?.get("maxOutputTokens").asInt() ?: 4096).coerceIn(1, 65536)

        val tools = mutableListOf<ToolSpec>()
        root["tools"].asArr()?.forEach { toolGroup ->
            toolGroup.asObj()?.get("function_declarations")?.asArr()?.forEach { fnEl ->
                val fn = fnEl.asObj() ?: return@forEach
                val name = fn["name"].asStr()?.takeIf { it.isNotBlank() } ?: return@forEach
                tools += ToolSpec(
                    name = name,
                    description = fn["description"].asStr().orEmpty(),
                    parameters = fn["parameters"]?.toString() ?: """{"type":"object","properties":{}}""",
                )
            }
        }

        val messages = mutableListOf<LlmMessage>()
        root["contents"].asArr()?.forEach { contentEl ->
            val obj = contentEl.asObj() ?: return@forEach
            val role = obj["role"].asStr()?.lowercase() ?: "user"
            val parts = obj["parts"].asArr().orEmpty()
            val textBuilder = StringBuilder()
            val toolUses = mutableListOf<ContentPart.ToolUse>()
            val toolResults = mutableListOf<ContentPart.ToolResultPart>()
            parts.forEach { pEl ->
                val p = pEl.asObj() ?: return@forEach
                p["text"].asStr()?.let {
                    if (textBuilder.isNotEmpty()) textBuilder.append('\n')
                    textBuilder.append(it)
                }
                p["functionCall"].asObj()?.let { fc ->
                    val name = fc["name"].asStr() ?: "tool"
                    val args = fc["args"]?.toString() ?: "{}"
                    toolUses += ContentPart.ToolUse(name, name, args)
                }
                p["functionResponse"].asObj()?.let { fr ->
                    val name = fr["name"].asStr() ?: "tool"
                    val res = fr["response"].asObj()?.get("result").asStr()
                        ?: fr["response"]?.toString().orEmpty()
                    toolResults += ContentPart.ToolResultPart(name, res, isError = false)
                }
            }
            when {
                toolResults.isNotEmpty() -> messages += LlmMessage(LlmRole.TOOL, toolResults)
                role == "model" || role == "assistant" -> {
                    val assistantParts = mutableListOf<ContentPart>()
                    if (textBuilder.isNotEmpty()) assistantParts += ContentPart.Text(textBuilder.toString())
                    assistantParts += toolUses
                    if (assistantParts.isNotEmpty()) messages += LlmMessage.assistant(assistantParts)
                }
                else -> {
                    messages += LlmMessage.user(textBuilder.toString())
                }
            }
        }
        if (messages.isEmpty()) {
            messages += LlmMessage.user("Hello")
        }

        return ParsedInboundRequest(
            inboundFormat = CcSwitchApiFormat.GEMINI_NATIVE,
            requestedModel = modelFromPath,
            stream = stream,
            llmRequest = LlmRequest(
                model = modelFromPath,
                system = systemText,
                messages = messages,
                tools = tools,
                maxTokens = maxTokens,
                thinking = true,
            ),
        )
    }

    private fun extractOpenAiContentText(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.asStr().orEmpty()
        is JsonArray -> content.mapNotNull { el ->
            val obj = el.asObj()
            obj?.get("text").asStr() ?: el.asStr()
        }.joinToString("\n")
        else -> ""
    }

    private fun stripBillingHeader(text: String): String {
        val lines = text.lines()
        val filtered = lines.filterNot { it.trimStart().startsWith(BILLING_HEADER_PREFIX, ignoreCase = true) }
        return filtered.joinToString("\n").trim()
    }

    fun formatAnthropicResponse(result: CcSwitchRoutedTurnResult): String = buildJsonObject {
        put("id", "msg_${UUID.randomUUID().toString().replace("-", "").take(24)}")
        put("type", "message")
        put("role", "assistant")
        put("model", result.resolvedModel)
        put(
            "content",
            buildJsonArray {
                if (result.thinking.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put("type", "thinking")
                            put("thinking", result.thinking)
                            put("signature", "cc-switch-thinking")
                        },
                    )
                }
                if (result.text.isNotBlank() || result.toolCalls.isEmpty()) {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", result.text)
                        },
                    )
                }
                result.toolCalls.forEach { tc ->
                    val parsedInput = runCatching { AgentJson.parseToJsonElement(tc.arguments) }
                        .getOrElse { buildJsonObject {} }
                    add(
                        buildJsonObject {
                            put("type", "tool_use")
                            put("id", tc.id)
                            put("name", tc.name)
                            put("input", parsedInput)
                        },
                    )
                }
            },
        )
        put(
            "stop_reason",
            when (result.stopReason) {
                StopReason.TOOL_USE -> "tool_use"
                StopReason.MAX_TOKENS -> "max_tokens"
                else -> "end_turn"
            },
        )
        put("stop_sequence", JsonNull)
        put(
            "usage",
            buildJsonObject {
                put("input_tokens", result.usage.inputTokens)
                put("output_tokens", result.usage.outputTokens)
                put("cache_read_input_tokens", result.usage.cacheReadTokens)
                put("cache_creation_input_tokens", result.usage.cacheWriteTokens)
            },
        )
    }.toString()

    fun formatAnthropicSse(result: CcSwitchRoutedTurnResult): String {
        val msgId = "msg_${UUID.randomUUID().toString().replace("-", "").take(24)}"
        val sb = StringBuilder()
        fun appendEvent(event: String, data: JsonObject) {
            sb.append("event: ").append(event).append('\n')
            sb.append("data: ").append(data.toString()).append("\n\n")
        }

        appendEvent(
            "message_start",
            buildJsonObject {
                put("type", "message_start")
                put(
                    "message",
                    buildJsonObject {
                        put("id", msgId)
                        put("type", "message")
                        put("role", "assistant")
                        put("model", result.resolvedModel)
                        put("content", buildJsonArray {})
                        put("stop_reason", JsonNull)
                        put(
                            "usage",
                            buildJsonObject {
                                put("input_tokens", result.usage.inputTokens)
                                put("output_tokens", 0)
                            },
                        )
                    },
                )
            },
        )

        var blockIndex = 0
        if (result.thinking.isNotBlank()) {
            appendEvent(
                "content_block_start",
                buildJsonObject {
                    put("type", "content_block_start")
                    put("index", blockIndex)
                    put("content_block", buildJsonObject { put("type", "thinking"); put("thinking", "") })
                },
            )
            appendEvent(
                "content_block_delta",
                buildJsonObject {
                    put("type", "content_block_delta")
                    put("index", blockIndex)
                    put(
                        "delta",
                        buildJsonObject {
                            put("type", "thinking_delta")
                            put("thinking", result.thinking)
                        },
                    )
                },
            )
            appendEvent(
                "content_block_stop",
                buildJsonObject { put("type", "content_block_stop"); put("index", blockIndex) },
            )
            blockIndex++
        }

        if (result.text.isNotBlank() || result.toolCalls.isEmpty()) {
            appendEvent(
                "content_block_start",
                buildJsonObject {
                    put("type", "content_block_start")
                    put("index", blockIndex)
                    put("content_block", buildJsonObject { put("type", "text"); put("text", "") })
                },
            )
            appendEvent(
                "content_block_delta",
                buildJsonObject {
                    put("type", "content_block_delta")
                    put("index", blockIndex)
                    put(
                        "delta",
                        buildJsonObject {
                            put("type", "text_delta")
                            put("text", result.text)
                        },
                    )
                },
            )
            appendEvent(
                "content_block_stop",
                buildJsonObject { put("type", "content_block_stop"); put("index", blockIndex) },
            )
            blockIndex++
        }

        result.toolCalls.forEach { tc ->
            appendEvent(
                "content_block_start",
                buildJsonObject {
                    put("type", "content_block_start")
                    put("index", blockIndex)
                    put(
                        "content_block",
                        buildJsonObject {
                            put("type", "tool_use")
                            put("id", tc.id)
                            put("name", tc.name)
                            put("input", buildJsonObject {})
                        },
                    )
                },
            )
            appendEvent(
                "content_block_delta",
                buildJsonObject {
                    put("type", "content_block_delta")
                    put("index", blockIndex)
                    put(
                        "delta",
                        buildJsonObject {
                            put("type", "input_json_delta")
                            put("partial_json", tc.arguments.ifBlank { "{}" })
                        },
                    )
                },
            )
            appendEvent(
                "content_block_stop",
                buildJsonObject { put("type", "content_block_stop"); put("index", blockIndex) },
            )
            blockIndex++
        }

        appendEvent(
            "message_delta",
            buildJsonObject {
                put("type", "message_delta")
                put(
                    "delta",
                    buildJsonObject {
                        put(
                            "stop_reason",
                            when (result.stopReason) {
                                StopReason.TOOL_USE -> "tool_use"
                                StopReason.MAX_TOKENS -> "max_tokens"
                                else -> "end_turn"
                            },
                        )
                    },
                )
                put("usage", buildJsonObject { put("output_tokens", result.usage.outputTokens) })
            },
        )
        appendEvent("message_stop", buildJsonObject { put("type", "message_stop") })
        return sb.toString()
    }

    fun formatOpenAiChatResponse(result: CcSwitchRoutedTurnResult): String = buildJsonObject {
        put("id", "chatcmpl-${UUID.randomUUID().toString().take(12)}")
        put("object", "chat.completion")
        put("created", System.currentTimeMillis() / 1000L)
        put("model", result.resolvedModel)
        put("provider", result.providerId)
        put(
            "choices",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("index", 0)
                        put(
                            "message",
                            buildJsonObject {
                                put("role", "assistant")
                                put("content", result.text)
                                if (result.thinking.isNotBlank()) {
                                    put("reasoning_content", result.thinking)
                                }
                                if (result.toolCalls.isNotEmpty()) {
                                    put(
                                        "tool_calls",
                                        buildJsonArray {
                                            result.toolCalls.forEach { tc ->
                                                add(
                                                    buildJsonObject {
                                                        put("id", tc.id)
                                                        put("type", "function")
                                                        put(
                                                            "function",
                                                            buildJsonObject {
                                                                put("name", tc.name)
                                                                put("arguments", tc.arguments.ifBlank { "{}" })
                                                            },
                                                        )
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }
                            },
                        )
                        put(
                            "finish_reason",
                            when (result.stopReason) {
                                StopReason.TOOL_USE -> "tool_calls"
                                StopReason.MAX_TOKENS -> "length"
                                else -> "stop"
                            },
                        )
                    },
                )
            },
        )
        put(
            "usage",
            buildJsonObject {
                put("prompt_tokens", result.usage.promptTokens)
                put("completion_tokens", result.usage.outputTokens)
                put("total_tokens", result.usage.promptTokens + result.usage.outputTokens)
            },
        )
    }.toString()

    fun formatOpenAiChatSse(result: CcSwitchRoutedTurnResult): String {
        val id = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
        val created = System.currentTimeMillis() / 1000L
        val chunk = buildJsonObject {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", created)
            put("model", result.resolvedModel)
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("index", 0)
                            put(
                                "delta",
                                buildJsonObject {
                                    put("role", "assistant")
                                    if (result.thinking.isNotBlank()) {
                                        put("reasoning_content", result.thinking)
                                    }
                                    if (result.text.isNotBlank() || result.toolCalls.isEmpty()) {
                                        put("content", result.text)
                                    }
                                    if (result.toolCalls.isNotEmpty()) {
                                        put(
                                            "tool_calls",
                                            buildJsonArray {
                                                result.toolCalls.forEachIndexed { idx, tc ->
                                                    add(
                                                        buildJsonObject {
                                                            put("index", idx)
                                                            put("id", tc.id)
                                                            put("type", "function")
                                                            put(
                                                                "function",
                                                                buildJsonObject {
                                                                    put("name", tc.name)
                                                                    put("arguments", tc.arguments.ifBlank { "{}" })
                                                                },
                                                            )
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    }
                                },
                            )
                            put(
                                "finish_reason",
                                when (result.stopReason) {
                                    StopReason.TOOL_USE -> "tool_calls"
                                    StopReason.MAX_TOKENS -> "length"
                                    else -> "stop"
                                },
                            )
                        },
                    )
                },
            )
            put(
                "usage",
                buildJsonObject {
                    put("prompt_tokens", result.usage.promptTokens)
                    put("completion_tokens", result.usage.outputTokens)
                    put("total_tokens", result.usage.promptTokens + result.usage.outputTokens)
                },
            )
        }
        return "data: $chunk\n\ndata: [DONE]\n\n"
    }

    fun formatOpenAiResponsesJson(result: CcSwitchRoutedTurnResult): String = buildJsonObject {
        val respId = "resp_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        put("id", respId)
        put("object", "response")
        put("status", "completed")
        put("model", result.resolvedModel)
        put(
            "output",
            buildJsonArray {
                if (result.text.isNotBlank() || result.toolCalls.isEmpty()) {
                    add(
                        buildJsonObject {
                            put("type", "message")
                            put("id", "msg_${UUID.randomUUID().toString().take(8)}")
                            put("role", "assistant")
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "output_text")
                                            put("text", result.text)
                                        },
                                    )
                                },
                            )
                        },
                    )
                }
                result.toolCalls.forEach { tc ->
                    add(
                        buildJsonObject {
                            put("type", "function_call")
                            put("id", "fc_${UUID.randomUUID().toString().take(8)}")
                            put("call_id", tc.id)
                            put("name", tc.name)
                            put("arguments", tc.arguments.ifBlank { "{}" })
                        },
                    )
                }
            },
        )
        put(
            "usage",
            buildJsonObject {
                put("input_tokens", result.usage.promptTokens)
                put("output_tokens", result.usage.outputTokens)
                put("total_tokens", result.usage.promptTokens + result.usage.outputTokens)
            },
        )
    }.toString()

    fun formatOpenAiResponsesSse(result: CcSwitchRoutedTurnResult): String {
        val sb = StringBuilder()
        fun emitData(obj: JsonObject) {
            sb.append("data: ").append(obj.toString()).append("\n\n")
        }
        if (result.thinking.isNotBlank()) {
            emitData(
                buildJsonObject {
                    put("type", "response.reasoning_summary_text.delta")
                    put("delta", result.thinking)
                },
            )
        }
        if (result.text.isNotBlank() || result.toolCalls.isEmpty()) {
            emitData(
                buildJsonObject {
                    put("type", "response.output_text.delta")
                    put("delta", result.text)
                },
            )
        }
        result.toolCalls.forEachIndexed { idx, tc ->
            val itemId = "fc_${idx + 1}"
            emitData(
                buildJsonObject {
                    put("type", "response.output_item.added")
                    put(
                        "item",
                        buildJsonObject {
                            put("type", "function_call")
                            put("id", itemId)
                            put("call_id", tc.id)
                            put("name", tc.name)
                        },
                    )
                },
            )
            emitData(
                buildJsonObject {
                    put("type", "response.output_item.done")
                    put(
                        "item",
                        buildJsonObject {
                            put("type", "function_call")
                            put("id", itemId)
                            put("call_id", tc.id)
                            put("name", tc.name)
                            put("arguments", tc.arguments.ifBlank { "{}" })
                        },
                    )
                },
            )
        }
        emitData(
            buildJsonObject {
                put("type", "response.completed")
                put(
                    "response",
                    buildJsonObject {
                        put("status", "completed")
                        put(
                            "usage",
                            buildJsonObject {
                                put("input_tokens", result.usage.promptTokens)
                                put("output_tokens", result.usage.outputTokens)
                            },
                        )
                    },
                )
            },
        )
        sb.append("data: [DONE]\n\n")
        return sb.toString()
    }

    fun formatGeminiNativeJson(result: CcSwitchRoutedTurnResult): String = buildJsonObject {
        put(
            "candidates",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put(
                            "content",
                            buildJsonObject {
                                put("role", "model")
                                put(
                                    "parts",
                                    buildJsonArray {
                                        if (result.thinking.isNotBlank()) {
                                            add(
                                                buildJsonObject {
                                                    put("thought", true)
                                                    put("text", result.thinking)
                                                },
                                            )
                                        }
                                        if (result.text.isNotBlank() || result.toolCalls.isEmpty()) {
                                            add(buildJsonObject { put("text", result.text) })
                                        }
                                        result.toolCalls.forEach { tc ->
                                            val argsObj = runCatching { AgentJson.parseToJsonElement(tc.arguments) }
                                                .getOrElse { buildJsonObject {} }
                                            add(
                                                buildJsonObject {
                                                    put(
                                                        "functionCall",
                                                        buildJsonObject {
                                                            put("name", tc.name)
                                                            put("args", argsObj)
                                                        },
                                                    )
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                        put("finishReason", "STOP")
                    },
                )
            },
        )
        put(
            "usageMetadata",
            buildJsonObject {
                put("promptTokenCount", result.usage.promptTokens)
                put("candidatesTokenCount", result.usage.outputTokens)
                put("totalTokenCount", result.usage.promptTokens + result.usage.outputTokens)
            },
        )
    }.toString()

    fun formatGeminiNativeSse(result: CcSwitchRoutedTurnResult): String =
        "data: ${formatGeminiNativeJson(result)}\n\n"
}

/**
 * Status of a live CLI configuration sync target (Claude Code, Codex, Gemini CLI).
 */
data class CcSwitchLiveConfigTargetStatus(
    val toolId: String,
    val displayName: String,
    val configFilePath: String,
    val backupFilePath: String,
    val hasBackup: Boolean,
    val syncedMode: String,
    val syncedProviderId: String,
    val syncedEndpoint: String,
    val syncedModel: String,
    val lastSyncedEpochMs: Long,
)

/**
 * Non-destructive multi-tool configuration sync engine modeled after `cc-switch`'s live config manager:
 * - Creates a first-write backup (`backups/live-first-write/<tool>/...`) before modifying any pre-existing file.
 * - Updates only connection fields (`endpoint`, `key`, `model`, `modelMapping`) in:
 *   1. Claude Code: `.claude/settings.json`
 *   2. OpenAI Codex: `.codex/config.toml` & `.codex/auth.json`
 *   3. Google Gemini CLI: `.gemini/.env` & `.gemini/settings.json`
 * - Preserves all user hooks, MCP servers, permissions, plugins, and custom keys.
 * - Writes the `PROXY_MANAGED` (`127.0.0.1:<port>`) contract in `ROUTING` and `AGGREGATION` modes,
 *   and restores the active provider's direct connection contract in `DIRECT` mode.
 */
class CcSwitchLiveConfigSyncer(
    private val storageRoot: Path,
    private val targetRootProvider: () -> Path = { storageRoot.resolve("cli-configs") },
) {
    private val backupRoot: Path = storageRoot.resolve("backups").resolve("live-first-write")
    @Volatile private var lastSyncedMs: Long = 0L

    fun inspectTargets(
        mode: CcSwitchOperatingMode,
        activeProfile: CcSwitchProviderProfile,
        proxyPort: Int,
    ): List<CcSwitchLiveConfigTargetStatus> {
        val root = targetRootProvider()
        val effectiveEndpoint = when (mode) {
            CcSwitchOperatingMode.DIRECT -> activeProfile.baseUrl
            CcSwitchOperatingMode.ROUTING, CcSwitchOperatingMode.AGGREGATION -> "http://127.0.0.1:$proxyPort"
        }
        val effectiveModel = when (mode) {
            CcSwitchOperatingMode.AGGREGATION -> CcSwitchStackCodec.encodeAggregatedModel(
                activeProfile.providerKey,
                activeProfile.defaultModel,
            )
            else -> activeProfile.defaultModel
        }

        val claudeFile = root.resolve(".claude").resolve("settings.json")
        val claudeBackup = backupRoot.resolve("claude").resolve("settings.json")

        val codexFile = root.resolve(".codex").resolve("config.toml")
        val codexBackup = backupRoot.resolve("codex").resolve("config.toml")

        val geminiFile = root.resolve(".gemini").resolve(".env")
        val geminiBackup = backupRoot.resolve("gemini").resolve(".env")

        return listOf(
            CcSwitchLiveConfigTargetStatus(
                toolId = "claude",
                displayName = "Claude Code (.claude/settings.json)",
                configFilePath = claudeFile.toAbsolutePath().toString(),
                backupFilePath = claudeBackup.toAbsolutePath().toString(),
                hasBackup = Files.isRegularFile(claudeBackup),
                syncedMode = if (Files.isRegularFile(claudeFile)) mode.displayName else "Not synced yet",
                syncedProviderId = activeProfile.id,
                syncedEndpoint = effectiveEndpoint,
                syncedModel = effectiveModel,
                lastSyncedEpochMs = lastSyncedMs,
            ),
            CcSwitchLiveConfigTargetStatus(
                toolId = "codex",
                displayName = "OpenAI Codex (.codex/config.toml)",
                configFilePath = codexFile.toAbsolutePath().toString(),
                backupFilePath = codexBackup.toAbsolutePath().toString(),
                hasBackup = Files.isRegularFile(codexBackup),
                syncedMode = if (Files.isRegularFile(codexFile)) mode.displayName else "Not synced yet",
                syncedProviderId = activeProfile.id,
                syncedEndpoint = effectiveEndpoint,
                syncedModel = effectiveModel,
                lastSyncedEpochMs = lastSyncedMs,
            ),
            CcSwitchLiveConfigTargetStatus(
                toolId = "gemini",
                displayName = "Gemini CLI (.gemini/.env)",
                configFilePath = geminiFile.toAbsolutePath().toString(),
                backupFilePath = geminiBackup.toAbsolutePath().toString(),
                hasBackup = Files.isRegularFile(geminiBackup),
                syncedMode = if (Files.isRegularFile(geminiFile)) mode.displayName else "Not synced yet",
                syncedProviderId = activeProfile.id,
                syncedEndpoint = effectiveEndpoint,
                syncedModel = effectiveModel,
                lastSyncedEpochMs = lastSyncedMs,
            ),
        )
    }

    @Synchronized
    fun syncAll(
        mode: CcSwitchOperatingMode,
        activeProfile: CcSwitchProviderProfile,
        proxyPort: Int,
    ): List<CcSwitchLiveConfigTargetStatus> {
        val root = targetRootProvider()
        syncClaudeSettings(root, mode, activeProfile, proxyPort)
        syncCodexConfig(root, mode, activeProfile, proxyPort)
        syncGeminiConfig(root, mode, activeProfile, proxyPort)
        lastSyncedMs = System.currentTimeMillis()
        return inspectTargets(mode, activeProfile, proxyPort)
    }

    private fun backupBeforeFirstWrite(tool: String, targetFile: Path) {
        if (!Files.isRegularFile(targetFile)) return
        val backupFile = backupRoot.resolve(tool).resolve(targetFile.fileName.toString())
        if (Files.isRegularFile(backupFile)) return
        Files.createDirectories(backupFile.parent)
        Files.copy(targetFile, backupFile, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun syncClaudeSettings(
        root: Path,
        mode: CcSwitchOperatingMode,
        profile: CcSwitchProviderProfile,
        proxyPort: Int,
    ) {
        val claudeDir = root.resolve(".claude")
        Files.createDirectories(claudeDir)
        val settingsFile = claudeDir.resolve("settings.json")
        backupBeforeFirstWrite("claude", settingsFile)

        val existingObj = if (Files.isRegularFile(settingsFile)) {
            runCatching { AgentJson.parseToJsonElement(Files.readString(settingsFile)).asObj() }.getOrNull()
        } else null

        val existingEnv = existingObj?.get("env").asObj() ?: buildJsonObject {}
        val isProxy = mode != CcSwitchOperatingMode.DIRECT
        val baseUrl = if (isProxy) "http://127.0.0.1:$proxyPort" else profile.baseUrl
        val authToken = if (isProxy) "PROXY_MANAGED" else profile.apiKey.ifBlank { "LOCAL_PROVIDER" }
        val primaryModel = if (mode == CcSwitchOperatingMode.AGGREGATION) {
            CcSwitchStackCodec.encodeClaudeCodeModel(profile.providerKey, profile.defaultModel)
        } else {
            profile.defaultModel
        }
        val mapping = profile.modelMapping

        val newEnv = buildJsonObject {
            existingEnv.forEach { (k, v) -> put(k, v) }
            put("ANTHROPIC_BASE_URL", baseUrl)
            put("ANTHROPIC_AUTH_TOKEN", authToken)
            put("ANTHROPIC_MODEL", primaryModel)
            put("ANTHROPIC_DEFAULT_HAIKU_MODEL", mapping.haikuModel.ifBlank { primaryModel })
            put("ANTHROPIC_DEFAULT_SONNET_MODEL", mapping.sonnetModel.ifBlank { primaryModel })
            put("ANTHROPIC_DEFAULT_OPUS_MODEL", mapping.opusModel.ifBlank { primaryModel })
            put("CC_SWITCH_MODE", mode.id)
            put("CC_SWITCH_PROVIDER", profile.id)
        }

        val patched = buildJsonObject {
            existingObj?.forEach { (k, v) ->
                if (k != "env") put(k, v)
            }
            put("env", newEnv)
        }
        Files.writeString(settingsFile, patched.toString(), StandardCharsets.UTF_8)
    }

    private fun syncCodexConfig(
        root: Path,
        mode: CcSwitchOperatingMode,
        profile: CcSwitchProviderProfile,
        proxyPort: Int,
    ) {
        val codexDir = root.resolve(".codex")
        Files.createDirectories(codexDir)
        val configFile = codexDir.resolve("config.toml")
        val authFile = codexDir.resolve("auth.json")
        backupBeforeFirstWrite("codex", configFile)
        backupBeforeFirstWrite("codex", authFile)

        val isProxy = mode != CcSwitchOperatingMode.DIRECT
        val providerKey = if (isProxy) "cc_switch_proxy" else profile.providerKey.replace('-', '_')
        val baseUrl = if (isProxy) "http://127.0.0.1:$proxyPort/v1" else {
            val raw = profile.baseUrl.trimEnd('/')
            if (raw.endsWith("/v1")) raw else "$raw/v1"
        }
        val model = if (mode == CcSwitchOperatingMode.AGGREGATION) {
            CcSwitchStackCodec.encodeAggregatedModel(profile.providerKey, profile.defaultModel)
        } else {
            profile.defaultModel
        }

        val preservedLines = if (Files.isRegularFile(configFile)) {
            Files.readAllLines(configFile, StandardCharsets.UTF_8).filterNot { line ->
                val t = line.trim()
                t.startsWith("model_provider ") ||
                    t.startsWith("model_provider=") ||
                    t.startsWith("model ") ||
                    t.startsWith("model=") ||
                    t.startsWith("# Managed by CC-Switch")
            }
        } else emptyList()

        val tomlBuilder = StringBuilder()
        tomlBuilder.appendLine("# Managed by CC-Switch Universal LLM Corner (mode=${mode.id})")
        tomlBuilder.appendLine("""model_provider = "$providerKey"""")
        tomlBuilder.appendLine("""model = "$model"""")
        val sectionHeader = "[model_providers.$providerKey]"
        val filteredPreserved = mutableListOf<String>()
        var skippingManagedSection = false
        for (line in preservedLines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                skippingManagedSection = trimmed == sectionHeader || trimmed == "[model_providers.cc_switch_proxy]"
            }
            if (!skippingManagedSection) {
                filteredPreserved += line
            }
        }
        if (filteredPreserved.any { it.isNotBlank() }) {
            tomlBuilder.appendLine(filteredPreserved.joinToString("\n").trim())
        }
        tomlBuilder.appendLine()
        tomlBuilder.appendLine(sectionHeader)
        tomlBuilder.appendLine("""name = "${profile.name}"""")
        tomlBuilder.appendLine("""base_url = "$baseUrl"""")
        tomlBuilder.appendLine("""wire_api = "responses"""")

        Files.writeString(configFile, tomlBuilder.toString(), StandardCharsets.UTF_8)

        val existingAuth = if (Files.isRegularFile(authFile)) {
            runCatching { AgentJson.parseToJsonElement(Files.readString(authFile)).asObj() }.getOrNull()
        } else null
        val authJson = buildJsonObject {
            existingAuth?.forEach { (k, v) -> put(k, v) }
            put("OPENAI_API_KEY", if (isProxy) "PROXY_MANAGED" else profile.apiKey.ifBlank { "LOCAL_PROVIDER" })
        }
        Files.writeString(authFile, authJson.toString(), StandardCharsets.UTF_8)
    }

    private fun syncGeminiConfig(
        root: Path,
        mode: CcSwitchOperatingMode,
        profile: CcSwitchProviderProfile,
        proxyPort: Int,
    ) {
        val geminiDir = root.resolve(".gemini")
        Files.createDirectories(geminiDir)
        val envFile = geminiDir.resolve(".env")
        val settingsFile = geminiDir.resolve("settings.json")
        backupBeforeFirstWrite("gemini", envFile)
        backupBeforeFirstWrite("gemini", settingsFile)

        val isProxy = mode != CcSwitchOperatingMode.DIRECT
        val baseUrl = if (isProxy) "http://127.0.0.1:$proxyPort" else profile.baseUrl
        val apiKey = if (isProxy) "PROXY_MANAGED" else profile.apiKey.ifBlank { "LOCAL_PROVIDER" }
        val model = if (mode == CcSwitchOperatingMode.AGGREGATION) {
            CcSwitchStackCodec.encodeAggregatedModel(profile.providerKey, profile.defaultModel)
        } else {
            profile.defaultModel
        }

        val existingLines = if (Files.isRegularFile(envFile)) {
            Files.readAllLines(envFile, StandardCharsets.UTF_8)
        } else emptyList()

        val managedKeys = setOf("GOOGLE_GEMINI_BASE_URL", "GEMINI_API_KEY", "GEMINI_MODEL", "CC_SWITCH_MODE")
        val preserved = existingLines.filterNot { line ->
            val key = line.substringBefore('=').trim()
            key in managedKeys
        }

        val envBuilder = StringBuilder()
        if (preserved.isNotEmpty()) {
            envBuilder.appendLine(preserved.joinToString("\n"))
        }
        envBuilder.appendLine("GOOGLE_GEMINI_BASE_URL=$baseUrl")
        envBuilder.appendLine("GEMINI_API_KEY=$apiKey")
        envBuilder.appendLine("GEMINI_MODEL=$model")
        envBuilder.appendLine("CC_SWITCH_MODE=${mode.id}")
        Files.writeString(envFile, envBuilder.toString(), StandardCharsets.UTF_8)

        val existingSettings = if (Files.isRegularFile(settingsFile)) {
            runCatching { AgentJson.parseToJsonElement(Files.readString(settingsFile)).asObj() }.getOrNull()
        } else null
        val settingsJson = buildJsonObject {
            existingSettings?.forEach { (k, v) -> if (k != "security") put(k, v) }
            put(
                "security",
                buildJsonObject {
                    put("auth", buildJsonObject { put("selectedType", "gemini-api-key") })
                },
            )
        }
        Files.writeString(settingsFile, settingsJson.toString(), StandardCharsets.UTF_8)
    }
}
