package dev.ide.android.workbench.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.ide.android.workbench.runtime.RuntimeBrowserBridge
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

/** Only composed while the owning session is live. Disposing clears and destroys this WebView. */
@Suppress("SetJavaScriptEnabled", "DEPRECATION", "OVERRIDE_DEPRECATION")
@Composable
internal fun RuntimePreview(address: RuntimePreviewAddress, sessionId: String) {
    val context = LocalContext.current
    var failure by remember(sessionId, address.url) { mutableStateOf<String?>(null) }
    var blocked by remember(sessionId, address.url) { mutableStateOf(false) }
    var loading by remember(sessionId, address.url) { mutableStateOf(true) }
    var rendererGone by remember(sessionId, address.url) { mutableStateOf(false) }
    val disposed = remember(sessionId, address.url) { AtomicBoolean(false) }
    val result = remember(sessionId, address.url) {
        runCatching {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(true)
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.mediaPlaybackRequiresUserGesture = true
                settings.setGeolocationEnabled(false)
                settings.safeBrowsingEnabled = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                isSaveEnabled = false
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                fun handleExternalOrCallback(view: WebView, url: String): Boolean {
                    if (address.permits(url)) return false
                    val uri = runCatching { Uri.parse(url) }.getOrNull()
                    val scheme = uri?.scheme?.lowercase(Locale.ROOT)
                    if (scheme in setOf("http", "https")) {
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            view.context.startActivity(intent)
                        }
                        return true
                    }
                    if (scheme == "codeassist") {
                        RuntimeBrowserBridge.recordCallback(url)
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            view.context.startActivity(intent)
                        }
                        return true
                    }
                    if (!disposed.get()) view.post { if (!disposed.get()) blocked = true }
                    return true
                }
                // Browser popups, external OAuth redirects, and HTTPS CDN assets are handled safely.
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
                    override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, message: Message): Boolean {
                        val transport = message.obj as? WebView.WebViewTransport ?: return false
                        val popup = WebView(view.context).apply {
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                                    val targetUrl = request.url.toString()
                                    handleExternalOrCallback(view, targetUrl)
                                    v.destroy()
                                    return true
                                }
                                override fun shouldOverrideUrlLoading(v: WebView, url: String): Boolean {
                                    handleExternalOrCallback(view, url)
                                    v.destroy()
                                    return true
                                }
                            }
                        }
                        transport.webView = popup
                        message.sendToTarget()
                        return true
                    }
                    override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean {
                        callback.onReceiveValue(null)
                        if (!disposed.get()) blocked = true
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        handleExternalOrCallback(view, request.url.toString())

                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                        handleExternalOrCallback(view, url)

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val urlStr = request.url.toString()
                        if (address.permits(urlStr)) return null
                        val scheme = request.url.scheme?.lowercase(Locale.ROOT)
                        // Permit external HTTPS assets (CDNs, fonts, stylesheets, images)
                        if (scheme == "https") return null
                        return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream("Only this session's loopback origin and HTTPS assets are allowed.".toByteArray()))
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        if (disposed.get()) return
                        if (!address.permits(url)) {
                            handleExternalOrCallback(view, url)
                            view.stopLoading()
                            return
                        }
                        loading = true
                    }

                    override fun onPageFinished(view: WebView, url: String) { if (!disposed.get()) loading = false }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame && !disposed.get()) { loading = false; failure = error.description.toString() }
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame && !disposed.get()) { loading = false; failure = "HTTP ${response.statusCode}: ${response.reasonPhrase}" }
                    }
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                        handler.cancel()
                        if (!disposed.get()) { loading = false; failure = "The preview's TLS certificate is not trusted. It was not bypassed." }
                    }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        if (!disposed.get()) {
                            loading = false
                            rendererGone = true
                            failure = "The WebView renderer stopped. Restart this runtime session to create a new preview."
                        }
                        return true
                    }
                }
                setDownloadListener { _, _, _, _, _ -> blocked = true }
            }
        }
    }
    val view = result.getOrNull()
    if (view == null) {
        Text("Embedded preview unavailable: ${result.exceptionOrNull()?.message ?: "WebView could not start"}", color = MaterialTheme.colorScheme.error)
        return
    }
    if (rendererGone) {
        Text(failure ?: "The preview renderer stopped.", color = MaterialTheme.colorScheme.error)
        return
    }
    DisposableEffect(view) {
        view.loadUrl(address.url)
        onDispose {
            disposed.set(true)
            runCatching {
                view.stopLoading()
                view.settings.javaScriptEnabled = false
                view.webChromeClient = null
                view.webViewClient = WebViewClient()
                view.loadUrl("about:blank")
                view.clearHistory()
                view.removeAllViews()
            }
            (view.parent as? ViewGroup)?.removeView(view)
            runCatching { view.destroy() }
        }
    }
    LaunchedEffect(view, loading) {
        if (loading) {
            delay(15_000)
            if (!disposed.get() && loading) {
                view.stopLoading()
                loading = false
                failure = "The local server did not finish loading within 15 seconds. Check its output or retry."
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (loading) Text("Connecting to the live session…", style = MaterialTheme.typography.bodySmall)
        failure?.let { Text("Preview did not load: $it", color = MaterialTheme.colorScheme.error) }
        if (blocked) Text("A navigation, resource, file chooser, popup, or download was blocked. Only this session's loopback origin is embedded.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { failure = null; loading = true; view.loadUrl(address.url) }) { Text("Reload preview") }
        AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(340.dp))
    }
}
