#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Command line interface for the local GitHub crawler.

Subcommands
-----------
``run``       run the daemon in the foreground (this is what launchd starts)
``once``      run exactly one crawl cycle and print a summary (proof of life)
``status``    show index size, graph size and the live rate-limit budget
``trending``  fetch one trending board on demand and show what was parsed
``history``   show the recorded star history for a repository
``graph``     show the neighbourhood of a node
``export``    dump the index as JSON
``token``     show which token source is in use (never the token itself)
``doctor``    verify token, database and network connectivity
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from .config import Config
from .daemon import CrawlerDaemon, build_logger
from .heartbeat import Heartbeat, HeartbeatStore, utc_now
from .github import GitHubClient
from .graph import GraphCrawler
from .ratelimit import RateLimiter
from .store import CrawlerStore
from .tokens import TokenError, describe_token, resolve_token
from .watchdog import (  # noqa: E402
    DEFAULT_LOG_DIR,
    Alert,
    Notifier,
    read_job_state,
    run_watchdog,
)


def _record_fatal(config: Config, message: str) -> None:
    """Report a fatal startup failure immediately.

    The watchdog would notice the crash loop a few minutes later. This says
    so at once: the heartbeat records the error and a notification goes out
    with the reason, so the user does not have to go looking for it.
    """
    try:
        store = HeartbeatStore(config.data_dir)
        store.write(
            Heartbeat(
                status="error",
                pid=os.getpid(),
                started_at=utc_now(),
                last_error=message,
            )
        )
    except Exception:
        pass
    try:
        Notifier(log_dir=DEFAULT_LOG_DIR).send(
            Alert(kind="FATAL", message=f"The GitHub crawler failed to start: {message}")
        )
    except Exception:
        pass


def _build_stack(config: Config, logger=None):
    token, source = resolve_token(config.token_file)
    store = CrawlerStore(config.db_path)
    limiter = RateLimiter(config)
    client = GitHubClient(token, config, limiter, store, logger=logger)
    graph = GraphCrawler(client, store, config, logger=logger)
    return token, source, store, limiter, client, graph


def _print(obj) -> None:
    print(json.dumps(obj, indent=2, sort_keys=True, default=str))


# ----------------------------------------------------------------------
def cmd_run(args, config: Config) -> int:
    logger = build_logger(config)
    token, source, store, limiter, client, graph = _build_stack(config, logger)
    logger.info("using token from %s (%s)", source, describe_token(token))
    daemon = CrawlerDaemon(config, client, store, graph, logger)
    try:
        daemon.run_forever()
    finally:
        store.close()
    return 0


def cmd_once(args, config: Config) -> int:
    logger = build_logger(config)
    token, source, store, limiter, client, graph = _build_stack(config, logger)
    logger.info("using token from %s (%s)", source, describe_token(token))
    daemon = CrawlerDaemon(config, client, store, graph, logger)
    try:
        stats = daemon.run_cycle()
        stats["rate_limit"] = limiter.snapshot()
        stats["graph"] = store.graph_stats()
        _print(stats)
    finally:
        store.close()
    return 0


def cmd_status(args, config: Config) -> int:
    if not config.db_path.exists():
        _print({"error": f"no index at {config.db_path}", "hint": "run `once` first"})
        return 1
    store = CrawlerStore(config.db_path)
    try:
        limiter = RateLimiter(config)
        payload = {
            "index": str(config.db_path),
            "graph": store.graph_stats(),
            "edges_by_relation": {r["relation"]: r["n"] for r in store.edge_breakdown()},
            "api_calls_total": store.api_call_count(),
            "api_calls_last_hour": store.api_call_count(60),
            "configured_budget": limiter.snapshot(),
            "github_quota_last_seen": store.last_known_quota(),
            "last_runs": [
                {
                    "id": r["id"],
                    "started_at": r["started_at"],
                    "status": r["status"],
                    "api_calls": r["api_calls"],
                    "web_calls": r["web_calls"],
                    "detail": r["detail"],
                }
                for r in store.recent_runs(limit=5)
            ],
            "daemon": daemon_health(config),
            "recent_trending": [
                {
                    "slug": r["slug"],
                    "stars": r["stars"],
                    "stars_in_period": r["stars_in_period"],
                    "period": r["period"],
                    "rank": r["trending_rank"],
                }
                for r in store.trending_repos(limit=10)
            ],
        }
        _print(payload)
    finally:
        store.close()
    return 0


