#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
OUT="$ROOT/.gradle/medialab-photo-extra"
ASSETS="$OUT/assets"
PACK="$OUT/photo-extra-arm64-v1.so"
LICENSES="$OUT/photo-extra-licenses.txt"
URL="https://github.com/INFEND/MEDIALAB/releases/download/v1.3.67/photo-extra-arm64-v1.so"
mkdir -p "$ASSETS"
VERSION="$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' app/build.gradle | head -1)"
rm -f "$OUT/publish-with-1.3.68"
if [[ "$VERSION" != 1.3.67 ]] && curl --fail --location --retry 2 --connect-timeout 15 --output "$PACK" "$URL"; then
  : # Existing immutable asset keeps its URL and hash across OTA.
elif [[ "$VERSION" == 1.3.67 || "$VERSION" == 1.3.68 ]]; then
  if [[ "$VERSION" == 1.3.68 ]]; then
    URL="${URL/v1.3.67/v1.3.68}"
    touch "$OUT/publish-with-1.3.68"
  fi
  mkdir -p "$OUT/src"
  JXL="$OUT/src/libjxl"
  if [[ ! -d "$JXL/.git" ]]; then git init -q "$JXL"; git -C "$JXL" remote add origin https://github.com/libjxl/libjxl.git; fi
  REV=794a5dcf0d54f9f0b20d288a12e87afb91d20dfc
  git -C "$JXL" fetch --depth=1 origin "$REV"
  git -C "$JXL" checkout --detach FETCH_HEAD
  test "$(git -C "$JXL" rev-parse HEAD)" = "$REV"
  git -C "$JXL" submodule update --init --recursive --depth=1
  NDK="$ANDROID_SDK_ROOT/ndk/27.2.12479018"
  TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
  export CC_aarch64_linux_android="$TOOLCHAIN/aarch64-linux-android29-clang"
  export CXX_aarch64_linux_android="$TOOLCHAIN/aarch64-linux-android29-clang++"
  export AR_aarch64_linux_android="$TOOLCHAIN/llvm-ar"
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC_aarch64_linux_android"
  export CARGO_TARGET_DIR="$OUT/cargo"
  # Exact crate version; retain the resolved Cargo.lock with the release sources.
  rustup toolchain install 1.94.0 --profile minimal --target aarch64-linux-android
  cargo +1.94.0 generate-lockfile --manifest-path native/photo-extra/oxipng/Cargo.toml
  cargo +1.94.0 build --locked --release --target aarch64-linux-android --manifest-path native/photo-extra/oxipng/Cargo.toml
  cp native/photo-extra/oxipng/Cargo.lock "$OUT/photo-extra-Cargo.lock"
  cmake -S native/photo-extra -B "$OUT/build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DJXL_SOURCE="$JXL" -DOXI_LIBRARY="$OUT/cargo/aarch64-linux-android/release/libmedialab_oxipng.a"
  cmake --build "$OUT/build" --target medialab_photo_extra --parallel 4
  cp "$OUT/build/libmedialab_photo_extra.so" "$PACK"
  "$TOOLCHAIN/llvm-strip" --strip-unneeded "$PACK"
  {
    printf 'MediaLAB PHOTO EXTRA v1\nPinned libjxl: %s\nOxiPNG: 10.2.1 (resolved versions in photo-extra-Cargo.lock)\n' "$REV"
    printf '\n--- libjxl ---\n'; cat "$JXL/LICENSE"
    for dep in highway brotli skcms; do
      printf '\n--- %s ---\n' "$dep"; cat "$JXL/third_party/$dep/LICENSE"
    done
    printf '\n--- Android NDK libc++ ---\n'; cat "$NDK/NOTICE"
    python3 - "$HOME/.cargo/registry/src" "$OUT/photo-extra-Cargo.lock" <<'PY'
import pathlib,sys,tomllib
root=pathlib.Path(sys.argv[1]); lock=tomllib.loads(pathlib.Path(sys.argv[2]).read_text())
for package in lock["package"]:
    if "source" not in package:continue
    name=package["name"]+"-"+package["version"]
    matches=list(root.glob("*/"+name))
    if not matches:continue
    print("\n--- "+name+" ---")
    for f in sorted(matches[0].iterdir()):
        if f.is_file() and f.name.lower().startswith(("license","copying","notice")):
            print(f.read_text(errors="replace"))
PY
  } > "$LICENSES"
else
  URL="${URL/v1.3.67/v1.3.68}"
  curl --fail --location --retry 4 --output "$PACK" "$URL"
fi
python3 - "$PACK" "$ASSETS/photo-extra.json" "$URL" <<'PY'
import hashlib,json,pathlib,subprocess,sys,re
p,manifest=map(pathlib.Path,sys.argv[1:3])
b=p.read_bytes()
assert 0<len(b)<32*1024*1024
assert b[:4]==b'\x7fELF' and b[4]==2 and int.from_bytes(b[18:20],'little')==183
dyn=subprocess.check_output(['readelf','-d',str(p)],text=True)
needed=re.findall(r'Shared library: \[(.*?)\]',dyn)
assert set(needed)<=set('libandroid.so libjnigraphics.so liblog.so libc.so libm.so libdl.so'.split()),needed
symbols=subprocess.check_output(['readelf','--dyn-syms','--wide',str(p)],text=True)
for name in ('nativeVersion','nativeOptimizePng','nativeEncodeJxl','nativeDecodeJxl'):
    assert 'Java_com_xarkon_medialab_PhotoExtraPack_'+name in symbols,name
for line in subprocess.check_output(['readelf','-lW',str(p)],text=True).splitlines():
    if line.strip().startswith('LOAD '):assert int(line.split()[-1],16)>=16384,line
manifest.write_text(json.dumps(dict(api=1,abi='arm64-v8a',bytes=len(b),sha256=hashlib.sha256(b).hexdigest(),url=sys.argv[3]))+'\n')
print('Verified PHOTO EXTRA:',len(b),'bytes; dependencies:',needed)
PY
