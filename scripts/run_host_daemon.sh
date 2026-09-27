#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=========================================================="
echo "  ⚡ Starting RemoteViber Host Daemon (Agent Launcher)"
echo "=========================================================="

python3 "$ROOT_DIR/viber-host/main.py" "$@"
