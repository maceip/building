#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Resolve the GitHub token used by the crawler.

The token is never stored in the repository and never logged. Resolution
order:

1. ``GH_TOKEN`` / ``GITHUB_TOKEN`` environment variable.
2. ``~/.config/building-crawler/token`` style file pointed at by
   ``CRAWLER_TOKEN_FILE`` (mode 0600).
3. The GitHub CLI's own credential store, via ``gh auth token``.

The third option is what makes the daemon use "my own GitHub account" with
no extra setup: it picks up whatever the ``gh`` CLI is already logged in as
and survives a token rotation without touching the service.
"""
from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path


class TokenError(RuntimeError):
    """Raised when no usable GitHub token could be located."""


def _from_env() -> tuple[str, str] | None:
    for name in ("GH_TOKEN", "GITHUB_TOKEN"):
        value = os.environ.get(name, "").strip()
        if value:
            return value, f"environment:{name}"
    return None


def _from_file(path: Path) -> tuple[str, str] | None:
    try:
        if not path.is_file():
            return None
        value = path.read_text(encoding="utf-8").strip()
        if value:
            return value, f"file:{path}"
    except OSError:
        return None
    return None


def _from_gh_cli() -> tuple[str, str] | None:
    binary = shutil.which("gh")
    if not binary:
        return None
    try:
        proc = subprocess.run(
            [binary, "auth", "token"],
            capture_output=True,
            text=True,
            timeout=15,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    value = (proc.stdout or "").strip()
    if proc.returncode == 0 and value:
        return value, "gh-cli"
    return None


def resolve_token(token_file: Path | None = None) -> tuple[str, str]:
    """Return ``(token, source_description)``.

    Raises [TokenError] when no token is available.
    """
    for resolver in (_from_env,):
        found = resolver()
        if found:
            return found

    if token_file is not None:
        found = _from_file(token_file)
        if found:
            return found

    found = _from_gh_cli()
    if found:
        return found

    raise TokenError(
        "No GitHub token found. Set GH_TOKEN, write one to "
        f"{token_file or 'the configured token file'} (mode 0600), or run `gh auth login`."
    )


def write_token_file(path: Path, token: str) -> None:
    """Persist a token to disk with restrictive permissions."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(token.strip() + "\n", encoding="utf-8")
    os.chmod(path, 0o600)


def describe_token(token: str) -> str:
    """Human-readable, non-secret description of a token."""
    if not token:
        return "none"
    prefix, _, rest = token.partition("_")
    if rest:
        return f"{prefix}_{'*' * 6} (len {len(token)})"
    return f"token (len {len(token)})"
