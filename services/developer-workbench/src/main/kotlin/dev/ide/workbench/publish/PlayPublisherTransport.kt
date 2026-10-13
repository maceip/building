package dev.ide.workbench.publish

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer

/** Non-default endpoints are allowed ONLY for explicitly opted-in loopback protocol tests. */
data class PlayPublisherConfig(
    val apiOrigin: String = "https://androidpublisher.googleapis.com/",
    val allowLoopbackForTests: Boolean = false,
    val chunkSizeBytes: Int = 2 * 1024 * 1024,
    val maxUploadRetries: Int = 3,
    val retryBaseDelayMillis: Long = 1000,
    val requestTimeoutMillis: Long = 120_000,
) {
    init {
        require(chunkSizeBytes in 262_144..16_777_216 && chunkSizeBytes % 262_144 == 0) { "Upload chunk size must be a multiple of 262144 between 256 KiB and 16 MiB." }
        require(maxUploadRetries in 0..8) { "Upload retry limit must be between zero and eight." }
        require(retryBaseDelayMillis in 0..60_000) { "Upload retry delay is invalid." }
        require(requestTimeoutMillis in 1..600_000) { "Request timeout is invalid." }
    }
}

internal val playJson = Json { ignoreUnknownKeys = true }
internal val jsonMediaType = "application/json; charset=utf-8".toMediaType()
internal val binaryMediaType = "application/octet-stream".toMediaType()
internal const val API_PATH = "androidpublisher/v3"

internal class PlayHttpResult(val status: Int, val body: String, val location: String?, val range: String?, val retryAfter: String?) {
    override fun toString(): String = "PlayHttpResult(status=$status, payload=redacted)"
}

