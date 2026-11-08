package fragments.agentui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Process-wide registry of embedded browser controllers keyed by `sessionId`,
 * allowing both the UI (`BrowserEmbedCard` / `AgentMobileBrowserSheet`) and
 * the agent tool runtime (`browser_navigate`, `browser_snapshot`, `browser_click`,
 * `browser_input`, `browser_extract`) to drive and inspect the same live WebView.
 */
object AgentEmbeddedBrowserRegistry {
    private val controllers = mutableMapOf<String, AgentEmbeddedBrowserController>()
    var activeSessionId: String = "muse-browser-1"

    fun getOrCreate(
        sessionId: String = activeSessionId,
        initialUrl: String = "https://scholar.google.com",
    ): AgentEmbeddedBrowserController {
        activeSessionId = sessionId
        return controllers.getOrPut(sessionId) {
            AgentEmbeddedBrowserController(sessionId = sessionId, initialUrl = initialUrl)
        }
    }

    fun current(): AgentEmbeddedBrowserController = getOrCreate(activeSessionId)
}

@Stable
class AgentEmbeddedBrowserController(
    val sessionId: String = "muse-browser-1",
    initialUrl: String = "https://scholar.google.com",
) {
    var url: String by mutableStateOf(normalizeBrowserUrl(initialUrl))
    var title: String by mutableStateOf("")
    var isLoading: Boolean by mutableStateOf(false)
    var canGoBack: Boolean by mutableStateOf(false)
    var canGoForward: Boolean by mutableStateOf(false)
    var isUserInControl: Boolean by mutableStateOf(false)
    var lastDomSnapshot: String by mutableStateOf("")
    var lastExtractedText: String by mutableStateOf("")

    internal val historyBack = mutableListOf<String>()
    internal val historyForward = mutableListOf<String>()

    internal var platformLoadUrl: ((String) -> Unit)? = null
    internal var platformLoadHtml: ((String, String?) -> Unit)? = null
    internal var platformGoBack: (() -> Unit)? = null
    internal var platformGoForward: (() -> Unit)? = null
    internal var platformReload: (() -> Unit)? = null
    internal var platformEvaluateJs: ((String, (String) -> Unit) -> Unit)? = null

    fun loadUrl(rawUrl: String) {
        val target = normalizeBrowserUrl(rawUrl)
        if (target.isEmpty()) return
        if (url.isNotEmpty() && url != target) {
            historyBack.add(url)
            historyForward.clear()
        }
        url = target
        isLoading = true
        syncHistoryFlags()
        val loader = platformLoadUrl
        if (loader != null) {
            loader(target)
        } else {
            isLoading = false
        }
    }

    fun loadHtml(html: String, baseUrl: String? = null) {
        if (baseUrl != null) {
            url = baseUrl
        }
        isLoading = true
        val loader = platformLoadHtml
        if (loader != null) {
            loader(html, baseUrl)
        } else {
            isLoading = false
        }
    }

    fun goBack() {
        val back = platformGoBack
        if (back != null) {
            back()
        } else if (historyBack.isNotEmpty()) {
            val prev = historyBack.removeAt(historyBack.lastIndex)
            historyForward.add(url)
            url = prev
            syncHistoryFlags()
        }
    }

    fun goForward() {
        val forward = platformGoForward
        if (forward != null) {
            forward()
        } else if (historyForward.isNotEmpty()) {
            val next = historyForward.removeAt(historyForward.lastIndex)
            historyBack.add(url)
            url = next
            syncHistoryFlags()
        }
    }

    fun reload() {
        val reloader = platformReload
        if (reloader != null) {
            reloader()
        } else if (url.isNotEmpty()) {
            loadUrl(url)
        }
    }

    fun evaluateJs(script: String, onResult: (String) -> Unit = {}) {
        val evaluator = platformEvaluateJs
        if (evaluator != null) {
            evaluator(script, onResult)
        } else {
            onResult("")
        }
    }

    internal fun syncHistoryFlags(platformBack: Boolean? = null, platformForward: Boolean? = null) {
        canGoBack = platformBack ?: historyBack.isNotEmpty()
        canGoForward = platformForward ?: historyForward.isNotEmpty()
    }
}

fun normalizeBrowserUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    return when {
        trimmed.startsWith("http://") ||
            trimmed.startsWith("https://") ||
            trimmed.startsWith("about:") ||
            trimmed.startsWith("data:") ||
            trimmed.startsWith("file://") -> trimmed
        trimmed.contains('.') && !trimmed.contains(' ') -> "https://$trimmed"
        else -> {
            val encoded = trimmed.replace(" ", "+")
            "https://www.google.com/search?q=$encoded"
        }
    }
}

@Composable
fun rememberAgentEmbeddedBrowserController(
    sessionId: String = "muse-browser-1",
    initialUrl: String = "https://scholar.google.com",
): AgentEmbeddedBrowserController {
    val controller = remember(sessionId) {
        AgentEmbeddedBrowserRegistry.getOrCreate(sessionId = sessionId, initialUrl = initialUrl)
    }
    LaunchedEffect(initialUrl) {
        val normalized = normalizeBrowserUrl(initialUrl)
        if (normalized.isNotEmpty() && controller.url.isEmpty()) {
            controller.loadUrl(normalized)
        }
    }
    return controller
}

@Composable
expect fun AgentEmbeddedWebView(
    controller: AgentEmbeddedBrowserController,
    modifier: Modifier = Modifier,
)
