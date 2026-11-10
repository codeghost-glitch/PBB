# PBB

Rust + JNI Android app for four PBB live DASH/clear-key streams.

## What it does

- 2x2 multi-view in every orientation.
- Focus one stream, then picture-in-picture for that one stream only. Focus keeps the 16:9 frame instead of stretching.
- On Android 12+ the system auto-enters PiP on the go-home gesture via setAutoEnterEnabled(true) while a stream is focused. It does not fire on the recents/overview gesture. The old onUserLeaveHint trigger fired for both, so swiping up to recents used to jump into PiP. On Android 8-11 and 3-button navigation, use the PiP button.
- Focus rings only appear for keyboard/D-pad navigation. Tapping a button does not leave the WebView focus outline stuck on it.
- Shaka network failures (1001 BAD_HTTP_STATUS, 1002 HTTP_ERROR, 1003 TIMEOUT) trigger a bounded backoff reload instead of leaving the tile on a dead error message.
- Focusing a stream turns its sound on automatically.
- Fullscreen button per focused stream: hides system bars, letterboxes the feed on black, exits with the floating button or the back key.
- Global default quality: auto, hd, or sd. Rust validates and persists the choice in app-private storage.
- PBB All Access is the default sound stream on launch.
- Record button pulls the focused stream DASH segments directly into Movies/PBB/ with video and audio. No MediaProjection, no consent dialog, no activity required: a foreground service keeps recording with the app backgrounded or the screen off.
- The service writes the encrypted init + media segments for one video and one audio representation to two temporary fragmented MP4 files, then remuxes them into a normal MP4 with the ClearKey on stop. Stream copy, no re-encode.
- The remuxer is a downloadable FFmpeg plugin, not part of the APK. It is fetched once on first record, verified against a pinned SHA-256, stored read-only, and loaded with System.load(). APK stays about 1 MB; the plugin is 1.3 MB downloaded on demand.
- Recording does not change focus, playback, sound, PiP, fullscreen or navigation: those locks were only needed for screen capture. The record button on any card stops a running recording.
- Stop from the notification shade. Swiping the app away from recents does not stop the recording.
- Recording state lives in the service, so after the activity is recreated the UI still shows which stream is recording and the stop button still works.
- In picture-in-picture the live badge, stream status, and controls are hidden, so only the video is shown.
- Neobrutalism styling from /home/rey/Downloads/Projects/neobrutalism with Coral & Cream defaults: warm cream, 2px black borders, 4px hard shadows, rounded cards, lowercase headings, scattered stars/blobs, mechanical press-down buttons.

## Shape

- Rust: stream config, clear keys, default-quality validation and persistence, JNI exports.
- Java: thin WebView host, PiP lifecycle, headless direct-pull recording service, MPD parsing and segment fetching.
- Downloadable FFmpeg plugin (C + NDK build script in ffmpeg/): decrypts CENC samples and muxes video + audio into an MP4.
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
7. on main only, creates or updates the rolling GitHub release v1.0 with one asset, `PBB-1.0-<build>.apk`

The release tag is fixed at v1.0. The asset name includes the build number so a new download never hits a stale cached APK. The workflow deletes old .apk assets so exactly one APK is offered. App version is always 1.0; versionCode carries the build number.

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

- No consent dialog: recording pulls segments directly.
- POST_NOTIFICATIONS is requested on Android 13+.
- WRITE_EXTERNAL_STORAGE is requested only on Android 9 and below. Android 10+ writes through MediaStore to Movies/PBB/.
- Foreground service type dataSync is declared for Android 14+.

## Runtime clear keys

The broadcaster rotates ClearKeys. When that happens Shaka marks every variant unplayable and reports 4012 RESTRICTIONS_CANNOT_BE_MET; the recorder would also produce garbage files because it uses the same key.

Keys live in a plain keys.json on a secret gist, fetched at startup from the unversioned raw URL pinned as KEYS_URL in MainActivity.java:

- Fetched off the UI thread with short timeouts, validated (4 streams, https URLs, 32-hex KID and key), cached in filesDir/streams.json, and used as the last-good config when offline.
- Rust STREAM_CONFIG_JSON in src/lib.rs is the built-in fallback.
- Pushed to the page through window.onStreamConfig(); changed keys destroy and re-init the Shaka players, so no restart is needed.
- Tap the status bar to call Pbb.refreshKeys() at any time. On 4012 the tiles and status bar say "keys rotated - tap status bar to refresh".
- Both playback (Pbb.getConfig()) and recording (startRecordService) read the same effective config.

When the studio publishes new keys, run:

~~~bash
./keys/update.sh
~~~

It fetches https://pbb-live-studio.vercel.app/data.js, decodes the obfuscated CH array with keys/decode-data.js, validates the pairs, and PATCHes the gist. No APK rebuild and no reinstall. Verify a new key before publishing by decrypting one live segment on the host with ffmpeg -decryption_key and decoding a few frames.

Current state: the studio site is a maintenance page ("refreshing the server keys") and its data.js still serves the old pairs, so keys.json carries those stale keys. Playback stays on 4012 until the studio publishes the new pairs and ./keys/update.sh is run.

## Downloadable recorder plugin

Android 10+ forbids execve() on files in an app home directory, so a downloaded ffmpeg executable cannot run. A downloaded shared library can, as long as it is read-only (Android 14+ rejects writable dynamic code). The plugin therefore is libpbbffmpeg.so, fetched as base64 from a private gist, decoded, chmod 0444, then loaded with System.load(). Only libc/libm/libdl are needed, and it is 16 KB page-aligned for Android 15+.

Hosted at the URL pinned in RecordService.java, together with its SHA-256. Both must be updated together when the plugin changes.

Rebuild with ./ffmpeg/build-android.sh (FFmpeg n7.1, arm64-v8a, minimal: mov demuxer, mp4 muxer, h264/aac parsers, file protocol; no TLS, libxml2 or DASH demuxer needed because Java does the fetching). Output is ffmpeg/out/libpbbffmpeg.so and .b64. Re-upload the .b64 to the gist, then update PLUGIN_URL and PLUGIN_SHA256.

The plugin is arm64-only. Non-arm64 devices get a clear error instead of a crash. On Android 15+ the dataSync foreground service has a daily time budget, so very long recordings may be curtailed by the system.

ffmpeg/host_test.c plus a host build of the same minimal FFmpeg validates pbb_remux against captured real segments without a device: build host ffmpeg with the same configure flags, compile host_test.c against it, and ffprobe the output. Verified: 360 H.264 frames + 563 AAC frames, clean decode.

Known limit: a rotated ClearKey produces a garbage file rather than an error, because remuxing copies packets without decoding them. Update STREAM_CONFIG_JSON in src/lib.rs when the broadcaster rotates keys.

DASH tfdt timestamps are absolute (time since the stream began). The remuxer sets AVFMT_AVOID_NEG_TS_MAKE_ZERO so the MP4 starts at 0; otherwise mvhd/tkhd carry a duration of hours or days and only an edit list hides it, which many players ignore. Files recorded before this fix can be repaired without re-encoding:

~~~bash
ffmpeg -i old.mp4 -c copy -movflags +faststart fixed.mp4
~~~

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
- FFmpeg n7.1 (downloaded plugin, LGPL-2.1-or-later build): source at https://github.com/FFmpeg/FFmpeg/tree/n7.1, rebuilt by ffmpeg/build-android.sh. The plugin is distributed separately from the APK.
