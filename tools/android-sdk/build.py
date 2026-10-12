#!/usr/bin/env python3
"""Build the app's Android-host SDK helpers from locked sources. No Termux app is involved."""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import urllib.request

HERE = Path(__file__).resolve().parent
TOOLS = ("aapt", "dexdump", "split-select", "zipalign")
NDK = {
    "darwin": ("darwin", "485c42ff6a04ef0ec9612420b3226de443f46e7a"),
    "linux": ("linux", "ec2d8801e42009edf66be853c1bab9ba216378f9"),
}


def run(*args, cwd=None):
    subprocess.run([str(a) for a in args], cwd=cwd, check=True)


def checkout(spec, path):
    if not (path / ".git").exists():
        path.mkdir(parents=True, exist_ok=True)
        run("git", "init", "-q", path)
        run("git", "remote", "add", "origin", spec["url"], cwd=path)
        run("git", "fetch", "--depth", "1", "--filter=blob:none", "origin", spec["commit"], cwd=path)
        if spec.get("sparse"):
            run("git", "sparse-checkout", "set", *spec["sparse"], cwd=path)
        run("git", "checkout", "--detach", spec["commit"], cwd=path)
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=path, text=True).strip()
    if actual != spec["commit"]:
        raise RuntimeError(f"Unexpected source revision in {path}; use a fresh work directory")


def ndk(work):
    host, expected = NDK[sys.platform]
    root = work / "android-ndk-r29-beta1"
    if not root.exists():
        archive = work / "ndk.zip"
        if not archive.exists():
            url = f"https://dl.google.com/android/repository/android-ndk-r29-beta1-{host}.zip"
            with urllib.request.urlopen(url, timeout=120) as source, archive.open("wb") as dest:
                shutil.copyfileobj(source, dest)
        with archive.open("rb") as source:
            actual = hashlib.file_digest(source, "sha1").hexdigest()
        if actual != expected:
            raise RuntimeError(f"NDK checksum mismatch: {archive}")
        run("unzip", "-q", archive, "-d", work)
    return root


def verify_elf(path):
    data = path.read_bytes()
    if data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", data, 18)[0] != 183:
        raise RuntimeError(f"Expected an aarch64 ELF executable: {path}")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    loads = [struct.unpack_from("<IIQQQQQQ", data, offset + i * size) for i in range(count)]
    if not all(h[-1] >= 16384 for h in loads if h[0] == 1):
        raise RuntimeError(f"ELF load segments are not 16 KB aligned: {path}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", type=Path, default=HERE / "build")
    parser.add_argument("--ndk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=True)
    lock = json.loads((HERE / "sources.lock.json").read_text())
    source = work / "sources"
    checkout(lock["upstream"], source)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as workers:
        list(workers.map(lambda item: checkout(item, source / item["path"]), lock["sources"]))
    ndk_root = args.ndk.resolve() if args.ndk else ndk(work)
    shutil.copyfile(HERE / "CMakeLists.txt", source / "CMakeLists.txt")
    shutil.copyfile(HERE / "exec-prefix.c", source / "exec-prefix.c")
    inc = source / "src/incremental_delivery/sysprop/include"
    inc.mkdir(exist_ok=True)
    for name, directory in [
        ("IncrementalProperties.sysprop.h", inc),
        ("IncrementalProperties.sysprop.cpp", inc.parent),
        ("platform_tools_version.h", source / "src/soong/cc/libbuildversion/include"),
    ]:
        shutil.copyfile(source / "patches/misc" / name, directory / name)
    patch = source / "patches/dex_file.cc.patch"
    dex = source / "src/art/libdexfile/dex/dex_file.cc"
    if "std::ostream& operator<<(std::ostream& os, EncodedArrayValueIterator::ValueType rhs)" not in dex.read_text():
        with patch.open("rb") as stream:
            subprocess.run(["patch", "-p1"], cwd=source / "src", stdin=stream, check=True)
    build = work / "cmake"
    run("cmake", "-G", "Ninja", "-S", source, "-B", build,
        f"-DCMAKE_TOOLCHAIN_FILE={ndk_root}/build/cmake/android.toolchain.cmake",
        "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_static",
        "-DCMAKE_BUILD_TYPE=MinSizeRel", "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
        "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384,-z,common-page-size=16384")
    run("cmake", "--build", build, "-j", min(os.cpu_count() or 4, 10), "--target", *TOOLS, "sdk-exec-prefix")
    host = "darwin-x86_64" if sys.platform == "darwin" else "linux-x86_64"
    strip = ndk_root / f"toolchains/llvm/prebuilt/{host}/bin/llvm-strip"
    output = args.output.resolve() / "jniLibs/arm64-v8a"
    output.mkdir(parents=True, exist_ok=True)
    manifest = {"sources": lock, "ndk": "29.0.13113456-beta1", "api": 26, "sha256": {}}
    for name in (*TOOLS, "exec-prefix"):
        binary = output / f"libsdk-{name}.so"
        built = build / "lib/libsdk-exec-prefix.so" if name == "exec-prefix" else build / "bin" / name
        shutil.copyfile(built, binary)
        run(strip, "--strip-all", binary)
        binary.chmod(0o755)
        verify_elf(binary)
        manifest["sha256"][binary.name] = hashlib.sha256(binary.read_bytes()).hexdigest()
    (args.output.resolve() / "provenance.json").write_text(json.dumps(manifest, indent=2) + "\n")
    notices = args.output.resolve() / "assets/build-tool-notices"
    notices.mkdir(parents=True, exist_ok=True)
    names = {"LICENSE", "LICENSE.TXT", "LICENSE.MD", "COPYING", "COPYRIGHT", "NOTICE", "NOTICE.TXT"}
    for directory in [source, *(source / item["path"] for item in lock["sources"]), ndk_root]:
        candidates = list(directory.glob("*")) + list(directory.glob("*/*"))
        for file in candidates:
            if file.is_file() and file.name.upper() in names:
                relative = file.relative_to(directory).as_posix().replace("/", "-")
                shutil.copyfile(file, notices / f"{directory.name}-{relative}.txt")
    (notices / "sources.json").write_text(json.dumps(lock, indent=2) + "\n")
    print(f"Built and verified SDK helpers: {output}")


if __name__ == "__main__":
    main()