def cmd_trending(args, config: Config) -> int:
    config = config.with_overrides(verbose=True)
    token, source, store, limiter, client, graph = _build_stack(config)
    try:
        html = client.fetch_trending_html(args.language, args.period)
        if not html:
            print("fetch failed", file=sys.stderr)
            return 1
        from .trending import parse_trending_html

        records = parse_trending_html(html, period=args.period, default_language=args.language)
        if args.json:
            _print(records)
        else:
            print(f"{len(records)} repos from trending/{args.language}?since={args.period}")
            for r in records:
                print(
                    f"  {r['trending_rank']:>2}. {r['owner']}/{r['name']:<34} "
                    f"{r['stars']:>7} stars  +{r['stars_in_period']:<5} {r['language']}"
                )
        return 0
    finally:
        store.close()


def cmd_history(args, config: Config) -> int:
    store = CrawlerStore(config.db_path)
    try:
        rows = store.snapshots_for(args.slug, args.limit)
        if not rows:
            print(f"no history for {args.slug}")
            return 1
        print(f"{args.slug}: {len(rows)} observations")
        for r in rows:
            print(
                f"  {r['observed_at']}  stars={r['stars']:<7} forks={r['forks']:<6} "
                f"+{r['stars_in_period'] or 0:<5} {r['period'] or '':<7} rank={r['trending_rank'] or '-'}"
            )
        return 0
    finally:
        store.close()


def cmd_graph(args, config: Config) -> int:
    store = CrawlerStore(config.db_path)
    try:
        node_type = "developer" if "/" not in args.node else "repo"
        rows = store.neighbours(node_type, args.node)
        print(f"{node_type} {args.node}: {len(rows)} edges")
        for r in rows[: args.limit]:
            direction = "->" if r["src_id"] == args.node.lower() else "<-"
            other = r["dst_id"] if direction == "->" else r["src_id"]
            print(f"  {direction} {r['relation']:<16} {other:<45} rel={r['relevance']} {r['reason'] or ''}")
        return 0
    finally:
        store.close()


def cmd_export(args, config: Config) -> int:
    store = CrawlerStore(config.db_path)
    try:
        repos = [dict(r) for r in store.export_repos()]
        edges = [dict(r) for r in store.export_edges()]
        payload = {
            "generated_at": store.get_meta("last_export") or None,
            "counts": store.graph_stats(),
            "repos": repos,
            "edges": edges,
        }
        if args.output:
            Path(args.output).write_text(json.dumps(payload, indent=2, default=str), encoding="utf-8")
            print(f"wrote {args.output} ({len(repos)} repos, {len(edges)} edges)")
        else:
            _print(payload)
        return 0
    finally:
        store.close()


def cmd_token(args, config: Config) -> int:
    try:
        token, source = resolve_token(config.token_file)
    except TokenError as exc:
        _print({"ok": False, "error": str(exc)})
        return 1
    _print({"ok": True, "source": source, "token": describe_token(token)})
    return 0


def cmd_doctor(args, config: Config) -> int:
    report = {"config": {"data_dir": str(config.data_dir), "db": str(config.db_path)}}
    try:
        token, source = resolve_token(config.token_file)
    except TokenError as exc:
        report["token"] = {"ok": False, "error": str(exc)}
        _print(report)
        return 1
    report["token"] = {"ok": True, "source": source, "token": describe_token(token)}

    store = CrawlerStore(config.db_path)
    limiter = RateLimiter(config)
    client = GitHubClient(token, config, limiter, store)
    try:
        user = client.api_get_json("user")
        report["api"] = {
            "ok": bool(user),
            "login": (user or {}).get("login"),
            "rate_limit": limiter.snapshot(),
        }
        html = client.fetch_trending_html("all", "daily")
        report["trending"] = {"ok": bool(html), "bytes": len(html or "")}
        report["index"] = store.graph_stats()
    finally:
        store.close()
    _print(report)
    return 0


def cmd_watchdog(args, config: Config) -> int:
    """Run one watchdog pass: detect a crash loop, a dead job or a stall."""
    alert = run_watchdog(
        data_dir=config.data_dir,
        log_dir=Path(args.log_dir) if args.log_dir else DEFAULT_LOG_DIR,
        webhook=args.webhook,
        notify=not args.no_notify,
        dry_run=args.dry_run,
        label=args.label,
    )
    if alert is None:
        print("watchdog: healthy")
        return 0
    suffix = " (already alerted, still active)" if alert.suppressed else ""
    print(f"watchdog: {alert.kind}{suffix} - {alert.message}")
    if alert.detail:
        print(f"  {alert.detail}")
    return 2


def cmd_health(args, config: Config) -> int:
    """Show whether the daemon is alive AND working."""
    _print(daemon_health(config))
    return 0


