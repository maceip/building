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
from crawler.heartbeat import Heartbeat, HeartbeatStore
from crawler.store import CrawlerStore
from crawler.tokens import describe_token
from crawler.watchdog import JobState, evaluate, parse_job_state, run_watchdog
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


class HeartbeatTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.store = HeartbeatStore(Path(self._tmp.name))

    def tearDown(self):
        self._tmp.cleanup()

    def test_roundtrip(self):
        self.store.write(Heartbeat(status="running", pid=42, cycles=7, repos=100))
        loaded = self.store.read()
        self.assertIsNotNone(loaded)
        self.assertEqual("running", loaded.status)
        self.assertEqual(42, loaded.pid)
        self.assertEqual(7, loaded.cycles)
        self.assertEqual(100, loaded.repos)

    def test_missing_file_reads_as_none(self):
        self.assertIsNone(self.store.read())

    def test_corrupt_file_reads_as_none(self):
        self.store.data_dir.mkdir(parents=True, exist_ok=True)
        self.store.path.write_text("{not json", encoding="utf-8")
        self.assertIsNone(self.store.read())

    def test_age_is_measured_from_the_update(self):
        self.store.write(Heartbeat(status="running"))
        age = self.store.age_seconds()
        self.assertIsNotNone(age)
        self.assertGreaterEqual(age, 0.0)
        self.assertLess(age, 60.0)

    def test_write_is_atomic(self):
        self.store.write(Heartbeat(status="running"))
        # No temp file must be left behind.
        leftovers = list(self.store.data_dir.glob("*.tmp"))
        self.assertEqual([], leftovers)


class WatchdogTest(unittest.TestCase):
    """The watchdog is what tells a crash loop apart from a healthy daemon."""

    def test_healthy_running_daemon_raises_nothing(self):
        alert = evaluate(
            job=JobState(loaded=True, running=True, runs=12, pid=99),
            heartbeat=Heartbeat(status="running"),
            heartbeat_age=30.0,
            runs_delta=0,
        )
        self.assertIsNone(alert)

    def test_crash_loop_is_detected(self):
        alert = evaluate(
            job=JobState(loaded=True, running=True, runs=30, pid=99),
            heartbeat=Heartbeat(status="running"),
            heartbeat_age=5.0,
            runs_delta=7,
        )
        self.assertIsNotNone(alert)
        self.assertEqual("CRASH_LOOP", alert.kind)

    def test_a_few_restarts_are_not_a_crash_loop(self):
        alert = evaluate(
            job=JobState(loaded=True, running=True, runs=13, pid=99),
            heartbeat=Heartbeat(status="running"),
            heartbeat_age=5.0,
            runs_delta=2,
        )
        self.assertIsNone(alert)

    def test_missing_job_is_reported(self):
        alert = evaluate(
            job=JobState(loaded=False),
            heartbeat=None,
            heartbeat_age=None,
            runs_delta=0,
        )
        self.assertIsNotNone(alert)
        self.assertEqual("NOT_RUNNING", alert.kind)

    def test_failed_exit_is_reported(self):
        alert = evaluate(
            job=JobState(loaded=True, running=False, runs=3, last_exit_code=1),
            heartbeat=Heartbeat(status="running"),
            heartbeat_age=5.0,
            runs_delta=0,
        )
        self.assertIsNotNone(alert)
        self.assertEqual("EXITED", alert.kind)

    def test_clean_stop_is_not_reported(self):
        alert = evaluate(
            job=JobState(loaded=True, running=False, runs=3, last_exit_code=0),
            heartbeat=Heartbeat(status="stopped"),
            heartbeat_age=5.0,
            runs_delta=0,
        )
        self.assertIsNone(alert)

    def test_stalled_daemon_is_reported(self):
        alert = evaluate(
            job=JobState(loaded=True, running=True, runs=3, pid=99),
            heartbeat=Heartbeat(status="running"),
            heartbeat_age=99_999.0,
            runs_delta=0,
        )
        self.assertIsNotNone(alert)
        self.assertEqual("STALLED", alert.kind)

    def test_running_without_a_heartbeat_is_reported(self):
        alert = evaluate(
            job=JobState(loaded=True, running=True, runs=3, pid=99),
            heartbeat=None,
            heartbeat_age=None,
            runs_delta=0,
        )
        self.assertIsNotNone(alert)
        self.assertEqual("NO_HEARTBEAT", alert.kind)


