#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Adaptive, budget-aware pacing for GitHub API traffic.

Three independent things keep the crawler polite:

1. **Pacing** - a minimum wall-clock gap between any two outbound calls.
2. **Budgets** - per-bucket hourly ceilings (core / graphql / search) that
   we enforce locally, *before* spending a request.
3. **Back-pressure from GitHub** - every response carries
   ``x-ratelimit-remaining`` and ``x-ratelimit-reset``; we mirror them and
   additionally stop entirely when the remaining quota drops under a
   reserve, so we never spend the last few requests of an hour.

A local counter is necessary in addition to the header mirror because the
headers only appear after a call has already been paid for.
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Callable

# GitHub buckets we care about. ``core`` covers most REST endpoints,
# ``graphql`` the GraphQL API, ``search`` the search endpoints (which have
# their own much smaller per-minute quota).
BUCKETS = ("core", "graphql", "search")

# Stop making API calls once this fraction of the bucket is consumed.
RESERVE_FRACTION = 0.15


class BudgetExhausted(RuntimeError):
    """Raised when the local budget for a bucket is spent for this window."""


@dataclass
class BucketState:
    """Local mirror of one GitHub rate-limit bucket."""

    name: str
    limit: int = 0
    remaining: int = 0
    reset_at: float = 0.0
    window_seconds: float = 3600.0
    window_started: float = field(default_factory=time.monotonic)
    window_calls: int = 0
    budget: int = 0

    def roll_window_if_needed(self, now: float) -> None:
        if now - self.window_started >= self.window_seconds:
            self.window_started = now
            self.window_calls = 0

    def seconds_until_reset(self, now: float) -> float:
        if self.reset_at <= 0:
            return 0.0
        # ``reset_at`` is a Unix epoch timestamp from GitHub, so it must be
        # compared against wall-clock time, not the monotonic clock.
        return max(0.0, self.reset_at - time.time())

    def local_remaining(self) -> int:
        return max(0, self.budget - self.window_calls)


