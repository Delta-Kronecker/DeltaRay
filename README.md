# DeltaRay

A simple, fast VPN client for Android — one tap to connect.

## Build locally

### Requirements

- **Flutter 3.47.1** (pinned in `src/lxbox/app/android/flutter.version`)
- **Rust** with the Android target: `rustup target add aarch64-linux-android`
- **Python 3**
- **Android SDK/NDK**
- `bash` and `git`

On Windows, run everything from **Git Bash** or **WSL**.

### One command

```bash
bash scripts/build-apk.sh
```

This script does everything

### Step by step (equivalent)

```bash
# 1. Fetch dependencies:
bash scripts/fetch-deps.sh

# 2. Build the combined Flutter APK
export ZERODPI_RUNTIME_DIR="$PWD/src/zerodpi/dist/android-app/full"
bash src/lxbox/scripts/build-local-apk.sh
```

## Releases

Pushing a tag (`v*`) builds the APK and publishes it as a GitHub Release via
`.github/workflows/build.yml`. Pushes to `main` run checks (analyze + tests)
only.

## Credits

- **ZeroDPI** — https://github.com/nullroute1970/ZeroDPI
- **L×Box** — https://github.com/Leadaxe/LxBox

All rights and licenses for the upstream components belong to their respective
authors.
