package dev.ide.workbench.scenario

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class ScenarioHttpResponse(val status: Int, val body: String, val bytes: Int, val durationMs: Long)

/** No injected general-purpose client: proxies, cookies, credentials, interceptors and redirects are off. */
internal class ScenarioHttpClient : AutoCloseable {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .proxy(Proxy.NO_PROXY)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = when (hostname) {
                "127.0.0.1" -> listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
                "::1" -> listOf(InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 }))
                else -> throw UnknownHostException("Scenario DNS is restricted to literal loopback")
            }
        })
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .build()

    fun origin(preview: String): HttpUrl {
        val uri = try { URI(preview) } catch (_: Exception) { throw ScenarioExecutionException("BLOCKED_URL") }
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")
        if (uri.scheme != "http" || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            host !in setOf("127.0.0.1", "localhost", "::1") || uri.port !in 1024..65535) {
            throw ScenarioExecutionException("BLOCKED_URL")
        }
        return HttpUrl.Builder().scheme("http").host(if (host == "localhost") "127.0.0.1" else host!!).port(uri.port).build()
    }

    suspend fun request(origin: HttpUrl, method: String, path: String, json: String?, maxBytes: Int, timeoutMs: Long): ScenarioHttpResponse {
        RegressionScenarioParser.validateHttpPath(path)
        val url = origin.resolve(path) ?: throw ScenarioExecutionException("BLOCKED_URL")
        if (url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port || url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw ScenarioExecutionException("BLOCKED_URL")
        }
        val requestBody = if (method in setOf("POST", "PUT", "PATCH", "DELETE"))
            (json ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()) else null
        val request = Request.Builder().url(url).method(method, requestBody)
            .header("Accept", "application/json, text/plain;q=0.9")
            .header("Accept-Encoding", "identity")
            .header("Connection", "close")
            .build()
        val started = System.nanoTime()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (it.code in 300..399) throw ScenarioExecutionException("BLOCKED_REDIRECT")
                            val body = it.body
                            if (body != null && body.contentLength() > maxBytes) throw ScenarioExecutionException("RESPONSE_TOO_LARGE")
                            val output = ByteArrayOutputStream()
                            body?.byteStream()?.use { stream ->
                                val buffer = ByteArray(4096)
                                while (true) {
                                    val count = stream.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > maxBytes) throw ScenarioExecutionException("RESPONSE_TOO_LARGE")
                                    output.write(buffer, 0, count)
                                }
                            }
                            val bytes = output.toByteArray()
                            ScenarioHttpResponse(it.code, bytes.toString(Charsets.UTF_8), bytes.size,
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
    }
}

internal fun networkOutcome(code: Int): ScenarioNetworkOutcome = when (code) {
    401, 403 -> ScenarioNetworkOutcome.AUTHENTICATION
    429 -> ScenarioNetworkOutcome.RATE_LIMITED
    in 400..499 -> ScenarioNetworkOutcome.CLIENT_ERROR
    in 500..599 -> ScenarioNetworkOutcome.SERVER_ERROR
    else -> ScenarioNetworkOutcome.SUCCESS
}
