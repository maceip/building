# GitHub Crawler (Android)

A standalone Android app that slowly crawls GitHub Trending and the
developer/repository graph **on the phone**, in the background, building a
persistent local index and history.

It is the phone-side counterpart of the macOS daemon in
`tools/github-crawler`. Both share the same index schema, the same graph
model and the same rate-limit policy, so their databases can be merged later.

## Why a separate app

The Building IDE is a very large application (bundled kotlinc, JDT, a full
Compose UI). A background indexer that has to run for weeks does not want
any of that in memory, and it does not want to compete with the editor for
CPU. This module is a deliberately small, dependency-free app: plain Android
Views, no AndroidX, no Compose - just OkHttp and the crawler.

## What it does

Every cycle performs three phases:

1. **Trending sweep** - fetches one GitHub Trending board
   (`/trending/<language>?since=<period>`), rotating through the configured
   languages and periods. Trending pages are plain HTML and cost **no** API
   quota.
2. **Graph expansion** - takes the next unexpanded developer from the local
   frontier and traverses their follow graph up to two hops: their
   repositories, their starred repositories, and who they follow (plus the
   same for their own follows).
3. **Relation linking** - derives repository-to-repository "similar project"
   edges from the local index. Entirely offline and free.

## Mapping to the in-app crawler

| Kotlin (in-app) | Kotlin (this app) | Notes |
| --- | --- | --- |
| `GitHubDiscoveryClient.parseTrendingHtml` | `crawler/TrendingParser.kt` | Same regular expressions, same output shape. |
| `GitHubDiscoveryClient.fetchLiveTrendingRepositories` | `crawler/TrendingParser.trendingUrl` | Same URL construction. |
| `GitHubDiscoveryClient.crawlRelatedAndAuthorNetwork` | `crawler/GraphCrawler.kt` | Same 2-hop traversal, same connection types. |
| `DeveloperGraphEngine.rankAndFilterRelatedToApp` | `crawler/RelevanceScorer.kt` | Identical weights: connection + language + keyword + star bonus, clamped to 10..99. |
| `GitHubDiscoveryClient.fetchRateLimit` | `crawler/CrawlerRateLimiter.kt` | Strictly stronger: budgets are enforced *before* spending. |

## Staying inside the API limits

The phone shares your GitHub token with the macOS daemon, so it takes a
deliberately small slice of the quota:

| Guard | Default | Effect |
| --- | --- | --- |
| `CrawlerConfig.API_MIN_INTERVAL_MS` | 4 s | Minimum gap between two API calls. |
| `CrawlerConfig.CORE_BUDGET_PER_HOUR` | 150 | Local ceiling on REST calls (3% of the 5000/hour quota). |
| `CrawlerConfig.GRAPHQL_BUDGET_PER_HOUR` | 150 | Local ceiling on GraphQL calls. |
| `CrawlerConfig.SEARCH_BUDGET_PER_MINUTE` / `_PER_HOUR` | 2 / 60 | Local ceiling on the much smaller search bucket. |
| `CrawlerConfig.MAX_CALLS_PER_CYCLE` | 25 | Ceiling on API calls in one cycle. |

On top of that:

- **Conditional requests.** Every GET carries the stored `ETag`; a `304 Not
  Modified` costs no quota, so re-checking a known repository is free.
- **Reserve.** Below 15% of a bucket, the crawler stops spending it.
- **Back-off.** `403`/`429` waits for the quota reset; `5xx` backs off
  exponentially with jitter.
- **GraphQL batching.** A developer's repositories + starred repositories +
  following list come from ONE request instead of three.

## The index

SQLite at `/data/data/dev.ide.crawler/databases/crawler-index.db` (inside the
app sandbox). The schema is identical to the macOS daemon's:

`repos`, `repo_snapshots` (the history), `developers`, `edges` (the graph),
`trending_runs`, `runs`, `api_calls` (the audit trail), `http_cache` (ETags).

Edge vocabulary: `developer -AUTHORED-> repo`, `developer -STARRED-> repo`,
`developer -FOLLOWS-> developer`, `repo -RELATED-> repo`.

## Using it

1. Build and install:
   ```sh
   ./gradlew :github-crawler-android:installDebug
   ```
2. Open **GitHub Crawler** and paste a GitHub personal access token
   (`repo` scope is enough) into the token field, then **Save token**.
3. Tap **Run one cycle** to see it work immediately, or **Start crawling** to
   leave it running in the background.

The screen shows the live index size (repos, developers, edges, snapshots),
how much of the GitHub quota is left, and a rolling log.

The crawler runs as a foreground service with an ongoing notification, so the
OS does not kill it while the phone is idle. It restarts itself after a
reboot. If Android 13+ asks for notification permission, grant it so the
service can keep running.

## Tests

```sh
./gradlew :github-crawler-android:testDebugUnitTest
```

Offline JVM tests: the trending parser against captured GitHub markup, the
relevance scoring against the Kotlin weights, and the rate limiter with a
fake clock. No network access and no device required.
