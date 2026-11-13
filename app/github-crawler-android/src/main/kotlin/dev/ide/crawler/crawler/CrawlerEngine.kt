package dev.ide.crawler.crawler

/**
 * One crawl cycle.
 *
 * The phone-side port of the macOS daemon's cycle
 * (tools/github-crawler/crawler/daemon.py):
 *
 *  1. **Trending sweep** - one GitHub trending board, rotating through the
 *     configured languages and periods. Costs no API quota.
 *  2. **Graph expansion** - pull the next unexpanded developer from the
 *     local frontier and traverse their follow graph up to two hops.
 *  3. **Relation linking** - derive repo-to-repo similarity edges purely
 *     from the local index. Costs nothing.
 */
class CrawlerEngine(
    private val api: GitHubApi,
    private val store: CrawlerStore,
    private val limiter: CrawlerRateLimiter,
    private val graph: GraphCrawler,
    private val log: (String) -> Unit = {},
) {

    /** What one cycle did. */
    data class CycleResult(
        val trending: Int = 0,
        val expanded: Int = 0,
        val related: Int = 0,
        val apiCalls: Int = 0,
        val webCalls: Int = 0,
        val reposSeen: Int = 0,
        val detail: String = "",
    )

    private val rotation = buildList {
        CrawlerConfig.TRENDING_PERIODS.forEach { period ->
            CrawlerConfig.TRENDING_LANGUAGES.forEach { language ->
                add(TrendingParser.normaliseLanguageKey(language) to period)
            }
        }
    }
    private var rotationIndex = 0

    private fun nextCombo(): Pair<String, String> {
        val combo = rotation[rotationIndex % rotation.size]
        rotationIndex++
        return combo
    }

    /** One trending board plus enrichment of its top entries. */
    fun sweepTrending(): Int {
        val (language, period) = nextCombo()
        log("trending sweep: $language ($period)")
        val html = api.fetchTrendingHtml(language, period)
        if (html.isNullOrBlank()) {
            log("trending fetch failed for $language/$period")
            store.recordTrendingRun(language, period, 0, "failed", "empty response")
            return 0
        }
        val records = TrendingParser.parseTrendingHtml(html, period = period, defaultLanguage = language)
        log("trending $language/$period -> ${records.size} repos")
        store.recordTrendingRun(language, period, records.size, "ok", null)

        var count = 0
        records.take(CrawlerConfig.ENRICH_TOP_N).forEach { record ->
            if (!limiter.canSpend("core")) {
                log("core budget reached, deferring enrichment")
                return count
            }
            try {
                graph.expandTrendingRepo(record.copy(trendingLanguage = language), depth = 1)
                count++
            } catch (e: Exception) {
                log("enrich failed for ${record.slug}: ${e.message}")
            }
        }
        return count
    }

    /** Expand developers from the frontier until the budget runs out. */
    fun expandGraph(maxCalls: Int = CrawlerConfig.MAX_CALLS_PER_CYCLE): Int {
        val callsBefore = limiter.totalApiCalls
        var expanded = 0
        while (limiter.totalApiCalls - callsBefore < maxCalls) {
            if (!limiter.canSpend("graphql") || !limiter.canSpend("core")) {
                log("budget reached, stopping graph expansion")
                break
            }
            val frontier = store.unexpandedDevelopers(1)
            if (frontier.isEmpty()) {
                log("graph frontier is empty")
                break
            }
            val login = frontier.first()
            try {
                graph.expandDeveloper(login, depth = CrawlerConfig.GRAPH_MAX_DEPTH)
                expanded++
            } catch (e: Exception) {
                log("expansion failed for $login: ${e.message}")
                store.markDeveloperExpanded(login)
                break
            }
        }
        return expanded
    }

    /** Offline similarity edges. */
    fun linkRelated(): Int = graph.computeRelatedEdges()

    /** Run one full cycle. */
    fun runCycle(): CycleResult {
        val runId = store.startRun("cycle")
        val apiBefore = limiter.totalApiCalls
        val webBefore = limiter.totalWebCalls
        val started = System.currentTimeMillis()
        var trending = 0
        var expanded = 0
        var related = 0
        var status = "ok"
        var detail = ""
        try {
            trending = sweepTrending()
            expanded = expandGraph()
            related = linkRelated()
            detail = "expanded=$expanded elapsed=${(System.currentTimeMillis() - started) / 1000}s"
        } catch (e: Exception) {
            status = "failed"
            detail = e.message ?: e.toString()
            log("cycle failed: $detail")
        }
        store.finishRun(
            runId, status,
            apiCalls = limiter.totalApiCalls - apiBefore,
            webCalls = limiter.totalWebCalls - webBefore,
            reposSeen = trending,
            edgesAdded = related,
            detail = detail,
        )
        return CycleResult(
            trending = trending,
            expanded = expanded,
            related = related,
            apiCalls = limiter.totalApiCalls - apiBefore,
            webCalls = limiter.totalWebCalls - webBefore,
            reposSeen = trending,
            detail = detail,
        )
    }
}
