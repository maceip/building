#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Install (or reinstall) the GitHub crawler as a macOS launchd agent.
#
# The agent runs the daemon in the background, restarts it if it dies, and
# keeps it at low priority so it never competes with real work on the
# machine. The daemon itself paces every API call, so this is safe to leave
# running.
#
#   ./install/install.sh          install and start
#   ./install/install.sh --stop   stop and remove
set -eu

LABEL="com.building.crawler"
HERE=$(cd "$(dirname "$0")" && pwd)
TOOL_DIR=$(dirname "$HERE")
REPO_DIR=$(dirname "$(dirname "$TOOL_DIR")")

DATA_DIR="${CRAWLER_DATA_DIR:-$HOME/Library/Application Support/building-crawler}"
LOG_DIR="${CRAWLER_LOG_DIR:-$HOME/Library/Logs/building-crawler}"
PLIST="$HOME/Library/LaunchAgents/${LABEL}.plist"
TOKEN_FILE="${DATA_DIR}/token"

# gh is commonly installed outside the default PATH.
PATH="${HOME}/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:${PATH:-}"
export PATH

# Materialise the token at install time so the daemon does not depend on
# the gh CLI being present (or on an interactive session) later on.
materialise_token() {
    if [ -s "${TOKEN_FILE}" ]; then
        echo "token: keeping existing ${TOKEN_FILE}"
        return 0
    fi
    if command -v gh >/dev/null 2>&1; then
        if gh auth token > "${TOKEN_FILE}.tmp" 2>/dev/null && [ -s "${TOKEN_FILE}.tmp" ]; then
            chmod 600 "${TOKEN_FILE}.tmp"
            mv "${TOKEN_FILE}.tmp" "${TOKEN_FILE}"
            echo "token: written from gh auth token -> ${TOKEN_FILE} (mode 600)"
            return 0
        fi
        rm -f "${TOKEN_FILE}.tmp"
    fi
    echo "token: could not read from gh - write one to ${TOKEN_FILE} (mode 600)" >&2
    return 1
}

stop_agent() {
    if launchctl bootout "gui/$(id -u)/${LABEL}" 2>/dev/null; then
        echo "stopped ${LABEL}"
    else
        echo "${LABEL} was not running"
    fi
}

write_plist() {
    mkdir -p "$HOME/Library/LaunchAgents" "$LOG_DIR" "$DATA_DIR"
    cat > "$PLIST" <<PLIST_EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>${LABEL}</string>

    <key>ProgramArguments</key>
    <array>
        <string>/bin/sh</string>
        <string>${TOOL_DIR}/install/run.sh</string>
        <string>run</string>
    </array>

    <key>EnvironmentVariables</key>
    <dict>
        <key>PATH</key>
        <string>${HOME}/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>
        <key>CRAWLER_DATA_DIR</key>
        <string>${DATA_DIR}</string>
        <key>PYTHONUNBUFFERED</key>
        <string>1</string>
    </dict>

    <key>RunAtLoad</key>
    <true/>

    <!-- Restart only after an unclean exit; a clean SIGTERM stop stays stopped. -->
    <key>KeepAlive</key>
    <dict>
        <key>SuccessfulExit</key>
        <false/>
    </dict>
    <key>ThrottleInterval</key>
    <integer>30</integer>

    <!-- Run at low priority: this is a background indexer, not real work. -->
    <key>ProcessType</key>
    <string>Background</string>
    <key>Nice</key>
    <integer>5</integer>
    <key>LowPriorityIO</key>
    <true/>

    <key>StandardOutPath</key>
    <string>${LOG_DIR}/crawler.out.log</string>
    <key>StandardErrorPath</key>
    <string>${LOG_DIR}/crawler.err.log</string>
</dict>
</plist>
PLIST_EOF
    chmod 600 "$PLIST"
    echo "wrote ${PLIST}"
}

start_agent() {
    # bootout first so a reinstall replaces cleanly instead of erroring.
    launchctl bootout "gui/$(id -u)/${LABEL}" 2>/dev/null || true
    if launchctl bootstrap "gui/$(id -u)" "$PLIST" 2>/dev/null; then
        echo "started ${LABEL}"
    else
        echo "failed to start ${LABEL}; try: launchctl bootstrap gui/$(id -u) ${PLIST}" >&2
        return 1
    fi
}

case "${1:-install}" in
    --stop|stop)
        stop_agent
        rm -f "$PLIST"
        echo "removed ${PLIST}"
        ;;
    install|restart)
        write_plist
        materialise_token || true
        start_agent
        echo
        echo "data:  ${DATA_DIR}"
        echo "logs:  ${LOG_DIR}"
        echo "check: launchctl print gui/$(id -u)/${LABEL} | head -20"
        ;;
    *)
        echo "usage: $0 [install|--stop]" >&2
        exit 2
        ;;
esac
