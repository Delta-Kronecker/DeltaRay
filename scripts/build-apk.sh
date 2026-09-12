#!/usr/bin/env bash
# Builds the COMBINED DeltaRay APK (L×Box Flutter UI + ZeroDPI engine).
#
#   ZERODPI_RUNTIME   ZeroDPI runtime variant (default: full)
#   ZERODPI_ABI       Rust target ABI (default: arm64-v8a)
#
# Requires on PATH: flutter, bash, and python3 (or py on Windows), plus an
# Android SDK/NDK (NDK is auto-downloaded by ZeroDPI's build.py if missing).
# On Windows run from Git Bash or WSL.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

ABI="${ZERODPI_ABI:-arm64-v8a}"
RUNTIME="${ZERODPI_RUNTIME:-full}"
RUNTIME_DIR="$REPO_ROOT/src/zerodpi/dist/android-app/$RUNTIME"

echo "==> [1/3] Fetch dependencies (libbox.aar + ZeroDPI runtime)"
bash "$REPO_ROOT/scripts/fetch-deps.sh"

echo "==> [2/3] Combined Flutter APK"
export ZERODPI_RUNTIME_DIR="$RUNTIME_DIR"
bash "$REPO_ROOT/src/lxbox/scripts/build-local-apk.sh"

echo ""
echo "==> Done. Combined APK written by Flutter (rename step inside build-local-apk.sh)."