class JobStateParserTest(unittest.TestCase):
    """`launchctl print` nests state; only the top level counts."""

    SAMPLE = """gui/501/com.building.crawler = {
	active count = 1
	path = /Users/mac/Library/LaunchAgents/com.building.crawler.plist
	type = LaunchAgent
	state = running

	stdout path = /tmp/out.log
	runs = 7
	pid = 4242
	minimum runtime = 30
	last exit code = 2
	properties = {
		some = value
		state = active
		state = active
	}
	job state = running
}
"""

    def test_parses_the_top_level_fields(self):
        state = parse_job_state(self.SAMPLE)
        self.assertTrue(state.loaded)
        self.assertTrue(state.running)
        self.assertEqual(7, state.runs)
        self.assertEqual(4242, state.pid)
        self.assertEqual(2, state.last_exit_code)

    def test_nested_state_lines_do_not_win(self):
        # The nested "state = active" lines must not overwrite the real one.
        state = parse_job_state(self.SAMPLE)
        self.assertTrue(state.running)

    def test_never_exited_is_not_a_code(self):
        sample = self.SAMPLE.replace("last exit code = 2", "last exit code = (never exited)")
        state = parse_job_state(sample)
        self.assertIsNone(state.last_exit_code)


class WatchdogRunTest(unittest.TestCase):
    """The end-to-end watchdog pass: baseline, detection and cooldown."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.data = Path(self._tmp.name) / "data"
        self.logs = Path(self._tmp.name) / "logs"
        self.data.mkdir(parents=True)

    def tearDown(self):
        self._tmp.cleanup()

    def _seed_heartbeat(self):
        """A fresh heartbeat, so NO_HEARTBEAT cannot mask what we test."""
        HeartbeatStore(self.data).write(Heartbeat(status="running", pid=1))

    def _run(self, job, notify=False):
        return run_watchdog(
            data_dir=self.data,
            log_dir=self.logs,
            notify=notify,
            job=job,
        )

    def test_first_pass_has_no_baseline_so_no_crash_loop(self):
        self._seed_heartbeat()
        # A job that has been up for days has a big run counter. The first
        # watchdog pass has nothing to compare it against, so it must not
        # call that a crash loop.
        alert = self._run(JobState(loaded=True, running=True, runs=151, pid=1))
        self.assertIsNone(alert)

    def test_a_second_pass_detects_the_growth(self):
        self._seed_heartbeat()
        self._run(JobState(loaded=True, running=True, runs=10, pid=1))
        alert = self._run(JobState(loaded=True, running=True, runs=22, pid=1))
        self.assertIsNotNone(alert)
        self.assertEqual("CRASH_LOOP", alert.kind)

    def test_a_quiet_job_is_never_a_crash_loop(self):
        self._seed_heartbeat()
        self._run(JobState(loaded=True, running=True, runs=10, pid=1))
        alert = self._run(JobState(loaded=True, running=True, runs=11, pid=1))
        self.assertIsNone(alert)

    def test_the_same_alert_is_not_repeated_inside_the_cooldown(self):
        self._seed_heartbeat()
        self._run(JobState(loaded=True, running=True, runs=10, pid=1))
        first = self._run(JobState(loaded=True, running=True, runs=22, pid=1))
        self.assertIsNotNone(first)
        self.assertFalse(first.suppressed)
        # The log must record the alert exactly once.
        alert_log = self.logs / "alerts.log"
        self.assertTrue(alert_log.is_file())
        self.assertEqual(1, alert_log.read_text(encoding="utf-8").count("CRASH_LOOP"))

    def test_a_suppressed_alert_is_still_reported_as_a_problem(self):
        self._seed_heartbeat()
        self._run(JobState(loaded=True, running=True, runs=10, pid=1))
        self._run(JobState(loaded=True, running=True, runs=22, pid=1))
        again = self._run(JobState(loaded=True, running=True, runs=30, pid=1))
        self.assertIsNotNone(again, "an active condition must not be reported as healthy")
        self.assertTrue(again.suppressed)

    def test_a_missing_job_is_reported_after_the_baseline(self):
        self._run(JobState(loaded=True, running=True, runs=1, pid=1))
        alert = self._run(JobState(loaded=False))
        self.assertIsNotNone(alert)
        self.assertEqual("NOT_RUNNING", alert.kind)

    def test_the_alert_off_switch_silences_everything(self):
        (self.data / "alerts-disabled").write_text("", encoding="utf-8")
        alert = self._run(JobState(loaded=False))
        self.assertIsNone(alert)
        self.assertFalse((self.logs / "alerts.log").exists())

    def test_an_intentional_stop_is_not_an_incident(self):
        self._run(JobState(loaded=True, running=True, runs=1, pid=1))
        (self.data / "stopped-intentionally").write_text("", encoding="utf-8")
        alert = self._run(JobState(loaded=False))
        self.assertIsNone(alert)


if __name__ == "__main__":
    unittest.main(verbosity=2)
