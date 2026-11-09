#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"

VERSION_NAME="${VERSION_NAME:-1.0}"
VERSION_CODE="${VERSION_CODE:-1}"

detect_sdk() {
  if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "${ANDROID_SDK_ROOT}" ]; then
    printf '%s' "${ANDROID_SDK_ROOT}"
    return
  fi
  if [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}" ]; then
    printf '%s' "${ANDROID_HOME}"
    return
  fi
  for candidate in /usr/local/lib/android/sdk /home/rey/Android/Sdk "$HOME/Android/Sdk"; do
    if [ -d "$candidate" ]; then
      printf '%s' "$candidate"
      return
    fi
  done
  printf ''
}

detect_ndk() {
  if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "${ANDROID_NDK_HOME}" ]; then
    printf '%s' "${ANDROID_NDK_HOME}"
    return
  fi
  if [ -n "${ANDROID_NDK_ROOT:-}" ] && [ -d "${ANDROID_NDK_ROOT}" ]; then
    printf '%s' "${ANDROID_NDK_ROOT}"
    return
  fi
  if [ -d "$ANDROID_SDK_ROOT/ndk" ]; then
    local newest
    newest="$(ls -1d "$ANDROID_SDK_ROOT"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
    if [ -n "$newest" ] && [ -d "$newest" ]; then
      printf '%s' "$newest"
      return
    fi
  fi
  printf ''
}

detect_build_tools() {
  ls -1d "$ANDROID_SDK_ROOT"/build-tools/* 2>/dev/null | sort -V | tail -1 || true
}

detect_platform_jar() {
  local newest
  newest="$(ls -1d "$ANDROID_SDK_ROOT"/platforms/android-* 2>/dev/null | sort -V | tail -1 || true)"
  if [ -n "$newest" ] && [ -f "$newest/android.jar" ]; then
    printf '%s' "$newest/android.jar"
  fi
}

ANDROID_SDK_ROOT="$(detect_sdk)"
if [ -z "$ANDROID_SDK_ROOT" ]; then
  echo "no Android SDK found; set ANDROID_SDK_ROOT" >&2
  exit 1
fi
export ANDROID_SDK_ROOT

ANDROID_NDK_HOME="$(detect_ndk)"
if [ -z "$ANDROID_NDK_HOME" ]; then
  echo "no Android NDK found; set ANDROID_NDK_HOME" >&2
  exit 1
fi
export ANDROID_NDK_HOME

if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in /home/rey/.local/jdk17 "${JAVA_HOME_17_X64:-}"; do
    if [ -n "$candidate" ] && [ -d "$candidate" ]; then
      JAVA_HOME="$candidate"
      break
    fi
  done
fi
if [ -n "${JAVA_HOME:-}" ]; then
  export JAVA_HOME
fi

BUILD_TOOLS="$(detect_build_tools)"
if [ -z "$BUILD_TOOLS" ] || [ ! -x "$BUILD_TOOLS/aapt2" ]; then
  echo "no usable build-tools found under $ANDROID_SDK_ROOT/build-tools" >&2
  exit 1
fi

PLATFORM="$(detect_platform_jar)"
if [ -z "$PLATFORM" ]; then
  echo "no android.jar found under $ANDROID_SDK_ROOT/platforms" >&2
  exit 1
fi

export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$BUILD_TOOLS:$ANDROID_SDK_ROOT/platform-tools:$PATH"

OUT="$ROOT/build"
APK_LIB="$OUT/apk/lib"
KEYSTORE="${PBB_KEYSTORE:-$ROOT/android/release.keystore}"
KEY_ALIAS="${PBB_KEY_ALIAS:-pbb}"
STORE_PASS="${PBB_KEYSTORE_PASS:-pbb-release}"
KEY_PASS="${PBB_KEY_PASS:-$STORE_PASS}"

echo "sdk:          $ANDROID_SDK_ROOT"
echo "ndk:          $ANDROID_NDK_HOME"
echo "build-tools:  $BUILD_TOOLS"
echo "platform:     $PLATFORM"
echo "version:      $VERSION_NAME ($VERSION_CODE)"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/apk" "$APK_LIB"

if [ ! -f "$KEYSTORE" ]; then
  echo "missing keystore: $KEYSTORE" >&2
  exit 1
fi

echo "== rust android libraries =="
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o "$APK_LIB" build --release --lib

echo "== resources =="
"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/android/res" -o "$OUT/res.zip"
"$BUILD_TOOLS/aapt2" link -o "$OUT/base.apk" -I "$PLATFORM" \
  --manifest "$ROOT/android/AndroidManifest.xml" \
  -A "$ROOT/android/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 26 --target-sdk-version 35 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --auto-add-overlay "$OUT/res.zip"

echo "== java =="
find "$ROOT/android/java" -name '*.java' > "$OUT/sources.txt"
find "$OUT/gen" -name 'R.java' >> "$OUT/sources.txt"
"$JAVA_HOME/bin/javac" -source 8 -target 8 -encoding UTF-8 -Xlint:-options \
  -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== dex =="
find "$OUT/classes" -name '*.class' -print0 |
  xargs -0 -r "$BUILD_TOOLS/d8" --lib "$PLATFORM" --min-api 26 --output "$OUT/dex"

echo "== package =="
cp "$OUT/base.apk" "$OUT/apk/unsigned.apk"
cp "$OUT/dex/classes.dex" "$OUT/apk/classes.dex"
( cd "$OUT/apk"; zip -q -j unsigned.apk classes.dex; zip -q -r unsigned.apk lib; )
"$BUILD_TOOLS/zipalign" -f -p 4 "$OUT/apk/unsigned.apk" "$OUT/apk/aligned.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
  --out "$ROOT/PBB.apk" "$OUT/apk/aligned.apk"

echo "== verify =="
"$BUILD_TOOLS/apksigner" verify --verbose "$ROOT/PBB.apk"
"$BUILD_TOOLS/aapt2" dump badging "$ROOT/PBB.apk" | head -8 || true
echo "built: $ROOT/PBB.apk"
