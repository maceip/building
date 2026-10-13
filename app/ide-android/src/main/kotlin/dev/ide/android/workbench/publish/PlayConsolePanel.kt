package dev.ide.android.workbench.publish

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.ClientCertRequest
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.ide.ui.backend.FileActions
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/** No WebView, network load, or browser launch until the user explicitly opens this entry. */
@Composable
internal fun ManualPlayConsoleEntry(fileActions: FileActions, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { open = true },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("play-open-embedded-console"),
    ) { Text("Open embedded Google Play Console · manual") }
    if (open) Dialog(
        onDismissRequest = { open = false },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            securePolicy = SecureFlagPolicy.SecureOn,
        ),
    ) {
        PlayConsolePanel(fileActions = fileActions, onClose = { open = false })
    }
}

/**
 * Manual website surface, not OAuth, Publisher API authorization, or an authenticated-session detector.
 * https://developers.google.com/identity/protocols/oauth2/policies#use-secure-browsers
 * Native AuthorizationClient tokens are deliberately not passed into this composable or its WebView.
 */
@Composable
fun PlayConsolePanel(fileActions: FileActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    SecurePlayPublishSurface()
    val context = LocalContext.current
    val state = remember(context) { PlayConsoleViewState() }
    val disposed = remember(context) { AtomicBoolean(false) }
    val result = remember(context) { runCatching { createPlayConsoleWebView(context, state, disposed) } }
    val view = result.getOrNull()
    val page = if (view == null) PlayConsolePage.UNAVAILABLE else state.page
    val usable = view != null && page != PlayConsolePage.RENDERER_GONE

    DisposableEffect(view) {
        val lifecycle = (context.findPublishActivity() as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (view != null && !disposed.get()) when (event) {
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_RESUME -> view.onResume() // No reload or browser launch on return.
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        view?.loadUrl(PLAY_CONSOLE_URL) // Composed only by the explicit manual entry above.
        onDispose {
            val alreadyDestroyed = disposed.getAndSet(true)
            lifecycle?.removeObserver(observer)
            if (!alreadyDestroyed) view?.let(::destroyPlayConsoleWebView)
        }
    }

    fun navigate(action: (WebView) -> Unit) {
        if (usable && view != null) {
            state.beginLoad()
            action(view)
        }
    }
    BackHandler {
        if (usable && state.canGoBack) navigate { it.goBack() } else onClose()
    }

    Surface(modifier.fillMaxSize().testTag("play-console-panel")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Google Play Console · manual", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClose, modifier = Modifier.testTag("play-console-close")) { Text("Close") }
            }
            Text(PLAY_CONSOLE_URL, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { navigate { it.goBack() } }, enabled = usable && state.canGoBack, modifier = Modifier.testTag("play-console-back")) { Text("Back") }
                TextButton(onClick = { navigate { it.goForward() } }, enabled = usable && state.canGoForward, modifier = Modifier.testTag("play-console-forward")) { Text("Forward") }
                // A clean root GET avoids replaying a form submission or a blocked sign-in redirect.
                TextButton(onClick = { navigate { it.loadUrl(PLAY_CONSOLE_URL) } }, enabled = usable, modifier = Modifier.testTag("play-console-reload")) { Text("Reload Console") }
            }
            if (page == PlayConsolePage.LOADING) LinearProgressIndicator(
                progress = { state.progress / 100f },
                modifier = Modifier.fillMaxWidth().testTag("play-console-progress"),
            )
            Text(
                page.message,
                modifier = Modifier.testTag("play-console-status"),
                color = if (page.hidesPage) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Separate app-local web cookies/storage. Native API authorization and browser sign-in do not sign this view in. No form automation.", style = MaterialTheme.typography.bodySmall)
            if (fileActions.canOpenUrl) OutlinedButton(
                onClick = {
                    // Always a clean Console URL: never relay an intercepted OAuth URL, token, or intent.
                    state.browserNotice = try {
                        fileActions.openUrl(PLAY_CONSOLE_URL)
                        "Browser opening requested. Returning does not establish or verify an embedded Console session."
                    } catch (_: Exception) {
                        "The system browser could not open. The embedded session is unchanged."
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("play-console-open-browser"),
            ) { Text("Open Console in system browser") }
            else Text("System-browser opening is unavailable on this host.", style = MaterialTheme.typography.bodySmall)
            state.browserNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("play-console-browser-notice")) }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (view != null && page != PlayConsolePage.RENDERER_GONE) {
                    AndroidView(
                        factory = { view },
                        update = { it.visibility = if (page.hidesPage) View.INVISIBLE else View.VISIBLE },
                        modifier = Modifier.fillMaxSize().testTag("play-console-webview"),
                    )
                }
                if (page.hidesPage) {
                    Text("Embedded page unavailable or blocked. This is not authenticated Console access. Retry manually or continue in your browser.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("Screen capture is blocked here. Closing destroys this view, not the app-local website cookies/storage.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal enum class PlayConsolePage(val message: String, val hidesPage: Boolean = true) {
    LOADING("Loading the manual Console page…", hidesPage = false),
    PAGE_LOADED("Page load finished. Sign-in and Console access are not verified; inspect the page itself.", hidesPage = false),
    SIGN_IN_REQUIRED("Embedded Google sign-in is blocked. Google sign-in requires a supported native/browser flow; open Console in the system browser manually."),
    NAVIGATION_BLOCKED("Navigation blocked. Only exact HTTPS play.google.com/console pages may open here; other addresses and external intents are not launched."),
    BROWSER_REQUIRED("This website action requires a browser. Pop-ups, device permissions, file uploads/downloads and form resubmissions are not enabled here."),
    LOAD_FAILED("Console did not finish loading. Check connectivity, retry manually, or use your browser. No signed-in session was verified."),
    TLS_BLOCKED("Certificate validation failed. The connection was cancelled; certificate errors are never bypassed."),
    RENDERER_GONE("The WebView renderer stopped. Close and reopen this panel, or continue manually in your browser."),
    UNAVAILABLE("Android WebView is unavailable. Use your browser; no embedded Console session was established."),
}

/** UI observations only; never an account, token, cookie, page HTML, or authentication-success flag. */
internal class PlayConsoleViewState {
    var page by mutableStateOf(PlayConsolePage.LOADING)
    var progress by mutableIntStateOf(0)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var browserNotice by mutableStateOf<String?>(null)

    fun beginLoad() {
        page = PlayConsolePage.LOADING
        progress = 0
        browserNotice = null
    }

    fun updateHistory(view: WebView) {
        val history = view.copyBackForwardList()
        canGoBack = history.currentIndex > 0 && playConsoleDestination(history.getItemAtIndex(history.currentIndex - 1)?.url.orEmpty()) == PlayConsoleDestination.CONSOLE
        canGoForward = history.currentIndex + 1 < history.size && playConsoleDestination(history.getItemAtIndex(history.currentIndex + 1)?.url.orEmpty()) == PlayConsoleDestination.CONSOLE
    }
}

/** Creates an unloaded WebView, so composition/manual-entry behavior and settings can be tested offline. */
@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal fun createPlayConsoleWebView(context: Context, state: PlayConsoleViewState, disposed: AtomicBoolean): WebView {
    WebView.setWebContentsDebuggingEnabled(false)
    val view = WebView(context)
    try {
        view.settings.apply {
            javaScriptEnabled = true // Required by Console itself; no injected scripts or bridges.
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true) // Route _blank/window.open to the rejecting onCreateWindow handler.
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            databaseEnabled = false
            saveFormData = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            mediaPlaybackRequiresUserGesture = true
            setGeolocationEnabled(false)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        view.isSaveEnabled = false
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        CookieManager.getInstance().apply {
            setAcceptCookie(true) // Normal first-party cookies in the app's WebView store only.
            setAcceptThirdPartyCookies(view, false)
        }
        fun block(page: PlayConsolePage) {
            if (!disposed.get()) {
                view.stopLoading()
                // A late error or completion must not overwrite an already-observed sign-in boundary.
                if (state.page != PlayConsolePage.SIGN_IN_REQUIRED) state.page = page
                state.progress = 0
                state.updateHistory(view)
            }
        }
        fun rejectNavigation(url: String, mainFrame: Boolean): Boolean {
            val destination = playConsoleDestination(url)
            val denied = if (mainFrame) destination != PlayConsoleDestination.CONSOLE else !playConsoleResourceAllowed(url)
            if (denied && (mainFrame || destination == PlayConsoleDestination.GOOGLE_SIGN_IN)) {
                block(if (destination == PlayConsoleDestination.GOOGLE_SIGN_IN) PlayConsolePage.SIGN_IN_REQUIRED else PlayConsolePage.NAVIGATION_BLOCKED)
            }
            return denied
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                disposed.get() || rejectNavigation(request.url.toString(), request.isForMainFrame) || (request.isForMainFrame && state.page.hidesPage)

            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                disposed.get() || rejectNavigation(url, true) || state.page.hidesPage

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                val destination = playConsoleDestination(url)
                val denied = if (request.isForMainFrame) destination != PlayConsoleDestination.CONSOLE else !playConsoleResourceAllowed(url)
                if (!disposed.get() && !denied) return null
                if (denied && (request.isForMainFrame || destination == PlayConsoleDestination.GOOGLE_SIGN_IN)) view.post {
                    block(if (destination == PlayConsoleDestination.GOOGLE_SIGN_IN) PlayConsolePage.SIGN_IN_REQUIRED else PlayConsolePage.NAVIGATION_BLOCKED)
                }
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (disposed.get()) return
                if (rejectNavigation(url, true) || state.page.hidesPage) { view.stopLoading(); return }
                state.page = PlayConsolePage.LOADING
                state.progress = 0
                state.updateHistory(view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (disposed.get() || rejectNavigation(url, true)) return
                if (state.page == PlayConsolePage.LOADING) state.page = PlayConsolePage.PAGE_LOADED
                state.updateHistory(view)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (!disposed.get() && !rejectNavigation(url, true)) state.updateHistory(view)
            }

            override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) {
                dontResend.sendToTarget()
                block(PlayConsolePage.BROWSER_REQUIRED)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !state.page.hidesPage) block(PlayConsolePage.LOAD_FAILED)
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && !state.page.hidesPage) block(PlayConsolePage.LOAD_FAILED)
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                block(PlayConsolePage.TLS_BLOCKED)
            }

            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                handler.cancel()
                block(PlayConsolePage.BROWSER_REQUIRED)
            }

            override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
                request.cancel()
                block(PlayConsolePage.BROWSER_REQUIRED)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                disposed.set(true)
                state.page = PlayConsolePage.RENDERER_GONE
                state.canGoBack = false
                state.canGoForward = false
                destroyPlayConsoleWebView(view)
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (!disposed.get() && state.page == PlayConsolePage.LOADING) state.progress = newProgress.coerceIn(0, 100)
            }
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true // Do not forward website messages to logs.
            override fun onPermissionRequest(request: PermissionRequest) { request.deny(); block(PlayConsolePage.BROWSER_REQUIRED) }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
                block(PlayConsolePage.BROWSER_REQUIRED)
            }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                block(PlayConsolePage.BROWSER_REQUIRED)
                return false
            }
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                callback.onReceiveValue(null)
                block(PlayConsolePage.BROWSER_REQUIRED)
                return true
            }
        }
        view.setDownloadListener { _, _, _, _, _ -> block(PlayConsolePage.BROWSER_REQUIRED) }
        return view
    } catch (failure: Exception) {
        destroyPlayConsoleWebView(view)
        throw failure
    }
}

internal fun destroyPlayConsoleWebView(view: WebView) {
    runCatching {
        view.stopLoading()
        view.settings.javaScriptEnabled = false
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        view.setDownloadListener(null)
        view.clearHistory()
        view.removeAllViews()
    }
    (view.parent as? ViewGroup)?.removeView(view)
    runCatching { view.destroy() }
    // Do not read, copy, inject, export or globally clear cookies/storage belonging to other WebViews.
}
