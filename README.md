# DeltaRay

**L×Box** + **ZeroDPI** in a single APK.

- `src/lxbox/` — L×Box: Android VPN client (Flutter UI, sing-box / libbox core). Complete, unmodified UI; hosts the merged app and the chooser screen.
- `src/zerodpi/` — ZeroDPI: cross-platform DPI bypass relay (Rust). Its Android app is consumed as an Android-library module (`:zerodpi`).
- `.github/workflows/build.yml` — CI/CD: runs analyze/tests, builds the combined APK on tag push / manual dispatch, and creates a GitHub Release.

## How it works

One application (`com.leadaxe.lxbox`) with a chooser entry point:

```
LauncherActivity (MAIN/LAUNCHER)
 ├─ L×Box    → com.leadaxe.lxbox.MainActivity      (Flutter)
 └─ ZeroDPI  → dev.zerodpi.android.MainActivity    (Compose) — as library
```

Back from either app returns to the chooser.

The ZeroDPI native runtime (`libzerodpi_exec.so` + config/list assets) is cross-compiled by `src/zerodpi/build.py` and staged into the APK under `jniLibs`/`assets`. The `:zerodpi` Gradle module picks that directory from the `zerodpiRuntimeDir` property or the `ZERODPI_RUNTIME_DIR` env var.

## Building locally

Prerequisites: Flutter SDK (pin in `src/lxbox/app/android/flutter.version`), Android SDK, a `bash` shell (Git Bash on Windows), and `python3`/`py`. The Android NDK is auto-downloaded by ZeroDPI's `build.py` if missing.

```bash
# One-shot: fetch deps + build the combined APK (arm64-v8a, full runtime)
bash scripts/build-apk.sh

# Or step-by-step
bash scripts/fetch-deps.sh          # libbox.aar + ZeroDPI runtime
ZERODPI_RUNTIME_DIR="$PWD/src/zerodpi/dist/android-app/full" \
  bash src/lxbox/scripts/build-local-apk.sh
```

Env knobs: `ZERODPI_RUNTIME` (rootless|full|both, default `full`), `ZERODPI_ABI` (default `arm64-v8a`).

## CI / Release

- Every push/PR → `flutter analyze` + `flutter test` (L×Box suite).
- Tag push `vX.Y.Z` → builds the combined APK and creates a GitHub Release with `DeltaRay-vX.Y.Z-arm64-v8a.apk`.
- Manual `workflow_dispatch` → build only.

Signing: set the standard L×Box secrets (`ANDROID_KEYSTORE_BASE64`/`ANDROID_KEYSTORE_PASSWORD`/`ANDROID_KEY_PASSWORD`/`ANDROID_KEY_ALIAS`) to sign release APKs; without them the APK is debug-signed.

> **Repo security note:** when sharing a Personal Access Token in chat, revoke/rotate it afterwards — it was used only to push this repository.