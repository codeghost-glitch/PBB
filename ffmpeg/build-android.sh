#!/usr/bin/env bash
# Builds the downloadable recorder plugin. Re-run when ffmpeg is upgraded.
#   ./ffmpeg/build-android.sh
# Output: ffmpeg/out/libpbbffmpeg.so and ffmpeg/out/libpbbffmpeg.so.b64
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out"
WORK="/tmp/pbb-ffmpeg-build"
FFMPEG_VERSION="n7.1"
# Immutable commit from the x264 stable branch; keeps plugin builds reproducible.
X264_VERSION="b35605ace3ddf7c1a5d67a2eb553f034aef41d55"
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/27.2.12479018}"
API=26
TARGET="aarch64-linux-android"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
SYSROOT="$TC/../sysroot"

CC="$TC/${TARGET}${API}-clang"
AR="$TC/llvm-ar"
NM="$TC/llvm-nm"
RANLIB="$TC/llvm-ranlib"
STRIP="$TC/llvm-strip"

for f in "$CC" "$AR" "$STRIP" "$SYSROOT"; do
    [ -e "$f" ] || { echo "missing $f" >&2; exit 1; }
done

mkdir -p "$WORK" "$OUT"
cd "$WORK"
export PKG_CONFIG_PATH="$WORK/x264prefix/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
if [ ! -f "FFmpeg-${FFMPEG_VERSION}/configure" ]; then
    curl -fsSL -o ffmpeg.tar.gz \
        "https://github.com/FFmpeg/FFmpeg/archive/refs/tags/${FFMPEG_VERSION}.tar.gz"
    tar xzf ffmpeg.tar.gz
fi
if [ ! -f "x264-${X264_VERSION}/configure" ]; then
    curl -fsSL -o x264.tar.bz2 \
        "https://code.videolan.org/videolan/x264/-/archive/${X264_VERSION}/x264-${X264_VERSION}.tar.bz2"
    tar xjf x264.tar.bz2
fi
X264_PREFIX="$WORK/x264prefix"
if [ ! -f "$X264_PREFIX/lib/libx264.a" ]; then
    mkdir -p "$X264_PREFIX"
    cd "x264-${X264_VERSION}"
    CC="$CC" AR="$AR" RANLIB="$RANLIB" ./configure \
        --host=aarch64-linux-android --enable-static --enable-pic \
        --disable-cli --disable-asm --prefix="$X264_PREFIX"
    make -j"$(nproc)"
    make install
    cd "$WORK/FFmpeg-${FFMPEG_VERSION}"
fi

cd "FFmpeg-${FFMPEG_VERSION}"

PREFIX="$WORK/prefix"
./configure \
    --prefix="$PREFIX" \
    --target-os=android \
    --arch=aarch64 \
    --cpu=armv8-a \
    --enable-cross-compile \
    --cc="$CC" --ar="$AR" --nm="$NM" --ranlib="$RANLIB" --strip="$STRIP" \
    --sysroot="$SYSROOT" \
    --extra-cflags="-O2 -fPIC -ffunction-sections -fdata-sections -I$X264_PREFIX/include" \
    --extra-ldflags="-Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -L$X264_PREFIX/lib" \
    --disable-everything \
    --disable-programs --disable-doc --disable-debug --disable-autodetect \
    --disable-avdevice --disable-avfilter \
    --disable-swresample --disable-postproc --disable-network \
    --disable-iconv --disable-zlib --disable-bzlib --disable-lzma \
    --disable-sdl2 --disable-asm \
    --enable-gpl --enable-libx264 \
    --enable-decoder=h264 --enable-decoder=aac \
    --enable-encoder=libx264 \
    --enable-swscale \
    --enable-demuxer=mov \
    --enable-muxer=mp4 \
    --enable-parser=h264 --enable-parser=aac \
    --enable-protocol=file \
    --enable-pic --disable-shared --enable-static

make -j"$(nproc)"
make install

"$CC" -O2 -fPIC -I"$PREFIX/include" -c "$HERE/pbb_ffmpeg.c" -o "$WORK/pbb_ffmpeg.o"

"$CC" -shared -o "$OUT/libpbbffmpeg.so" "$WORK/pbb_ffmpeg.o" \
    -Wl,--start-group \
    "$PREFIX/lib/libavformat.a" \
    "$PREFIX/lib/libavcodec.a" \
    "$PREFIX/lib/libavutil.a" \
    "$PREFIX/lib/libswscale.a" \
    "$X264_PREFIX/lib/libx264.a" \
    -Wl,--end-group \
    -lm -ldl \
    -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
    -Wl,-soname,libpbbffmpeg.so

"$STRIP" --strip-unneeded "$OUT/libpbbffmpeg.so"
base64 -w 0 "$OUT/libpbbffmpeg.so" > "$OUT/libpbbffmpeg.so.b64"

# clangd uses this to find jni.h and the generated ffmpeg headers.
mkdir -p "$HERE/include"
cp -R "$PREFIX/include/." "$HERE/include/"
JDK="${JAVA_HOME:-$HOME/.local/jdk17}"
cat > "$HERE/compile_commands.json" <<EOF
[{"directory":"$HERE","command":"clang -I$HERE/include -I$JDK/include -I$JDK/include/linux -fsyntax-only $HERE/pbb_ffmpeg.c","file":"$HERE/pbb_ffmpeg.c"}]
EOF

echo "built: $OUT/libpbbffmpeg.so ($(stat -c%s "$OUT/libpbbffmpeg.so") bytes)"
echo "sha256: $(sha256sum "$OUT/libpbbffmpeg.so" | cut -d' ' -f1)"
"$TC/llvm-readelf" -d "$OUT/libpbbffmpeg.so" | grep NEEDED || true
