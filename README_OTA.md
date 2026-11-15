# MediaLAB 1.3.0 · GitHub OTA

Repository: https://github.com/INFEND/MEDIALAB

- One pinned updater endpoint: https://github.com/INFEND/MEDIALAB/releases/latest/download/update.json
- Release APK: https://github.com/INFEND/MEDIALAB/releases/latest/download/MediaLAB.apk
- FFmpegKit Full 8.1.9 is downloaded in CI, SHA-256 verified, added to app/libs, and **bundled into the APK**. No Android-side AAR file is necessary.
- Official keystore is provided by the `MEDIALAB_SIGNING_BUNDLE` GitHub Actions secret as a Base64 ZIP containing `signing.json` and one `.p12` file. JSON: `{ "alias": "...", "keystore_password": "...", "key_password": "...", "certificate_sha256": "..." }`.
- For user installing a debug-signed version: Android cannot install a release signed by another key over it. Back up app data and uninstall the debug APK once to install the first official release. Future signed OTA updates will install over the official release.
- Version bumps require increasing versionCode, versionName, and release_notes/vX.Y.Z.txt.
- Downloaded APK SHA-256 and signature must match installed app before Android installation is offered.

## Notes
Never commit release signing keys, secrets, passwords, or an unverified third-party AAR. Build step checks the SHA-256 of FFmpeg binary published by its upstream GitHub release.

The workflow accepts legacy secret names `MEDIA_LAB_SIGNING_BUNDLE`, `MEDIALAB_RELEASE_SIGNING_BUNDLE`, `ANDROID_SIGNING_BUNDLE`, `SIGNING_BUNDLE` and `MEDIALAB_SIGNING`. All values must be Base64 ZIP bundles with signing.json and one PKCS12 file.

## Lightweight APK and persistent FFmpeg (1.3.27+)
Only `MediaLAB.apk` is used by OTA. FFmpeg 8.1.9 ARM64 is separately published as `ffmpeg-arm64-8.1.9.zip` in v1.3.27. The application verifies hashes from the signed APK and extracts into app-private no-backup storage. The engine survives APK updates. Migration from an embedded APK requires one download. For future codec-version upgrades change pinned AAR, engine version and asset URL together. No full APK is built.
