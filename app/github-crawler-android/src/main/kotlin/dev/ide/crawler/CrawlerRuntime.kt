package dev.ide.crawler

import android.content.Context
import dev.ide.crawler.crawler.CrawlerEngine
import dev.ide.crawler.crawler.CrawlerRateLimiter
import dev.ide.crawler.crawler.CrawlerStats
import dev.ide.crawler.crawler.CrawlerStore
import dev.ide.crawler.crawler.GitHubApi
import dev.ide.crawler.crawler.GraphCrawler
import dev.ide.crawler.crawler.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared, observable state for the crawler.
 *
 * The foreground service writes it, the status screen reads it. A single
 * instance lives in the process; it is created on demand so the app has no
 * startup cost when the crawler is not running.
 */
object CrawlerRuntime {

    /** What the UI shows. */
    data class Status(
        val running: Boolean = false,
        val hasToken: Boolean = false,
        val tokenDescription: String = "not set",
        val stats: CrawlerStats = CrawlerStats(0, 0, 0, 0, 0),
        val lastCycleSummary: String = "no cycle yet",
        val logLines: List<String> = emptyList(),
        val coreRemaining: Int? = null,
        val graphqlRemaining: Int? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val logBuffer = ArrayDeque<String>()

    @Volatile
    private var store: CrawlerStore? = null

    @Volatile
    private var engine: CrawlerEngine? = null

    @Volatile
    private var limiter: CrawlerRateLimiter? = null

    /** Record a log line and publish it to the UI. */
    @Synchronized
    fun log(message: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        logBuffer.addLast("$stamp  $message")
        while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeFirst()
        android.util.Log.i(TAG, message)
        publish()
    }

    /** Build (or reuse) the crawl stack for this process. */
    fun ensureStack(context: Context): Pair<CrawlerEngine, CrawlerStore> {
        val existingEngine = engine
        val existingStore = store
        if (existingEngine != null && existingStore != null) return existingEngine to existingStore

        val appContext = context.applicationContext
        val newStore = CrawlerStore(appContext)
        val newLimiter = CrawlerRateLimiter()
        val tokens = TokenStore(appContext)
        val api = GitHubApi(tokens.read(), newLimiter, newStore, log = { log(it) })
        val graph = GraphCrawler(api, newStore, log = { log(it) })
        val newEngine = CrawlerEngine(api, newStore, newLimiter, graph, log = { log(it) })

        store = newStore
        limiter = newLimiter
        engine = newEngine
        publish()
        return newEngine to newStore
    }

    /** The token currently in use, if any. */
    fun currentToken(context: Context): String? = TokenStore(context.applicationContext).read()

    /** Publish the current state to the UI. */
    fun publish() {
        val currentStore = store
        val currentLimiter = limiter
        val tokens = _status.value
        val stats = currentStore?.graphStats() ?: CrawlerStats(0, 0, 0, 0, 0)
        val snapshot = currentLimiter?.snapshot()
        _status.value = tokens.copy(
            stats = stats,
            logLines = synchronized(this) { logBuffer.toList() },
            coreRemaining = (snapshot?.get("core") as? Map<*, *>)?.get("remoteRemaining") as? Int,
            graphqlRemaining = (snapshot?.get("graphql") as? Map<*, *>)?.get("remoteRemaining") as? Int,
        )
    }

    /** Record that the service started. */
    fun onStarted(context: Context) {
        val tokens = TokenStore(context.applicationContext)
        _status.value = _status.value.copy(
            running = true,
            hasToken = tokens.read() != null,
            tokenDescription = tokens.describe(),
        )
        publish()
    }

    /** Record that the service stopped. */
    fun onStopped() {
        _status.value = _status.value.copy(running = false)
        publish()
    }

    /** Record the outcome of one cycle. */
    fun onCycle(result: CrawlerEngine.CycleResult) {
        _status.value = _status.value.copy(
            lastCycleSummary = "trending=${result.trending} expanded=${result.expanded} " +
                "related=${result.related} api=${result.apiCalls} web=${result.webCalls}",
        )
        publish()
    }

    /** Refresh the token description after the user saved one. */
    fun onTokenChanged(context: Context) {
        val tokens = TokenStore(context.applicationContext)
        _status.value = _status.value.copy(
            hasToken = tokens.read() != null,
            tokenDescription = tokens.describe(),
        )
        publish()
    }

    private const val TAG = "Crawler"
    private const val MAX_LOG_LINES = 80
}
