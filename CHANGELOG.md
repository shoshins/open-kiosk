# Changelog

All notable changes are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Fullscreen, immersive WebView kiosk with first-run URL setup.
- Navigation locked to the start URL's origin; `file:`, `intent:` and
  `javascript:` URLs refused; no file or content access from pages.
- Recovery: reload when the network returns, retry failed loads with
  exponential backoff, rebuild the WebView after a renderer crash.
- Start on boot (Android 8.1–9) and optional Home app role.
- Keep screen awake.
- Back gestures on Android 13+ go through `OnBackInvokedDispatcher`.
- Targets Android 16 (API 36), as Google Play requires from 31 August 2026.
- F6 wall panel hardware notes.
- PIN-protected settings, opened by five taps in the top-left corner;
  PBKDF2-hashed PIN with growing lockouts; screen closes after two idle
  minutes and is kept out of screenshots.
- Authenticated local REST API (`/api/v1`), off by default and
  loopback-only unless LAN access is enabled.
- MQTT 3.1.1 client with TLS hostname verification, Last Will availability,
  command envelopes with expiry and acknowledgements, and Home Assistant
  discovery.
- Opt-in JavaScript bridge (`window.openKiosk`) with origin checks.
- F6 round secondary screen through the bridge.
- Screensaver (clock, black, dim, URL) on idle or on command; brightness;
  wake and sleep.
- Text-to-speech and an allowlisted app launcher.
- Telemetry: memory, storage, network, battery, uptime.
- MQTT password encrypted with an Android Keystore key; API tokens stored
  as hashes.
