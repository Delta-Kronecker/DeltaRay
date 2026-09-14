# DeltaRay v0.2.1

## What's new

- **Release notifications.** On launch the app checks GitHub for the latest
  release; when a newer version is published you get a dialog to open either
  the GitHub release page (download the APK) or the Telegram channel
  (`t.me/DeltaRayReleases`).

## Also in this build

- **Runtime resources update over the air** (from v0.2.0): the launcher reads
  `version.txt` from the DeltaRay repository; when it differs from the bundled
  version, `config.toml`, `sni_list.txt` and `ip_list.txt` are downloaded and
  applied to the ZeroDPI profile — updated target lists and config ship without
  reinstalling the app.
- Launcher home shows live upload/download volume from the kernel status stream.
- Watchdog stats reset per session; config switches are no longer shown in the
  watchdog log line; the home screen is fully centered.
- ZeroDPI scan progress is shown in per-cent, tracked live for the current scan
  stage; startup always picks a direction from the auto twin.
- SOCKS5 CONNECT reply is parsed per RFC 1928 (RSV byte consumed); config
  flip-flop is suppressed until every candidate has been tested in the session.

## Download

Attached: `DeltaRay-v0.2.1-arm64-v8a.apk`