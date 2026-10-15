#!/usr/bin/env python3
"""Stage XTool and Swift toolchain into the developer-runtime-pack.

Reuses the existing LLVM / Clang / LLD native binaries already staged in
the runtime pack, avoiding double-bundling LLVM.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import zipfile

HERE = Path(__file__).resolve().parent


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def verify_elf(data: bytes, name: str) -> list[int]:
    if not (data.startswith(b"\x7fELF") and len(data) >= 64 and data[4] == 2 and data[5] == 1):
        raise ValueError(f"Not a 64-bit little-endian ELF file: {name}")
    machine = struct.unpack_from("<H", data, 18)[0]
    if machine != 183:  # EM_AARCH64
        raise ValueError(f"ELF is not aarch64 (machine={machine}): {name}")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 54)
    alignments = []
    for i in range(phnum):
        offset = phoff + i * phentsize
        if offset + 56 > len(data):
            break
        p_type = struct.unpack_from("<I", data, offset)[0]
        if p_type == 1:  # PT_LOAD
            p_align = struct.unpack_from("<Q", data, offset + 48)[0]
            alignments.append(p_align)
            if p_align < 16384:
                raise ValueError(f"ELF LOAD segment alignment {p_align} < 16384: {name}")
    return alignments


def stage_native(directory: Path, name: str, data: bytes, owner: str) -> dict:
    alignment = verify_elf(data, name)
    digest = sha256(data)
    packaged = f"libdevruntime_{digest}.so"
    dest = directory / packaged
    if not dest.is_file() or dest.read_bytes() != data:
        dest.write_bytes(data)
        dest.chmod(0o755)
    return {
        "path": name,
        "library": packaged,
        "sha256": digest,
        "size": len(data),
        "owner": owner,
        "loadAlignments": alignment,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack-dir", type=Path, default=HERE.parent.parent / "app/ide-android/build/developer-runtime-pack")
    parser.add_argument("--xtool-output", type=Path, default=Path("/Users/mac/xtool/Android/output"))
    parser.add_argument("--android-runtime", type=Path, default=Path("/Users/mac/xtool/.build/android-runtime"))
    args = parser.parse_args()

    pack_dir = args.pack_dir.resolve()
    xtool_output = args.xtool_output.resolve()
    android_runtime = args.android_runtime.resolve()

    if not xtool_output.is_dir():
        print(f"XTool output directory not found at {xtool_output}; skipping XTool staging.")
        return 0

    assets_dir = pack_dir / "assets/developer-runtime"
    native_dir = pack_dir / "jniLibs/arm64-v8a"
    manifest_file = assets_dir / "manifest.json"
    prefix_zip = assets_dir / "prefix.zip"

    if not manifest_file.is_file() or not prefix_zip.is_file():
        print(f"Base runtime pack not staged yet in {pack_dir}; skipping.")
        return 0

    with open(manifest_file, "r", encoding="utf-8") as f:
        manifest = json.load(f)

    # 1. Update packages declaration
    existing_pkg_names = {p["name"] for p in manifest.get("packages", [])}
    packages = manifest.setdefault("packages", [])
    if "swift" not in existing_pkg_names:
        packages.append({
            "name": "swift",
            "version": "6.4.1",
            "size": 132440968,
            "license": "Apache-2.0 with LLVM Exceptions",
        })
    if "xtool" not in existing_pkg_names:
        packages.append({
            "name": "xtool",
            "version": "0.1.0",
            "size": 191650928,
            "license": "Apache-2.0",
        })
    if "sourcekit-lsp" not in existing_pkg_names:
        packages.append({
            "name": "sourcekit-lsp",
            "version": "6.4.1",
            "size": 12528256,
            "license": "Apache-2.0 with LLVM Exceptions",
        })

    native_entries = {entry["path"]: entry for entry in manifest.get("native", [])}
    links_entries = {link["path"]: link["target"] for link in manifest.get("links", [])}

    # 2. Stage xtool CLI, macro server, swift-launcher, libxtool-exec
    binaries = {
        "bin/xtool": xtool_output / "xtool",
        "bin/OpenAppleMacrosServer": xtool_output / "OpenAppleMacrosServer",
        "bin/swift-launcher": xtool_output / "swift-launcher",
    }
    for bin_path, bin_file in binaries.items():
        if bin_file.is_file():
            data = bin_file.read_bytes()
            native_entries[bin_path] = stage_native(native_dir, bin_path, data, "xtool (Apache-2.0)")

    # Direct libxtool-exec.so for LD_PRELOAD
    libxtool_exec = xtool_output / "libxtool-exec.so"
    if libxtool_exec.is_file():
        exec_data = libxtool_exec.read_bytes()
        native_entries["lib/libxtool-exec.so"] = stage_native(native_dir, "lib/libxtool-exec.so", exec_data, "xtool (Apache-2.0)")
        shutil.copyfile(libxtool_exec, native_dir / "libxtool-exec.so")

    # Shared libraries in xtool_output
    for so_file in sorted(xtool_output.glob("*.so*")):
        if so_file.name == "libxtool-exec.so":
            continue
        try:
            data = so_file.read_bytes()
            if data.startswith(b"\x7fELF"):
                staged = stage_native(native_dir, f"lib/xtool/{so_file.name}", data, "xtool/swift (Apache-2.0)")
                native_entries[f"lib/xtool/{so_file.name}"] = staged
                # Only create top-level lib symlink if not colliding with base runtime libraries (OpenSSL, libc++, liblzma)
                base_colliding = {"libc++_shared.so", "libcrypto.so.3", "libcrypto.so", "libssl.so.3", "libssl.so", "liblzma.so", "liblzma.so.5"}
                if so_file.name not in base_colliding and f"lib/{so_file.name}" not in native_entries:
                    links_entries[f"lib/{so_file.name}"] = f"lib/xtool/{so_file.name}"
                else:
                    links_entries.pop(f"lib/{so_file.name}", None)
        except Exception as e:
            print(f"Note: skipped {so_file.name}: {e}")

    # 3. Stage swift executables from android_runtime if present
    if android_runtime.is_dir():
        swift_bins = {
            "bin/swift-frontend": android_runtime / "usr/bin/swift-frontend",
            "bin/sourcekit-lsp": android_runtime / "usr/bin/sourcekit-lsp",
            "bin/swift-package": android_runtime / "usr/bin/swift-package",
            "bin/swift-driver": android_runtime / "usr/bin/swift-driver",
            "bin/swift-build-tool": android_runtime / "usr/bin/swift-build-tool",
        }
        for bin_path, bin_file in swift_bins.items():
            if bin_file.is_file():
                try:
                    data = bin_file.read_bytes()
                    native_entries[bin_path] = stage_native(native_dir, bin_path, data, "swift (Apache-2.0 with LLVM Exceptions)")
                except Exception as e:
                    print(f"Note: skipping {bin_path}: {e}")

        # Add symlinks for swift, swiftc, swift-build, swift-test, etc.
        links_entries["bin/swift"] = "bin/swift-frontend"
        links_entries["bin/swiftc"] = "bin/swift-frontend"
        links_entries["bin/swift-build"] = "bin/swift-package"
        links_entries["bin/swift-test"] = "bin/swift-package"
        links_entries["bin/swift-run"] = "bin/swift-package"
        links_entries["bin/swift-sdk"] = "bin/swift-package"

    # Update manifest native and links lists
    manifest["native"] = sorted(native_entries.values(), key=lambda e: e["path"])
    manifest["links"] = [{"path": p, "target": t} for p, t in sorted(links_entries.items())]

    # Recompute payload SHA256 and ID
    manifest["payloadSha256"] = sha256(prefix_zip.read_bytes())
    manifest["payloadBytes"] = prefix_zip.stat().st_size
    manifest_bytes = json.dumps({"native": manifest["native"], "links": manifest["links"]}, sort_keys=True).encode()
    manifest["id"] = "termux-arm64-" + sha256(manifest_bytes)[:16]

    with open(manifest_file, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)

    print(f"Successfully staged XTool & Swift into runtime pack at {pack_dir}")
    return 0


if __name__ == "__main__":
    exit(main())
