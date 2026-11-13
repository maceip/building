#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Configuration for the local GitHub / Trending background crawler.

Everything is overridable through environment variables prefixed with
``CRAWLER_`` so the launchd agent and an interactive shell can share one
implementation. The defaults are deliberately gentle: the daemon is meant to
run for months on a laptop without ever being the reason an account gets
rate limited.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, replace
from pathlib import Path

ENV_PREFIX = "CRAWLER_"

DEFAULT_DATA_DIR = Path.home() / "Library" / "Application Support" / "building-crawler"

# Languages whose trending pages we rotate through. ``all`` is GitHub's
# language-agnostic board and is the most valuable single feed.
DEFAULT_LANGUAGES = (
    "all",
    "kotlin",
    "swift",
    "python",
    "typescript",
    "javascript",
    "java",
    "rust",
    "c++",
)

# ``daily`` moves fastest, ``weekly`` is steadier, ``monthly`` barely moves.
DEFAULT_PERIODS = ("daily", "weekly")

# GitHub's own trending board accepts these ``since`` values.
SUPPORTED_PERIODS = ("daily", "weekly", "monthly")


def _env(name: str, default: str) -> str:
    return os.environ.get(ENV_PREFIX + name, default)


def _env_float(name: str, default: float) -> float:
    raw = os.environ.get(ENV_PREFIX + name)
    if raw is None or not raw.strip():
        return default
    try:
        return float(raw)
    except ValueError:
        return default


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(ENV_PREFIX + name)
    if raw is None or not raw.strip():
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def _env_bool(name: str, default: bool) -> bool:
    raw = os.environ.get(ENV_PREFIX + name)
    if raw is None or not raw.strip():
        return default
    return raw.strip().lower() in ("1", "true", "yes", "on")


@dataclass(frozen=True)
class Config:
    """Immutable runtime configuration."""

    data_dir: Path
    db_path: Path
    token_file: Path
    log_path: Path

    api_base: str = "https://api.github.com"
    web_base: str = "https://github.com"
    graphql_url: str = "https://api.github.com/graphql"

    # --- Pacing -----------------------------------------------------------
    # Minimum wall-clock gap between two outbound API calls. This is the
    # single knob that makes the crawler "slow" in the human sense: the
    # daemon still finishes a useful unit of work every few minutes, but it
    # never looks like a scraper from the outside.
    api_min_interval: float = 1.5
    # Sleep between two HTTP calls that are *not* API calls (trending HTML).
    web_min_interval: float = 8.0

    # --- Budgets ----------------------------------------------------------
    # Fraction of each GitHub bucket we allow ourselves. The authenticated
    # core/graphql buckets are 5000/hour; staying under 15% leaves the
    # account usable for everything else (the IDE app, CI, git pushes).
    core_budget_per_hour: int = 600
    graphql_budget_per_hour: int = 600
    search_budget_per_minute: int = 4
    search_budget_per_hour: int = 400
    # Hard ceiling for a single cycle, so one slow hour cannot spend a day.
    max_calls_per_cycle: int = 60

    # --- Work selection ---------------------------------------------------
    trending_interval: int = 2700  # seconds between trending sweeps (45 min)
    trending_languages: tuple[str, ...] = DEFAULT_LANGUAGES
    trending_periods: tuple[str, ...] = DEFAULT_PERIODS
    enrich_top_n: int = 5  # trending repos to enrich with REST metadata
    graph_expand_per_cycle: int = 2  # developers expanded per cycle
    graph_max_depth: int = 2  # follow-graph hops to follow
    peer_fetch_count: int = 6  # repos/stars pulled per developer
    per_page: int = 30

    # --- Graph scoring ----------------------------------------------------
    min_stars: int = 10  # mirrors DeveloperGraphEngine.filterAndRankPeerProjects
    exclude_forks: bool = True

    # --- Misc -------------------------------------------------------------
    user_agent: str = "building-crawler/1.0 (local background service; +https://github.com/maceip/building)"
    request_timeout: int = 30
    verbose: bool = False

    @classmethod
    def from_env(cls) -> "Config":
        data_dir = Path(_env("DATA_DIR", str(DEFAULT_DATA_DIR))).expanduser()
        languages = tuple(
            p.strip() for p in _env("LANGUAGES", ",".join(DEFAULT_LANGUAGES)).split(",") if p.strip()
        )
        periods = tuple(
            p.strip().lower()
            for p in _env("PERIODS", ",".join(DEFAULT_PERIODS)).split(",")
            if p.strip().lower() in SUPPORTED_PERIODS
        )
        return cls(
            data_dir=data_dir,
            db_path=Path(_env("DB_PATH", str(data_dir / "index.db"))).expanduser(),
            token_file=Path(_env("TOKEN_FILE", str(data_dir / "token"))).expanduser(),
            log_path=Path(_env("LOG_PATH", str(data_dir / "crawler.log"))).expanduser(),
            api_base=_env("API_BASE", "https://api.github.com").rstrip("/"),
            web_base=_env("WEB_BASE", "https://github.com").rstrip("/"),
            graphql_url=_env("GRAPHQL_URL", "https://api.github.com/graphql"),
            api_min_interval=_env_float("API_MIN_INTERVAL", 1.5),
            web_min_interval=_env_float("WEB_MIN_INTERVAL", 8.0),
            core_budget_per_hour=_env_int("CORE_BUDGET_PER_HOUR", 600),
            graphql_budget_per_hour=_env_int("GRAPHQL_BUDGET_PER_HOUR", 600),
            search_budget_per_minute=_env_int("SEARCH_BUDGET_PER_MINUTE", 4),
            search_budget_per_hour=_env_int("SEARCH_BUDGET_PER_HOUR", 400),
            max_calls_per_cycle=_env_int("MAX_CALLS_PER_CYCLE", 60),
            trending_interval=_env_int("TRENDING_INTERVAL", 2700),
            trending_languages=languages or DEFAULT_LANGUAGES,
            trending_periods=periods or DEFAULT_PERIODS,
            enrich_top_n=_env_int("ENRICH_TOP_N", 5),
            graph_expand_per_cycle=_env_int("GRAPHQL_EXPAND_PER_CYCLE", 2),
            graph_max_depth=_env_int("GRAPH_MAX_DEPTH", 2),
            peer_fetch_count=_env_int("PEER_FETCH_COUNT", 6),
            per_page=_env_int("PER_PAGE", 30),
            min_stars=_env_int("MIN_STARS", 10),
            exclude_forks=_env_bool("EXCLUDE_FORKS", True),
            verbose=_env_bool("VERBOSE", False),
        )

    def with_overrides(self, **kwargs) -> "Config":
        clean = {k: v for k, v in kwargs.items() if v is not None}
        return replace(self, **clean)

    def ensure_dirs(self) -> None:
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
