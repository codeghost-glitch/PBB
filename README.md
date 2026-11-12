# PBB

Rust + JNI Android app for three PBB live DASH/clear-key streams.

## What it does

- Three-feed multi-view in one screen: one large top feed and two bottom feeds. The grid is locked to portrait, because that is the layout it was drawn for. Only the fullscreen picture turns the phone sideways.
- The brightness level belongs to the fullscreen picture. It is remembered and put back the next time a feed goes fullscreen, but on the main screen and in the pip window the phone keeps its own brightness.
- Every card has one-click fullscreen and picture-in-picture: the button focuses that feed, plays it alone with sound, then enters the mode. Focus keeps the 16:9 frame instead of stretching.
- On Android 12+ the system auto-enters PiP on the go-home gesture via setAutoEnterEnabled(true) once a stream is focused (any fullscreen or PiP tap focuses one). It does not fire on the recents/overview gesture. The old onUserLeaveHint trigger fired for both, so swiping up to recents used to jump into PiP. On Android 8-11 and 3-button navigation, use the PiP button.
- Focus rings only appear for keyboard/D-pad navigation. Tapping a button does not leave the WebView focus outline stuck on it.
- Shaka network failures (1001 BAD_HTTP_STATUS, 1002 HTTP_ERROR, 1003 TIMEOUT) trigger a bounded backoff reload instead of leaving the tile on a dead error message.
- Opening fullscreen or PiP turns that feed's sound on automatically.
- Fullscreen button on every card: hides system bars, letterboxes the feed on black, exits with the keypad's exit key or the back key. The controls are a two-column keypad in the bottom-right corner, under a right thumb, with a word under every key - zoom, next, mini, lock, exit - and the exit key across the bottom. The keypad floats over the picture only while it is up and hides itself after a few seconds, so the video stays centred and never moves or gives up a lane. Locking it leaves only the lock key. A short centre chip names the feed when fullscreen opens or the feed is switched. The recording clock of a camera that is recording moves to the top left corner in fullscreen, out of the way of the keypad and the mini tile. The mini feed shows the next camera in a small tile in the top-right corner of fullscreen, and tapping that tile swaps it with the big feed. The mini tile keeps hd and its own sound, so both feeds are audible at once; the feed hidden behind fullscreen is the only one that drops to sd. A swap clears the zoom - the feed arriving in the big slot starts unzoomed and the zoom key loses its highlight. It is the same card element moved by CSS, so no video is re-attached and both feeds keep playing. The card-level PiP button is still there for watching while you leave the app. Exiting fullscreen drops the focus and lands on the three-feed grid.
- Global default quality: auto, hd, or sd. Rust validates and persists the choice in app-private storage. hd is the tallest variant the feed publishes; while one feed is focused the other two drop to sd, so the phone is not decoding three 1080p streams at once.
- PBB All Access is the default sound stream on launch.
- Record any camera on its own: the record button on a card starts that camera, or stops it, while the other cameras keep recording. All three can run at once. Every tap is confirmed by the service, and a recording card gets a red tally on the picture with its running time.
- The header has a master record button: "record all" starts every camera that is not already recording, then turns into "stop all" with the number of active recordings. Starting a batch asks for the notification permission once, not once per camera.
- Each recording pulls that stream's DASH segments directly into Movies/PBB/ with video and audio. No MediaProjection, no consent dialog, no activity required: the foreground service keeps every recording alive with the app backgrounded or the screen off.
- Each camera is an independent session with its own worker thread, temporary files and notification. The pulls run in parallel; only the final remux is serialised, because libavformat keeps process-wide state and two muxers driven at once would interleave. Stream copy, no re-encode.
- The service writes each session's encrypted init + media segments for one video and one audio representation to two temporary fragmented MP4 files, then remuxes them into a normal MP4 with that stream's ClearKey on stop.
- The remuxer is a FFmpeg plugin that ships inside the APK as base64 in the assets. It is decoded on first record, verified against a pinned SHA-256, stored read-only, and loaded with System.load(). A pinned gist is the fallback for builds made without the asset. The APK is about 1.6 MB because of it, and recording works offline.
- Recording does not change focus, playback, sound, PiP, fullscreen or navigation: those locks were only needed for screen capture. The record button on a card only affects that camera. A running recording also no longer turns the phone sideways: the recording happens in the service and the screen keeps the orientation the layout asks for.
- Per-camera notifications come and go under one summary notification: stop a single camera from its own notification, or stop every camera from the summary or the master button.
- Recording state lives in the service, so after the activity is recreated the page rebuilds every tally and its elapsed time from Pbb.recordingState() and the stop buttons still work.
- In picture-in-picture the live badge, stream status, tally and controls are hidden, so only the video is shown.
- Android 15 forces edge to edge, so the WebView sits in a container padded by the system bar and cutout insets. The top bar clears the phone's status bar and the footer clears the navigation bar; immersive fullscreen zeroes the insets and the padding drops again.
- Control-desk styling on the neobrutalism system: warm cream paper with a faint dot grid, 2px ink borders, hard offset shadows, lowercase headings, and mechanical press-down buttons. The header is one row on phones - eye mark, live count, a segmented quality control, and an ink master record button. A recording card is the loudest thing on screen: red border, red hard shadow, and a steady tally with the elapsed time on the picture. No pulsing. The launcher icon is the header eye with a red tally dot: a vector for adaptive and themed icons, rasterised with an ink frame for legacy densities.
- A night palette for the gallery: the key beside the master record button puts the desk into the dark - warm charcoal room, cream silkscreen lines, black offset shadows. The red tally is unchanged, because a tally is a lamp and a lamp does not change colour when the lights go out. The desk follows the phone's own dark mode until you press the key; after that your choice is remembered. The shell follows too, so the window behind the page and the system bar icons match the room.

