package dev.ide.android.workbench.ui

import android.graphics.Bitmap
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
import java.io.ByteArrayInputStream
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
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.domStorageEnabled = false
                settings.databaseEnabled = false
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
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                // No addJavascriptInterface, local file URLs, permission delegation, or automatic intents.
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
                    override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, message: Message): Boolean = false
                    override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean {
                        callback.onReceiveValue(null)
                        if (!disposed.get()) blocked = true
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    private fun deny(view: WebView, url: String): Boolean {
                        if (address.permits(url)) return false
                        if (!disposed.get()) view.post { if (!disposed.get()) blocked = true }
                        return true
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = deny(view, request.url.toString())
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = deny(view, url)
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (!deny(view, request.url.toString())) return null
                        return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream("Only this session's loopback origin is allowed.".toByteArray()))
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        if (disposed.get()) return
                        if (deny(view, url)) { view.stopLoading(); return }
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
