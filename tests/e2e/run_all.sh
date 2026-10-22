#!/usr/bin/env bash
set -e

# ==============================================================================
# Dual Track E2E Test Suite Invocation Script
# Authoritative runner for all 4 Tiers of Compiler & Live GUI Execution Pipeline
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "================================================================================"
echo " Starting Dual Track E2E Test Suite Execution"
echo " Repo Root: $REPO_ROOT"
echo " Date: $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
echo "================================================================================"

cd "$REPO_ROOT"

# Run the master E2E test runner
python3 "$REPO_ROOT/tests/e2e/runner.py"

echo "================================================================================"
echo " Dual Track E2E Test Suite Completed Successfully!"
echo "================================================================================"
