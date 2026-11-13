package dev.ide.crawler.crawler

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Live host tests for the phone crawler's network layer.
 *
 * These run on the build machine, not on the phone, and they exercise the
 * REAL [GitHubApi] against the REAL GitHub API: the trending scraper, the
 * REST repository fetch, the GraphQL developer bundle, and the ETag
 * conditional-request path.
 *
 * They exist because the phone is not always connected to the build
 * machine, and a silent GraphQL-shape or JSON-parsing regression would
 * otherwise leave the device-side index empty with nothing looking broken.
 *
 * A token is required for the GraphQL parts. When none can be found the
 * tests are skipped rather than failed, so an offline machine is not a red
 * build. To run them with a token:
 *
 *   ./gradlew :github-crawler-android:testDebugUnitTest \
 *       -Dgithub.token="$(gh auth token)"
 *
 * or by exporting `GH_TOKEN`.
 */
class LiveCrawlerHostTest {

    private fun resolveToken(): String? {
        System.getProperty("github.token")?.takeIf { it.isNotBlank() }?.let { return it }
        System.getenv("GH_TOKEN")?.takeIf { it.isNotBlank() }?.let { return it }
        System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }?.let { return it }
        // The GitHub CLI is the usual source on this machine.
        return runCatching {
            val process = ProcessBuilder("gh", "auth", "token")
                .redirectErrorStream(false)
                .start()
            val value = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor(20, TimeUnit.SECONDS) && value.isNotBlank()) value else null
        }.getOrNull()
    }

    private fun api(token: String?, persistence: CrawlerPersistence = InMemoryCrawlerPersistence()):
        Pair<GitHubApi, CrawlerRateLimiter> {
        val limiter = CrawlerRateLimiter(
            coreBudgetPerHour = 60,
            graphqlBudgetPerHour = 60,
            apiMinIntervalMs = 200,
            webMinIntervalMs = 200,
            sleeper = { },
        )
        return GitHubApi(token, limiter, persistence, log = { Log.d("LiveCrawlerHostTest", it) }) to limiter
    }

    @Test
    fun liveTrendingPageIsParsedByTheDeviceParser() {
        val (client, _) = api(null)
        val html = client.fetchTrendingHtml("all", "daily")
        assertNotNull("the trending page must be reachable", html)
        assertTrue("the trending page must not be empty", html!!.isNotBlank())

        val records = TrendingParser.parseTrendingHtml(html, period = "daily")
        assertTrue("expected trending repositories, got none", records.isNotEmpty())
        assertTrue("expected a full trending board", records.size >= 5)

        val first = records.first()
        assertTrue("slug must be owner/name", first.slug.contains("/"))
        assertTrue("stars must be parsed", (first.stars ?: 0) > 0)
        assertTrue("language must be parsed", !first.language.isNullOrBlank())
        assertEquals("rank must start at 1", 1, first.trendingRank)
    }

    @Test
    fun liveRepositoryIsFetchedAndNormalised() {
        val token = resolveToken()
        assumeTrue("no GitHub token available", token != null)
        val (client, _) = api(token)

        val repo = client.getRepo("maceip", "building")
        assertNotNull("the repository must be fetched", repo)
        assertEquals("maceip/building", repo!!.slug)
        assertEquals("maceip", repo.owner)
        assertEquals("building", repo.name)
        assertNotNull("the star count must be parsed even when it is zero", repo.stars)
        assertEquals("Kotlin", repo.language)
        assertTrue("html url must be built", repo.htmlUrl!!.startsWith("https://github.com/"))
    }

    @Test
    fun livePopularRepositoryHasItsStarsParsed() {
        val token = resolveToken()
        assumeTrue("no GitHub token available", token != null)
        val (client, _) = api(token)

        val repo = client.getRepo("square", "okhttp")
        assertNotNull("the repository must be fetched", repo)
        assertTrue("a popular repository must have stars", (repo!!.stars ?: 0) > 0)
        assertTrue("a popular repository must have forks", (repo.forks ?: 0) > 0)
        assertTrue("topics must be parsed", repo.topics.isNotEmpty())
        assertNotNull("the default branch must be parsed", repo.defaultBranch)
    }

    @Test
    fun liveGraphQlBundleResolvesAUser() {
        val token = resolveToken()
        assumeTrue("no GitHub token available", token != null)
        val (client, _) = api(token)

        val bundle = client.developerBundle("maceip")
        assertNotNull("the GraphQL bundle must resolve a user", bundle)
        assertEquals("maceip", bundle!!.developer.login)
        assertTrue("a user must have repositories", bundle.repos.isNotEmpty())
        assertTrue("a user must have a following list", bundle.following.isNotEmpty())

        val repo = bundle.repos.first()
        assertTrue("a repo slug must be owner/name", repo.slug.contains("/"))
        assertTrue("a repo must have stars", (repo.stars ?: 0) >= 0)
    }

    @Test
    fun liveGraphQlBundleResolvesAnOrganization() {
        val token = resolveToken()
        assumeTrue("no GitHub token available", token != null)
        val (client, _) = api(token)

        // Organizations have repositories but no starred list and no follow graph;
        // the query uses an inline fragment for User so one query covers both.
        val bundle = client.developerBundle("alibaba")
        assertNotNull("the GraphQL bundle must resolve an organization", bundle)
        assertTrue("an organization must have repositories", bundle!!.repos.isNotEmpty())
        assertTrue("an organization must not report starred repos", bundle.starred.isEmpty())
    }

    @Test
    fun conditionalRequestsAreFreeAgainstTheQuota() {
        val token = resolveToken()
        assumeTrue("no GitHub token available", token != null)
        val persistence = InMemoryCrawlerPersistence()
        val (client, limiter) = api(token, persistence)

        val first = client.getRepo("maceip", "building")
        assertNotNull("the first fetch must succeed", first)
        val callsAfterFirst = limiter.totalApiCalls

        val second = client.getRepo("maceip", "building")
        assertNotNull("the second fetch must succeed", second)
        assertEquals("the second fetch must be a conditional request", 1, client.cachedHits)

        val cached = persistence.calls.count { it["cached"] == true }
        assertTrue("the cached response must be recorded as cached", cached >= 1)
        assertTrue("the first fetch must have cost a call", callsAfterFirst >= 1)
        assertFalse("the cache must hold an ETag", persistence.cacheGet("https://api.github.com/repos/maceip/building").first == null)
    }
}
