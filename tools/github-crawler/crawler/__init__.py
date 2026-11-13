#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Slow, rate-limit-safe background crawler for GitHub trending and the
developer/repository graph.

Extracted from the in-app crawler in
``app/ide-core/src/main/kotlin/dev/ide/core/discovery/`` (branch
``feat/unified-ios-crawler-llm-corner``) into a standalone daemon that runs
on macOS under launchd and builds a persistent local index and history.
"""
from __future__ import annotations

__version__ = "1.0.0"

__all__ = [
    "config",
    "daemon",
    "github",
    "graph",
    "ratelimit",
    "store",
    "tokens",
    "trending",
]