## Shape

- Rust: stream config, clear keys, default-quality validation and persistence, JNI exports.
- Java: thin WebView host, PiP lifecycle, multi-session direct-pull recording service, MPD parsing and segment fetching.
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
5. node --check on the page plus the page checks in android/test/app.test.mjs
6. ./build.sh
7. uploads PBB-apk as a workflow artifact
8. on main only, creates or updates the rolling GitHub release v1.0 with one asset, `PBB-1.0-<build>.apk`

The release tag is fixed at v1.0. The asset name includes the build number so a new download never hits a stale cached APK. The workflow deletes old .apk assets so exactly one APK is offered. App version is always 1.0; versionCode carries the build number.

## Signing

The signing key is **not** in the working tree: `android/release.keystore` is gitignored, and `build.sh` refuses to run without `PBB_KEYSTORE_PASS`. Locally:

~~~bash
PBB_KEYSTORE_PASS=... ./build.sh
~~~

CI decodes the key from the `PBB_KEYSTORE_B64` repository secret into `$RUNNER_TEMP` for the run only, so every release still carries the same signature and updates install over the previous APK. To rotate the key, generate a new keystore, replace both secrets, and bump nothing else - existing installs then need one uninstall, because Android does not accept a different signer as an update.

### The old key is in the git history

The first release committed `android/release.keystore` and the old `build.sh` had the password written in it. Both were removed later, but the file is still in the history: `git show 2858aff:android/release.keystore` hands it over, and the password in that commit was `pbb-release`.

Treat that key as public. Anyone with a clone can sign an APK with the same package name and the same signature, and it installs over the real app with the app's data.

A replacement key was generated on 2026-11-11 and is in place at `android/release.keystore` (gitignored). Finishing the rotation is three steps that only the operator can do:

1. Set the repository secret `PBB_KEYSTORE_B64` to the base64 of that keystore.
2. Set the repository secret `PBB_KEYSTORE_PASS` to its password. Both values are in `pbb-key-rotation.txt` next to this repository, not in the repository itself.
3. Ship a release with it. The old installs need one uninstall first, because Android refuses to update an app signed by a different key.