class RateLimiter:
    """Paces and budgets outbound GitHub traffic."""

    def __init__(
        self,
        config,
        sleeper: Callable[[float], None] = time.sleep,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._config = config
        self._sleep = sleeper
        self._clock = clock
        self._last_call_at = 0.0
        self._blocked_until = 0.0
        self._buckets = {
            "core": BucketState("core", budget=config.core_budget_per_hour, window_seconds=3600.0),
            "graphql": BucketState("graphql", budget=config.graphql_budget_per_hour, window_seconds=3600.0),
            "search": BucketState("search", budget=config.search_budget_per_minute, window_seconds=60.0),
            "search_hour": BucketState("search_hour", budget=config.search_budget_per_hour, window_seconds=3600.0),
        }
        self.total_api_calls = 0
        self.total_web_calls = 0
        self.rate_limited_hits = 0
        self.backoff_until = 0.0

    # ------------------------------------------------------------------
    # Introspection
    # ------------------------------------------------------------------
    def snapshot(self) -> dict:
        now = self._clock()
        out = {}
        for name, bucket in self._buckets.items():
            bucket.roll_window_if_needed(now)
            out[name] = {
                "budget": bucket.budget,
                "used_in_window": bucket.window_calls,
                "local_remaining": bucket.local_remaining(),
                "remote_limit": bucket.limit,
                "remote_remaining": bucket.remaining,
                "reset_in_s": round(bucket.seconds_until_reset(now), 1),
            }
        out["total_api_calls"] = self.total_api_calls
        out["total_web_calls"] = self.total_web_calls
        out["rate_limited_hits"] = self.rate_limited_hits
        return out

    # ------------------------------------------------------------------
    # Budget accounting
    # ------------------------------------------------------------------
    def _budget_for(self, resource: str) -> BucketState:
        # Everything REST that is not search counts against ``core``.
        if resource in ("core", "graphql", "search"):
            return self._buckets[resource]
        return self._buckets["core"]

    def _budget_ceiling(self, resource: str) -> int:
        if resource == "search":
            return self._config.search_budget_per_minute
        if resource == "graphql":
            return self._config.graphql_budget_per_hour
        return self._config.core_budget_per_hour

    def can_spend(self, resource: str) -> bool:
        now = self._clock()
        bucket = self._budget_for(resource)
        bucket.roll_window_if_needed(now)
        ceiling = self._budget_ceiling(resource)
        if bucket.window_calls >= ceiling:
            return False
        if resource == "search":
            hour_bucket = self._buckets["search_hour"]
            hour_bucket.roll_window_if_needed(now)
            if hour_bucket.window_calls >= hour_bucket.budget:
                return False
        if bucket.limit and bucket.remaining <= max(1, int(bucket.limit * RESERVE_FRACTION)):
            # GitHub tells us the bucket is nearly empty - stop before the
            # reserve is gone rather than after.
            return False
        return True

    # ------------------------------------------------------------------
    # Pacing
    # ------------------------------------------------------------------
    def wait_for_slot(self, resource: str = "core", web: bool = False) -> None:
        """Block until it is acceptable to make the next call."""
        now = self._clock()
        interval = self._config.web_min_interval if web else self._config.api_min_interval

        if now < self.backoff_until:
            self._sleep(self.backoff_until - now)
            now = self._clock()
        if now < self._blocked_until:
            self._sleep(self._blocked_until - now)
            now = self._clock()

        elapsed = now - self._last_call_at
        if elapsed < interval:
            self._sleep(interval - elapsed)
            now = self._clock()

        self._last_call_at = self._clock()

    # ------------------------------------------------------------------
    # Accounting after a call
    # ------------------------------------------------------------------
    def record_api_call(self, resource: str) -> None:
        now = self._clock()
        bucket = self._budget_for(resource)
        bucket.roll_window_if_needed(now)
        bucket.window_calls += 1
        if resource == "search":
            hour_bucket = self._buckets["search_hour"]
            hour_bucket.roll_window_if_needed(now)
            hour_bucket.window_calls += 1
        self.total_api_calls += 1

    def record_web_call(self) -> None:
        self.total_web_calls += 1

    def observe_headers(self, headers) -> None:
        """Mirror GitHub's rate-limit headers into our local state."""
        resource = _header(headers, "x-ratelimit-resource")
        if not resource:
            return
        bucket = self._budget_for(resource)
        try:
            bucket.limit = int(_header(headers, "x-ratelimit-limit") or 0)
            bucket.remaining = int(_header(headers, "x-ratelimit-remaining") or 0)
            reset = int(_header(headers, "x-ratelimit-reset") or 0)
        except ValueError:
            return
        if reset:
            bucket.reset_at = reset

    def note_rate_limited(self, headers, retry_after: float | None = None) -> float:
        """Record a 403/429 and compute how long to back off. Returns seconds."""
        self.rate_limited_hits += 1
        now = self._clock()
        remaining = _header(headers, "x-ratelimit-remaining")
        reset = _header(headers, "x-ratelimit-reset")

        wait = 0.0
        if retry_after and retry_after > 0:
            wait = retry_after
        elif remaining is not None and remaining.strip() == "0" and reset:
            try:
                wait = max(0.0, float(int(reset)) - time.time())
            except ValueError:
                wait = 0.0
        if wait <= 0:
            # Secondary rate limit / abuse detection: back off generously.
            wait = 60.0

        wait = min(max(wait, 1.0), 3600.0)
        self.backoff_until = now + wait
        self._blocked_until = now + wait
        return wait

    def note_transient_failure(self, attempt: int) -> float:
        """Exponential backoff with jitter for 5xx / network errors."""
        base = min(2.0 ** max(0, attempt), 300.0)
        wait = base * (0.5 + 0.5 * ((attempt * 2654435761) % 1000) / 1000.0)
        self.backoff_until = self._clock() + wait
        return wait


def _header(headers, name: str) -> str | None:
    if headers is None:
        return None
    try:
        return headers.get(name)
    except AttributeError:
        return None
