MediaLAB build input: ffmpeg-kit-full-8.1.9.aar (pinned SHA-256).
Source: https://github.com/ffmpegkit-maintained/ffmpeg/releases/download/v8.1.9-lts-android/ffmpeg-kit-full-8.1.9-arm64-v8a-x86_64.aar
Keep the AAR in app/libs for local Gradle builds. Gradle creates a Java-only AAR and separate ARM64 ZIP in .gradle/medialab-engine-8.1.9/. The APK contains no native FFmpeg libraries. The device downloads the versioned ARM64 ZIP only when missing or invalid. NativeLoader.java is adapted from the LGPL upstream FFmpegKit source at v8.1.9-lts-android; the copyright and license header are preserved.
