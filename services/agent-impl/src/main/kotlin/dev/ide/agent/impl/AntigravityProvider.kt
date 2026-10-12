package dev.ide.agent.impl

import dev.ide.agent.LlmClient
import dev.ide.agent.LlmModelInfo
import dev.ide.agent.LlmProvider
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.ProviderConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * The Antigravity unified gateway provider. Reaches Google's Code Assist backend
 * (`cloudcode-pa.googleapis.com/v1internal:streamGenerateContent`) with an OAuth Bearer token (or delegates
 * to [GeminiProvider] when given a standard `AIza…` Gemini API key), giving access to Gemini 3 / Claude /
 * GPT-OSS models. The wire uses [GeminiWire] + [GeminiStreamDecoder] wrapped in a `{project, model, request}`
 * envelope with each streamed candidate nested under `response`.
 */
class AntigravityProvider(private val transport: LlmTransport) : LlmProvider {
    override val id: String = "antigravity"
    override val displayName: String = "Antigravity"
    override val models: List<LlmModelInfo> = listOf(
        LlmModelInfo("gemini-3.1-pro-high", "Gemini 3.1 Pro (high)", supportsThinking = true),
        LlmModelInfo("gemini-3-pro-high", "Gemini 3 Pro (high)", supportsThinking = true),
        LlmModelInfo("gemini-3-pro-low", "Gemini 3 Pro (low)", supportsThinking = true),
        LlmModelInfo("gemini-3-flash", "Gemini 3 Flash", supportsThinking = true),
        LlmModelInfo("claude-opus-4-6-thinking", "Claude Opus 4.6 (thinking)", supportsThinking = true),
        LlmModelInfo("claude-sonnet-4-6", "Claude Sonnet 4.6"),
        LlmModelInfo("gpt-oss-120b-medium", "GPT-OSS 120B"),
    )
    override val defaultModel: String = "gemini-3-pro-high"

    override fun client(config: ProviderConfig): LlmClient {
        if (config.apiKey.trim().startsWith("AIza")) {
            return GeminiProvider(transport).client(config)
        }
        val session = AntigravitySession(transport, config)
        return LlmClient { request -> stream(request, config, session) }
    }

    private fun stream(request: LlmRequest, config: ProviderConfig, session: AntigravitySession): Flow<LlmStreamEvent> = flow {
        val token = session.accessToken()
        val project = session.projectId(token)
        val base = config.baseUrl?.trimEnd('/') ?: DEFAULT_BASE
        val sse = SseRequest(
            url = "$base/$API_VERSION:streamGenerateContent?alt=sse",
            headers = apiHeaders(token),
            jsonBody = buildBody(request, project),
            caCertificatePem = config.caCertificatePem,
        )
        val decoder = GeminiStreamDecoder()
        transport.sse(sse).collect { data -> decoder.decode(unwrap(data)).forEach { emit(it) } }
        decoder.finish().forEach { emit(it) }
    }.catch { e -> emit(LlmStreamEvent.Failed(e.message ?: "Antigravity stream error", e)) }

    private fun buildBody(request: LlmRequest, project: String): String = buildJsonObject {
        put("project", project)
        put("model", request.model)
        put("request", buildJsonObject {
            request.system?.takeIf { it.isNotBlank() }?.let { put("systemInstruction", GeminiWire.systemInstruction(it)) }
            put("contents", GeminiWire.contents(request.messages))
            if (request.tools.isNotEmpty()) put("tools", GeminiWire.toolDeclarations(request.tools))
            put("generationConfig", buildJsonObject { put("maxOutputTokens", request.maxTokens) })
        })
        put("userAgent", "antigravity")
        put("requestId", UUID.randomUUID().toString())
    }.toString()

    /** Unwraps the `{ "response": { ... } }` gateway envelope so the standard Gemini decoder reads the inner
     *  candidate/usage payload; passes anything without that key through unchanged. */
    private fun unwrap(data: String): String {
        val obj = runCatching { AgentJson.parseToJsonElement(data).asObj() }.getOrNull() ?: return data
        return obj["response"]?.toString() ?: data
    }

    companion object {
        const val DEFAULT_BASE = "https://cloudcode-pa.googleapis.com"
        const val API_VERSION = "v1internal"

        const val USER_AGENT = "antigravity/1.15.8 linux/amd64"
        const val API_CLIENT = "google-cloud-sdk vscode_cloudshelleditor/0.1"
        const val CLIENT_METADATA = """{"ideType":"IDE_UNSPECIFIED","platform":"PLATFORM_UNSPECIFIED","pluginType":"GEMINI"}"""

        fun apiHeaders(accessToken: String): Map<String, String> = mapOf(
            "Authorization" to "Bearer $accessToken",
            "content-type" to "application/json",
            "User-Agent" to USER_AGENT,
            "X-Goog-Api-Client" to API_CLIENT,
            "Client-Metadata" to CLIENT_METADATA,
        )
    }
}

