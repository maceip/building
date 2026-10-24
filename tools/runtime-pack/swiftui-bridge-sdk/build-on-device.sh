#!/usr/bin/env bash
# Rebuild + install the Cupertino SwiftUI bridge on a connected Pixel, then pull
# the built artifacts back into android-aarch64/ so a fresh Mac pack build
# (stage_xtool.py) bakes a version-matched bridge.
#
# The host Mac has no 6.4.1 Android SDK, so the bridge is compiled with the
# device's OWN swiftc (always the same version as the runtime pack). This
# guarantees the compiler/bridge versions can never drift apart.
#
# Usage:  bash tools/runtime-pack/swiftui-bridge-sdk/build-on-device.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SOURCES="$HERE/sources"
OUT="$HERE/android-aarch64"
PKG=ai.building.dev
# Sources are staged where `shell` can write (adb push) and `run-as` can read.
DEV_SRC=/data/local/tmp/bridge-src
REMOTE_WORK=/data/user/0/$PKG/files/bridge-build

command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not found on PATH" >&2; exit 1; }
DEV="$(adb devices -l 2>/dev/null | awk 'NR>1 && $2=="device"{print $1; exit}')"
[ -n "$DEV" ] || { echo "ERROR: no authorized device connected (check USB + USB debugging)" >&2; exit 1; }
echo "Device: $DEV"

[ -d "$SOURCES" ] || { echo "ERROR: $SOURCES missing" >&2; exit 1; }

