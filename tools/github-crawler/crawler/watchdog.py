#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""The crawler watchdog: notice when the daemon stops doing its job.

launchd will happily restart a crashing daemon forever, every
``ThrottleInterval`` seconds, and say nothing. That is the worst failure
mode for a background service: it looks installed, it looks enabled, and
it is quietly doing nothing.

This watchdog runs as its own launchd agent and answers the question the
launch agent cannot:

* **CRASH_LOOP** - the job has been restarted several times in a short
  window. The daemon is crashing, and launchd is papering over it.
* **NOT_RUNNING** - the job is not loaded at all.
* **EXITED** - the job is loaded but stopped, with a failure exit code.
* **STALLED** - the process is alive but the heartbeat has gone stale, so
  the daemon is wedged rather than working.

Alerts go to the alert log and a macOS notification by default, and
optionally to a webhook (ntfy, Slack, Discord, ...) if one is configured.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from .heartbeat import Heartbeat, HeartbeatStore, utc_now

LABEL = "com.building.crawler"
DEFAULT_DATA_DIR = Path.home() / "Library" / "Application Support" / "building-crawler"
DEFAULT_LOG_DIR = Path.home() / "Library" / "Logs" / "building-crawler"

# A crash loop is this many restarts inside one watchdog interval.
CRASH_LOOP_THRESHOLD = 3
# The heartbeat is stale after this many seconds without a cycle.
STALE_AFTER_SECONDS = 5400  # 90 minutes; the cycle interval is 45 minutes.
# Do not repeat the same alert more often than this.
ALERT_COOLDOWN_SECONDS = 3600


@dataclass
class JobState:
    """What launchd says about the crawler job."""

    loaded: bool = False
    running: bool = False
    runs: int = 0
    last_exit_code: int | None = None
    pid: int = 0


@dataclass
class Alert:
    kind: str
    message: str
    detail: str = ""
    # True when this condition was already reported and is only being
    # repeated because it is still active. It is still a problem, so it is
    # still returned - just not re-sent.
    suppressed: bool = False


