#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=========================================================="
echo "  🚀 Launching RemoteViber Desktop Client (Linux GUI)"
echo "=========================================================="

python3 "$ROOT_DIR/viber-client/desktop/runner.py"
