#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Launchd wrapper for the crawler watchdog.
#
# The watchdog is a separate agent from the crawler itself, on purpose: if
# the crawler is crash-looping, the thing that notices must not be the thing
# that is broken.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
TOOL_DIR=$(dirname "$HERE")

export PYTHONUNBUFFERED=1
export PATH="${HOME}/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:${PATH:-}"

PY=$(command -v python3 || true)
if [ -z "${PY}" ]; then
    echo "github-crawler-watchdog: python3 was not found on PATH" >&2
    exit 78
fi

exec "${PY}" "${TOOL_DIR}/main.py" watchdog "$@"
