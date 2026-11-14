# GitHub / Trending background crawler

A slow, rate-limit-safe background service that crawls GitHub Trending and the
developer follow-graph using **your own GitHub account**, and builds a
persistent local **index** and **history** on this laptop.

It was extracted from the in-app crawler that lives in
`app/ide-core/src/main/kotlin/dev/ide/core/discovery/` (branch
`feat/unified-ios-crawler-llm-corner`, commit `92f6493c8`) and reworked into
a standalone daemon that runs under `launchd`, completely independent of the
IDE app.

---

## What it actually does

Every cycle performs three phases:

1. **Trending sweep** - fetches one GitHub Trending board
   (`/trending/<language>?since=<period>`), rotating through the configured
   languages and periods. GitHub's trending pages are plain HTML and cost
   **no API quota**.
2. **Graph expansion** - takes the next unexpanded developer from the local
   frontier and traverses their follow graph up to two hops: their
   repositories, their starred repositories, and who they follow (plus the
   same for their own follows).
3. **Relation linking** - derives repository-to-repository "similar project"
   edges from the local index. This phase is entirely offline and free.

The result is a growing local graph of repositories and developers, with a
time series of star/fork counts for every repository it has ever seen.

## Mapping to the Kotlin crawler

| Kotlin (in-app) | Python (this service) | Notes |
| --- | --- | --- |
| `GitHubDiscoveryClient.parseTrendingHtml` | `crawler/trending.py: parse_trending_html` | Same regular expressions, same output shape. |
| `GitHubDiscoveryClient.fetchLiveTrendingRepositories` | `crawler/trending.py: trending_url` | Same URL construction. |
| `GitHubDiscoveryClient.crawlRelatedAndAuthorNetwork` | `crawler/graph.py: GraphCrawler.expand_developer` | Same 2-hop traversal, same connection types. |
| `DeveloperGraphEngine.rankAndFilterRelatedToApp` | `crawler/graph.py: relevance_score` | Identical weights: connection + language + keyword + star bonus. |
| `DeveloperGraphEngine.filterAndRankPeerProjects` | `crawler/graph.py` + `Config.min_stars` | Same minimum-star and fork filters. |
| `GitHubDiscoveryClient.fetchRateLimit` | `crawler/ratelimit.py: RateLimiter` | Strictly stronger: budgets are enforced *before* spending. |
| `GitHubDiscoveryClient.verifyToken` | `crawler/tokens.py: resolve_token` | Token is never stored in the repo and never logged. |

The in-app Kotlin crawler is left untouched - the IDE app keeps working
exactly as before. This service is additive.

## Why it stays inside the API limits

GitHub's authenticated buckets are **5000 requests/hour** for both `core` and
`graphql`, and **30/minute** for `search`. The daemon enforces four
independent guards:

| Guard | Default | Effect |
| --- | --- | --- |
| `api_min_interval` | 1.5 s | Minimum gap between any two API calls. |
| `core_budget_per_hour` | 600 | Local ceiling on REST calls (~12% of quota). |
| `graphql_budget_per_hour` | 600 | Local ceiling on GraphQL calls (~12% of quota). |
| `search_budget_per_minute` / `_per_hour` | 4 / 400 | Local ceiling on the much smaller search bucket. |

On top of that:

- **Conditional requests.** Every GET carries the stored `ETag`. A `304 Not
  Modified` costs **no** quota, so re-checking a known repository is free.
  This is what makes a continuous index affordable.
- **Reserve.** When GitHub reports fewer than 15% of a bucket remaining, the
  daemon stops spending it instead of using it up.
- **Back-off.** A `403`/`429` triggers a wait until the quota reset (or the
  `Retry-After` value), and `5xx`/network errors back off exponentially with
  jitter.
- **Per-cycle ceiling.** `max_calls_per_cycle` (60) stops one slow hour from
  spending a whole day.
- **GraphQL batching.** A developer's repositories + starred repositories +
  following list are fetched in **one** GraphQL request instead of three REST
  requests, so a 2-hop traversal costs a third of what it would otherwise.

In practice the daemon uses well under 2% of the hourly quota.

## Install

```sh
cd ~/building/tools/github-crawler
./install/install.sh install     # writes the launchd agent and starts it
```

The installer:

- writes `~/Library/LaunchAgents/com.building.crawler.plist`;
- copies your `gh` token into
  `~/Library/Application Support/building-crawler/token` with mode `0600`;
- starts the agent at low priority (`Nice 5`, `ProcessType Background`), so it
  never competes with real work.

To stop and remove it:

```sh
./install/install.sh --stop
```

## Token resolution

The daemon resolves the token in this order and never writes it to the
repository or the log:

1. `GH_TOKEN` / `GITHUB_TOKEN` environment variable;
2. the token file (`CRAWLER_TOKEN_FILE`, default `<data dir>/token`);
3. `gh auth token` - whatever the GitHub CLI is already logged in as.

`python3 main.py token` prints which source is in use without revealing it.

## Commands

```sh
python3 main.py once            # run one full cycle and print a summary
python3 main.py status          # index size, graph size, budget, last runs
python3 main.py trending kotlin daily
python3 main.py history maceip/building
python3 main.py graph maceip     # neighbourhood of a developer
python3 main.py export --output index.json
python3 main.py token
python3 main.py doctor          # verify token, API and trending access
```

## The local index

SQLite at `~/Library/Application Support/building-crawler/index.db` (WAL mode).