/**
 * Session-scoped OAuth + project state for [AntigravityProvider]. Exchanges a refresh token for short-lived
 * access tokens and resolves the Code Assist project id once via `<token>|<projectId>` or `loadCodeAssist`.
 */
internal class AntigravitySession(
    private val transport: LlmTransport,
    config: ProviderConfig,
) {
    private val base: String = config.baseUrl?.trimEnd('/') ?: AntigravityProvider.DEFAULT_BASE
    private val refreshToken: String?
    private val staticAccess: String?
    private val clientId: String
    private val clientSecret: String

    private val mutex = Mutex()
    private var project: String
    private var cachedAccess: String? = null
    private var accessExpiryMs: Long = 0

    init {
        val parts = config.apiKey.trim().split('|').map { it.trim() }
        val token = parts.getOrNull(0).orEmpty()
        project = parts.getOrNull(1).orEmpty()
        clientId = parts.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: System.getenv("ANTIGRAVITY_CLIENT_ID").orEmpty()
        clientSecret = parts.getOrNull(3)?.takeIf { it.isNotEmpty() } ?: System.getenv("ANTIGRAVITY_CLIENT_SECRET").orEmpty()
        if (token.startsWith("1//")) {
            refreshToken = token
            staticAccess = null
        } else {
            refreshToken = null
            staticAccess = token.takeIf { it.isNotEmpty() }
        }
    }

    suspend fun accessToken(): String {
        staticAccess?.let { return it }
        val refresh = refreshToken
            ?: throw IllegalStateException("Antigravity needs an OAuth token or API key. Configure one in the AI provider settings.")
        mutex.withLock {
            cachedAccess?.let { if (System.currentTimeMillis() < accessExpiryMs - EXPIRY_SKEW_MS) return it }
            val body = transport.postForm(
                TOKEN_URL,
                mapOf("content-type" to "application/x-www-form-urlencoded"),
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refresh,
                    "client_id" to clientId,
                    "client_secret" to clientSecret,
                ),
            )
            val obj = AgentJson.parseToJsonElement(body).asObj()
            val access = obj?.get("access_token").asStr()
                ?: throw IllegalStateException("Antigravity sign-in failed: no access token returned. Re-authenticate the account.")
            cachedAccess = access
            accessExpiryMs = System.currentTimeMillis() + (obj?.get("expires_in").asInt() ?: 3600) * 1000L
            return access
        }
    }

    suspend fun projectId(accessToken: String): String {
        if (project.isNotBlank()) return project
        mutex.withLock {
            if (project.isNotBlank()) return project
            project = discoverProject(accessToken)
            return project
        }
    }

    private suspend fun discoverProject(accessToken: String): String {
        runCatching { loadCodeAssist(accessToken) }.getOrNull()?.let { return it }
        runCatching { onboardFreeProject(accessToken) }.getOrNull()?.let { return it }
        throw IllegalStateException(
            "Antigravity could not resolve a Google Cloud project. Add one as '<token>|<projectId>' in the AI provider settings.",
        )
    }

    private suspend fun loadCodeAssist(accessToken: String): String? {
        val body = transport.post(
            "$base/${AntigravityProvider.API_VERSION}:loadCodeAssist",
            AntigravityProvider.apiHeaders(accessToken),
            buildJsonObject { put("metadata", metadata()) }.toString(),
        )
        val p = AgentJson.parseToJsonElement(body).asObj()?.get("cloudaicompanionProject")
        return p.asStr()?.takeIf { it.isNotBlank() } ?: p.asObj()?.get("id").asStr()?.takeIf { it.isNotBlank() }
    }

    private suspend fun onboardFreeProject(accessToken: String): String? {
        repeat(ONBOARD_ATTEMPTS) { attempt ->
            val body = transport.post(
                "$base/${AntigravityProvider.API_VERSION}:onboardUser",
                AntigravityProvider.apiHeaders(accessToken),
                buildJsonObject { put("tierId", "FREE"); put("metadata", metadata()) }.toString(),
            )
            val obj = AgentJson.parseToJsonElement(body).asObj()
            val id = obj?.get("response").asObj()?.get("cloudaicompanionProject").asObj()?.get("id").asStr()
            if (obj?.get("done").asBool() == true && !id.isNullOrBlank()) return id
            if (attempt < ONBOARD_ATTEMPTS - 1) delay(ONBOARD_DELAY_MS)
        }
        return null
    }

    private fun metadata() = buildJsonObject {
        put("ideType", "IDE_UNSPECIFIED")
        put("platform", "PLATFORM_UNSPECIFIED")
        put("pluginType", "GEMINI")
    }

    companion object {
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val EXPIRY_SKEW_MS = 60_000L
        const val ONBOARD_ATTEMPTS = 6
        const val ONBOARD_DELAY_MS = 2_000L
    }
}
