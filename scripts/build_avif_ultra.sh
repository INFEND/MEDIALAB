#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
OUT="$ROOT/.gradle/medialab-avif-ultra"
ASSETS="$OUT/assets"
PACK="$OUT/avif-ultra-arm64-v1.so"
URL="https://github.com/INFEND/MEDIALAB/releases/download/v1.3.69/avif-ultra-arm64-v1.so"
mkdir -p "$ASSETS"
VERSION="$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' app/build.gradle | head -1)"
rm -f "$OUT/publish-with-current"
if curl --fail --location --retry 1 --connect-timeout 15 --output "$PACK" "$URL"; then
  echo "Using published AVIF ULTRA module"
else
  echo "Published AVIF ULTRA module missing; rebuilding pinned source for v$VERSION"
  URL="https://github.com/INFEND/MEDIALAB/releases/download/v$VERSION/avif-ultra-arm64-v1.so"
  touch "$OUT/publish-with-current"
  AVIF="$OUT/src/libavif"
  mkdir -p "$OUT/src"
  if [[ ! -d "$AVIF/.git" ]]; then git init -q "$AVIF"; git -C "$AVIF" remote add origin https://github.com/AOMediaCodec/libavif.git; fi
  REV=1aadfad932c98c069a1204261b1856f81f3bc199
  git -C "$AVIF" fetch --depth=1 origin "$REV"
  git -C "$AVIF" checkout --detach FETCH_HEAD
  test "$(git -C "$AVIF" rev-parse HEAD)" = "$REV"
  AOM="$AVIF/ext/aom"
  if [[ ! -d "$AOM/.git" ]]; then git clone --branch v3.12.1 --depth 1 https://aomedia.googlesource.com/aom "$AOM"; fi
  NDK="$ANDROID_SDK_ROOT/ndk/27.2.12479018"
  cmake -S "$AOM" -B "$AOM/build.libavif" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DCONFIG_PIC=1 -DAOM_TARGET_CPU=arm64 \
    -DENABLE_DOCS=0 -DENABLE_EXAMPLES=0 -DENABLE_TESTDATA=0 -DENABLE_TESTS=0 -DENABLE_TOOLS=0
  cmake --build "$AOM/build.libavif" --parallel 4
  cmake -S native/avif-ultra -B "$OUT/build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DAVIF_SOURCE="$AVIF"
  cmake --build "$OUT/build" --target medialab_avif_ultra --parallel 4
  cp "$OUT/build/libmedialab_avif_ultra.so" "$PACK"
  "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$PACK"
  {
    printf 'MediaLAB AVIF ULTRA v1\nlibavif revision %s\nlibaom revision ' "$REV"
    git -C "$AOM" rev-parse HEAD
    printf '\n--- libavif ---\n'; cat "$AVIF/LICENSE"
    printf '\n--- libaom ---\n'; cat "$AOM/LICENSE" "$AOM/PATENTS"
    printf '\n--- Android NDK ---\n'; cat "$NDK/NOTICE"
  } > "$OUT/avif-ultra-licenses.txt"
fi
python3 - "$PACK" "$ASSETS/avif-ultra.json" "$URL" <<'PY'
import hashlib,json,pathlib,subprocess,sys,re
p,manifest=map(pathlib.Path,sys.argv[1:3])
b=p.read_bytes()
assert 0<len(b)<32*1024*1024
assert b[:4]==b'\x7fELF' and b[4]==2 and int.from_bytes(b[18:20],'little')==183
dyn=subprocess.check_output(['readelf','-d',str(p)],text=True)
needed=re.findall(r'Shared library: \[(.*?)\]',dyn)
assert set(needed)<=set('libandroid.so libjnigraphics.so liblog.so libc.so libm.so libdl.so'.split()),needed
symbols=subprocess.check_output(['readelf','--dyn-syms','--wide',str(p)],text=True)
for name in ('nativeVersion','nativeEncode'):
    assert 'Java_com_xarkon_medialab_AvifUltraPack_'+name in symbols,name
for line in subprocess.check_output(['readelf','-lW',str(p)],text=True).splitlines():
    if line.strip().startswith('LOAD '):assert int(line.split()[-1],16)>=16384,line
manifest.write_text(json.dumps(dict(api=1,abi='arm64-v8a',bytes=len(b),sha256=hashlib.sha256(b).hexdigest(),url=sys.argv[3]))+'\n')
print('Verified AVIF ULTRA:',len(b),'bytes; dependencies:',needed)
PY
