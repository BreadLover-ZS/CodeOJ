#!/usr/bin/env bash
# Disposable complete local stack; no global database/runtime installation.
set -euo pipefail
task_repo="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 -B "$task_repo/scripts/local/start-stack.py" "$@"