| Table | Contents |
| --- | --- |
| `repos` | Current state of every repository seen (stars, forks, topics, license, timestamps). |
| `repo_snapshots` | **History** - one row per observation, with stars, forks, stars-in-period and trending rank. This is what makes growth visible over time. |
| `developers` | Developer profiles discovered while traversing the follow graph. |
| `edges` | **The graph**: `developer -AUTHORED-> repo`, `developer -STARRED-> repo`, `developer -FOLLOWS-> developer`, `repo -RELATED-> repo`. Each edge keeps the original Kotlin connection label in `reason`. |
| `trending_runs` / `runs` | One row per trending sweep and per cycle. |
| `api_calls` | Audit trail of every outbound request - the proof of the budget. |
| `http_cache` | ETag + body cache, so unchanged resources cost no quota. |

Query it directly at any time:

```sh
sqlite3 ~/Library/Application\ Support/building-crawler/index.db \
  "SELECT slug, stars FROM repos ORDER BY stars DESC LIMIT 10;"
```

## Tuning

Every knob is an environment variable prefixed with `CRAWLER_`:

| Variable | Default | Meaning |
| --- | --- | --- |
| `CRAWLER_DATA_DIR` | `~/Library/Application Support/building-crawler` | Where the index, token and log live. |
| `CRAWLER_API_MIN_INTERVAL` | `1.5` | Seconds between API calls. Raise to crawl slower. |
| `CRAWLER_CORE_BUDGET_PER_HOUR` | `600` | REST calls per hour. |
| `CRAWLER_GRAPHQL_BUDGET_PER_HOUR` | `600` | GraphQL calls per hour. |
| `CRAWLER_TRENDING_INTERVAL` | `2700` | Seconds between trending sweeps. |
| `CRAWLER_LANGUAGES` | `all,kotlin,swift,python,typescript,javascript,java,rust,c++` | Trending boards to rotate through. |
| `CRAWLER_PERIODS` | `daily,weekly` | Trending periods to rotate through. |
| `CRAWLER_ENRICH_TOP_N` | `5` | Trending repos enriched with full metadata per sweep. |
| `CRAWLER_MAX_CALLS_PER_CYCLE` | `60` | Hard ceiling on API calls in one cycle. |
| `CRAWLER_GRAPH_MAX_DEPTH` | `2` | Follow-graph hops to traverse. |

To crawl noticeably slower, raise `CRAWLER_API_MIN_INTERVAL` and lower
`CRAWLER_MAX_CALLS_PER_CYCLE`. To crawl faster (still safely), raise the
budgets - but keep `core`/`graphql` under ~1000/hour.

## When something goes wrong

`launchd` will happily restart a crashing daemon forever, every 30 seconds,
and say nothing. That is the worst failure mode for a background service: it
looks installed, it looks enabled, and it is quietly doing nothing. So the
daemon does not go alone - a second agent, the **watchdog**, watches it.

```sh
./install/install.sh install     # installs both agents
```

| Agent | Job | What it does |
| --- | --- | --- |
| the crawler | `com.building.crawler` | runs the daemon, restarts it if it dies |
| the watchdog | `com.building.crawler.watchdog` | checks every 5 minutes whether the crawler is actually working |

### What the watchdog notices

| Condition | Meaning |
| --- | --- |
| `CRASH_LOOP` | the job restarted 3+ times inside one interval - launchd is papering over a crash |
| `EXITED` | the job is loaded but stopped with a failure exit code |
| `NOT_RUNNING` | the job is not loaded at all |
| `STALLED` | the process is alive but has not finished a cycle in 90 minutes - alive but wedged |
| `NO_HEARTBEAT` | the process is running but never reported a heartbeat |

The crawler writes a heartbeat (`state.json`) on every cycle, which is what
separates *alive* from *alive and working*. A process-liveness check cannot
tell those apart.

### Where you get told

1. **A macOS notification** - a banner with the reason.
2. **`~/Library/Logs/building-crawler/alerts.log`** - every alert, forever.
3. **A webhook**, if you set one - `CRAWLER_ALERT_WEBHOOK=https://...`. The
   body carries both `message` (ntfy) and `text` (Slack, Discord), so any of
   them work unchanged. ntfy.sh needs no account:
   `CRAWLER_ALERT_WEBHOOK=https://ntfy.sh/my-crawler`.
4. **Immediately on a fatal startup error** - a crash before the daemon
   starts is reported at once, with the reason, instead of waiting for the
   watchdog to notice.

The same alert is not repeated inside an hour, so a long crash loop
announces itself once rather than every five minutes.

### The off switch

```sh
./install/install.sh --mute      # silence alerts
./install/install.sh --unmute
touch "$HOME/Library/Application Support/building-crawler/alerts-disabled"
```

Stopping the service on purpose is not an incident: `./install/install.sh
--stop` marks it as deliberate, so the watchdog stays quiet.

### Checking by hand

```sh
python3 main.py health      # is it alive AND working?
python3 main.py watchdog    # run one watchdog pass now
python3 main.py status      # the index plus the daemon health block
```

`health` shows the launchd job state, the heartbeat age, the cycle count and
the last error:

```json
{
  "job_loaded": true,
  "job_running": true,
  "job_runs": 1,
  "heartbeat_status": "running",
  "heartbeat_age_seconds": 243.9,
  "cycles": 1,
  "last_error": null
}
```


## Tests

```sh
python3 tools/github-crawler/test_crawler.py
```

44 offline tests: the trending parser against captured GitHub markup, the
relevance scoring against the Kotlin weights, the rate limiter with a fake
clock, the store against a temporary database, and the watchdog's detection
logic - baseline handling, crash-loop detection, the alert cooldown and the
off switches. No network access needed. Verified on Python 3.9 and 3.14.
