#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
OUT="$ROOT/.gradle/medialab-photo-ultra"
ASSETS="$OUT/assets"
PACK="$OUT/photo-ultra-arm64-v1.so"
LICENSES="$OUT/photo-ultra-licenses.txt"
URL="https://github.com/INFEND/MEDIALAB/releases/download/v1.3.67/photo-ultra-arm64-v1.so"
mkdir -p "$ASSETS"
VERSION="$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' app/build.gradle | head -1)"
rm -f "$OUT/publish-with-current"
FOUND=0
for tag in 1.3.67 1.3.68 1.3.69; do
  if [[ "$tag" == "$VERSION" ]]; then continue; fi
  CANDIDATE="https://github.com/INFEND/MEDIALAB/releases/download/v$tag/photo-ultra-arm64-v1.so"
  if curl --fail --location --retry 1 --connect-timeout 15 --output "$PACK" "$CANDIDATE"; then
    URL="$CANDIDATE"; FOUND=1; break
  fi
done
if [[ "$FOUND" == 0 ]]; then
  if [[ "$VERSION" != "1.3.67" && "$VERSION" != "1.3.68" && "$VERSION" != "1.3.69" ]]; then
    echo "Missing published photo-ultra package; add a new approved release location before rebuilding"; exit 1
  fi
  URL="https://github.com/INFEND/MEDIALAB/releases/download/v$VERSION/photo-ultra-arm64-v1.so"
  touch "$OUT/publish-with-current"
  mkdir -p "$OUT/src"
  fetch() {
    local dir="$1" url="$2" rev="$3"
    if [[ ! -d "$dir/.git" ]]; then git init -q "$dir"; git -C "$dir" remote add origin "$url"; fi
    git -C "$dir" fetch --depth=1 origin "$rev"
    git -C "$dir" checkout --detach FETCH_HEAD
    test "$(git -C "$dir" rev-parse HEAD)" = "$rev"
  }
  JPEGLI="$OUT/src/jpegli"
  WEBP="$OUT/src/libwebp"
  fetch "$JPEGLI" https://github.com/google/jpegli.git c7d342c25d3587ac0110c915989ad94f414b6c8d
  git -C "$JPEGLI" submodule update --init --recursive --depth=1
  fetch "$WEBP" https://github.com/webmproject/libwebp.git 4fa21912338357f89e4fd51cf2368325b59e9bd9
  NDK="$ANDROID_SDK_ROOT/ndk/27.2.12479018"
  cmake -S native/photo-ultra -B "$OUT/build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DJPEGLI_SOURCE="$JPEGLI" -DWEBP_SOURCE="$WEBP"
  cmake --build "$OUT/build" --target medialab_photo_ultra --parallel 4
  cp "$OUT/build/libmedialab_photo_ultra.so" "$PACK"
  "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$PACK"
  {
    printf 'MediaLAB PHOTO ULTRA native module v1\nPinned sources:\n'
    printf 'https://github.com/google/jpegli/tree/c7d342c25d3587ac0110c915989ad94f414b6c8d\n'
    printf 'https://github.com/webmproject/libwebp/tree/4fa21912338357f89e4fd51cf2368325b59e9bd9\n'
    printf '\n--- JPEGli ---\n'; cat "$JPEGLI/LICENSE"
    printf '\n--- Highway ---\n'; cat "$JPEGLI/third_party/highway/LICENSE"
    printf '\n--- libwebp ---\n'; cat "$WEBP/COPYING" "$WEBP/PATENTS"
    printf '\n--- skcms ---\n'; cat "$JPEGLI/third_party/skcms/LICENSE"
    printf '\n--- libjpeg-turbo headers ---\n'; cat "$JPEGLI/third_party/libjpeg-turbo/LICENSE.md"
    printf '\n--- Android NDK libc++ ---\n'; cat "$NDK/NOTICE"
  } > "$LICENSES"
fi
python3 - "$PACK" "$ASSETS/photo-ultra.json" "$URL" <<'PY'
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
    assert 'Java_com_xarkon_medialab_PhotoUltraPack_'+name in symbols,name
headers=subprocess.check_output(['readelf','-lW',str(p)],text=True)
for line in headers.splitlines():
    if line.strip().startswith('LOAD '):
        assert int(line.split()[-1],16)>=16384,line
manifest.write_text(json.dumps(dict(api=1,abi='arm64-v8a',bytes=len(b),sha256=hashlib.sha256(b).hexdigest(),url=sys.argv[3]))+'\n')
print('Verified PHOTO ULTRA native module:',len(b),'bytes; system dependencies:',needed)
PY
