# Releasing

Releases are built, signed and published by
[`.github/workflows/release.yml`](.github/workflows/release.yml) when a
version tag is pushed. The APK lands on the
[Releases page](https://github.com/shoshins/open-kiosk/releases), and the
latest one is always at:

```
https://github.com/shoshins/open-kiosk/releases/latest/download/open-kiosk.apk
```

## One-time setup: the signing key

Android only installs an update if it is signed with the same key as the
installed app, so this key must be kept safe for the life of the project.
**Back it up somewhere other than this machine. Never commit it.**

1. Create the key (you will be asked for passwords; use a password manager):

   ```bash
   keytool -genkeypair -v -keystore open-kiosk-release.jks \
     -alias open-kiosk -keyalg RSA -keysize 4096 -validity 10000
   ```

2. In the repository: **Settings → Secrets and variables → Actions → New
   repository secret**, add:

   | Secret | Value |
   |---|---|
   | `OPEN_KIOSK_KEYSTORE_BASE64` | output of `base64 -i open-kiosk-release.jks` |
   | `OPEN_KIOSK_KEYSTORE_PASSWORD` | the keystore password |
   | `OPEN_KIOSK_KEY_ALIAS` | `open-kiosk` |
   | `OPEN_KIOSK_KEY_PASSWORD` | the key password |

If you later publish on Google Play with Play App Signing, this key becomes
the upload key; Google holds the app signing key.

## Cutting a release

1. Update `CHANGELOG.md`: move the `Unreleased` items under the new version.
2. Tag and push:

   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

The workflow derives `versionName` from the tag (`0.1.0`) and `versionCode`
as `major × 10000 + minor × 100 + patch` (`100`), so versions must only go
up. Each release carries a versioned APK, a fixed-name `open-kiosk.apk`, and
`SHA256SUMS`.

## Verifying a download

```bash
sha256sum -c SHA256SUMS
apksigner verify --print-certs open-kiosk.apk
```
