#!/usr/bin/env bash
# §104 — скачивает ядро Xray (libxray.aar из libxray-android.zip) из
# GitHub Releases XTLS/libXray с проверкой SHA256.
#
# Версия и sha256 пинятся в app/android/xray.version (single source of
# truth для local + CI): строка 1 = тег, строка 2 = sha256 архита zip.
# Override: ./scripts/fetch-xray.sh <ver> <sha256-zip>
#
# Тег libXray совпадает с тегом Xray-core (v26.9.9 = «Updated Xray-core
# to v26.9.9») — пин здесь и есть пин кора.
#
# Идемпотентен: если в libs/ уже лежит AAR той же версии (маркер
# .libxray.version) — выходит сразу. Используется build-local-apk.sh и CI
# (build.yml → android job → "Fetch Xray core").

set -euo pipefail
cd "$(dirname "$0")/.."

REPO="XTLS/libXray"
PIN="app/android/xray.version"
VER_FILE="$(sed -n 1p "$PIN" | tr -d '[:space:]')"
SHA_FILE="$(sed -n 2p "$PIN" | tr -d '[:space:]')"
VER="${1:-$VER_FILE}"
SHA="${2:-$SHA_FILE}"
DEST="app/android/app/libs"
ZIP="libxray-android.zip"
BASE_URL="https://github.com/$REPO/releases/download/$VER"

if [ -z "$VER" ] || [ -z "$SHA" ]; then
  echo "✗ $PIN must contain <tag> and <sha256> (got VER='$VER' SHA='$SHA')" >&2
  exit 1
fi

if [ -f "$DEST/libxray.aar" ] && [ -f "$DEST/.libxray.version" ] \
   && [ "$(cat "$DEST/.libxray.version")" = "$VER" ]; then
  echo "✓ libxray.aar already $VER — skipping"
  exit 0
fi

echo "→ Fetching $ZIP ($REPO @ $VER)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

curl -fsSL --retry 3 -o "$TMP/$ZIP" "$BASE_URL/$ZIP"

# sha256sum (linux/CI) или shasum -a 256 (macOS).
if command -v sha256sum >/dev/null 2>&1; then
  SHACMD="sha256sum"
else
  SHACMD="shasum -a 256"
fi
GOT="$($SHACMD "$TMP/$ZIP" | awk '{print $1}')"
if [ "$GOT" != "$SHA" ]; then
  echo "✗ SHA256 mismatch for $ZIP" >&2
  echo "  expected $SHA" >&2
  echo "  got      $GOT" >&2
  exit 1
fi

# AAR лежит внутри zip как libxray-android/libXray.aar.
if command -v unzip >/dev/null 2>&1; then
  unzip -q -j "$TMP/$ZIP" "libxray-android/libXray.aar" -d "$TMP"
elif command -v python3 >/dev/null 2>&1; then
  python3 -c "import zipfile; zipfile.ZipFile('$TMP/$ZIP').extract('libxray-android/libXray.aar', '$TMP')"
  mv "$TMP/libxray-android/libXray.aar" "$TMP/libXray.aar"
else
  rmdir "$TMP" 2>/dev/null || true
  echo "✗ need unzip or python3 to extract $ZIP" >&2
  exit 1
fi
[ -f "$TMP/libXray.aar" ] || { echo "✗ libXray.aar not found in $ZIP" >&2; exit 1; }

mkdir -p "$DEST"
mv "$TMP/libXray.aar" "$DEST/libxray.aar"
printf '%s' "$VER" > "$DEST/.libxray.version"
echo "✅ $DEST/libxray.aar ← $VER (Xray-core)"
