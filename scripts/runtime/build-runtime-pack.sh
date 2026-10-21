#!/usr/bin/env bash
# Builds the hybrid Bionic + Musl nativeLibraryDir runtime pack for Android 15+ (W^X SELinux compliant).
#
# Combines:
#   1. Termux bionic Node.js 24 (`libnode.so`) + dependencies (`libcares`, `libcrypto`, `libssl`, `libsqlite3`,
#      `libicui18n`, `libicuuc`, `libicudata`, `libc++_shared`, `libz`) flattened via `patchelf` so all SONAMEs
#      match Android's `lib*.so` installer requirement in `jniLibs/arm64-v8a/`.
#   2. Alpine musl dynamic loader (`ld-musl-aarch64.so.1` -> `libldmusl.so`) + `libgcc_s.so.1` -> `libmuslgcc.so`
#      + `libstdc++.so.6` -> `libmuslstdcpp.so` so prebuilt Linux-musl binaries (`claude`, `codex`, `rg`, etc.)
#      can execute directly on Android via an `filesDir/runtime/bin/ld-musl-aarch64.so.1 -> <nativeLibraryDir>/libldmusl.so`
#      symlink without PRoot overhead.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
WORK_DIR="${WORK_DIR:-/tmp/codeassist-runtime-pack-build}"
OUT_JNILIBS="${OUT_JNILIBS:-${REPO_ROOT}/app/ide-android/src/main/jniLibs/arm64-v8a}"
OUT_ASSETS="${OUT_ASSETS:-${REPO_ROOT}/app/ide-android/src/main/assets/runtime}"

TERMUX_MIRROR="${TERMUX_MIRROR:-https://packages.termux.dev/apt/termux-main}"
ALPINE_MIRROR="${ALPINE_MIRROR:-https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64}"

for tool in curl python3 patchelf ar tar xz; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "error: required build tool '$tool' not found in PATH" >&2
    exit 1
  fi
done

mkdir -p "${WORK_DIR}/debs" "${WORK_DIR}/apks" "${WORK_DIR}/root" "${WORK_DIR}/musl"
mkdir -p "${OUT_JNILIBS}" "${OUT_ASSETS}"

echo "[1/5] Fetching Termux Packages index..."
curl -fsSL "${TERMUX_MIRROR}/dists/stable/main/binary-aarch64/Packages" -o "${WORK_DIR}/Packages"

resolve_deb_url() {
  local pkg="$1"
  python3 - "${WORK_DIR}/Packages" "$pkg" <<'PY'
import sys

packages_path, target = sys.argv[1], sys.argv[2]
current = {}
with open(packages_path, "r", encoding="utf-8", errors="replace") as f:
    for raw in f:
        line = raw.rstrip("\n")
        if not line:
            if current.get("Package") == target:
                print(current["Filename"])
                sys.exit(0)
            current = {}
            continue
        if ":" in line and not line.startswith(" "):
            k, v = line.split(":", 1)
            current[k.strip()] = v.strip()
if current.get("Package") == target:
    print(current["Filename"])
    sys.exit(0)
sys.exit(1)
PY
}

TERMUX_PKGS=(
  nodejs-lts
  c-ares
  openssl
  libsqlite
  libicu
  libc++
  zlib
  ripgrep
)

echo "[2/5] Downloading and extracting Termux aarch64 packages..."
for pkg in "${TERMUX_PKGS[@]}"; do
  rel_url="$(resolve_deb_url "$pkg")"
  deb_file="${WORK_DIR}/debs/${pkg}.deb"
  if [[ ! -f "$deb_file" ]]; then
    curl -fsSL "${TERMUX_MIRROR}/${rel_url}" -o "$deb_file"
  fi
  (
    cd "${WORK_DIR}/debs"
    ar x "$deb_file" data.tar.xz
    tar -xJf data.tar.xz -C "${WORK_DIR}/root"
    rm -f data.tar.xz
  )
done

USR_DIR="${WORK_DIR}/root/data/data/com.termux/files/usr"

