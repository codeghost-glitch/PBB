# PBB

Rust + JNI Android app for three PBB live DASH/clear-key streams.

## What it does

- Three-feed multi-view in one screen: one large top feed and two bottom feeds in every orientation.
- Every card has one-click fullscreen and picture-in-picture: the button focuses that feed, plays it alone with sound, then enters the mode. Focus keeps the 16:9 frame instead of stretching.
- On Android 12+ the system auto-enters PiP on the go-home gesture via setAutoEnterEnabled(true) once a stream is focused (any fullscreen or PiP tap focuses one). It does not fire on the recents/overview gesture. The old onUserLeaveHint trigger fired for both, so swiping up to recents used to jump into PiP. On Android 8-11 and 3-button navigation, use the PiP button.
- Focus rings only appear for keyboard/D-pad navigation. Tapping a button does not leave the WebView focus outline stuck on it.
- Shaka network failures (1001 BAD_HTTP_STATUS, 1002 HTTP_ERROR, 1003 TIMEOUT) trigger a bounded backoff reload instead of leaving the tile on a dead error message.
- Opening fullscreen or PiP turns that feed's sound on automatically.
- Fullscreen button on every card: hides system bars, letterboxes the feed on black, exits with the floating button or the back key. The control column has lock, exit, zoom, and a next-feed button that switches which live feed is fullscreen without leaving it. Exiting fullscreen drops the focus and lands on the three-feed grid.
- Global default quality: auto, hd, or sd. Rust validates and persists the choice in app-private storage. hd is the tallest variant the feed publishes; while one feed is focused the other two drop to sd, so the phone is not decoding three 1080p streams at once.
- PBB All Access is the default sound stream on launch.
- Record button turns pink with a stop icon while a recording runs. It pulls that stream's DASH segments directly into Movies/PBB/ with video and audio. No MediaProjection, no consent dialog, no activity required: a foreground service keeps recording with the app backgrounded or the screen off.
- The service writes the encrypted init + media segments for one video and one audio representation to two temporary fragmented MP4 files, then remuxes them into a normal MP4 with the ClearKey on stop. Stream copy, no re-encode.
- The remuxer is a downloadable FFmpeg plugin, not part of the APK. It is fetched once on first record, verified against a pinned SHA-256, stored read-only, and loaded with System.load(). APK stays about 1 MB; the plugin is 1.3 MB downloaded on demand.
- Recording does not change focus, playback, sound, PiP, fullscreen or navigation: those locks were only needed for screen capture. The record button on any card stops a running recording.
- Stop from the notification shade. Swiping the app away from recents does not stop the recording.
- Recording state lives in the service, so after the activity is recreated the UI still shows which stream is recording and the stop button still works.
- In picture-in-picture the live badge, stream status, and controls are hidden, so only the video is shown.
- Android 15 forces edge to edge, so the WebView sits in a container padded by the system bar and cutout insets. The top bar clears the phone's status bar and the footer clears the navigation bar; immersive fullscreen zeroes the insets and the padding drops again.
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
| PBB_KEYSTORE | android/release.keystore (gitignored) |
| PBB_KEY_ALIAS | pbb |
| PBB_KEYSTORE_PASS | required, no default |

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

The signing key is **not** in the repo: `android/release.keystore` is gitignored, and `build.sh` refuses to run without `PBB_KEYSTORE_PASS`. Locally:

~~~bash
PBB_KEYSTORE_PASS=... ./build.sh
~~~

CI decodes the key from the `PBB_KEYSTORE_B64` repository secret into `$RUNNER_TEMP` for the run only, so every release still carries the same signature and updates install over the previous APK. To rotate the key, generate a new keystore, replace both secrets, and bump nothing else - existing installs then need one uninstall, because Android does not accept a different signer as an update.

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

## Stream source and runtime clear keys

The broadcaster rotates ClearKeys. When that happens Shaka marks every variant unplayable and reports 4012 RESTRICTIONS_CANNOT_BE_MET; an in-flight recording would also produce garbage files because it uses the same key.

The app uses the three feeds from https://pbb-live.vercel.app. MainActivity fetches each channel's `/api/stream?channel=<id>` endpoint at startup and on manual refresh, reads `directManifestUri` and `ClearKey.k1`/`k2`, and converts them to the app's `{name,url,kid,key}` config. Direct Akamai MPDs are used so playback and recording do not depend on the site's manifest proxy.

