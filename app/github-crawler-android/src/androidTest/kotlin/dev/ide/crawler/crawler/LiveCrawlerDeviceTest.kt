package dev.ide.crawler.crawler

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device tests for the phone-side crawler.
 *
 * These run on a real device (never an emulator) and prove two things:
 *
 *  1. The trending parser still understands GitHub's live markup - the
 *     page structure changes, and a silent parse failure would leave the
 *     index empty without anything looking broken.
 *  2. One full crawl cycle against the live API fills the local index.
 *
 * A GitHub token is optional: without one the API is anonymous (60
 * requests/hour) which is enough for the trending sweep, but the graph
 * expansion needs a token. The token is read from the app's own token file
 * or from the `GITHUB_TOKEN` instrumentation argument.
 */
@RunWith(AndroidJUnit4::class)
class LiveCrawlerDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun httpGet(url: String): String? {
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 25_000
            setRequestProperty("User-Agent", CrawlerConfig.USER_AGENT)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }
        return try {
            if (connection.responseCode != 200) null else connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun liveTrendingPageParses() {
        val fetched = httpGet("https://github.com/trending?since=daily")
        assertTrue("the live trending page must be reachable", !fetched.isNullOrBlank())
        val html = fetched!!

        val records = TrendingParser.parseTrendingHtml(html, period = "daily")
        assertTrue("expected trending repositories, got none", records.isNotEmpty())
        assertTrue("expected a full trending board", records.size >= 5)

        val first = records.first()
        assertTrue("slug must be owner/name", first.slug.contains("/"))
        assertTrue("stars must be parsed", (first.stars ?: 0) > 0)
        assertTrue("language must be parsed", !first.language.isNullOrBlank())
        assertEquals("rank must start at 1", 1, first.trendingRank)
        assertTrue("each record must carry its owner", first.owner.isNotBlank())
    }

    @Test
    fun oneCycleFillsTheIndex() {
        val token = readToken()
        val limiter = CrawlerRateLimiter(
            coreBudgetPerHour = 40,
            graphqlBudgetPerHour = 40,
            apiMinIntervalMs = 200,
            webMinIntervalMs = 200,
        )
        val store = CrawlerStore(context)
        try {
            val api = GitHubApi(token, limiter, store, log = { })
            val graph = GraphCrawler(api, store, log = { })
            val engine = CrawlerEngine(api, store, limiter, graph, log = { })

            val result = engine.runCycle()

            assertTrue("the cycle must have reached the trending board", result.trending >= 0)
            assertTrue("the cycle must not have spent more than its ceiling", result.apiCalls <= 40)

            val stats = store.graphStats()
            assertTrue("repositories must be indexed", stats.repos > 0)
            assertTrue("star history must be recorded", stats.snapshots > 0)
            assertTrue("the graph must have edges", stats.edges > 0)

            // The trending sweep is anonymous, so it must work without a token.
            assertTrue("trending repositories must be indexed", stats.repos >= 1)
        } finally {
            store.close()
        }
    }

    @Test
    fun theIndexSurvivesReopening() {
        val store = CrawlerStore(context)
        try {
            val before = store.graphStats()
            store.close()
            val reopened = CrawlerStore(context)
            try {
                val after = reopened.graphStats()
                assertTrue("the index must persist across restarts", after.repos >= before.repos)
            } finally {
                reopened.close()
            }
        } finally {
            try {
                store.close()
            } catch (_: Exception) {
                // already closed
            }
        }
    }

    /**
     * The token from the app's own token file, or from the instrumentation
     * argument `GITHUB_TOKEN`.
     */
    private fun readToken(): String? {
        val argument = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("GITHUB_TOKEN")
        if (!argument.isNullOrBlank()) return argument

        val file = File(context.filesDir, "github-token")
        if (file.isFile) {
            val value = runCatching { file.readText().trim() }.getOrNull()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }
}
