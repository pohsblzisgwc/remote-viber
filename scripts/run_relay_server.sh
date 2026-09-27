#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=========================================================="
echo "  🌐 Starting RemoteViber Linux Signaling & Relay Server"
echo "=========================================================="

python3 "$ROOT_DIR/viber-server/main.py" "$@"