- Fetched off the UI thread with short timeouts; all three channel responses must be clearkey feeds with https MPDs and 32-hex KID/key pairs. The combined config is cached in filesDir/streams.json and used as the last-good config when offline.
- Rust STREAM_CONFIG_JSON in src/lib.rs is the built-in fallback with the last known three feeds.
- Pushed to the page through window.onStreamConfig(); changed keys destroy and re-init the Shaka players, so no restart is needed.
- The status bar is the thin footer card at the bottom of the page, below the three-feed grid: a teal dot plus the status text, with the back button when focused. It is hidden in PiP and fullscreen. In the grid it never hides itself: with no message it reads "tap to refresh", because the card is also the refresh control.
- The status bar is the refresh control: tapping it calls Pbb.refreshKeys(). There is no separate refresh icon in the top bar or in fullscreen. On 4012 the tiles say "keys rotated - tap refresh" and the status bar says "keys rotated - tap this bar to refresh".
- A manual refresh always resolves its status text: changed keys re-init the players, unchanged keys report that the source has not published new ones, and a failed fetch reports the connection error. Silent startup fetches never overwrite the status.
- Both playback (Pbb.getConfig()) and recording (startRecordService) read the same effective config, so key rotation needs no APK rebuild or reinstall.

To refresh the checked-in fallback snapshot from the same live API:

~~~bash
./keys/update.sh
~~~

It writes keys/keys.json and rewrites `STREAM_CONFIG_JSON` in src/lib.rs with the same values, so the built-in fallback can not drift from the snapshot. The runtime app reads neither: it fetches the API directly and caches the result in filesDir/streams.json. Commit both files after a rotation, or leave the fallback stale and let the first launch with a network fix it.

## Downloadable recorder plugin

Android 10+ forbids execve() on files in an app home directory, so a downloaded ffmpeg executable cannot run. A downloaded shared library can, as long as it is read-only (Android 14+ rejects writable dynamic code). The plugin therefore is libpbbffmpeg.so, fetched as base64 from a private gist, decoded, chmod 0444, then loaded with System.load(). Only libc/libm/libdl are needed, and it is 16 KB page-aligned for Android 15+.

Hosted at the URL pinned in RecordService.java, together with its SHA-256. Both must be updated together when the plugin changes.

Rebuild with ./ffmpeg/build-android.sh (FFmpeg n7.1, arm64-v8a, minimal: mov demuxer, mp4 muxer, h264/aac parsers, file protocol; no TLS, libxml2 or DASH demuxer needed because Java does the fetching). Output is ffmpeg/out/libpbbffmpeg.so and .b64. Re-upload the .b64 to the gist, then update PLUGIN_URL and PLUGIN_SHA256.

The plugin is arm64-only. Non-arm64 devices get a clear error instead of a crash. On Android 15+ the dataSync foreground service has a six-hour budget, and `onTimeout` then ends the pull through the normal save path, so the file is published instead of lost.

The pinned gist is a single point of failure: if it disappears, recording stops working until a new URL ships in an APK. Shipping the .so in the APK removes that dependency.

A long stall in the archive (the live edge moving past what the server still serves) no longer splices across the hole: the current part is saved and a second file `..._2.mp4` continues. A failed remux keeps the pulled segments in the app cache and says so in the status text instead of deleting the only copy.

ffmpeg/host_test.c plus a host build of the same minimal FFmpeg validates pbb_remux against captured real segments without a device: build host ffmpeg with the same configure flags, compile host_test.c against it, and ffprobe the output. Verified: 360 H.264 frames + 563 AAC frames, clean decode.

Known limit: an in-flight recording keeps the key it started with, so a rotation can produce a garbage file because remuxing copies packets without decoding them. Refresh the feeds and start a new recording after rotation.

DASH tfdt timestamps are absolute (time since the stream began). The remuxer sets AVFMT_AVOID_NEG_TS_MAKE_ZERO so the MP4 starts at 0; otherwise mvhd/tkhd carry a duration of hours or days and only an edit list hides it, which many players ignore. Files recorded before this fix can be repaired without re-encoding:

~~~bash
ffmpeg -i old.mp4 -c copy -movflags +faststart fixed.mp4
~~~

## Validation performed

- cargo fmt --check
- cargo clippy --all-targets -- -D warnings
- cargo test
- node --check android/assets/app.js
- python3 -m json.tool keys/keys.json
- three-feed layout measured in headless Chromium at phone portrait, phone landscape, tablet and desktop viewports: every card keeps a visible video area, no clipped controls, no overlapping chrome
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
