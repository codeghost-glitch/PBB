# PBB

Rust + JNI Android app for four PBB live DASH/clear-key streams.

## What it does

- 2x2 multi-view on wide/landscape screens; single-column stack on narrow portrait screens.
- Focus one stream, then picture-in-picture for that one stream only. PiP also auto-enters when leaving the app from focus mode.
- Global default quality: auto, hd, or sd. Rust validates and persists the choice in app-private storage.
- Record button uses Android MediaProjection to capture the focused stream into Movies/PBB/. Recording is video-only screen capture because DASH clear-key playback is DRM-protected and captureStream() is not a reliable source.
- Neobrutalism styling from /home/rey/Downloads/Projects/neobrutalism with Coral & Cream defaults: warm cream, 2px black borders, 4px hard shadows, rounded cards, lowercase headings, scattered stars/blobs, mechanical press-down buttons.

## Shape

- Rust: stream config, clear keys, default-quality validation and persistence, JNI exports.
- Java: thin WebView host, PiP lifecycle, MediaProjection foreground recording service.
- WebView assets: local DM Sans, vendored Shaka Player 4.14.8, local HTML/CSS/JS.
- No Gradle, no AndroidX, no Kotlin, no network font/CDN runtime dependency.

## Versioning

The app version is fixed at **1.0**.

- versionName: 1.0 (constant)
- versionCode: build number, injected by the build (VERSION_CODE, default 1)

The manifest declares neither, so aapt2 injects both from the build environment.

## Build

~~~bash
cd PBB
./build.sh
~~~

Output: PBB.apk

Environment overrides, all optional:

| Variable | Default |
|---|---|
| VERSION_NAME | 1.0 |
| VERSION_CODE | 1 |
| ANDROID_SDK_ROOT | auto-detected |
| ANDROID_NDK_HOME | newest NDK under the SDK |
| JAVA_HOME | auto-detected |
| PBB_KEYSTORE | android/release.keystore |
| PBB_KEY_ALIAS | pbb |
| PBB_KEYSTORE_PASS | pbb-release |

## CI and release

.github/workflows/android.yml runs on push to main, pull requests, and manual dispatch:

1. rust toolchain with the three Android targets
2. cargo-ndk
3. android SDK and NDK
4. cargo fmt --check, cargo clippy -D warnings, cargo test
5. ./build.sh
6. uploads PBB-apk as a workflow artifact
7. on main only, creates or updates the rolling GitHub release v1.0 with PBB-1.0-<build>.apk

Because the release tag is fixed at v1.0 and the asset is re-uploaded with --clobber, there is one stable download link while the app version stays 1.0.

## Signing

android/release.keystore is committed so every build carries the same signature and updates install over the previous APK.

It is a self-signed personal-release key stored in plain form in this repository, with the password in build.sh. That is deliberate for a private, non-Play-Store build. If this repository is ever made public, rotate the key and move it to repository secrets.

## Requirements

- Android SDK platform 35 and build-tools 35.0.0.
- Android NDK 27.2.12479018.
- JDK 17.
- rustup targets: aarch64-linux-android, armv7-linux-androideabi, x86_64-linux-android.
- cargo-ndk.

## Runtime permissions

- Screen-capture consent is requested when recording starts.
- POST_NOTIFICATIONS is requested on Android 13+.
- WRITE_EXTERNAL_STORAGE is requested only on Android 9 and below. Android 10+ writes through MediaStore to Movies/PBB/.
- Foreground service type mediaProjection is declared for Android 14+.

## Validation performed

- cargo fmt --check
- cargo clippy --all-targets -- -D warnings
- cargo test
- cargo ndk build for arm64-v8a, armeabi-v7a, x86_64
- javac against android.jar
- d8 dexing
- aapt2 resource packaging
- apksigner verify
- aapt2 dump badging
- No emulator or on-device playback test.

## Licenses

- Shaka Player: Apache-2.0, see android/assets/LICENSE-shaka.txt
- DM Sans: SIL Open Font License 1.1, see android/assets/fonts/OFL.txt
