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
- F6 wall panel hardware notes.
