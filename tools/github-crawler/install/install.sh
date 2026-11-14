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
WATCHDOG_LABEL="com.building.crawler.watchdog"
HERE=$(cd "$(dirname "$0")" && pwd)
TOOL_DIR=$(dirname "$HERE")
REPO_DIR=$(dirname "$(dirname "$TOOL_DIR")")

DATA_DIR="${CRAWLER_DATA_DIR:-$HOME/Library/Application Support/building-crawler}"
LOG_DIR="${CRAWLER_LOG_DIR:-$HOME/Library/Logs/building-crawler}"
PLIST="$HOME/Library/LaunchAgents/${LABEL}.plist"
WATCHDOG_PLIST="$HOME/Library/LaunchAgents/${WATCHDOG_LABEL}.plist"
MARKER_STOPPED="${DATA_DIR}/stopped-intentionally"
MARKER_DISABLED="${DATA_DIR}/alerts-disabled"
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
    if launchctl bootout "gui/$(id -u)/${WATCHDOG_LABEL}" 2>/dev/null; then
        echo "stopped ${WATCHDOG_LABEL}"
    else
        echo "${WATCHDOG_LABEL} was not running"
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

write_watchdog_plist() {
    mkdir -p "$HOME/Library/LaunchAgents" "$LOG_DIR" "$DATA_DIR"
    cat > "$WATCHDOG_PLIST" <<PLIST_EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>${WATCHDOG_LABEL}</string>

    <key>ProgramArguments</key>
    <array>
        <string>/bin/sh</string>
        <string>${TOOL_DIR}/install/watchdog.sh</string>
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

    <!-- Check every five minutes. -->
    <key>StartInterval</key>
    <integer>300</integer>
    <key>RunAtLoad</key>
    <true/>

    <key>ProcessType</key>
    <string>Background</string>
    <key>LowPriorityIO</key>
    <true/>

    <key>StandardOutPath</key>
    <string>${LOG_DIR}/watchdog.log</string>
    <key>StandardErrorPath</key>
    <string>${LOG_DIR}/watchdog.err.log</string>
</dict>
</plist>
PLIST_EOF
    chmod 600 "$WATCHDOG_PLIST"
    echo "wrote ${WATCHDOG_PLIST}"
}

start_watchdog() {
    launchctl bootout "gui/$(id -u)/${WATCHDOG_LABEL}" 2>/dev/null || true
    if launchctl bootstrap "gui/$(id -u)" "$WATCHDOG_PLIST" 2>/dev/null; then
        echo "started ${WATCHDOG_LABEL}"
    else
        echo "failed to start ${WATCHDOG_LABEL}; try: launchctl bootstrap gui/$(id -u) ${WATCHDOG_PLIST}" >&2
        return 1
    fi
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
        rm -f "$PLIST" "$WATCHDOG_PLIST"
        # Tell the watchdog this was deliberate, so it does not raise an
        # incident for a service the operator asked to stop.
        mkdir -p "$DATA_DIR"
        touch "$MARKER_STOPPED"
        echo "removed ${PLIST} and ${WATCHDOG_PLIST}"
        ;;
    install|restart)
        write_plist
        write_watchdog_plist
        materialise_token || true
        rm -f "$MARKER_STOPPED"
        start_agent
        start_watchdog
        echo
        echo "data:  ${DATA_DIR}"
        echo "logs:  ${LOG_DIR}"
        echo "alerts: ${LOG_DIR}/alerts.log (+ a macOS notification)"
        echo "mute:  touch '${MARKER_DISABLED}'"
        echo "check: launchctl print gui/$(id -u)/${LABEL} | head -20"
        ;;
    --mute)
        mkdir -p "$DATA_DIR"
        touch "$MARKER_DISABLED"
        echo "alerts muted (remove ${MARKER_DISABLED} to unmute)"
        ;;
    --unmute)
        rm -f "$MARKER_DISABLED"
        echo "alerts enabled"
        ;;
    *)
        echo "usage: $0 [install|--stop|--mute|--unmute]" >&2
        exit 2
        ;;
esac
