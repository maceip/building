package dev.ide.core.qualitysemantic

import dev.ide.agent.impl.LlmTransport
import dev.ide.agent.impl.OkHttpLlmTransport
import dev.ide.agent.impl.SseRequest
import kotlinx.coroutines.flow.Flow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** One confirmed run owns one guard, shared by every retry and protocol fallback. No URL/body/key logging. */
internal class SemanticEgressGuard(approvedOrigin: String) : Interceptor {
    private val origin = approvedOrigin.toHttpUrl()
    private val wireRequests = AtomicInteger()
    val sentCount: Int get() = wireRequests.get()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!sameOrigin(request.url) || request.method != "POST" || request.url.username.isNotEmpty() ||
            request.url.password.isNotEmpty() || request.url.fragment != null) throw IOException("Semantic destination is not approved.")
        while (true) {
            val previous = wireRequests.get()
            if (previous >= 2) throw IOException("Semantic request limit reached.")
            if (wireRequests.compareAndSet(previous, previous + 1)) break
        }
        return chain.proceed(request)
    }

    fun client(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        // Application interceptor validates before DNS; counting happens per actual network exchange below.
        .addInterceptor { chain ->
            if (!sameOrigin(chain.request().url)) throw IOException("Semantic destination is not approved.")
            chain.proceed(chain.request())
        }
        // Counts even library-level follow-ups inside a single provider/chat invocation.
        .addNetworkInterceptor(this).build()

    private fun sameOrigin(url: HttpUrl): Boolean = url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port
}

/** Reuses provider wire formats and SSE transport; forbids discovery, cache creation, OAuth and other side calls. */
internal class SemanticOnlyTransport(private val delegate: OkHttpLlmTransport) : LlmTransport {
    override fun sse(request: SseRequest): Flow<String> = delegate.sse(request)
    // LlmTransport defaults reject get/post/postForm. Gemini falls back to inline system text if caching is unavailable.
    // OAuth-only providers require a reviewed multi-origin auth flow and are deliberately unavailable here.
}
