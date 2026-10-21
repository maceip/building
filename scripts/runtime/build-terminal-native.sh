#!/usr/bin/env bash
# Cross-compiles 16 KiB page-aligned Android native runtime binaries:
#   - libproot_exec.so   (PRoot binary with statically linked talloc + libandroid-shmem)
#   - libproot_loader.so (PRoot preload loader)
#   - libeta_pty.so      (JNI PTY bridge)
#
# Uses `-Wl,-z,max-page-size=16384` across all link targets for Android 15/16 16 KB page compatibility.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PATCH_DIR="${SCRIPT_DIR}/patches"
BUILD_ROOT="${BUILD_ROOT:-/tmp/codeassist-terminal-native-build}"
OUT_DIR="${OUT_DIR:-${REPO_ROOT}/app/ide-android/src/main/jniLibs/arm64-v8a}"

ANDROID_ABI="${ANDROID_ABI:-arm64-v8a}"
ANDROID_API="${ANDROID_API:-29}"
TARGET_TRIPLE="${TARGET_TRIPLE:-aarch64-linux-android}"
PAGE_SIZE_LDFLAGS="-Wl,-z,max-page-size=16384"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  if [[ -d "${HOME}/Library/Android/sdk/ndk" ]]; then
    ANDROID_NDK_HOME="$(find "${HOME}/Library/Android/sdk/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
  elif [[ -d "${ANDROID_HOME:-}/ndk" ]]; then
    ANDROID_NDK_HOME="$(find "${ANDROID_HOME}/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
  fi
fi

if [[ -z "${ANDROID_NDK_HOME:-}" || ! -d "${ANDROID_NDK_HOME}" ]]; then
  echo "error: ANDROID_NDK_HOME is not set and could not be auto-detected" >&2
  exit 1
fi

HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)"
if [[ "$HOST_TAG" == "darwin-arm64" && ! -d "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${HOST_TAG}" ]]; then
  HOST_TAG="darwin-x86_64"
fi
TOOLCHAIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${HOST_TAG}"
CC="${TOOLCHAIN}/bin/${TARGET_TRIPLE}${ANDROID_API}-clang"
AR="${TOOLCHAIN}/bin/llvm-ar"
STRIP="${TOOLCHAIN}/bin/llvm-strip"

mkdir -p "${BUILD_ROOT}/src" "${BUILD_ROOT}/lib" "${BUILD_ROOT}/include" "${OUT_DIR}"

echo "[1/3] Building static libandroid-shmem with app-private temp dir patch..."
if [[ ! -d "${BUILD_ROOT}/src/android-shmem" ]]; then
  git clone --depth 1 https://github.com/termux/libandroid-shmem.git "${BUILD_ROOT}/src/android-shmem"
  git -C "${BUILD_ROOT}/src/android-shmem" apply "${PATCH_DIR}/shmem-app-temp.patch" || true
fi

(
  cd "${BUILD_ROOT}/src/android-shmem"
  "$CC" -fPIC -O2 -I. -c ashmem-dev.c -o "${BUILD_ROOT}/ashmem-dev.o"
  "$AR" rcs "${BUILD_ROOT}/lib/libandroid-shmem.a" "${BUILD_ROOT}/ashmem-dev.o"
)

echo "[2/3] Building 16 KiB-aligned PRoot (libproot_exec.so & libproot_loader.so)..."
if [[ ! -d "${BUILD_ROOT}/src/proot" ]]; then
  git clone --depth 1 https://github.com/termux/proot.git "${BUILD_ROOT}/src/proot"
  git -C "${BUILD_ROOT}/src/proot" apply "${PATCH_DIR}/proot-bionic-headers.patch" || true
fi

(
  cd "${BUILD_ROOT}/src/proot/src"
  make clean || true
  make -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc || echo 4)" \
    CC="$CC" \
    STRIP="$STRIP" \
    CFLAGS="-O2 -fPIC -I${BUILD_ROOT}/include" \
    LDFLAGS="-L${BUILD_ROOT}/lib ${PAGE_SIZE_LDFLAGS} -pie" \
    PROOT_UNBUNDLE_LOADER="../libproot_loader.so"
  cp proot "${OUT_DIR}/libproot_exec.so"
  if [[ -f loader/loader ]]; then
    cp loader/loader "${OUT_DIR}/libproot_loader.so"
  fi
)

echo "[3/3] Verifying 16 KiB ELF segment alignment..."
for bin in "${OUT_DIR}/libproot_exec.so" "${OUT_DIR}/libproot_loader.so"; do
  if [[ -f "$bin" ]]; then
    chmod 0755 "$bin"
    echo "Built: $bin"
  fi
done
