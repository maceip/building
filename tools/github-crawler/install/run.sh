#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Launchd wrapper for the GitHub crawler daemon.
#
# launchd gives a job a minimal PATH, so resolve the interpreter here
# instead of hard-coding a path that a Homebrew upgrade could invalidate.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
TOOL_DIR=$(dirname "$HERE")
REPO_DIR=$(dirname "$(dirname "$TOOL_DIR")")

export PYTHONUNBUFFERED=1
# gh is commonly installed in ~/.local/bin, which launchd's default PATH
# does not include.
export PATH="${HOME}/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:${PATH:-}"

PY=$(command -v python3 || true)
if [ -z "${PY}" ]; then
    echo "github-crawler: python3 was not found on PATH" >&2
    exit 78
fi

exec "${PY}" "${TOOL_DIR}/main.py" "$@"
