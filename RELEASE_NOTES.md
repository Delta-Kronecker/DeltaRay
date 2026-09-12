# DeltaRay Release Notes

## v0.1.0

First combined build: **L×Box** (sing-box / libbox VPN client, Flutter UI) and
**ZeroDPI** (cross-platform DPI bypass relay, Rust engine) shipped in a single APK.

### What's inside

- One APK, package `com.leadaxe.lxbox`, with a chooser entry screen: pick
  **L×Box** or **ZeroDPI**; Back returns to the chooser.
- ZeroDPI runtime: `full` variant (`arm64-v8a`).
  - Packet-intercept methods (SoCKS/transparent) require root.
  - Socket-level methods (port-forward) work without root.
- L×Box: full feature set — subscriptions, proxies, rules, tun2socks, service
  notifications, backup/restore.

### Installation

```
adb install -r DeltaRay-v0.1.0-arm64-v8a.apk
```

### Notes

- Built for `arm64-v8a` only in this release.
- ZeroDPI is self-contained inside the app: config, SNI list, and relay server
  assets are staged at build time from the repo's `config.toml` /
  `sni_list.txt` / `ip_list.txt`.
- No re-signing conflicts: app, its libbox core, and the embedded ZeroDPI
  module all share the same APK signature.