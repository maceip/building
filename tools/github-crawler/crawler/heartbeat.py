#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""The crawler's heartbeat file.

The daemon writes a small JSON document on every cycle. It answers the
question a process-liveness check cannot: *is the crawler actually doing
work*, or is it wedged (alive, but stuck in a loop that never finishes a
cycle)?

The watchdog reads it to tell three situations apart:

* **healthy** - the heartbeat is fresh and the job is running.
* **wedged** - the process is alive but the heartbeat has gone stale.
* **dead** - the job is not running at all.
"""
from __future__ import annotations

import json
import os
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path

STATE_FILE = "state.json"


def utc_now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


@dataclass
class Heartbeat:
    """What the daemon last reported about itself."""

    status: str = "starting"  # starting | running | stopped | error
    pid: int = 0
    started_at: str = ""
    updated_at: str = ""
    last_cycle_started_at: str = ""
    last_cycle_finished_at: str = ""
    cycles: int = 0
    consecutive_errors: int = 0
    last_error: str | None = None
    api_calls: int = 0
    repos: int = 0
    edges: int = 0

    def to_json(self) -> str:
        return json.dumps(asdict(self), indent=2, sort_keys=True)


class HeartbeatStore:
    """Reads and writes the heartbeat file, atomically."""

    def __init__(self, data_dir: Path) -> None:
        self.data_dir = Path(data_dir)
        self.path = self.data_dir / STATE_FILE

    def write(self, heartbeat: Heartbeat) -> None:
        self.data_dir.mkdir(parents=True, exist_ok=True)
        heartbeat.updated_at = utc_now()
        temp = self.path.with_suffix(".json.tmp")
        temp.write_text(heartbeat.to_json(), encoding="utf-8")
        # Replace atomically: the watchdog may read this at any moment, and a
        # half-written file would look like a corrupt heartbeat.
        os.replace(temp, self.path)

    def read(self) -> Heartbeat | None:
        try:
            raw = self.path.read_text(encoding="utf-8")
        except OSError:
            return None
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            return None
        if not isinstance(data, dict):
            return None
        known = {f for f in Heartbeat.__dataclass_fields__}
        return Heartbeat(**{k: v for k, v in data.items() if k in known})

    def age_seconds(self, now: float | None = None) -> float | None:
        """Seconds since the heartbeat was last written, or None if unknown."""
        heartbeat = self.read()
        if heartbeat is None or not heartbeat.updated_at:
            return None
        stamp = _parse_iso(heartbeat.updated_at)
        if stamp is None:
            return None
        current = now if now is not None else time.time()
        return max(0.0, current - stamp)

    def clear(self) -> None:
        try:
            if self.path.is_file():
                self.path.unlink()
        except OSError:
            pass


def _parse_iso(value: str) -> float | None:
    try:
        return time.mktime(time.strptime(value, "%Y-%m-%dT%H:%M:%SZ")) - time.timezone
    except (ValueError, OverflowError):
        return None