echo "[3/5] Fetching Alpine musl loader and C++ runtime..."
ALPINE_INDEX="${WORK_DIR}/apks/APKINDEX.tar.gz"
curl -fsSL "${ALPINE_MIRROR}/APKINDEX.tar.gz" -o "$ALPINE_INDEX"
tar -xzf "$ALPINE_INDEX" -C "${WORK_DIR}/apks" APKINDEX

resolve_apk_file() {
  local pkg="$1"
  awk -v pkg="$pkg" '
    $0 == "P:" pkg { found=1 }
    found && /^V:/ { ver=substr($0, 3); print pkg "-" ver ".apk"; exit }
  ' "${WORK_DIR}/apks/APKINDEX"
}

for apk_pkg in musl libgcc libstdc++; do
  apk_name="$(resolve_apk_file "$apk_pkg")"
  curl -fsSL "${ALPINE_MIRROR}/${apk_name}" -o "${WORK_DIR}/apks/${apk_name}"
  tar -xzf "${WORK_DIR}/apks/${apk_name}" -C "${WORK_DIR}/musl" 2>/dev/null || true
done

echo "[4/5] Copying and flattening SONAMEs via patchelf for Android nativeLibraryDir..."
cp -L "${USR_DIR}/bin/node" "${OUT_JNILIBS}/libnode.so"
cp -L "${USR_DIR}/bin/rg" "${OUT_JNILIBS}/librg.so"
cp -L "${USR_DIR}/lib/libcares.so" "${OUT_JNILIBS}/libcares.so"
cp -L "${USR_DIR}/lib/libcrypto.so.3" "${OUT_JNILIBS}/libcrypto3.so"
cp -L "${USR_DIR}/lib/libssl.so.3" "${OUT_JNILIBS}/libssl3.so"
cp -L "${USR_DIR}/lib/libsqlite3.so" "${OUT_JNILIBS}/libsqlite3.so"
cp -L "${USR_DIR}/lib/libicui18n.so" "${OUT_JNILIBS}/libicui18n.so"
cp -L "${USR_DIR}/lib/libicuuc.so" "${OUT_JNILIBS}/libicuuc.so"
cp -L "${USR_DIR}/lib/libicudata.so" "${OUT_JNILIBS}/libicudata.so"
cp -L "${USR_DIR}/lib/libc++_shared.so" "${OUT_JNILIBS}/libc++_shared.so"
cp -L "${USR_DIR}/lib/libz.so.1" "${OUT_JNILIBS}/libtxz.so"

cp -L "${WORK_DIR}/musl/lib/ld-musl-aarch64.so.1" "${OUT_JNILIBS}/libldmusl.so"
cp -L "${WORK_DIR}/musl/usr/lib/libgcc_s.so.1" "${OUT_JNILIBS}/libmuslgcc.so"
cp -L "${WORK_DIR}/musl/usr/lib/libstdc++.so.6" "${OUT_JNILIBS}/libmuslstdcpp.so"

declare -A SONAME_MAP=(
  ["libcrypto.so.3"]="libcrypto3.so"
  ["libssl.so.3"]="libssl3.so"
  ["libsqlite3.so.0"]="libsqlite3.so"
  ["libz.so.1"]="libtxz.so"
  ["libgcc_s.so.1"]="libmuslgcc.so"
  ["libstdc++.so.6"]="libmuslstdcpp.so"
  ["ld-musl-aarch64.so.1"]="libldmusl.so"
)

for so_file in "${OUT_JNILIBS}"/lib*.so; do
  base="$(basename "$so_file")"
  chmod 0755 "$so_file"
  if [[ "$base" != "libldmusl.so" && "$base" != "libnode.so" && "$base" != "librg.so" ]]; then
    patchelf --set-soname "$base" "$so_file" || true
  fi
  for old_dep in "${!SONAME_MAP[@]}"; do
    new_dep="${SONAME_MAP[$old_dep]}"
    if patchelf --print-needed "$so_file" 2>/dev/null | grep -qx "$old_dep"; then
      patchelf --replace-needed "$old_dep" "$new_dep" "$so_file"
    fi
  done
  patchelf --remove-rpath "$so_file" 2>/dev/null || true
done

echo "[5/5] Runtime pack staged in ${OUT_JNILIBS}"
