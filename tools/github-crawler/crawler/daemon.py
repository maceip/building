#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""The slow background crawler daemon.

One cycle consists of three phases:

1. **Trending sweep** - one GitHub trending board (``/trending/<lang>?since=<period>``),
   rotating through the configured languages and periods. Costs no API quota.
2. **Graph expansion** - pull the next unexpanded developer from the local
   frontier and traverse their follow graph (2 hops). This is where the
   graph actually grows, and it is rate limited by the [RateLimiter] and by
   the hourly budgets.
3. **Relation linking** - derive repo-to-repo similarity edges purely from
   the local index. Costs nothing.

The loop is deliberately slow. Between any two API calls the daemon waits
``api_min_interval`` seconds, and the total number of API calls per hour is
capped well below GitHub's quota.
"""
from __future__ import annotations

import logging
import logging.handlers
import random
import signal
import sys
import time

from .graph import GraphCrawler
from .trending import normalise_language_key, parse_trending_html, trending_url


def build_logger(config) -> logging.Logger:
    logger = logging.getLogger("crawler")
    logger.setLevel(logging.DEBUG if config.verbose else logging.INFO)
    logger.propagate = False
    fmt = logging.Formatter("%(asctime)s %(levelname)-7s %(message)s", "%Y-%m-%d %H:%M:%S")

    if config.log_path:
        config.log_path.parent.mkdir(parents=True, exist_ok=True)
        handler = logging.handlers.RotatingFileHandler(
            str(config.log_path), maxBytes=2_000_000, backupCount=5
        )
        handler.setFormatter(fmt)
        logger.addHandler(handler)

    stream = logging.StreamHandler(sys.stderr)
    stream.setFormatter(fmt)
    logger.addHandler(stream)
    return logger


class CrawlerDaemon:
    """Owns the crawl loop."""

    def __init__(self, config, client, store, graph: GraphCrawler, logger=None) -> None:
        self.config = config
        self.client = client
        self.store = store
        self.graph = graph
        self.log = logger or logging.getLogger("crawler")
        self._stop = False
        self._rotation = self._build_rotation()
        self._rotation_index = 0
        self._last_sweep = 0.0
        self._last_link = 0.0

    # ------------------------------------------------------------------
    def _build_rotation(self) -> list[tuple[str, str]]:
        combos = []
        for period in self.config.trending_periods:
            for language in self.config.trending_languages:
                combos.append((normalise_language_key(language), period))
        return combos or [("all", "daily")]

    def _next_combo(self) -> tuple[str, str]:
        combo = self._rotation[self._rotation_index % len(self._rotation)]
        self._rotation_index += 1
        return combo

    # ------------------------------------------------------------------
    def sweep_trending(self) -> int:
        """One trending board fetch plus enrichment of its top entries."""
        language, period = self._next_combo()
        url = trending_url(self.config.web_base, language, period)
        self.log.info("trending sweep: %s (%s)", language, period)

        html = self.client.fetch_trending_html(language, period)
        if not html:
            self.log.warning("trending fetch failed for %s/%s", language, period)
            self.store.record_trending_run(language, period, 0, "failed", "empty response")
            return 0

        records = parse_trending_html(html, period=period, default_language=language)
        self.log.info("trending %s/%s -> %d repos", language, period, len(records))
        self.store.record_trending_run(language, period, len(records), "ok")

        count = 0
        for record in records[: max(1, self.config.enrich_top_n)]:
            if not self.client.limiter.can_spend("core"):
                self.log.info("core budget reached, deferring enrichment")
                break
            record["trending_language"] = language
            try:
                self.graph.expand_trending_repo(record, depth=1)
                count += 1
            except Exception as exc:  # one bad repo must not kill the cycle
                self.log.warning("enrich failed for %s: %s", record.get("slug"), exc, exc_info=True)
        self._last_sweep = time.time()
        return count

    # ------------------------------------------------------------------
    def expand_graph(self, max_calls: int | None = None) -> int:
        """Expand developers from the frontier until the budget runs out."""
        budget = max_calls if max_calls is not None else self.config.max_calls_per_cycle
        calls_before = self.client.limiter.total_api_calls
        expanded = 0

        while (self.client.limiter.total_api_calls - calls_before) < budget:
            if not self.client.limiter.can_spend("graphql") or not self.client.limiter.can_spend("core"):
                self.log.info("budget reached, stopping graph expansion")
                break
            frontier = self.store.unexpanded_developers(limit=1)
            if not frontier:
                self.log.info("graph frontier is empty")
                break
            login = frontier[0]["login"]
            try:
                self.graph.expand_developer(login, depth=self.config.graph_max_depth)
                expanded += 1
            except Exception as exc:
                self.log.warning("expansion failed for %s: %s", login, exc, exc_info=True)
                # Avoid hammering a developer that always fails.
                self.store.mark_developer_expanded(login)
                break
        return expanded

    # ------------------------------------------------------------------
    def link_related(self) -> int:
        added = self.graph.compute_related_edges()
        if added:
            self.log.info("linked %d related repo pairs", added)
        self._last_link = time.time()
        return added

    # ------------------------------------------------------------------
    def run_cycle(self) -> dict:
        run_id = self.store.start_run("cycle")
        started = time.time()
        stats = {"trending": 0, "expanded": 0, "related": 0, "api_calls": 0, "web_calls": 0}

        api_before = self.client.limiter.total_api_calls
        web_before = self.client.limiter.total_web_calls

        try:
            stats["trending"] = self.sweep_trending()
            stats["expanded"] = self.expand_graph()
            stats["related"] = self.link_related()
            stats["api_calls"] = self.client.limiter.total_api_calls - api_before
            stats["web_calls"] = self.client.limiter.total_web_calls - web_before
            self.store.finish_run(
                run_id,
                "ok",
                api_calls=stats["api_calls"],
                web_calls=stats["web_calls"],
                repos_seen=stats["trending"],
                edges_added=stats["related"],
                detail=f"expanded={stats['expanded']} elapsed={int(time.time() - started)}s",
            )
        except Exception as exc:
            self.log.exception("cycle failed")
            self.store.finish_run(run_id, "failed", detail=str(exc))
            raise
        return stats

    # ------------------------------------------------------------------
    def run_forever(self) -> None:
        self.log.info(
            "crawler daemon starting: data=%s interval=%ss languages=%s periods=%s",
            self.config.data_dir,
            self.config.trending_interval,
            ",".join(self.config.trending_languages),
            ",".join(self.config.trending_periods),
        )
        self._install_signals()
        while not self._stop:
            try:
                stats = self.run_cycle()
                self.log.info(
                    "cycle done: trending=%d expanded=%d related=%d api=%d web=%d",
                    stats["trending"], stats["expanded"], stats["related"],
                    stats["api_calls"], stats["web_calls"],
                )
            except Exception:
                # Never let one bad cycle kill the daemon.
                time.sleep(60)
                continue

            if self._stop:
                break
            # Sleep in small slices so a stop signal is honoured promptly.
            # A little jitter keeps restarts from lining up with GitHub's
            # quota reset window.
            deadline = time.time() + jitter(self.config.trending_interval)
            while not self._stop and time.time() < deadline:
                time.sleep(min(5.0, max(0.0, deadline - time.time())))
        self.log.info("crawler daemon stopped")

    def stop(self, *_args) -> None:
        self._stop = True

    def _install_signals(self) -> None:
        for sig in (signal.SIGTERM, signal.SIGINT):
            try:
                signal.signal(sig, self.stop)
            except (ValueError, OSError):
                pass


def jitter(seconds: float, spread: float = 0.2) -> float:
    """Add a little jitter so restarts do not align perfectly."""
    return seconds * (1.0 + random.uniform(-spread, spread))