def daemon_health(config: Config) -> dict:
    """The daemon's own view of its health, for `status` and `health`."""
    store = HeartbeatStore(config.data_dir)
    heartbeat = store.read()
    age = store.age_seconds()
    job = read_job_state()
    return {
        "job_loaded": job.loaded,
        "job_running": job.running,
        "job_runs": job.runs,
        "last_exit_code": job.last_exit_code,
        "pid": job.pid,
        "heartbeat_status": heartbeat.status if heartbeat else None,
        "heartbeat_age_seconds": round(age, 1) if age is not None else None,
        "cycles": heartbeat.cycles if heartbeat else None,
        "last_cycle_finished_at": heartbeat.last_cycle_finished_at if heartbeat else None,
        "consecutive_errors": heartbeat.consecutive_errors if heartbeat else None,
        "last_error": heartbeat.last_error if heartbeat else None,
    }


# ----------------------------------------------------------------------
def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="github-crawler",
        description="Slow background crawler that indexes GitHub trending and the developer graph.",
    )
    parser.add_argument("--data-dir", help="override the crawler data directory")
    parser.add_argument("--db", help="override the SQLite index path")
    parser.add_argument("--verbose", action="store_true", help="verbose logging")
    sub = parser.add_subparsers(dest="command", required=True)

    p_run = sub.add_parser("run", help="run the daemon in the foreground")
    p_run.set_defaults(func=cmd_run)

    p_once = sub.add_parser("once", help="run one crawl cycle and exit")
    p_once.set_defaults(func=cmd_once)

    p_status = sub.add_parser("status", help="show index and budget status")
    p_status.set_defaults(func=cmd_status)

    p_trend = sub.add_parser("trending", help="fetch one trending board on demand")
    p_trend.add_argument("language", nargs="?", default="all")
    p_trend.add_argument("period", nargs="?", default="daily")
    p_trend.add_argument("--json", action="store_true")
    p_trend.set_defaults(func=cmd_trending)

    p_hist = sub.add_parser("history", help="show recorded history for a repository")
    p_hist.add_argument("slug", help="owner/name")
    p_hist.add_argument("--limit", type=int, default=50)
    p_hist.set_defaults(func=cmd_history)

    p_graph = sub.add_parser("graph", help="show the neighbourhood of a node")
    p_graph.add_argument("node", help="a login or owner/name")
    p_graph.add_argument("--limit", type=int, default=40)
    p_graph.set_defaults(func=cmd_graph)

    p_export = sub.add_parser("export", help="export the index as JSON")
    p_export.add_argument("--output", help="write to a file instead of stdout")
    p_export.set_defaults(func=cmd_export)

    p_token = sub.add_parser("token", help="show the active token source")
    p_token.set_defaults(func=cmd_token)

    p_doctor = sub.add_parser("doctor", help="verify token, API and trending access")
    p_doctor.set_defaults(func=cmd_doctor)

    p_health = sub.add_parser("health", help="show whether the daemon is alive and working")
    p_health.set_defaults(func=cmd_health)

    p_watch = sub.add_parser("watchdog", help="run one watchdog pass (detect a crash loop)")
    p_watch.add_argument("--webhook", help="POST the alert to this URL as well")
    p_watch.add_argument("--log-dir", help="where to write alerts.log")
    p_watch.add_argument("--no-notify", action="store_true", help="skip the macOS notification")
    p_watch.add_argument("--dry-run", action="store_true", help="evaluate but do not alert")
    p_watch.add_argument(
        "--label",
        default=os.environ.get("CRAWLER_LABEL", "com.building.crawler"),
        help="the launchd job to watch (default: %(default)s)",
    )
    p_watch.set_defaults(func=cmd_watchdog)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    config = Config.from_env()
    overrides = {}
    if args.data_dir:
        overrides["data_dir"] = Path(args.data_dir).expanduser()
        overrides["db_path"] = Path(args.data_dir).expanduser() / "index.db"
        overrides["token_file"] = Path(args.data_dir).expanduser() / "token"
        overrides["log_path"] = Path(args.data_dir).expanduser() / "crawler.log"
    if args.db:
        overrides["db_path"] = Path(args.db).expanduser()
    if args.verbose:
        overrides["verbose"] = True
    config = config.with_overrides(**overrides)
    config.ensure_dirs()

    try:
        return args.func(args, config)
    except TokenError as exc:
        _record_fatal(config, str(exc))
        print(f"error: {exc}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        return 130
    except Exception as exc:
        _record_fatal(config, f"{type(exc).__name__}: {exc}")
        raise


if __name__ == "__main__":
    sys.exit(main())
