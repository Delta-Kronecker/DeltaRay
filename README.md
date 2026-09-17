# DeltaRay

A simple, fast VPN client for Android — one tap to connect.

DeltaRay combines two upstream projects into a single APK:

- **[L×Box](https://github.com/Leadaxe/LxBox)** — the Flutter UI and the sing-box (`libbox`) VPN core.
- **[ZeroDPI](https://github.com/nullroute1970/ZeroDPI)** — a DPI-bypass engine.

The result is an Android app (`arm64-v8a`) that is easy to use and quick to connect.

## Build locally

### Requirements

- **Flutter 3.47.1** (pinned in `src/lxbox/app/android/flutter.version`)
- **Rust** with the Android target: `rustup target add aarch64-linux-android`
- **Python 3**
- **Android SDK/NDK** (the NDK is downloaded automatically by ZeroDPI's `build.py` if missing)
- `bash` and `git`

On Windows, run everything from **Git Bash** or **WSL**.

### One command

```bash
bash scripts/build-apk.sh
```

This script does everything:

1. Fetches the pinned sing-box core (`libbox.aar`) and cross-compiles the ZeroDPI Android runtime — `scripts/fetch-deps.sh`.
2. Builds the combined release APK — `src/lxbox/scripts/build-local-apk.sh`.

### Step by step (equivalent)

```bash
# 1. Fetch dependencies: libbox.aar + ZeroDPI runtime
bash scripts/fetch-deps.sh

# 2. Build the combined Flutter APK
export ZERODPI_RUNTIME_DIR="$PWD/src/zerodpi/dist/android-app/full"
bash src/lxbox/scripts/build-local-apk.sh
```

### Environment variables

| Variable          | Default      | Description                                  |
| ----------------- | ------------ | -------------------------------------------- |
| `ZERODPI_ABI`     | `arm64-v8a`  | Target ABI: `arm64-v8a` or `armeabi-v7a`.    |
| `ZERODPI_RUNTIME` | `full`       | ZeroDPI runtime variant.                     |

### Output

```
src/lxbox/app/build/app/outputs/flutter-apk/app-${ZERODPI_ABI}-release.apk
```

For example, `app-arm64-v8a-release.apk` or `app-armeabi-v7a-release.apk`.

## Releases

Pushing a tag (`v*`) builds the APK and publishes it as a GitHub Release via
`.github/workflows/build.yml`. Pushes to `main` run checks (analyze + tests)
only.

## Credits

- **ZeroDPI** — https://github.com/nullroute1970/ZeroDPI
- **L×Box** — https://github.com/Leadaxe/LxBox

All rights and licenses for the upstream components belong to their respective
authors.