def read_job_state(label: str = LABEL) -> JobState:
    """Parse `launchctl print` for the crawler job."""
    domain = f"gui/{os.getuid()}"
    try:
        proc = subprocess.run(
            ["launchctl", "print", f"{domain}/{label}"],
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return JobState()

    if proc.returncode != 0:
        return JobState()

    return parse_job_state(proc.stdout)


def parse_job_state(output: str) -> JobState:
    """Parse the `launchctl print` output of a job."""
    state = JobState(loaded=True)
    for raw in output.splitlines():
        # `launchctl print` nests a lot of state: the top-level entries are
        # indented with one tab, and there are nested `state = active`
        # lines further in that would otherwise overwrite the real one.
        if not raw.startswith("\t") or raw.startswith("\t\t"):
            continue
        stripped = raw.strip()
        if stripped.startswith("runs ="):
            state.runs = _int(stripped.split("=", 1)[1])
        elif stripped.startswith("pid ="):
            state.pid = _int(stripped.split("=", 1)[1])
        elif stripped.startswith("state ="):
            value = stripped.split("=", 1)[1].strip()
            state.running = value == "running"
        elif stripped.startswith("last exit code ="):
            value = stripped.split("=", 1)[1].strip()
            if value.startswith("("):
                state.last_exit_code = None  # "(never exited)"
            else:
                state.last_exit_code = _int(value)
    return state


def _int(value: str) -> int:
    try:
        return int(value.strip())
    except (TypeError, ValueError):
        return 0


def evaluate(
    job: JobState,
    heartbeat: Heartbeat | None,
    heartbeat_age: float | None,
    runs_delta: int,
    stale_after: int = STALE_AFTER_SECONDS,
    log_dir: Path = DEFAULT_LOG_DIR,
) -> Alert | None:
    """Decide whether the crawler needs attention. Pure, so it is testable."""
    if not job.loaded:
        return Alert(
            "NOT_RUNNING",
            f"The GitHub crawler is not loaded into launchd ({LABEL}).",
            "Nothing is crawling. Reinstall it with tools/github-crawler/install/install.sh install",
        )

    if runs_delta >= CRASH_LOOP_THRESHOLD:
        return Alert(
            "CRASH_LOOP",
            f"The GitHub crawler has restarted {runs_delta} times in the last few minutes.",
            f"launchd is papering over a crash loop. The last error is in "
            f"{log_dir}/crawler.err.log",
        )

    if not job.running:
        if job.last_exit_code not in (None, 0):
            return Alert(
                "EXITED",
                f"The GitHub crawler stopped with exit code {job.last_exit_code}.",
                f"See {log_dir}/crawler.err.log",
            )
        return None

    if heartbeat is None:
        return Alert(
            "NO_HEARTBEAT",
            "The GitHub crawler is running but has never reported a heartbeat.",
            f"It may be wedged before its first cycle finished. See {log_dir}/crawler.err.log",
        )

    if heartbeat_age is not None and heartbeat_age > stale_after:
        return Alert(
            "STALLED",
            f"The GitHub crawler has not completed a cycle in {int(heartbeat_age // 60)} minutes.",
            f"It is alive but not working. See {log_dir}/crawler.err.log",
        )

    return None


class WatchdogState:
    """The watchdog's own bookkeeping: the last run count and last alert."""

    def __init__(self, path: Path) -> None:
        self.path = path

    def read(self) -> dict:
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            return data if isinstance(data, dict) else {}
        except (OSError, json.JSONDecodeError):
            return {}

    def write(self, data: dict) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temp = self.path.with_suffix(".json.tmp")
        temp.write_text(json.dumps(data, indent=2, sort_keys=True), encoding="utf-8")
        os.replace(temp, self.path)


class Notifier:
    """Where an alert goes: the log, a macOS notification, and a webhook."""

    def __init__(
        self,
        log_dir: Path = DEFAULT_LOG_DIR,
        webhook: str | None = None,
        notify: bool = True,
        quiet: bool = False,
    ) -> None:
        self.log_dir = Path(log_dir)
        self.webhook = webhook
        self.notify = notify
        self.quiet = quiet
        self.log_dir.mkdir(parents=True, exist_ok=True)

    def send(self, alert: Alert) -> None:
        self._write_log(alert)
        self._macos_notification(alert)
        self._webhook(alert)

    def _write_log(self, alert: Alert) -> None:
        path = self.log_dir / "alerts.log"
        with path.open("a", encoding="utf-8") as handle:
            handle.write(f"{utc_now()}  {alert.kind}: {alert.message}\n")
            if alert.detail:
                handle.write(f"{utc_now()}    {alert.detail}\n")

    def _macos_notification(self, alert: Alert) -> None:
        if not self.notify:
            return
        script = (
            f'display notification {json.dumps(alert.message)} '
            f'with title {json.dumps("GitHub Crawler")} '
            f'subtitle {json.dumps(alert.kind)}'
        )
        try:
            subprocess.run(
                ["/usr/bin/osascript", "-e", script],
                capture_output=True,
                text=True,
                timeout=20,
                check=False,
            )
        except (OSError, subprocess.SubprocessError):
            pass

    def _webhook(self, alert: Alert) -> None:
        if not self.webhook:
            return
        # "message" is what ntfy wants, "text" is what Slack and Discord want.
        payload = json.dumps(
            {
                "title": "GitHub Crawler",
                "message": alert.message,
                "text": alert.message,
                "kind": alert.kind,
            }
        ).encode("utf-8")
        request = urllib.request.Request(
            self.webhook,
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            urllib.request.urlopen(request, timeout=15).close()
        except (urllib.error.URLError, OSError, ValueError):
            pass


def alerts_disabled(data_dir: Path) -> bool:
    """The user's off switch: a file in the data directory."""
    return (Path(data_dir) / "alerts-disabled").exists()


def intentionally_stopped(data_dir: Path) -> bool:
    """Set when the operator stopped the service on purpose."""
    return (Path(data_dir) / "stopped-intentionally").exists()


def run_watchdog(
    data_dir: Path = DEFAULT_DATA_DIR,
    log_dir: Path = DEFAULT_LOG_DIR,
    webhook: str | None = None,
    notify: bool = True,
    dry_run: bool = False,
    label: str = LABEL,
    quiet: bool = False,
    job: JobState | None = None,
) -> Alert | None:
    """One watchdog pass. Returns the alert that was raised, if any."""
    data_dir = Path(data_dir)
    log_dir = Path(log_dir)

    if alerts_disabled(data_dir):
        return None

    store = HeartbeatStore(data_dir)
    heartbeat = store.read()
    age = store.age_seconds()

    if job is None:
        job = read_job_state(label)
    state_file = data_dir / "watchdog-state.json"
    watchdog_state = WatchdogState(state_file)
    previous = watchdog_state.read()

    # The run counter is cumulative since the job was loaded, so it only
    # means "crashing" when it grows BETWEEN two observations. On the very
    # first pass there is no baseline, and an old counter (a job that has
    # been up for weeks) must not be mistaken for a crash loop.
    has_baseline = "runs" in previous
    runs_delta = max(0, job.runs - int(previous.get("runs", 0))) if has_baseline else 0
    last_alert_at = float(previous.get("last_alert_at", 0.0))
    last_alert_kind = previous.get("last_alert_kind", "")
    now = time.time()

    alert = evaluate(job, heartbeat, age, runs_delta, log_dir=log_dir)

    # A deliberate stop is not an incident.
    if alert is not None and alert.kind in ("NOT_RUNNING", "EXITED") and intentionally_stopped(data_dir):
        alert = None

    # Do not repeat the same alert inside the cooldown. It is still an
    # active condition, though, so it must not be reported as healthy.
    suppressed = False
    if alert is not None:
        in_cooldown = (
            alert.kind == last_alert_kind
            and now - last_alert_at < ALERT_COOLDOWN_SECONDS
        )
        if in_cooldown:
            suppressed = True
            alert.suppressed = True

    if alert is not None and not dry_run and not alert.suppressed:
        Notifier(log_dir=log_dir, webhook=webhook, notify=notify).send(alert)

    watchdog_state.write(
        {
            "runs": job.runs,
            "last_alert_at": now if (alert is not None and not alert.suppressed) else last_alert_at,
            "last_alert_kind": alert.kind if (alert is not None and not alert.suppressed) else last_alert_kind,
            "last_check_at": now,
            "heartbeat_age": age,
        }
    )

    if alert is not None and not dry_run:
        print(f"{utc_now()}  ALERT {alert.kind}: {alert.message}", file=sys.stderr)
    elif not quiet:
        if suppressed:
            print(f"{utc_now()}  watchdog: {last_alert_kind} (already alerted, runs={job.runs}, +{runs_delta})")
        else:
            state = "healthy" if alert is None else alert.kind
            print(f"{utc_now()}  watchdog: {state} (runs={job.runs}, +{runs_delta})")

    return alert


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Watch the GitHub crawler daemon.")
    parser.add_argument("--data-dir", default=str(DEFAULT_DATA_DIR))
    parser.add_argument("--log-dir", default=str(DEFAULT_LOG_DIR))
    parser.add_argument("--webhook", default=os.environ.get("CRAWLER_ALERT_WEBHOOK"))
    parser.add_argument("--no-notify", action="store_true", help="skip the macOS notification")
    parser.add_argument("--dry-run", action="store_true", help="evaluate but do not alert")
    parser.add_argument(
        "--label",
        default=os.environ.get("CRAWLER_LABEL", LABEL),
        help="the launchd job to watch (default: %(default)s)",
    )
    args = parser.parse_args(argv)

    alert = run_watchdog(
        data_dir=Path(args.data_dir),
        log_dir=Path(args.log_dir),
        webhook=args.webhook,
        notify=not args.no_notify,
        dry_run=args.dry_run,
        label=args.label,
    )
    return 2 if alert is not None else 0


if __name__ == "__main__":
    sys.exit(main())
