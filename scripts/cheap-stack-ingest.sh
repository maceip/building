#!/usr/bin/env bash
# Cheap-stack telemetry client (shell). Usage: ingest.sh <app> <kind> [detail-text]
# Config: CHEAP_STACK_URL, CHEAP_STACK_TOKEN or ~/gcp-cheap-stack/.ingest-token
set -euo pipefail
APP="${1:?app required}"; KIND="${2:?kind required}"; DETAIL="${3:-}"
URL="${CHEAP_STACK_URL:-https://cheap-stack-api-4mvkrnysdq-uc.a.run.app}"
TOKEN="${CHEAP_STACK_TOKEN:-$(cat "${CHEAP_STACK_TOKEN_FILE:-$HOME/gcp-cheap-stack/.ingest-token}")}"
python3 -c 'import json,sys; print(json.dumps({"kind":sys.argv[1],"detail":sys.argv[2]}))' "$KIND" "$DETAIL" > /tmp/cheap-ingest-body.json
# Fail-open by default (CHEAP_STACK_STRICT=1 restores hard failure for probes).
if curl -fsSL -m 15 -X POST "${URL%/}/api/events/${APP}" \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  --data-binary @/tmp/cheap-ingest-body.json; then
  echo
elif [ "${CHEAP_STACK_STRICT:-0}" = "1" ]; then
  exit 1
else
  echo "WARN: cheap-stack ingest unavailable, continuing without telemetry" >&2
fi
