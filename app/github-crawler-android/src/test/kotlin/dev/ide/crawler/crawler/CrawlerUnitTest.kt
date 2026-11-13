package dev.ide.crawler.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline JVM tests for the phone-side crawler.
 *
 * The trending parser is exercised against a captured snippet of GitHub's
 * markup, the relevance scoring against the Kotlin weights from
 * `DeveloperGraphEngine`, and the rate limiter with a fake clock.
 */
class TrendingParserTest {

    @Test
    fun parsesCompactCounts() {
        assertEquals(12345, TrendingParser.parseCompactCount("12,345"))
        assertEquals(1200, TrendingParser.parseCompactCount("1.2k"))
        assertEquals(4_500_000, TrendingParser.parseCompactCount("4.5m"))
        assertEquals(0, TrendingParser.parseCompactCount(""))
        assertEquals(0, TrendingParser.parseCompactCount("no digits"))
    }

    @Test
    fun normalisesLanguageKeys() {
        assertEquals("c++", TrendingParser.normaliseLanguageKey("cpp"))
        assertEquals("c++", TrendingParser.normaliseLanguageKey("C++"))
        assertEquals("all", TrendingParser.normaliseLanguageKey(""))
        assertEquals("kotlin", TrendingParser.normaliseLanguageKey("Kotlin"))
    }

    @Test
    fun buildsTrendingUrls() {
        assertEquals(
            "https://github.com/trending?since=daily",
            TrendingParser.trendingUrl("all", "daily"),
        )
        assertEquals(
            "https://github.com/trending/kotlin?since=weekly",
            TrendingParser.trendingUrl("kotlin", "weekly"),
        )
        // GitHub accepts the literal "c++" segment, same as the in-app crawler.
        assertTrue(TrendingParser.trendingUrl("c++", "daily").contains("/trending/c++"))
    }

    @Test
    fun parsesATrendingRow() {
        val records = TrendingParser.parseTrendingHtml(TRENDING_SNIPPET, period = "daily")
        assertEquals(2, records.size)

        val first = records[0]
        assertEquals("acme", first.owner)
        assertEquals("widget-forge", first.name)
        assertEquals("acme/widget-forge", first.slug)
        assertEquals(12345, first.stars)
        assertEquals(1200, first.forks)
        assertEquals(678, first.starsInPeriod)
        assertEquals("Rust", first.language)
        assertEquals(1, first.trendingRank)
        assertTrue(first.description!!.contains("fast & tiny widget toolchain"))

        val second = records[1]
        assertEquals(1234, second.starsInPeriod)
        assertEquals(2, second.trendingRank)
        assertEquals(42, second.forks)
    }

    @Test
    fun parsesNothingFromEmptyHtml() {
        assertTrue(TrendingParser.parseTrendingHtml("").isEmpty())
        assertTrue(TrendingParser.parseTrendingHtml("<html>nothing</html>").isEmpty())
    }

    companion object {
        /** A trimmed but structurally faithful copy of one GitHub trending row. */
        private val TRENDING_SNIPPET = """
            <article class="Box-row">
              <h2 class="h3 lh-condensed">
                <a href="/acme/widget-forge" data-view-component="true" class="Link">
                  <span class="text-normal">acme /</span>
                  widget-forge
                </a>
              </h2>
              <p class="col-9 color-fg-muted my-1 pr-4">
                A fast &amp; <strong>tiny</strong> widget toolchain
              </p>
              <div class="f6 color-fg-muted mt-2">
                <span itemprop="programmingLanguage">Rust</span>
                <a href="/acme/widget-forge/stargazers" class="Link Link--muted d-inline-block mr-3">
                  12,345
                </a>
                <a href="/acme/widget-forge/forks" class="Link Link--muted d-inline-block mr-3">
                  1.2k
                </a>
                <span class="d-inline-block ml-0 mr-3">
                  <span class="css-truncate-target" data-href="/acme/widget-forge/stargazers">
                    678 stars today
                  </span>
                </span>
                <img class="avatar-user" src="https://avatars.githubusercontent.com/u/1?v=4" width="20">
              </div>
            </article>
            <article class="Box-row">
              <h2 class="h3 lh-condensed">
                <a href="/other/thing" class="Link"><span class="text-normal">other /</span>thing</a>
              </h2>
              <p class="col-9 color-fg-muted my-1 pr-4">Another project</p>
              <span itemprop="programmingLanguage">Go</span>
              <a href="/other/thing/stargazers" class="Link">9,876</a>
              <a href="/other/thing/forks" class="Link">42</a>
              <span>1,234 stars this week</span>
            </article>
        """.trimIndent()
    }
}

