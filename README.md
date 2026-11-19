# PBB

An Android app for three PBB live cameras. It plays the feeds together in one screen, lets you switch between three server routes, and records any camera to `Movies/PBB/`. Main and Dining can also be saved as one stacked video.

The app reads fresh stream URLs and ClearKeys from `pbbcollab.live` at startup and on refresh. A rotation on the site reaches the app without a new build.

## What it does

- Three feeds in one portrait screen: one large program feed and two previews.
- Fullscreen and picture-in-picture on any feed. The fullscreen keys sit top-right; the mini feed sits bottom-right.
- A server sheet with main, backup and mirror routes. The choice is stored on the phone.
- HD and SD quality buttons. HD uses the tallest rendition; SD uses the smallest rendition at least 480 lines tall.
- Record one, two or three cameras at once. Each recording gets its own notification and stop action.
- Stitch button: records Main Access and Dining Area, then joins them into one stacked MP4.
- Dark-only, one type system, one hairline, 44 px touch targets.

## Build

Requirements:

- Android SDK platform 35 and build-tools 35.0.0.
- Android NDK 27.2.12479018.
- JDK 17.
- Rust targets `aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android`.
- `cargo-ndk`.
- Node, for the page checks.

~~~bash
./build.sh
~~~

Output: `PBB.apk`.

Environment overrides:

| Variable | Default |
|---|---|
| `VERSION_NAME` | `1.0` |
| `VERSION_CODE` | `1` |
| `ANDROID_SDK_ROOT` | auto-detected |
| `ANDROID_NDK_HOME` | newest NDK under the SDK |
| `JAVA_HOME` | auto-detected |
| `PBB_KEYSTORE` | `android/release.keystore` |
| `PBB_KEY_ALIAS` | `pbb` |
| `PBB_KEYSTORE_PASS` | required |
| `PBB_KEY_PASS` | keystore password |

Local build:

~~~bash
PBB_KEYSTORE_PASS=... ./build.sh
~~~

## Signing

The release key is not in the working tree. `build.sh` fails without a keystore and password.

CI decodes `PBB_KEYSTORE_B64` and `PBB_KEYSTORE_PASS` into `$RUNNER_TEMP` for the run only. That keeps the same signer, so updates install over older builds.

The first release committed the old key, and the old `build.sh` contained its password. Both were removed, but the key is still in git history. Treat it as public. Anyone with a clone can sign an APK that installs over the real app and can read its data.

A replacement key was generated on 2026-11-11. To finish the rotation:

1. Set `PBB_KEYSTORE_B64` to the base64 of that keystore.
2. Set `PBB_KEYSTORE_PASS` to its password.
3. Ship a release. Old installs need one uninstall because Android does not accept a different signer as an update.

The rotation details are in `pbb-key-rotation.txt` outside this repository. Purging the old key from history is optional; it rewrites commit ids and needs a force push and fresh clones.

## Stream source

The app uses the site's own session handshake:

~~~text
POST https://www.pbbcollab.live/api/streams/session
Headers: X-PBB-Client: v3
Body:    { k, v: base64(seed XOR 0x3C) }

Reply:   { nonce, streams: { all, din, ext, main } = { endpoint, token } }
Cookie:  pbb_sid
~~~

Each token is base64 `kid:key` XORed with `0x5A` and the reply nonce. The app reads the three manifests with the session cookie, puts a `<BaseURL>` in each, and inlines them as `data:` URLs. Playback and recording use the same config.

The config is cached in `filesDir/streams.json`. `keys/keys.json` is the checked-in fallback used by Rust when there is no network. Refresh it with:

~~~bash
./keys/update.sh
~~~

### Servers

- `s1`: main route.
- `s2`: backup route through the site's proxy.
- `s3`: `pbb3collablive.xyz` segment mirror with keys from the live handshake.

The app routes all segment requests through `shouldInterceptRequest`. The site proxy needs the site's own Origin; the CDN and the mirror do not get the session cookie. The mirror camera ids are in `CAM_MIRROR`.

## Recording

`RecordService` pulls DASH video and audio segments directly into temporary files, then an FFmpeg plugin decrypts and muxes them into an MP4 in `Movies/PBB/`. There is no screen capture and no consent dialog.

- One worker and one notification per camera. Temp files live in app-private storage until the file is published.
- Video and audio segments are pulled together. Pulls run in parallel; remuxing is serialised because libavformat is not reentrant.
- Stop sets the stop flag, disconnects in-flight reads and interrupts the worker. The tally clears at once and the status says saving. Tapping stop again is safe.
- If MediaStore refuses a file, the app keeps a copy in its own Movies folder and reports that path.
- A failed remux keeps the pulled segments instead of deleting the only copy.
- The recorder uses the live edge from the MPD plus a bounded range scan. It does not walk the archive from zero.
- The plugin is arm64-only, shipped as base64 in the APK and pinned by SHA-256. Non-arm64 devices get a clear error.

The stitch button records cameras 0 and 1, then stacks them into `PBB_stitch_...mp4`. The two originals are deleted only after the joined file is published.

Known limit: a recording that was already running keeps its key. If the broadcaster rotates during that recording, the file may not play. Refresh and start a new recording after a rotation.

## CI and release

`.github/workflows/android.yml` runs on push to `main`, pull requests and manual dispatch.

1. Rust format, clippy and tests.
2. `node --check` and the page checks.
3. `./build.sh`.
4. On `main`, one rolling release `v1.0` with one APK asset named `PBB-1.0-<build>.apk`.

Fork pull requests still run rust and page tests. They skip signing and APK build because repository secrets are not available to forks.

## Checks

Run locally before release:

~~~bash
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo test
node --check android/assets/app.js
node android/test/app.test.mjs
python3 -m json.tool keys/keys.json
./build.sh
~~~

`build.sh` also compiles the Java tests and runs `com.pbb.live.RecorderLogicTest`. It fails if Node is missing.

No emulator or on-device playback test was done in this workspace.

## Licenses

- Shaka Player: Apache-2.0, see `android/assets/LICENSE-shaka.txt`.
- DM Sans: SIL Open Font License 1.1, see `android/assets/fonts/OFL.txt`.
- FFmpeg n7.1: LGPL-2.1-or-later build, source at <https://github.com/FFmpeg/FFmpeg/tree/n7.1>, rebuilt by `ffmpeg/build-android.sh`.
