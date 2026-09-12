#!/usr/bin/env bash
# Fetches external build inputs for the combined DeltaRay APK:
#   1. sing-box-lx core (libbox.aar) — from Leadaxe/sing-box-lx releases.
#   2. ZeroDPI Android runtime (libzerodpi_exec.so + config/list assets) —
#      cross-compiled with the repo's own build.py (--android-skip-apk).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

ABI="${ZERODPI_ABI:-arm64-v8a}"
RUNTIME="${ZERODPI_RUNTIME:-full}"

if command -v python3 >/dev/null 2>&1; then
  PY="python3"
elif command -v py >/dev/null 2>&1; then
  PY="py"
else
  PY="python"
fi

echo "==> [1/2] sing-box-lx core (libbox.aar)"
bash "$REPO_ROOT/src/lxbox/scripts/fetch-libbox.sh"

echo "==> [2/2] ZeroDPI Android runtime ($RUNTIME / $ABI)"
# build.py must be run from the ZeroDPI tree (cargo subprocesses inherit cwd).
( cd "$REPO_ROOT/src/zerodpi" \
  && "$PY" build.py --platform android \
       --android-app-abi "$ABI" \
       --android-app-runtime "$RUNTIME" \
       --android-app-build-type debug \
       --android-skip-apk )

echo "---> runtime ready at: $REPO_ROOT/src/zerodpi/dist/android-app/$RUNTIME"