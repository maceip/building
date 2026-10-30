#!/usr/bin/env bash
# Publish a building (CodeAssist) benchmark/verification result to BigQuery
# via the cheap-stack ingest. Usage: publish-benchmark.sh <kind> '<detail-json>'
# Example: publish-benchmark.sh on-device-build '{"apkBytes":12345,"buildMs":6789,"device":"xiaomi"}'
# Only measured values supplied by the caller are sent.
set -euo pipefail
"$(dirname "$0")/cheap-stack-ingest.sh" building "${1:?kind required}" "${2:-{\}}"
