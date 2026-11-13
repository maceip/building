#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Entry point for the GitHub crawler daemon.

Usage:
    python3 tools/github-crawler/main.py <command> [options]

Commands: run | once | status | trending | history | graph | export | token | doctor
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from crawler.cli import main  # noqa: E402

if __name__ == "__main__":
    sys.exit(main())
