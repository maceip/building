package fragments.agentui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

private const val DOM_SNAPSHOT_JS = """
(function() {
  try {
    var title = document.title || '';
    var url = location.href || '';
    var headings = Array.from(document.querySelectorAll('h1,h2,h3')).slice(0, 8).map(function(e){ return e.innerText.trim(); }).filter(Boolean);
    var inputs = Array.from(document.querySelectorAll('input,textarea,select')).slice(0, 12).map(function(e, i){
      var label = e.getAttribute('placeholder') || e.getAttribute('name') || e.getAttribute('aria-label') || e.id || ('input-' + i);
      return '[input:' + label + ' value=' + (e.value || '') + ']';
    });
    var buttons = Array.from(document.querySelectorAll('button,a[href],[role="button"]')).slice(0, 15).map(function(e){
      return (e.innerText || e.getAttribute('aria-label') || '').trim();
    }).filter(Boolean);
    var bodyText = (document.body && document.body.innerText ? document.body.innerText : '').replace(/\s+/g, ' ').trim().slice(0, 900);
    return JSON.stringify({
      title: title,
      url: url,
      headings: headings,
      inputs: inputs,
      buttons: buttons,
      excerpt: bodyText
    });
  } catch (err) {
    return JSON.stringify({ error: String(err) });
  }
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun AgentEmbeddedWebView(
    controller: AgentEmbeddedBrowserController,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val webView = remember(controller.sessionId) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            isFocusable = true
            isFocusableInTouchMode = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!url.isNullOrEmpty()) {
                        controller.url = url
                    }
                    controller.isLoading = true
                    controller.syncHistoryFlags(
                        platformBack = view?.canGoBack(),
                        platformForward = view?.canGoForward(),
                    )
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!url.isNullOrEmpty()) {
                        controller.url = url
                    }
                    val pageTitle = view?.title
                    if (!pageTitle.isNullOrEmpty()) {
                        controller.title = pageTitle
                    }
                    controller.isLoading = false
                    controller.syncHistoryFlags(
                        platformBack = view?.canGoBack(),
                        platformForward = view?.canGoForward(),
                    )
                    val stageScript = controller.pendingStageJsOnLoad
                    if (!stageScript.isNullOrBlank()) {
                        view?.evaluateJavascript(stageScript, null)
                    }
                    view?.evaluateJavascript(DOM_SNAPSHOT_JS) { rawJson ->
                        if (!rawJson.isNullOrBlank() && rawJson != "null") {
                            controller.lastDomSnapshot = rawJson.trim('"')
                                .replace("\\\"", "\"")
                                .replace("\\n", "\n")
                            controller.lastExtractedText = controller.lastDomSnapshot
                        }
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = false
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    if (!title.isNullOrEmpty()) {
                        controller.title = title
                    }
                }
            }
        }
    }

    DisposableEffect(controller, webView) {
        controller.platformLoadUrl = { targetUrl ->
            webView.post {
                webView.loadUrl(targetUrl)
            }
        }
        controller.platformLoadHtml = { html, baseUrl ->
            webView.post {
                webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
            }
        }
        controller.platformGoBack = {
            webView.post {
                if (webView.canGoBack()) {
                    webView.goBack()
                }
            }
        }
        controller.platformGoForward = {
            webView.post {
                if (webView.canGoForward()) {
                    webView.goForward()
                }
            }
        }
        controller.platformReload = {
            webView.post {
                webView.reload()
            }
        }
        controller.platformEvaluateJs = { script, onResult ->
            webView.post {
                webView.evaluateJavascript(script) { res ->
                    onResult(res ?: "")
                }
            }
        }

        val initial = controller.url
        if (initial.isNotEmpty() && webView.url.isNullOrEmpty()) {
            webView.loadUrl(initial)
        }

        onDispose {
            controller.platformLoadUrl = null
            controller.platformLoadHtml = null
            controller.platformGoBack = null
            controller.platformGoForward = null
            controller.platformReload = null
            controller.platformEvaluateJs = null
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier,
        update = { view ->
            val target = controller.url
            if (target.isNotEmpty() && view.url.isNullOrEmpty()) {
                view.loadUrl(target)
            }
        },
    )
}
