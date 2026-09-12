# DeltaRay Release Notes

## vX.Y.Z

- Combined APK: **L×Box** (sing-box / libbox VPN client, Flutter UI) + **ZeroDPI** (cross-platform DPI bypass relay, Rust engine) in a single APK.
- On first launch the app shows a chooser: pick **L×Box** or **ZeroDPI**; use Back to return to the chooser.
- ZeroDPI runtime: `full` variant (packet-interception methods require root; socket-only methods work without root).

Installation:

```
adb install -r DeltaRay-vX.Y.Z-arm64-v8a.apk
```