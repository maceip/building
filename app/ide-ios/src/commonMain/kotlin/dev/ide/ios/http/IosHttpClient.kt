package dev.ide.ios.http

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataWithBytes
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.posix.memcpy

/**
 * Result of an HTTP request made via [IosHttpClient].
 */
data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String>,
    val error: String? = null,
) {
    val isSuccessful: Boolean get() = statusCode in 200..299
}

/**
 * Native HTTP client for iOS powered by Apple Foundation's [NSURLSession].
 *
 * Executes requests synchronously on calling background worker threads via a POSIX semaphore.
 */
@OptIn(ExperimentalForeignApi::class)
object IosHttpClient {

    private val session: NSURLSession by lazy {
        val config = NSURLSessionConfiguration.defaultSessionConfiguration().apply {
            setTimeoutIntervalForRequest(30.0)
            setTimeoutIntervalForResource(60.0)
        }
        NSURLSession.sessionWithConfiguration(config)
    }

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Double = 30.0,
    ): HttpResponse = request("GET", url, headers, null, timeoutSeconds)

    fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String,
        timeoutSeconds: Double = 60.0,
    ): HttpResponse = request("POST", url, headers, body, timeoutSeconds)

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        timeoutSeconds: Double = 30.0,
    ): HttpResponse {
        val nsUrl = NSURL.URLWithString(url) ?: return HttpResponse(0, "", emptyMap(), "Invalid URL: $url")
        val req = (NSMutableURLRequest.requestWithURL(nsUrl) as? NSMutableURLRequest) ?: NSMutableURLRequest()
        req.setURL(nsUrl)
        req.setHTTPMethod(method)
        req.setTimeoutInterval(timeoutSeconds)
        headers.forEach { (k, v) ->
            req.setValue(v, forHTTPHeaderField = k)
        }
        if (body != null) {
            val bytes = body.encodeToByteArray()
            if (bytes.isNotEmpty()) {
                val data = bytes.usePinned { pinned ->
                    NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
                }
                req.setHTTPBody(data)
            }
        }

        var statusCode = 0
        var responseBody = ""
        val responseHeaders = mutableMapOf<String, String>()
        var errorDesc: String? = null

        val sema = dispatch_semaphore_create(0)
        val task = session.dataTaskWithRequest(req) { data, response, error ->
            try {
                if (error != null) {
                    errorDesc = error.localizedDescription
                }
                val httpResponse = response as? NSHTTPURLResponse
                if (httpResponse != null) {
                    statusCode = httpResponse.statusCode.toInt()
                    val allHeaderFields = httpResponse.allHeaderFields
                    for ((k, v) in allHeaderFields) {
                        if (k != null && v != null) {
                            responseHeaders[k.toString().lowercase()] = v.toString()
                        }
                    }
                }
                if (data != null && data.length > 0u) {
                    val len = data.length.toInt()
                    val bytes = ByteArray(len)
                    bytes.usePinned { pinned ->
                        memcpy(pinned.addressOf(0), data.bytes, data.length)
                    }
                    responseBody = bytes.decodeToString()
                }
            } finally {
                dispatch_semaphore_signal(sema)
            }
        }
        task.resume()
        dispatch_semaphore_wait(sema, DISPATCH_TIME_FOREVER)

        return HttpResponse(statusCode, responseBody, responseHeaders, errorDesc)
    }
}
