# Contributing to Open Kiosk

Thanks for helping. Open Kiosk runs unattended on home networks, so every
change is judged on two questions first: is it safe, and is it fast?

## Ground rules

- **Security over features.** Nothing a web page can reach may expose a shell,
  arbitrary intents or the filesystem. New capabilities are opt-in, typed and
  origin-checked. Remote APIs stay off by default.
- **Performance over convenience.** The app is plain Kotlin on the Android
  framework. Please do not add AndroidX, Compose or large libraries without
  discussing it in an issue first, with numbers (APK size, memory, start time).
- **Android 8.1 (API 27) must keep working.** Feature-detect newer APIs; never
  assume them.
- **Fail explicitly.** An unsupported operation returns a clear error; it does
  not silently do nothing.
- **No secrets in code, tests or docs.** Use environment variables or
  placeholders.

## Development setup

You need JDK 17 and the Android SDK (platform 35).

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Install on a device with `adb install app/build/outputs/apk/debug/app-debug.apk`.

The website lives in `site/` and is plain HTML and CSS; open
`site/index.html` in a browser to preview it.

Maintainers: see [RELEASING.md](RELEASING.md) for signing and publishing.

## Pull requests

1. Open an issue first for anything larger than a bug fix.
2. Keep pull requests small and focused.
3. Add or update tests. CI must be green.
4. Update `CHANGELOG.md` and the docs when behavior changes.
5. Describe how you tested, including the Android version and device.

By contributing, you agree that your contributions are licensed under the
Apache License 2.0.

## Hardware reports

Tried Open Kiosk on a panel? Open an issue with the device model, Android
version, System WebView version (`adb shell dumpsys webviewupdate`) and what
worked. Vendor-specific findings go in `docs/hardware/`.
