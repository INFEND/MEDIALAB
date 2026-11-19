#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
OUT="$ROOT/.gradle/medialab-video-ultra"
ASSETS="$OUT/assets"
PACK="$OUT/video-ultra-arm64-v1.so"
URL="https://github.com/INFEND/MEDIALAB/releases/download/v1.3.68/video-ultra-arm64-v1.so"
mkdir -p "$ASSETS"
VERSION="$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' app/build.gradle | head -1)"
if [[ "$VERSION" == 1.3.68 ]]; then
  mkdir -p "$OUT/src"
  SVT="$OUT/src/svt-av1"
  FF="$OUT/src/ffmpeg"
  PREFIX="$OUT/prefix"
  fetch() {
    local dir="$1" url="$2" rev="$3"
    if [[ ! -d "$dir/.git" ]]; then git init -q "$dir"; git -C "$dir" remote add origin "$url"; fi
    git -C "$dir" fetch --depth=1 origin "$rev"
    git -C "$dir" checkout --detach FETCH_HEAD
    test "$(git -C "$dir" rev-parse HEAD)" = "$rev"
  }
  fetch "$SVT" https://github.com/AOMediaCodec/SVT-AV1.git b33dcc56cc64fcb3b3569094af8ab1d0d81ab4c1
  fetch "$FF" https://github.com/FFmpeg/FFmpeg.git 38b88335f99e76ed89ff3c93f877fdefce736c13
  NDK="$ANDROID_SDK_ROOT/ndk/27.2.12479018"
  TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
  CC="$TC/bin/aarch64-linux-android29-clang"
  CXX="$TC/bin/aarch64-linux-android29-clang++"
  cmake -S "$SVT" -B "$OUT/svt-build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_INSTALL_LIBDIR=lib \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DBUILD_SHARED_LIBS=OFF -DBUILD_APPS=OFF \
    -DBUILD_TESTING=OFF -DSVT_AV1_LTO=OFF -DEXCLUDE_HASH=ON
  cmake --build "$OUT/svt-build" --parallel 4
  cmake --install "$OUT/svt-build"
  export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
  unset PKG_CONFIG_PATH
  mkdir -p "$OUT/ff-build"
  (
    cd "$OUT/ff-build"
    "$FF/configure" --prefix="$PREFIX" --target-os=android --arch=aarch64 \
      --enable-cross-compile --cc="$CC" --cxx="$CXX" --ar="$TC/bin/llvm-ar" \
      --nm="$TC/bin/llvm-nm" --ranlib="$TC/bin/llvm-ranlib" --strip="$TC/bin/llvm-strip" \
      --sysroot="$TC/sysroot" --pkg-config=pkg-config --pkg-config-flags=--static \
      --extra-cflags="-I$PREFIX/include/svt-av1 -fPIC" --extra-ldflags="-L$PREFIX/lib -Wl,-z,max-page-size=16384" \
      --disable-autodetect --disable-network --disable-programs --disable-doc \
      --disable-avdevice --disable-avfilter --disable-swscale --disable-swresample \
      --disable-everything --enable-static --disable-shared --enable-pic \
      --enable-avcodec --enable-avformat --enable-avutil --enable-protocol=file \
      --enable-demuxer=mov,matroska,avi,mpegts,mpegvideo,flv,asf,ogg \
      --enable-muxer=matroska \
      --enable-decoder=h264,hevc,av1,vp8,vp9,mpeg4,mpeg2video,ffv1,vc1,wmv3,mjpeg \
      --enable-parser=h264,hevc,av1,vp8,vp9,mpeg4video,mpegvideo,mjpeg,vc1,aac,ac3,opus \
      --enable-libsvtav1 --enable-encoder=libsvtav1
    make -j4
    make install
  )
  "$CXX" -std=c++17 -O2 -fPIC -I"$PREFIX/include" -c native/video-ultra/video_ultra.cpp -o "$OUT/video_ultra.o"
  "$CXX" -shared -Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL -Wl,--no-undefined \
    -static-libstdc++ -o "$PACK" "$OUT/video_ultra.o" \
    -Wl,--start-group "$PREFIX/lib/libavformat.a" "$PREFIX/lib/libavcodec.a" "$PREFIX/lib/libavutil.a" "$PREFIX/lib/libSvtAv1Enc.a" -Wl,--end-group \
    -lm -ldl -llog -latomic
  "$TC/bin/llvm-strip" --strip-unneeded "$PACK"
  # Corresponding source and relinkable bridge object for the LGPL native module.
  mkdir -p "$OUT/relink"
  cp native/video-ultra/video_ultra.cpp scripts/build_video_ultra.sh "$OUT/video_ultra.o" "$OUT/relink/"
  cp "$FF/COPYING.LGPLv2.1" "$OUT/relink/FFmpeg-COPYING.LGPLv2.1"
  cp "$SVT/LICENSE" "$OUT/relink/SVT-LICENSE"
  cp "$SVT/PATENTS" "$OUT/relink/SVT-PATENTS"
  git -C "$FF" archive --format=tar.gz --prefix=ffmpeg/ HEAD > "$OUT/relink/ffmpeg-source.tar.gz"
  git -C "$SVT" archive --format=tar.gz --prefix=svt-av1/ HEAD > "$OUT/relink/svt-source.tar.gz"
  cp "$NDK/NOTICE" "$OUT/relink/Android-NDK-NOTICE"
  cp "$OUT/ff-build/ffbuild/config.mak" "$OUT/relink/ffmpeg-config.mak"
  tar -C "$OUT/relink" -czf "$OUT/video-ultra-source-relink.tar.gz" .
else
  curl --fail --location --retry 4 --output "$PACK" "$URL"
fi
python3 - "$PACK" "$ASSETS/video-ultra.json" "$URL" <<'PY'
import hashlib,json,pathlib,subprocess,sys,re
p,manifest=map(pathlib.Path,sys.argv[1:3])
b=p.read_bytes()
assert 0<len(b)<64*1024*1024
assert b[:4]==b'\x7fELF' and b[4]==2 and int.from_bytes(b[18:20],'little')==183
dyn=subprocess.check_output(['readelf','-d',str(p)],text=True)
needed=re.findall(r'Shared library: \[(.*?)\]',dyn)
assert set(needed)<=set('libandroid.so liblog.so libc.so libm.so libdl.so'.split()),needed
symbols=subprocess.check_output(['readelf','--dyn-syms','--wide',str(p)],text=True)
for name in ('nativeVersion','nativeEncode'):
    assert 'Java_com_xarkon_medialab_VideoUltraPack_'+name in symbols,name
assert 'avcodec_open2' not in symbols,'FFmpeg symbols must not conflict with base runtime'
for line in subprocess.check_output(['readelf','-lW',str(p)],text=True).splitlines():
    if line.strip().startswith('LOAD '):assert int(line.split()[-1],16)>=16384,line
manifest.write_text(json.dumps(dict(api=1,abi='arm64-v8a',bytes=len(b),sha256=hashlib.sha256(b).hexdigest(),url=sys.argv[3]))+'\n')
print('Verified VIDEO ULTRA:',len(b),'bytes; dependencies:',needed)
PY
