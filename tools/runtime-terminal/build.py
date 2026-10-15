#!/usr/bin/env python3
"""Build and verify the tiny JNI PTY library using the existing pinned Android NDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
VENDOR = ROOT / "app/ide-android/vendor/termux-terminal"


def verify_elf(path):
    data = path.read_bytes()
    if data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", data, 18)[0] != 183:
        raise RuntimeError(f"Expected AArch64 ELF: {path}")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    loads = [struct.unpack_from("<IIQQQQQQ", data, offset + index * size) for index in range(count)]
    if not all(segment[-1] >= 16384 for segment in loads if segment[0] == 1):
        raise RuntimeError(f"PTY library is not 16 KiB aligned: {path}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, default=ROOT / "tools/android-sdk/build/android-ndk-r29-beta1")
    args = parser.parse_args()
    host = "darwin-x86_64" if sys.platform == "darwin" else "linux-x86_64"
    compiler = args.ndk / f"toolchains/llvm/prebuilt/{host}/bin/aarch64-linux-android26-clang"
    if not compiler.is_file():
        raise RuntimeError(f"Pinned NDK is not provisioned. Run buildNativeSdkTools first: {compiler}")
    output = args.output.resolve()
    library = output / "jniLibs/arm64-v8a/libruntime-pty.so"
    library.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(compiler), str(HERE / "pty.c"), str(HERE / "atomic-files.c"), "-ldl", "-std=c11", "-O2", "-fPIC", "-shared", "-Wall", "-Wextra", "-Werror",
                    "-Wl,-z,relro,-z,now,-z,max-page-size=16384,-z,common-page-size=16384", "-Wl,-soname,libruntime-pty.so", "-o", str(library)], check=True)
    verify_elf(library)
    notices = output / "assets/terminal-notices"
    notices.mkdir(parents=True, exist_ok=True)
    for name in ("LICENSE-APACHE-2.0.txt", "NOTICE.txt", "UPSTREAM-LICENSE.txt", "upstream.lock.json"):
        shutil.copyfile(VENDOR / name, notices / name)
    (notices / "pty-provenance.json").write_text(json.dumps({"api": 26, "abi": "arm64-v8a", "ndk": "r29-beta1",
        "sourceSha256": hashlib.sha256((HERE / "pty.c").read_bytes()).hexdigest(),
        "atomicFilesSha256": hashlib.sha256((HERE / "atomic-files.c").read_bytes()).hexdigest(),
        "librarySha256": hashlib.sha256(library.read_bytes()).hexdigest()}, indent=2) + "\n")
    print(f"Built API-26 public JNI PTY, AArch64/16-KiB ELF verified: {library}")


if __name__ == "__main__":
    main()
