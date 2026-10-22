#!/usr/bin/env python3
"""Merge pinned Android Swift/xtool inputs into a separately verified runtime pack.

Shared Termux packages stay owned by the base pack. Apple SDKs are never input to
this distributor. The sibling xtool checkout supplies its pinned Android builds.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import struct
import zipfile
from build import collect, sha256, stage_native, zip_entry

HERE = Path(__file__).resolve().parent


def stage(source, output, xtool_root, lock_path=HERE / "xtool-inputs.lock.json"):
    if source.resolve() == output.resolve():
        raise ValueError("Swift staging requires a separate output from the base runtime")
    lock = json.loads(lock_path.read_text())
    assets = source / "assets/developer-runtime"
    original = json.loads((assets / "manifest.json").read_text())
    if original.get("xtoolInputs"):
        raise ValueError("Source must be the base runtime, before Swift staging")
    shared = {p["name"]: p["sha256"] for p in original["packages"]}
    for name, digest in lock["reusedPackageSha256"].items():
        if shared.get(name) != digest:
            raise ValueError("Shared toolchain changed; review Swift compatibility and input lock: " + name)
    notice_paths = ("LICENSE.md", "Android/swift-launcher.c", "Android/README.md")
    for path in notice_paths:
        if not (xtool_root / path).is_file():
            raise ValueError("Missing xtool source notice: " + path)
    # Verify all inputs before replacing generated output; never ship half a compiler.
    archives = {}
    for package in lock["packages"]:
        archive = xtool_root / ".build/android-debs" / Path(package["filename"]).name
        data = archive.read_bytes()
        if len(data) != package["size"] or sha256(data) != package["sha256"]:
            raise ValueError("Swift package checksum mismatch: " + str(archive))
        archives[package["name"]] = archive
    binaries = {}
    for name, expected in lock["binaries"].items():
        data = (xtool_root / "Android/output" / name).read_bytes()
        if len(data) != expected["size"] or sha256(data) != expected["sha256"]:
            raise ValueError("xtool output changed; rebuild/review the input lock: " + name)
        binaries[name] = data
    files, links, owners = collect(lock["packages"], archives, max_member_bytes=256 * 1024 * 1024)
    base_paths = {r["path"] for key in ("files", "native", "links") for r in original[key]}
    # Retain Building's env helper and its existing executable relocation contract.
    files.pop("bin/env", None)
    links.pop("bin/env", None)
    collisions = (set(files) | set(links)) & base_paths
    if collisions:
        raise ValueError("Swift inputs would replace base paths: " + str(sorted(collisions)))
    for name, data in binaries.items():
        destination = "bin/" + name if name in {"xtool", "OpenAppleMacrosServer", "swift-launcher"} else "lib/xtool/" + name
        files[destination], owners[destination] = data, "xtool"
    # Swift uses argv[0] to select its driver; this adapter supplies relocated resources.
    for name in ("swift", "swiftc"):
        links["bin/" + name] = "bin/swift-launcher"
        owners["bin/" + name] = "xtool"
    # Termux creates this directory alias in a package hook. We do not run hooks.
    clang_versions = {p.split("/")[2] for p in base_paths if p.startswith("lib/clang/")}
    if "lib/clang/latest" in links.values():
        if len(clang_versions) != 1:
            raise ValueError("Cannot resolve the shared Clang resource directory")
        links = {name: ("lib/clang/" + next(iter(clang_versions)) if target == "lib/clang/latest" else target)
                 for name, target in links.items()}
    # Keep runtime libraries apart from Android's link-time system-library stubs.
    for name in files:
        if name.startswith("opt/ndk-multilib/aarch64-linux-android/lib/") and name.endswith(".so"):
            leaf = Path(name).name
            if leaf.startswith(("libswift", "libFoundation", "lib_Foundation", "libTesting", "libXCTest", "lib_Testing")):
                links["lib/swift-runtime/" + leaf] = name
                owners["lib/swift-runtime/" + leaf] = "swift-runtime-aarch64"
    # Stage the precompiled Android aarch64 Cupertino SwiftUI bridge SDK directly into lib/swift/android/.
    bridge_sdk = Path(os.environ.get("SWIFTUI_BRIDGE_SDK", str(Path.home() / ".building/swiftui-bridge-sdk/android-aarch64")))
    if (bridge_sdk / "libSwiftUI.a").is_file() and (bridge_sdk / "SwiftUI.swiftmodule/aarch64-unknown-linux-android.swiftmodule").is_file():
        lib_bytes = (bridge_sdk / "libSwiftUI.a").read_bytes()
        for dst in ("lib/swift/android/libSwiftUI.a", "lib/swift/android/aarch64/libSwiftUI.a"):
            files[dst], owners[dst] = lib_bytes, "swiftui-bridge-sdk"
        for mod in ("SwiftUI", "Combine", "CoreGraphics", "UIKit"):
            mod_file = bridge_sdk / f"{mod}.swiftmodule/aarch64-unknown-linux-android.swiftmodule"
            if mod_file.is_file():
                mod_bytes = mod_file.read_bytes()
                for target_triple in ("aarch64-unknown-linux-android", "aarch64-unknown-linux-android28"):
                    dst = f"lib/swift/android/{mod}.swiftmodule/{target_triple}.swiftmodule"
                    files[dst], owners[dst] = mod_bytes, "swiftui-bridge-sdk"
    paths = base_paths | set(files) | set(links)
    paths |= {str(parent) for path in list(paths) for parent in Path(path).parents if str(parent) != "."}
    for name, target_path in links.items():
        seen = {name}
        while target_path in links and target_path not in seen:
            seen.add(target_path)
            target_path = links[target_path]
        if target_path not in paths or target_path in links:
            raise ValueError(f"Dangling/cyclic Swift link: {name} -> {target_path}")
    if output.exists():
        shutil.rmtree(output)  # This task's generated output only, never source/user data.
    shutil.copytree(source, output)
    target = output / "assets/developer-runtime"
    native_dir = output / "jniLibs/arm64-v8a"
    manifest = dict(original)
    for key in ("files", "native", "links"):
        manifest[key] = list(original[key])
    with zipfile.ZipFile(target / "prefix.zip", "a", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name, data in sorted(files.items()):
            # NDK system-library stubs are compiler inputs, not executable runtime libraries.
            ndk_stub = name.startswith("opt/ndk-multilib/") and name not in links.values()
            elf_host = not ndk_stub and data[:6] == b"\x7fELF\x02\x01" and len(data) >= 64 and struct.unpack_from("<H", data, 18)[0] == 183 and struct.unpack_from("<H", data, 16)[0] in (2, 3)
            if elf_host:
                manifest["native"].append(dict(stage_native(native_dir, name, data, owners[name]), component="xtool"))
            else:
                original_hash = sha256(data)
                if data.startswith(b"#!/data/data/com.termux/files/usr/"):
                    data = b"#!/usr/" + data[len(b"#!/data/data/com.termux/files/usr/"):]
                zip_entry(archive, name, data)
                manifest["files"].append(dict(path=name, owner=owners[name], component="swift_support", size=len(data), sha256=sha256(data), originalSha256=original_hash, executableScript=data.startswith(b"#!")))
    manifest["links"] += [dict(path=name, target=target_path, owner=owners[name], component="xtool") for name, target_path in sorted(links.items())]
    manifest["packages"] = original["packages"] + lock["packages"]
    manifest["xtoolInputs"] = lock
    manifest["payloadSha256"] = sha256((target / "prefix.zip").read_bytes())
    manifest["payloadBytes"] = (target / "prefix.zip").stat().st_size
    manifest.pop("id", None)
    manifest["id"] = "termux-arm64-" + sha256(json.dumps(manifest, sort_keys=True).encode())[:16]
    (target / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    notices = target / "notices/xtool"
    notices.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(lock_path, notices / "inputs.lock.json")
    for path in notice_paths:
        shutil.copyfile(xtool_root / path, notices / Path(path).name)
    print(f"Swift/xtool: {len(files)} additional files, {len(links)} links; reused {len(lock['reusedPackages'])} base packages", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--xtool-root", type=Path, default=Path(os.environ.get("XTOOL_ROOT", str(HERE.parents[2] / "xtool"))))
    args = parser.parse_args()
    stage(args.source, args.output, args.xtool_root)