class RateLimiterTest {

    @Test
    fun budgetBlocksAfterCeiling() {
        val limiter = CrawlerRateLimiter(
            coreBudgetPerHour = 2,
            apiMinIntervalMs = 0,
            sleeper = { },
            clock = { 1_000L },
        )
        assertTrue(limiter.canSpend("core"))
        limiter.recordApiCall("core")
        assertTrue(limiter.canSpend("core"))
        limiter.recordApiCall("core")
        assertFalse(limiter.canSpend("core"))
    }

    @Test
    fun searchHasMinuteAndHourCeilings() {
        val limiter = CrawlerRateLimiter(
            searchBudgetPerMinute = 1,
            searchBudgetPerHour = 2,
            apiMinIntervalMs = 0,
            sleeper = { },
            clock = { 1_000L },
        )
        limiter.recordApiCall("search")
        assertFalse("minute ceiling reached", limiter.canSpend("search"))
    }

    @Test
    fun reserveStopsBeforeTheQuotaIsEmpty() {
        val limiter = CrawlerRateLimiter(
            coreBudgetPerHour = 100,
            apiMinIntervalMs = 0,
            sleeper = { },
            clock = { 1_000L },
        )
        limiter.observeHeaders(limit = 5000, remaining = 100, resetEpochSeconds = null, resource = "core")
        assertFalse("below the 15% reserve", limiter.canSpend("core"))
    }

    @Test
    fun rateLimitBackoffIsRecorded() {
        val limiter = CrawlerRateLimiter(
            apiMinIntervalMs = 0,
            sleeper = { },
            clock = { 1_000L },
        )
        val wait = limiter.noteRateLimited(null)
        assertTrue(wait > 0)
        assertEquals(1, limiter.rateLimitedHits)
    }
}

class RelevanceScorerTest {

    @Test
    fun sameAuthorScoresHighest() {
        val (score, _) = RelevanceScorer.score(
            connection = "SAME_AUTHOR",
            targetLanguage = "Kotlin",
            peerLanguage = "Kotlin",
            targetKeywords = setOf("widget"),
            peerDescription = "a widget",
            peerName = "widget",
            peerStars = 100,
        )
        assertTrue(score >= 36)
    }

    @Test
    fun languageBonusIsWorth24() {
        val (sameLang, _) = RelevanceScorer.score(
            "RELATED_ARCHITECTURE", "Kotlin", "Kotlin", setOf("widget"), "widget thing", "thing", 500,
        )
        val (diffLang, _) = RelevanceScorer.score(
            "RELATED_ARCHITECTURE", "Kotlin", "Rust", setOf("widget"), "widget thing", "thing", 500,
        )
        assertEquals(24, sameLang - diffLang)
    }

    @Test
    fun scoreIsClamped() {
        val (score, _) = RelevanceScorer.score(
            "SAME_AUTHOR", "Kotlin", "Kotlin", setOf("a", "b", "c", "d"), "a b c d", "d", 99999,
        )
        assertTrue(score <= 99)
        assertTrue(score >= 10)
    }

    @Test
    fun keywordOverlapRaisesTheScore() {
        val (withOverlap, _) = RelevanceScorer.score(
            "RELATED_ARCHITECTURE", "Kotlin", "Rust", setOf("widget", "toolkit"), "widget toolkit", "tool", 10,
        )
        val (withoutOverlap, _) = RelevanceScorer.score(
            "RELATED_ARCHITECTURE", "Kotlin", "Rust", setOf("widget", "toolkit"), "unrelated thing", "other", 10,
        )
        assertTrue(withOverlap > withoutOverlap)
    }
}
