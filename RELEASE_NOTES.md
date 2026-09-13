# DeltaRay v0.2.0

## What's new

- **Runtime resources update over the air.** On launch the launcher reads
  `version.txt` from the DeltaRay repository; when it differs from the version
  bundled into the APK (or the last applied one), the runtime files
  (`config.toml`, `sni_list.txt`, `ip_list.txt`) are downloaded and applied to
  the ZeroDPI profile — no reinstall needed to ship updated target lists or
  config.

## Launcher

- Launcher home now shows live upload/download volume from the kernel status
  stream (`↑ … · ↓ …`).
- Watchdog stats reset on every session (tests / ok / timeouts / switches).
  Config switches are no longer shown in the watchdog log line.
- Launcher home is fully centered; ZeroDPI scan progress is shown in per-cent,
  tracked live for the current scan stage.
- Startup always picks a direction from the auto twin.

## ZeroDPI

- SOCKS5 CONNECT reply is parsed per RFC 1928 (RSV byte consumed) — no more
  spurious "socks5 bind addr" timeouts on the first probe.
- Flip-flop between configs is suppressed until every candidate has been tested
  within the current session.

## Download

Attached: `DeltaRay-v0.2.0-arm64-v8a.apk`