internal class PlayPublisherTransport(
    private val tokens: PlayAccessTokenProvider,
    config: PlayPublisherConfig,
) : AutoCloseable {
    private val origin: HttpUrl = config.apiOrigin.toHttpUrlOrNull()
        ?: invalid("The Publisher API origin is invalid.")
    private val loopback = config.allowLoopbackForTests && origin.host in setOf("127.0.0.1", "localhost", "::1")
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val activeCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()
    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(config.requestTimeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(config.requestTimeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(config.requestTimeoutMillis, TimeUnit.MILLISECONDS)
        .callTimeout(config.requestTimeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    init {
        if (origin.username.isNotEmpty() || origin.password.isNotEmpty() || origin.query != null || origin.fragment != null || origin.encodedPath != "/") {
            invalid("The Publisher API origin must contain only an approved scheme and host.")
        }
        if (!loopback && (origin.scheme != "https" || origin.host != "androidpublisher.googleapis.com" || origin.port != 443)) {
            invalid("Publisher credentials may only be sent to the official HTTPS Google API origin.")
        }
        if (loopback && origin.scheme !in setOf("http", "https")) invalid("Unsupported loopback test origin.")
    }

    fun url(vararg segments: String, upload: Boolean = false, query: Map<String, String> = emptyMap()): HttpUrl {
        val builder = origin.newBuilder()
        if (upload) builder.addPathSegment("upload")
        builder.addPathSegments(API_PATH)
        segments.forEach(builder::addPathSegment)
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    /** Location is a bearer capability: never return it to UI, logs, persisted state, or arbitrary hosts. */
    fun uploadLocation(location: String?, initialUrl: HttpUrl): HttpUrl {
        val target = location?.let { initialUrl.resolve(it) }
            ?: throw PlayPublishingException(PlayErrorKind.PROTOCOL, "Google did not provide a resumable upload location.")
        checkAllowed(target)
        val canonical = initialUrl.encodedPath.removePrefix("/resumable")
        if (target.encodedPath != canonical && target.encodedPath != "/resumable$canonical") {
            throw PlayPublishingException(PlayErrorKind.PROTOCOL, "Google returned an unexpected resumable upload path; no credentials were forwarded.")
        }
        return target
    }

    suspend fun request(method: String, url: HttpUrl, body: RequestBody? = null, headers: Map<String, String> = emptyMap()): PlayHttpResult {
        if (closed.get()) throw PlayPublishingException(PlayErrorKind.NETWORK, "This publishing session is closed. Start a new authorized session.")
        checkAllowed(url)
        val token = try { tokens.accessToken() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: PlayPublishingException) { throw e }
        catch (_: Exception) { throw PlayPublishingException(PlayErrorKind.AUTHORIZATION, "Google authorization could not supply an access token. Authorize again.") }
        if (token.isBlank() || token.length > 16384 || token.any { it.code < 33 || it.code > 126 }) {
            throw PlayPublishingException(PlayErrorKind.AUTHORIZATION, "The access-token provider returned an invalid token. Authorize again.")
        }
        val request = Request.Builder().url(url).method(method, body)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val call = http.newCall(request)
        activeCalls.add(call)
        if (closed.get()) { activeCalls.remove(call); call.cancel(); throw networkFailure() }
        val parentJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
        val cancellation = parentJob?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) call.cancel()
        }
        val response = try { call.awaitResponse() }
        catch (e: kotlinx.coroutines.CancellationException) { activeCalls.remove(call); cancellation?.dispose(); throw e }
        catch (_: IOException) { activeCalls.remove(call); cancellation?.dispose(); kotlinx.coroutines.currentCoroutineContext().ensureActive(); throw networkFailure() }
        catch (_: java.util.concurrent.RejectedExecutionException) { activeCalls.remove(call); cancellation?.dispose(); throw networkFailure() }
        return try {
            withContext(Dispatchers.IO) {
                response.use {
                    val responseBody = it.body
                    val text = if (responseBody == null) "" else {
                        if (responseBody.contentLength() > MAX_RESPONSE_BYTES) {
                            throw PlayPublishingException(PlayErrorKind.PROTOCOL, "Google returned an unexpectedly large response.")
                        }
                        val buffer = Buffer()
                        val source = responseBody.source()
                        while (buffer.size <= MAX_RESPONSE_BYTES) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            if (source.read(buffer, minOf(8192, MAX_RESPONSE_BYTES + 1 - buffer.size)) == -1L) break
                        }
                        if (buffer.size > MAX_RESPONSE_BYTES) throw PlayPublishingException(PlayErrorKind.PROTOCOL, "Google returned an unexpectedly large response.")
                        buffer.readUtf8()
                    }
                    PlayHttpResult(it.code, text, it.header("Location"), it.header("Range"), it.header("Retry-After"))
                }
            }
        } catch (_: IOException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            throw networkFailure()
        } finally {
            response.close()
            activeCalls.remove(call)
            cancellation?.dispose()
        }
    }

    fun requireSuccess(result: PlayHttpResult): PlayHttpResult {
        if (result.status in 200..299) return result
        val reason = knownErrorReason(result.body)
        val (kind, message) = when {
            result.status in 300..399 -> PlayErrorKind.PROTOCOL to "The Publisher API redirected the request; credentials were not forwarded."
            result.status == 401 -> PlayErrorKind.AUTHORIZATION to "Your Google access token was rejected or expired. Authorize again; do not substitute an ID token."
            result.status == 403 -> PlayErrorKind.PERMISSION to "Google Play denied this operation. Check the selected account, API enablement, app permissions, and account eligibility in Play Console."
            result.status == 404 || result.status == 410 -> PlayErrorKind.NOT_FOUND to "The app, edit, or upload session was not found or has expired. Check Play Console setup and the edit before starting again."
            reason == "CHANGES_ALREADY_IN_REVIEW" -> PlayErrorKind.CONFLICT to "Changes are already in review. This client did not cancel that review; resolve it in Play Console before committing."
            result.status == 409 -> PlayErrorKind.CONFLICT to "Google Play reported conflicting changes. Reload the app state and create a new edit rather than overwriting another publisher's work."
            result.status == 429 -> PlayErrorKind.RATE_LIMIT to "Google Play temporarily limited requests. Wait and try again."
            result.status >= 500 -> PlayErrorKind.NETWORK to "Google Play is temporarily unavailable. The result of a write may be uncertain; inspect the app state before retrying."
            else -> PlayErrorKind.VALIDATION to "Google Play rejected this operation. Check artifact, version, listing, track, policy declarations, and account requirements in Play Console."
        }
        throw PlayPublishingException(kind, message, result.status, reason)
    }

    private fun checkAllowed(target: HttpUrl) {
        val permitted = if (loopback) {
            target.scheme == origin.scheme && target.host == origin.host && target.port == origin.port
        } else {
            target.scheme == "https" && target.port == 443 && target.host in setOf("androidpublisher.googleapis.com", "www.googleapis.com")
        }
        if (!permitted || target.username.isNotEmpty() || target.password.isNotEmpty() || target.fragment != null) {
            throw PlayPublishingException(PlayErrorKind.PROTOCOL, "An untrusted API or upload destination was rejected before sending credentials.")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeCalls.forEach(Call::cancel)
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    private fun knownErrorReason(body: String): String? {
        val root = runCatching { playJson.parseToJsonElement(body) }.getOrNull() ?: return null
        fun find(value: JsonElement): String? = when (value) {
            is JsonObject -> {
                (value["reason"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in KNOWN_REASONS }
                    ?: value.values.firstNotNullOfOrNull(::find)
            }
            is kotlinx.serialization.json.JsonArray -> value.firstNotNullOfOrNull(::find)
            else -> null
        }
        return find(root)
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
        private val KNOWN_REASONS = setOf("CHANGES_ALREADY_IN_REVIEW", "FAILED_PRECONDITION", "PERMISSION_DENIED", "UNAUTHENTICATED", "NOT_FOUND", "INVALID_ARGUMENT", "RATE_LIMIT_EXCEEDED", "QUOTA_EXCEEDED")
    }
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

internal fun networkFailure() = PlayPublishingException(PlayErrorKind.NETWORK, "The Google Play request was interrupted. A write may have completed remotely; inspect its state before retrying.")
internal fun invalid(message: String): Nothing = throw PlayPublishingException(PlayErrorKind.VALIDATION, message)
internal fun protocol(message: String): Nothing = throw PlayPublishingException(PlayErrorKind.PROTOCOL, message)