Until step 3 is done the old key is still valid for anyone who has a clone. If the repository was ever pushed anywhere public, the old key is already burned - do not skip step 3.

Purging the file from the history is optional and does not replace the rotation: `git filter-repo --path android/release.keystore --invert-paths` (or `git filter-branch --index-filter 'git rm --cached --ignore-unmatch android/release.keystore' -- --all`) rewrites every commit id, so it needs a force push and a fresh clone everywhere.

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
- The status strip sits below the grid: a status LED, the message, a recording badge with the number of active recordings and the longest elapsed time, a refresh icon, and the back button when a feed is focused. The LED turns red while any camera records. It is hidden in PiP and fullscreen. In the grid it never hides itself: with no message it reads "tap refresh to reload the stream keys".
- The refresh icon calls Pbb.refreshKeys(); it is the only refresh control in the grid. Fullscreen hides the strip, so a message that arrives there is mirrored by the on-screen indicator. On 4012 the tiles say "keys rotated - tap refresh" and the strip says "keys rotated - tap refresh to reload".
- While the keys are known to be stale the record buttons refuse to start and say so. A recording that is remuxed with the wrong key is a file that will not play, and it would land in Movies/PBB looking like a normal recording.
- A manual refresh always resolves its status text: changed keys re-init the players, unchanged keys report that the source has not published new ones, and a failed fetch reports the connection error. Silent startup fetches never overwrite the status.
- Both playback (Pbb.getConfig()) and recording (startRecordService) read the same effective config, so key rotation needs no APK rebuild or reinstall.

To refresh the checked-in fallback snapshot from the same live API:

~~~bash
./keys/update.sh
~~~

It writes keys/keys.json and rewrites `STREAM_CONFIG_JSON` in src/lib.rs with the same values, so the built-in fallback can not drift from the snapshot. The runtime app reads neither: it fetches the API directly and caches the result in filesDir/streams.json. Commit both files after a rotation, or leave the fallback stale and let the first launch with a network fix it.

## Downloadable recorder plugin

Android 10+ forbids execve() on files in an app home directory, so a downloaded ffmpeg executable cannot run. A downloaded shared library can, as long as it is read-only (Android 14+ rejects writable dynamic code). The plugin therefore is libpbbffmpeg.so, shipped in the APK as base64 in assets/libpbbffmpeg.so.b64, decoded, chmod 0444, then loaded with System.load(). A pinned gist is the fallback for builds made without the asset. Only libc/libm/libdl are needed, and it is 16 KB page-aligned for Android 15+.

Hosted at the URL pinned in RecordService.java, together with its SHA-256. Both must be updated together when the plugin changes.

Rebuild with ./ffmpeg/build-android.sh (FFmpeg n7.1, arm64-v8a, minimal: mov demuxer, mp4 muxer, h264/aac parsers, file protocol; no TLS, libxml2 or DASH demuxer needed because Java does the fetching). Output is ffmpeg/out/libpbbffmpeg.so and .b64. Re-upload the .b64 to the gist, then update PLUGIN_URL and PLUGIN_SHA256.

The plugin is arm64-only. Non-arm64 devices get a clear error instead of a crash. On Android 15+ the dataSync foreground service has a six-hour budget, and `onTimeout` then ends the pull through the normal save path, so the file is published instead of lost.

The bundled copy means recording works offline and does not depend on the gist surviving; the gist is only used by builds that ship without the asset.

A long stall in the archive (the live edge moving past what the server still serves) no longer splices across the hole: the current part is saved and a second file `..._2.mp4` continues. A failed remux keeps the pulled segments in the app cache and says so in the status text instead of deleting the only copy.

ffmpeg/host_test.c plus a host build of the same minimal FFmpeg validates pbb_remux against captured real segments without a device: build host ffmpeg with the same configure flags, compile host_test.c against it, and ffprobe the output. Verified: 360 H.264 frames + 563 AAC frames, clean decode.

