#!/usr/bin/env bash
# Copies all recorded sessions from the watch into ./data/sessions/ (macOS / Linux / Git Bash).
# Usage: scripts/pull_sessions.sh            (watch must show in `adb devices`)
set -euo pipefail

PKG="dev.malzbro.wristshot"
REMOTE="/sdcard/Android/data/${PKG}/files/sessions"
DEST="$(cd "$(dirname "$0")/.." && pwd)/data"

mkdir -p "$DEST"
echo "Sessions on the watch:"
adb shell ls -l "$REMOTE"
# Creates (or updates) ./data/sessions/
adb pull "$REMOTE" "$DEST"
echo "Copied to $DEST/sessions"
