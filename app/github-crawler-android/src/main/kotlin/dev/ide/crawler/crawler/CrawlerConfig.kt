package dev.ide.crawler.crawler

/**
 * Runtime configuration for the phone-side crawler.
 *
 * The defaults mirror the macOS daemon (tools/github-crawler) so both ends
 * behave the same and their databases can be merged. Every value can be
 * overridden from the app's settings screen.
 */
object CrawlerConfig {

    /** Minimum gap between two outbound API calls. This is what makes it "slow". */
    const val API_MIN_INTERVAL_MS = 4_000L

    /** Minimum gap between two trending page fetches (these cost no API quota). */
    const val WEB_MIN_INTERVAL_MS = 30_000L

    /**
     * The phone shares the user's GitHub token with the macOS daemon, so it
     * gets a deliberately small slice of the hourly quota. GitHub allows
     * 5000/hour for an authenticated account; 150 leaves the account
     * overwhelmingly free while still building the graph steadily.
     */
    const val CORE_BUDGET_PER_HOUR = 150
    const val GRAPHQL_BUDGET_PER_HOUR = 150
    const val SEARCH_BUDGET_PER_MINUTE = 2
    const val SEARCH_BUDGET_PER_HOUR = 60

    /** Never spend more than this many API calls in one cycle. */
    const val MAX_CALLS_PER_CYCLE = 25

    /** Seconds between trending sweeps. */
    const val TRENDING_INTERVAL_SECONDS = 3_600L

    /** Trending boards to rotate through. */
    val TRENDING_LANGUAGES = listOf("all", "kotlin", "swift", "python", "typescript", "javascript", "java", "rust")
    val TRENDING_PERIODS = listOf("daily", "weekly")

    /** How many trending repositories get full REST metadata per sweep. */
    const val ENRICH_TOP_N = 3

    /** Repositories / starred items pulled per developer in the graph walk. */
    const val PEER_FETCH_COUNT = 4

    /** Follow-graph hops to traverse. */
    const val GRAPH_MAX_DEPTH = 2

    const val API_BASE = "https://api.github.com"
    const val WEB_BASE = "https://github.com"
    const val GRAPHQL_URL = "https://api.github.com/graphql"

    const val USER_AGENT = "building-crawler-android/1.0 (local background indexer)"
}
