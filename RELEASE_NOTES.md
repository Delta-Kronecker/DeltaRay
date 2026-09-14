# DeltaRay v0.2.1

## What's new

- **New launcher look.** The home screen is painted in the icon's palette
  (deep green-graphite background, gold/amber accent, warm cream text) with a
  soft gradient. The flat connect button was replaced by a **circular gold
  button with an animated progress ring** that lives off the ZeroDPI scan:
  the ring springs to the current percent, the percent is shown right inside
  the button, an indeterminate rotating arc + pulsing glow cover the phases
  before progress is known, and a power/play glyph marks states.
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
  phase (the TCP pre-pass, which used to race to ~98 % instantly, no longer
  counts); startup always picks a direction from the auto twin.
- SOCKS5 CONNECT reply is parsed per RFC 1928 (RSV byte consumed); config
  flip-flop is suppressed until every candidate has been tested in the session.

## Download

Attached: `DeltaRay-v0.2.1-arm64-v8a.apk`