package dev.ide.crawler.crawler

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Adaptive, budget-aware pacing for GitHub API traffic.
 *
 * The phone-side port of the macOS daemon's limiter (tools/github-crawler/
 * crawler/ratelimit.py). Four independent guards keep the crawler polite:
 *
 *  1. **Pacing** - a minimum wall-clock gap between any two outbound calls.
 *  2. **Budgets** - per-bucket hourly ceilings enforced *before* a request is
 *     spent, because the rate-limit headers only appear after it is paid for.
 *  3. **Reserve** - stop spending a bucket once GitHub reports it is nearly
 *     empty, instead of using the last requests up.
 *  4. **Back-pressure** - a 403/429 pauses until the quota resets; a 5xx
 *     backs off exponentially.
 */
class CrawlerRateLimiter(
    private val apiMinIntervalMs: Long = CrawlerConfig.API_MIN_INTERVAL_MS,
    private val webMinIntervalMs: Long = CrawlerConfig.WEB_MIN_INTERVAL_MS,
    private val coreBudgetPerHour: Int = CrawlerConfig.CORE_BUDGET_PER_HOUR,
    private val graphqlBudgetPerHour: Int = CrawlerConfig.GRAPHQL_BUDGET_PER_HOUR,
    private val searchBudgetPerMinute: Int = CrawlerConfig.SEARCH_BUDGET_PER_MINUTE,
    private val searchBudgetPerHour: Int = CrawlerConfig.SEARCH_BUDGET_PER_HOUR,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private class Bucket(
        val name: String,
        val budget: Int,
        val windowMs: Long,
    ) {
        var limit = 0
        var remaining = 0
        var resetAt = 0L
        var windowStarted = 0L
        var windowCalls = 0

        fun roll(now: Long) {
            if (now - windowStarted >= windowMs) {
                windowStarted = now
                windowCalls = 0
            }
        }
    }

    private val core = Bucket("core", coreBudgetPerHour, 3_600_000L).also { it.windowStarted = clock() }
    private val graphql = Bucket("graphql", graphqlBudgetPerHour, 3_600_000L).also { it.windowStarted = clock() }
    private val search = Bucket("search", searchBudgetPerMinute, 60_000L).also { it.windowStarted = clock() }
    private val searchHour = Bucket("search_hour", searchBudgetPerHour, 3_600_000L).also { it.windowStarted = clock() }

    private var lastCallAt = 0L
    private var blockedUntil = 0L

    @Volatile
    var totalApiCalls = 0
        private set

    @Volatile
    var totalWebCalls = 0
        private set

    @Volatile
    var rateLimitedHits = 0
        private set

    private fun bucketFor(resource: String): Bucket = when (resource) {
        "graphql" -> graphql
        "search" -> search
        else -> core
    }

    /** True when another call against [resource] is allowed right now. */
    fun canSpend(resource: String): Boolean {
        val now = clock()
        val bucket = bucketFor(resource)
        bucket.roll(now)
        if (bucket.windowCalls >= bucket.budget) return false
        if (resource == "search") {
            searchHour.roll(now)
            if (searchHour.windowCalls >= searchHour.budget) return false
        }
        if (bucket.limit > 0 && bucket.remaining <= max(1, (bucket.limit * RESERVE_FRACTION).toInt())) {
            return false
        }
        return true
    }

    /** Block until it is acceptable to make the next call. */
    fun waitForSlot(resource: String = "core", web: Boolean = false) {
        val interval = if (web) webMinIntervalMs else apiMinIntervalMs
        var now = clock()
        if (now < blockedUntil) {
            sleeper(blockedUntil - now)
            now = clock()
        }
        val elapsed = now - lastCallAt
        if (elapsed < interval) {
            sleeper(interval - elapsed)
        }
        lastCallAt = clock()
    }

    fun recordApiCall(resource: String) {
        val now = clock()
        val bucket = bucketFor(resource)
        bucket.roll(now)
        bucket.windowCalls++
        if (resource == "search") {
            searchHour.roll(now)
            searchHour.windowCalls++
        }
        totalApiCalls++
    }

    fun recordWebCall() {
        totalWebCalls++
    }

    /** Mirror GitHub's rate-limit headers into our local state. */
    fun observeHeaders(limit: Int?, remaining: Int?, resetEpochSeconds: Long?, resource: String?) {
        val bucket = bucketFor(resource ?: "core")
        if (limit != null) bucket.limit = limit
        if (remaining != null) bucket.remaining = remaining
        if (resetEpochSeconds != null && resetEpochSeconds > 0) bucket.resetAt = resetEpochSeconds * 1000L
    }

    /**
     * Record a 403/429 and compute how long to back off. Returns the wait in
     * milliseconds.
     */
    fun noteRateLimited(retryAfterSeconds: Long?): Long {
        rateLimitedHits++
        val now = clock()
        var wait = 0L
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            wait = retryAfterSeconds * 1000L
        } else if (core.remaining == 0 && core.resetAt > now) {
            wait = core.resetAt - now
        }
        if (wait <= 0L) {
            // Secondary rate limit / abuse detection: back off generously.
            wait = 60_000L
        }
        wait = min(max(wait, 1_000L), 3_600_000L)
        blockedUntil = now + wait
        return wait
    }

    /** Exponential backoff with jitter for 5xx / network errors. */
    fun noteTransientFailure(attempt: Int): Long {
        val base = min(2.0.pow(max(0, attempt)).toLong(), 300_000L)
        val jitter = (base * 0.5 * ((attempt * 2654435761L) % 1000) / 1000.0).toLong()
        val wait = base / 2 + jitter
        blockedUntil = clock() + wait
        return wait
    }

    fun snapshot(): Map<String, Any> {
        val now = clock()
        core.roll(now)
        graphql.roll(now)
        search.roll(now)
        searchHour.roll(now)
        return mapOf(
            "core" to mapOf("budget" to core.budget, "used" to core.windowCalls, "remoteRemaining" to core.remaining),
            "graphql" to mapOf("budget" to graphql.budget, "used" to graphql.windowCalls, "remoteRemaining" to graphql.remaining),
            "search" to mapOf("budget" to search.budget, "used" to search.windowCalls),
            "totalApiCalls" to totalApiCalls,
            "totalWebCalls" to totalWebCalls,
            "rateLimitedHits" to rateLimitedHits,
        )
    }

    companion object {
        /** Stop spending a bucket once this fraction of it is gone. */
        const val RESERVE_FRACTION = 0.15
    }
}
