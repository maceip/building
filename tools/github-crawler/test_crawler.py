#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Offline tests for the GitHub crawler.

No network access is required: the trending parser is exercised against a
captured snippet of GitHub's trending markup, the rate limiter is driven
with a fake clock, and the store is exercised against a temporary database.

Run with:
    python3 tools/github-crawler/test_crawler.py
"""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from crawler.config import Config
from crawler.graph import relevance_score
from crawler.ratelimit import RateLimiter
from crawler.store import CrawlerStore
from crawler.tokens import describe_token
from crawler.trending import (
    normalise_language_key,
    parse_compact_count,
    parse_trending_html,
    trending_url,
)

# A trimmed but structurally faithful copy of one GitHub trending row.
TRENDING_SNIPPET = """
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
"""


class TrendingParserTest(unittest.TestCase):
    def test_parse_compact_count(self):
        self.assertEqual(parse_compact_count("12,345"), 12345)
        self.assertEqual(parse_compact_count("1.2k"), 1200)
        self.assertEqual(parse_compact_count("4.5m"), 4_500_000)
        self.assertEqual(parse_compact_count(""), 0)
        self.assertEqual(parse_compact_count("no digits"), 0)

    def test_normalise_language_key(self):
        self.assertEqual(normalise_language_key("cpp"), "c++")
        self.assertEqual(normalise_language_key("C++"), "c++")
        self.assertEqual(normalise_language_key(""), "all")
        self.assertEqual(normalise_language_key("Kotlin"), "kotlin")

    def test_trending_url(self):
        self.assertEqual(
            trending_url("https://github.com", "all", "daily"),
            "https://github.com/trending?since=daily",
        )
        self.assertEqual(
            trending_url("https://github.com", "kotlin", "weekly"),
            "https://github.com/trending/kotlin?since=weekly",
        )
        # GitHub accepts the literal "c++" segment, same as the Kotlin
        # implementation in GitHubDiscoveryClient.fetchLiveTrendingRepositories.
        self.assertIn("/trending/c++", trending_url("https://github.com", "c++", "daily"))

    def test_parse_trending_html(self):
        records = parse_trending_html(TRENDING_SNIPPET, period="daily")
        self.assertEqual(len(records), 2)

        first = records[0]
        self.assertEqual(first["owner"], "acme")
        self.assertEqual(first["name"], "widget-forge")
        self.assertEqual(first["slug"], "acme/widget-forge")
        self.assertEqual(first["stars"], 12345)
        self.assertEqual(first["forks"], 1200)
        self.assertEqual(first["stars_in_period"], 678)
        self.assertEqual(first["language"], "Rust")
        self.assertEqual(first["trending_rank"], 1)
        self.assertIn("fast & tiny widget toolchain", first["description"])
        self.assertTrue(first["built_by_avatars"])

        second = records[1]
        self.assertEqual(second["stars_in_period"], 1234)
        self.assertEqual(second["trending_rank"], 2)
        self.assertEqual(second["forks"], 42)

    def test_parse_empty(self):
        self.assertEqual(parse_trending_html(""), [])
        self.assertEqual(parse_trending_html("<html>nothing</html>"), [])


class RelevanceScoreTest(unittest.TestCase):
    """Mirrors DeveloperGraphEngine.rankAndFilterRelatedToApp."""

    def test_same_author_is_high(self):
        score, _ = relevance_score(
            "SAME_AUTHOR", "Kotlin", "Kotlin", {"widget"}, "a widget", "widget", 100
        )
        self.assertGreaterEqual(score, 36)

    def test_language_bonus_and_keyword_bonus(self):
        same_lang, _ = relevance_score(
            "RELATED_ARCHITECTURE", "Kotlin", "Kotlin", {"widget"}, "widget thing", "thing", 500
        )
        diff_lang, _ = relevance_score(
            "RELATED_ARCHITECTURE", "Kotlin", "Rust", {"widget"}, "widget thing", "thing", 500
        )
        self.assertEqual(same_lang - diff_lang, 24)

    def test_score_is_clamped(self):
        score, _ = relevance_score(
            "SAME_AUTHOR", "Kotlin", "Kotlin", {"a", "b", "c", "d"}, "a b c d", "d", 99999
        )
        self.assertLessEqual(score, 99)
        self.assertGreaterEqual(score, 10)


class RateLimiterTest(unittest.TestCase):
    def _config(self, **overrides) -> Config:
        base = Config(
            data_dir=Path("/tmp"),
            db_path=Path("/tmp/x.db"),
            token_file=Path("/tmp/token"),
            log_path=Path("/tmp/x.log"),
        )
        return base.with_overrides(**overrides)

    def test_budget_blocks_after_ceiling(self):
        slept: list[float] = []
        limiter = RateLimiter(
            self._config(core_budget_per_hour=2, api_min_interval=0.0),
            sleeper=slept.append,
            clock=lambda: 1000.0,
        )
        self.assertTrue(limiter.can_spend("core"))
        limiter.record_api_call("core")
        self.assertTrue(limiter.can_spend("core"))
        limiter.record_api_call("core")
        self.assertFalse(limiter.can_spend("core"))

    def test_search_has_minute_and_hour_ceilings(self):
        limiter = RateLimiter(
            self._config(search_budget_per_minute=1, search_budget_per_hour=2, api_min_interval=0.0),
            sleeper=lambda _s: None,
            clock=lambda: 1000.0,
        )
        limiter.record_api_call("search")
        self.assertFalse(limiter.can_spend("search"))  # minute ceiling hit
        limiter._buckets["search"].window_calls = 0  # minute rolls over
        limiter.record_api_call("search")
        self.assertFalse(limiter.can_spend("search"))  # hour ceiling hit

    def test_reserve_stops_before_quota_is_empty(self):
        limiter = RateLimiter(
            self._config(core_budget_per_hour=100, api_min_interval=0.0),
            sleeper=lambda _s: None,
            clock=lambda: 1000.0,
        )
        limiter._buckets["core"].limit = 5000
        limiter._buckets["core"].remaining = 100  # below the 15% reserve
        self.assertFalse(limiter.can_spend("core"))

    def test_rate_limit_backoff_is_recorded(self):
        limiter = RateLimiter(
            self._config(api_min_interval=0.0),
            sleeper=lambda _s: None,
            clock=lambda: 1000.0,
        )
        wait = limiter.note_rate_limited({"x-ratelimit-remaining": "0", "x-ratelimit-reset": "0"})
        self.assertGreater(wait, 0)
        self.assertEqual(limiter.rate_limited_hits, 1)


class StoreTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.store = CrawlerStore(Path(self._tmp.name) / "index.db")

    def tearDown(self):
        self.store.close()
        self._tmp.cleanup()

    def _repo(self, slug="acme/widget", **overrides):
        repo = {
            "slug": slug,
            "owner": slug.split("/")[0],
            "name": slug.split("/")[1],
            "description": "a widget",
            "stars": 100,
            "forks": 10,
            "language": "Kotlin",
            "topics": ["android", "widget"],
        }
        repo.update(overrides)
        return repo

    def test_upsert_is_idempotent_and_tracks_changes(self):
        is_new, changed = self.store.upsert_repo(self._repo(), "trending")
        self.assertTrue(is_new)
        self.assertFalse(changed)

        is_new, changed = self.store.upsert_repo(self._repo(), "trending")
        self.assertFalse(is_new)
        self.assertFalse(changed)

        is_new, changed = self.store.upsert_repo(self._repo(stars=150), "trending")
        self.assertFalse(is_new)
        self.assertTrue(changed)

        row = self.store.repo("acme/widget")
        self.assertEqual(row["stars"], 150)
        self.assertEqual(row["first_seen_at"], row["last_seen_at"])

    def test_snapshots_accumulate_history(self):
        self.store.upsert_repo(self._repo(), "trending")
        for stars in (100, 110, 120):
            self.store.add_snapshot("acme/widget", stars=stars, source="trending")
        rows = self.store.snapshots_for("acme/widget")
        self.assertEqual(len(rows), 3)
        self.assertEqual([r["stars"] for r in rows], [100, 110, 120])

    def test_edges_are_unique(self):
        first = self.store.add_edge("developer", "a", "repo", "acme/widget", "AUTHORED")
        second = self.store.add_edge("developer", "a", "repo", "acme/widget", "AUTHORED")
        self.assertTrue(first)
        self.assertFalse(second)
        self.assertEqual(self.store.edge_count(), 1)

    def test_unexpanded_developer_frontier(self):
        self.store.upsert_developer({"login": "alice"})
        self.store.upsert_developer({"login": "bob"})
        frontier = [r["login"] for r in self.store.unexpanded_developers()]
        self.assertIn("alice", frontier)

        self.store.add_edge("developer", "alice", "repo", "acme/widget", "AUTHORED")
        frontier = [r["login"] for r in self.store.unexpanded_developers()]
        self.assertNotIn("alice", frontier)
        self.assertIn("bob", frontier)

    def test_graph_stats(self):
        self.store.upsert_repo(self._repo(), "trending")
        self.store.upsert_developer({"login": "alice"})
        self.store.add_edge("developer", "alice", "repo", "acme/widget", "AUTHORED")
        stats = self.store.graph_stats()
        self.assertEqual(stats["repos"], 1)
        self.assertEqual(stats["developers"], 1)
        self.assertEqual(stats["edges"], 1)

    def test_http_cache_roundtrip(self):
        self.store.cache_put("https://example.test/x", "etag-1", "body", 200)
        etag, body = self.store.cache_get("https://example.test/x")
        self.assertEqual(etag, "etag-1")
        self.assertEqual(body, "body")


class TokenTest(unittest.TestCase):
    def test_describe_token_never_leaks(self):
        described = describe_token("gho_abcdefghijklmnop")
        self.assertNotIn("abcdefghijklmnop", described)
        self.assertIn("gho_", described)


class ConfigTest(unittest.TestCase):
    def test_defaults_are_conservative(self):
        config = Config.from_env()
        self.assertLessEqual(config.core_budget_per_hour, 1000)
        self.assertLessEqual(config.graphql_budget_per_hour, 1000)
        self.assertGreaterEqual(config.api_min_interval, 1.0)
        self.assertLessEqual(config.search_budget_per_minute, 10)


if __name__ == "__main__":
    unittest.main(verbosity=2)
