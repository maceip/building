#!/usr/bin/env python3
"""Reuse the product's verified Node bytes; stage only their ELF dependency closure.

No network acquisition. Prepare product inputs with :ide-android:partitionDeveloperRuntimePack.
The tiny installer-owned launch adapters stay in base; all engine bytes and host
implementation DEX are on demand. The spike deliberately does not include npm.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
SOURCE = REPO / "app/ide-android/build/developer-runtime-splits"
OUTPUT = HERE / "build/payload"


def main():
    ndk = Path(os.environ.get("ANDROID_NDK_HOME", REPO / "tools/android-sdk/build/android-ndk-r29-beta1"))
    host = "darwin-x86_64" if sys.platform == "darwin" else "linux-x86_64"
    tools = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    cc = tools / "aarch64-linux-android26-clang"
    records = {}
    manifests = {}
    for part in ("base", "node"):
        path = SOURCE / part / "assets" / ("developer-runtime" if part == "base" else "developer-runtime-node") / "manifest.json"
        if not path.is_file():
            raise SystemExit("First run ./gradlew -PIDE_RUNTIME_DELIVERY=play :ide-android:partitionDeveloperRuntimePack")
        manifests[part] = json.loads(path.read_text())
        for record in manifests[part]["native"]:
            records[record["path"]] = (part, record)
    if manifests["base"]["sourcePackId"] != manifests["node"]["sourcePackId"]:
        raise ValueError("Runtime parts are from different builds")
    system = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so"}
    selected = {}
    links = {link["path"]: link["target"] for manifest in manifests.values() for link in manifest["links"]}

    def visit(path):
        if path in selected:
            return
        resolved = path
        seen = set()
        while resolved in links:
            if resolved in seen:
                raise ValueError(f"Symlink loop: {path}")
            seen.add(resolved)
            resolved = links[resolved]
        part, original = records[resolved]
        record = dict(original, path=path)
        source = SOURCE / part / "jniLibs/arm64-v8a" / record["library"]
        digest = hashlib.file_digest(source.open("rb"), "sha256").hexdigest()
        if digest != record["sha256"]:
            raise ValueError(f"Runtime checksum mismatch: {path}")
        selected[path] = (source, record)
        info = subprocess.check_output([str(tools / "llvm-readelf"), "-d", str(source)], text=True)
        for library in re.findall(r"\(NEEDED\).*?\[(.*?)\]", info):
            if "lib/" + library in records or "lib/" + library in links:
                visit("lib/" + library)
            elif library not in system:
                raise ValueError(f"Unresolved native dependency: {library} from {path}")

    visit("bin/node")
    if OUTPUT.exists():
        shutil.rmtree(OUTPUT)
    base = OUTPUT / "base/jniLibs/arm64-v8a"
    native = OUTPUT / "node/jniLibs/arm64-v8a"
    assets = OUTPUT / "node/assets/node-runtime"
    for path in (base, native, assets):
        path.mkdir(parents=True)
    for source, record in selected.values():
        shutil.copyfile(source, native / record["library"])
    for name, source, options in (
        ("libsdk-exec-prefix.so", REPO / "tools/android-sdk/exec-prefix.c", ["-shared", "-fPIC", "-ldl"]),
        ("libdevruntime-node.so", REPO / "tools/runtime-pack/node-launcher.c", ["-fPIE", "-pie"]),
    ):
        subprocess.run([str(cc), "-Oz", "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
                        "-Wl,--build-id=sha1", str(source), *options, "-o", str(base / name)], check=True)
        subprocess.run([str(tools / "llvm-strip"), "--strip-unneeded", str(base / name)], check=True)
    # Preserve the product's source/license inventory (including shared dependencies).
    notices = SOURCE / "base/assets/developer-runtime"
    for path in notices.iterdir():
        if "notice" in path.name.lower() or "license" in path.name.lower() or path.name.endswith(".md"):
            if path.is_dir():
                shutil.copytree(path, assets / path.name)
            else:
                shutil.copyfile(path, assets / path.name)
    manifest = {
        "sourcePackId": manifests["node"]["sourcePackId"],
        "native": [r for _, r in selected.values()],
        "purpose": "Node-only delivery proof; npm, Swift and other IDE engines are not included",
    }
    (assets / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Staged {len(selected)} verified ELF files, {sum(r['size'] for _, r in selected.values()):,} bytes on demand")


if __name__ == "__main__":
    main()
