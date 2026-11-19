#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/android/os" "$TMP/com/xarkon/medialab"
cat > "$TMP/android/os/SystemClock.java" <<'EOF'
package android.os;
public class SystemClock { public static long elapsedRealtime(){return System.nanoTime()/1000000L;} }
EOF
cat > "$TMP/com/xarkon/medialab/ConvertEngine.java" <<'EOF'
package com.xarkon.medialab;
class ConvertEngine {static String humanBytes(long n){return Long.toString(n);}}
EOF
javac -d "$TMP" "$TMP/android/os/SystemClock.java" "$TMP/com/xarkon/medialab/ConvertEngine.java" \
 app/src/main/java/com/xarkon/medialab/ResumableModuleDownload.java scripts/TestResumableModuleDownload.java
java -cp "$TMP" com.xarkon.medialab.TestResumableModuleDownload