# 1. push bridge sources to a shell-writable, run-as-readable location
echo "Pushing bridge sources..."
# Repair perms first: a prior run can leave the dir without execute, which blocks
# traversal for rm/push. chmod 755 the dir (owner only needs x on the parent).
adb shell chmod -R 755 "$DEV_SRC" >/dev/null 2>&1 || true
adb shell rm -rf "$DEV_SRC" >/dev/null 2>&1 || true
adb shell mkdir -p "$DEV_SRC" >/dev/null 2>&1 || true
for f in "$SOURCES"/*.swift; do
  adb push "$f" "$DEV_SRC/" >/dev/null 2>&1
done
adb shell chmod -R 755 "$DEV_SRC" >/dev/null 2>&1 || true

# 2. write the remote build+install script
REMOTE_SCRIPT=/data/local/tmp/bridge_remote.sh
cat > /tmp/bridge_remote.sh <<'REMOTE_EOF'
#!/system/bin/sh
set -e
P=$(ls -d /data/user/0/ai.building.dev/files/dr/p/* 2>/dev/null | head -1)
[ -n "$P" ] || { echo "no pack dir"; exit 1; }
PU=$P/usr
FB=/data/user/0/ai.building.dev/files
export PREFIX=$PU
export LD_LIBRARY_PATH=$PU/lib:$PU/lib/aarch64-linux-android:$PU/lib/swift-runtime:$PU/lib/swift/host:$PU/lib/swift/host/compiler:$PU/lib/xtool
export SOURCEKIT_TOOLCHAIN_PATH=$PU SDKROOT=$PU TMPDIR=$P/../tmp
SRC=/data/local/tmp/bridge-src
WORK=$FB/bridge-build
rm -rf "$WORK"
mkdir -p "$WORK"
cd "$WORK"

echo "=== compiling SwiftUI module (device swiftc) ==="
mkdir -p "$WORK/SwiftUI.swiftmodule"
"$PU/bin/swiftc" -emit-object -emit-module -emit-module-path "$WORK/SwiftUI.swiftmodule/aarch64-unknown-linux-android28.swiftmodule" -module-name SwiftUI -target aarch64-unknown-linux-android28 \
  -sdk "$PU" -Xcc -isystem -Xcc "$PU/include/aarch64-linux-android" -parse-as-library \
  -module-cache-path "$WORK/mc" "$SRC"/*.swift
"$PU/bin/aarch64-linux-android-ar" rcs libSwiftUI.a *.o
# Mirror both triples (some toolchain lookups use the plain triple).
cp "$WORK/SwiftUI.swiftmodule/aarch64-unknown-linux-android28.swiftmodule" "$WORK/SwiftUI.swiftmodule/aarch64-unknown-linux-android.swiftmodule"

echo "=== generating CoreGraphics/UIKit/Combine re-export stubs ==="
for m in CoreGraphics UIKit Combine; do
  mkdir -p "$WORK/$m.swiftmodule"
  printf '@_exported import SwiftUI\n' > "$m.swift"
  "$PU/bin/swiftc" -emit-module -emit-module-path "$WORK/$m.swiftmodule/aarch64-unknown-linux-android28.swiftmodule" -module-name "$m" -target aarch64-unknown-linux-android28 \
    -sdk "$PU" -Xcc -isystem -Xcc "$PU/include/aarch64-linux-android" -parse-as-library \
    -module-cache-path "$WORK/mc_$m" "$m.swift"
  cp "$WORK/$m.swiftmodule/aarch64-unknown-linux-android28.swiftmodule" "$WORK/$m.swiftmodule/aarch64-unknown-linux-android.swiftmodule"
done

echo "=== installing into live runtime pack ==="
mkdir -p "$PU/lib/swift/android/aarch64" \
         "$PU/lib/swift/android/SwiftUI.swiftmodule" \
         "$PU/lib/swift/android/CoreGraphics.swiftmodule" \
         "$PU/lib/swift/android/UIKit.swiftmodule" \
         "$PU/lib/swift/android/Combine.swiftmodule"
cp libSwiftUI.a "$PU/lib/swift/android/libSwiftUI.a"
cp libSwiftUI.a "$PU/lib/swift/android/aarch64/libSwiftUI.a"
cp SwiftUI.swiftmodule/aarch64-unknown-linux-android.swiftmodule "$PU/lib/swift/android/SwiftUI.swiftmodule/aarch64-unknown-linux-android.swiftmodule"
cp SwiftUI.swiftmodule/aarch64-unknown-linux-android.swiftmodule "$PU/lib/swift/android/SwiftUI.swiftmodule/aarch64-unknown-linux-android28.swiftmodule"
for m in CoreGraphics UIKit Combine; do
  cp "$m.swiftmodule/aarch64-unknown-linux-android.swiftmodule" "$PU/lib/swift/android/$m.swiftmodule/aarch64-unknown-linux-android.swiftmodule"
  cp "$m.swiftmodule/aarch64-unknown-linux-android.swiftmodule" "$PU/lib/swift/android/$m.swiftmodule/aarch64-unknown-linux-android28.swiftmodule"
done

echo "=== installing into developer-runtime cache (survives app restart) ==="
CACHE=$P/developer-runtime/swiftui-sdk/android-aarch64
mkdir -p "$CACHE/SwiftUI.swiftmodule" "$CACHE/CoreGraphics.swiftmodule" "$CACHE/UIKit.swiftmodule" "$CACHE/Combine.swiftmodule"
cp libSwiftUI.a "$CACHE/"
cp SwiftUI.swiftmodule/aarch64-unknown-linux-android.swiftmodule "$CACHE/SwiftUI.swiftmodule/aarch64-unknown-linux-android.swiftmodule"
cp SwiftUI.swiftmodule/aarch64-unknown-linux-android.swiftmodule "$CACHE/SwiftUI.swiftmodule/aarch64-unknown-linux-android28.swiftmodule"
for m in CoreGraphics UIKit Combine; do
  cp "$m.swiftmodule/aarch64-unknown-linux-android.swiftmodule" "$CACHE/$m.swiftmodule/aarch64-unknown-linux-android.swiftmodule"
  cp "$m.swiftmodule/aarch64-unknown-linux-android.swiftmodule" "$CACHE/$m.swiftmodule/aarch64-unknown-linux-android28.swiftmodule"
done
echo "6.4.1" > "$CACHE/VERSION"
echo "INSTALL_OK"
REMOTE_EOF

adb push /tmp/bridge_remote.sh "$REMOTE_SCRIPT" >/dev/null 2>&1
adb shell chmod 644 "$REMOTE_SCRIPT" >/dev/null 2>&1
echo "Running on-device build + install..."
adb shell run-as "$PKG" sh "$REMOTE_SCRIPT" 2>&1

# 3. pull built artifacts back to the Mac via exec-out (run-as streams app-private
#    files to stdout; avoids the directory-traversal denial of `adb pull`).
echo "Pulling built artifacts to $OUT ..."
rm -rf "$OUT"
mkdir -p "$OUT/SwiftUI.swiftmodule" "$OUT/CoreGraphics.swiftmodule" "$OUT/UIKit.swiftmodule" "$OUT/Combine.swiftmodule"
PACK_ID=$(adb shell run-as "$PKG" ls /data/user/0/ai.building.dev/files/dr/p 2>/dev/null | head -1 | tr -d '\r')
PU=/data/user/0/ai.building.dev/files/dr/p/$PACK_ID/usr
pull() { # pull <app-path> <local-path>
  adb exec-out run-as "$PKG" cat "$1" > "$2"
}
pull "$PU/lib/swift/android/libSwiftUI.a" "$OUT/libSwiftUI.a"
pull "$PU/lib/swift/android/SwiftUI.swiftmodule/aarch64-unknown-linux-android.swiftmodule" "$OUT/SwiftUI.swiftmodule/aarch64-unknown-linux-android.swiftmodule"
pull "$PU/lib/swift/android/SwiftUI.swiftmodule/aarch64-unknown-linux-android28.swiftmodule" "$OUT/SwiftUI.swiftmodule/aarch64-unknown-linux-android28.swiftmodule"
for m in CoreGraphics UIKit Combine; do
  pull "$PU/lib/swift/android/$m.swiftmodule/aarch64-unknown-linux-android.swiftmodule" "$OUT/$m.swiftmodule/aarch64-unknown-linux-android.swiftmodule"
  pull "$PU/lib/swift/android/$m.swiftmodule/aarch64-unknown-linux-android28.swiftmodule" "$OUT/$m.swiftmodule/aarch64-unknown-linux-android28.swiftmodule"
done
cp "$HERE/VERSION" "$OUT/VERSION"
echo "Done. Artifacts in: $OUT"
find "$OUT" -type f -exec ls -la {} \; 2>/dev/null | awk '{print $5, $9}'