Known limit: an in-flight recording keeps the key it started with, so a rotation can produce a garbage file because remuxing copies packets without decoding them. Refresh the feeds and start a new recording after rotation.

DASH tfdt timestamps are absolute (time since the stream began). The remuxer sets AVFMT_AVOID_NEG_TS_MAKE_ZERO so the MP4 starts at 0; otherwise mvhd/tkhd carry a duration of hours or days and only an edit list hides it, which many players ignore. Files recorded before this fix can be repaired without re-encoding:

~~~bash
ffmpeg -i old.mp4 -c copy -movflags +faststart fixed.mp4
~~~

## Multi-camera recording

- MainActivity sends one RECORD_START intent per camera, each carrying that stream's MPD, key, file name and index. RecordService keeps a session per index: worker thread, temp files `pull_video_<index>.mp4` / `pull_audio_<index>.mp4` / `pull_out_<index>.mp4`, stop flag and notification.
- The temp files live in filesDir, not the cache dir: Android may delete the cache when it needs space, and these are the only copy of the recording until the remux publishes it.
- The recorder uses the same quality rules as the page: hd is the tallest variant the feed publishes, sd is the shortest, and auto - which cannot adapt like the player does - takes the tallest variant up to 1280 wide.
- The live edge is found by probing the archive, and the last edge found for a manifest is remembered, so a second recording starts from there instead of walking the whole archive again.
- The video and the audio segment of one step are pulled at the same time, so a segment costs one round trip instead of two.
- ACTION_STOP with a stream index stops that session; without one it stops every session. Each per-camera notification carries its own stop action, and the summary notification carries stop all.
- The service's static STARTED map is the source of truth for the activity. Every transition broadcasts `{active, index, count, elapsed, kind, message}`, and `Pbb.recordingState()` returns the active indices with their elapsed seconds so a rebuilt page can restore the tally lights and clocks.
- Pulls run in parallel. The native remux is inside `REMUX_LOCK` because libavformat is not reentrant across muxers; only the final mux is serialised, not the download.
- Each file is named from its camera, so concurrent saves never collide in Movies/PBB/. One dataSync foreground service covers every session, so the Android 15 six-hour budget is shared rather than multiplied.

## Validation performed

- cargo fmt --check
- cargo clippy --all-targets -- -D warnings
- cargo test
- node --check android/assets/app.js
- the page checks in android/test/app.test.mjs (stubbed DOM and Pbb bridge, no dependencies): picture-in-picture focus, the record button with rotated keys, record all, and the slider levels
- these two run in CI on every push and inside build.sh, so a broken page fails the build
- python3 -m json.tool keys/keys.json
- three-feed layout measured in headless Chromium at phone portrait, phone landscape, tablet and desktop viewports (before the multi-camera redesign; no browser binary was available for a new pass)
- tree-sitter parse of the changed Java/JS/HTML/CSS files: no ERROR nodes
- cargo ndk build for arm64-v8a, armeabi-v7a, x86_64
- javac against android.jar
- d8 dexing
- aapt2 resource packaging
- apksigner verify
- aapt2 dump badging
- No emulator or on-device playback test.
- This workspace has no JDK or Android SDK, so javac, d8, aapt2 and the signer were not re-run for the multi-camera change; the next build.sh run must confirm them.
- The same is true of the audit fixes: the Java changes were read, not compiled. The next build.sh run must confirm them.

## Licenses

- Shaka Player: Apache-2.0, see android/assets/LICENSE-shaka.txt
- DM Sans: SIL Open Font License 1.1, see android/assets/fonts/OFL.txt
- FFmpeg n7.1 (downloaded plugin, LGPL-2.1-or-later build): source at https://github.com/FFmpeg/FFmpeg/tree/n7.1, rebuilt by ffmpeg/build-android.sh. The plugin is distributed separately from the APK.
