#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Inspect the actual APK/AAB and verify its complete bundled development runtime.

No extraction into a runtime directory, downloads, execution, or feature pruning.
Sizes distinguish stored archive bytes from uncompressed bytes; neither is an
installed-storage estimate. Reports are local build artifacts, not device proof.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import importlib.util
import io
import json
from pathlib import Path, PurePosixPath
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("runtime_pack", ROOT / "tools/runtime-pack/build.py")
runtime_pack = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime_pack)

REQUIRED_PACKAGES = {"nodejs-lts", "npm", "python", "dash", "clang", "llvm", "lld", "cmake", "ninja"}


def digest(stream):
    return hashlib.file_digest(stream, "sha256").hexdigest()


def entries(archive):
    result = {}
    for entry in archive.infolist():
        if entry.filename in result:
            raise ValueError("Duplicate archive entry: " + entry.filename)
        path = PurePosixPath(entry.filename)
        if path.is_absolute() or ".." in path.parts or "\\" in entry.filename:
            raise ValueError("Unsafe archive path: " + entry.filename)
        result[entry.filename] = entry
    return result


def verify_record(archive, entry, record):
    if entry.file_size != record["size"]:
        raise ValueError("Size mismatch: " + entry.filename)
    with archive.open(entry) as stream:
        if digest(stream) != record["sha256"]:
            raise ValueError("SHA-256 mismatch: " + entry.filename)


def verify_runtime(archive, inventory, base):
    manifest_path = base + "assets/developer-runtime/manifest.json"
    manifest = json.loads(archive.read(manifest_path))
    packages = manifest["packages"]
    names = [p["name"] for p in packages]
    if len(names) != len(set(names)):
        raise ValueError("Duplicate runtime package definitions")
    missing = REQUIRED_PACKAGES - set(names)
    if missing:
        raise ValueError("Mandatory development packages missing: " + ", ".join(sorted(missing)))
    if "pnpm" not in {p["name"] for p in manifest["npmTools"]}:
        raise ValueError("Bundled pnpm is missing")
    prefix_bytes = archive.read(base + "assets/developer-runtime/prefix.zip")
    if hashlib.sha256(prefix_bytes).hexdigest() != manifest["payloadSha256"]:
        raise ValueError("Runtime prefix archive SHA-256 mismatch")
    native_names = set()
    logical_paths = set()
    for record in manifest["native"]:
        if record["path"] in logical_paths:
            raise ValueError("Duplicate runtime path: " + record["path"])
        logical_paths.add(record["path"])
        name = base + "lib/arm64-v8a/" + record["library"]
        verify_record(archive, inventory[name], record)
        if name not in native_names:
            runtime_pack.verify_elf(archive.read(name), name)
        native_names.add(name)
    packaged = {name for name in inventory if name.startswith(base + "lib/arm64-v8a/libdevruntime_")}
    if packaged != native_names:
        raise ValueError("Runtime native payloads disagree with manifest: " + str(sorted(packaged ^ native_names)))
    launcher = base + "lib/arm64-v8a/libdevruntime-launcher.so"
    with archive.open(launcher) as stream:
        if digest(stream) != manifest["launcherSha256"]:
            raise ValueError("Runtime launcher SHA-256 mismatch")
    runtime_pack.verify_elf(archive.read(launcher), launcher)
    with zipfile.ZipFile(io.BytesIO(prefix_bytes)) as prefix:
        prefix_entries = entries(prefix)
        expected = {r["path"] for r in manifest["files"]}
        if len(expected) != len(manifest["files"]) or expected != set(prefix_entries):
            raise ValueError("Runtime data inventory disagrees with prefix archive")
        for record in manifest["files"]:
            if record["path"] in logical_paths:
                raise ValueError("Data/native runtime path collision: " + record["path"])
            logical_paths.add(record["path"])
            verify_record(prefix, prefix_entries[record["path"]], record)
    links = {}
    for record in manifest["links"]:
        path, target = record["path"], record["target"]
        for value in (path, target):
            if PurePosixPath(value).is_absolute() or ".." in PurePosixPath(value).parts or "\\" in value:
                raise ValueError("Unsafe runtime link: " + path)
        if path in logical_paths or path in links:
            raise ValueError("Runtime link collides with existing path: " + path)
        links[path] = target
    for path, target in links.items():
        seen = {path}
        while target in links and target not in seen:
            seen.add(target)
            target = links[target]
        if target not in logical_paths:
            raise ValueError("Dangling or cyclic runtime link: " + path)
    for name in ("bin/node", "bin/npm", "bin/pnpm", "bin/python3", "bin/sh", "bin/clang", "bin/clang++", "bin/ld.lld"):
        if name not in logical_paths and name not in links:
            raise ValueError("Mandatory runtime entry point missing: " + name)
    return {"id": manifest["id"], "packages": len(packages), "logicalNativeFiles": len(manifest["native"]),
            "uniqueNativePayloads": len(native_names), "dataFiles": len(manifest["files"]), "links": len(links),
            "verified": True}


def category(name, base):
    path = name.removeprefix(base)
    if path.startswith("classes") and path.endswith(".dex"):
        return "DEX"
    parts = path.split("/")
    return "/".join(parts[:2]) if parts[0] in ("lib", "assets") else parts[0]


def audit(path):
    with zipfile.ZipFile(path) as archive:
        inventory = entries(archive)
        base = "base/" if "base/manifest/AndroidManifest.xml" in inventory else ""
        runtime = verify_runtime(archive, inventory, base)
        groups = defaultdict(lambda: {"storedBytes": 0, "uncompressedBytes": 0, "entries": 0})
        candidates = defaultdict(list)
        for entry in inventory.values():
            if entry.is_dir():
                continue
            group = groups[category(entry.filename, base)]
            group["storedBytes"] += entry.compress_size
            group["uncompressedBytes"] += entry.file_size
            group["entries"] += 1
            if entry.file_size >= 100_000:
                candidates[(entry.CRC, entry.file_size)].append(entry)
        duplicates = []
        for same_size in candidates.values():
            if len(same_size) < 2:
                continue
            by_hash = defaultdict(list)
            for entry in same_size:
                with archive.open(entry) as stream:
                    by_hash[digest(stream)].append(entry)
            for same_bytes in by_hash.values():
                if len(same_bytes) > 1:
                    duplicates.append({"paths": [e.filename for e in same_bytes],
                                       "uncompressedBytesEach": same_bytes[0].file_size,
                                       "storedBytes": sum(e.compress_size for e in same_bytes)})
        stored_bytes = sum(entry.compress_size for entry in inventory.values())
        report = {"artifact": str(path), "artifactBytes": path.stat().st_size, "runtime": runtime,
                  "storedPayloadBytes": stored_bytes,
                  "archiveOverheadBytes": path.stat().st_size - stored_bytes,
                  "groups": dict(sorted(groups.items(), key=lambda p: -p[1]["storedBytes"])),
                  "duplicatePayloads": duplicates,
                  "notes": ["Archive overhead includes headers, alignment, signatures and any free space from incremental packaging.",
                            "Equal bytes can have different required consumers; duplicates are not automatic deletion candidates.",
                            "Archive validation does not prove device execution, 16-KiB hardware support, signing, or store delivery."]}
    with path.open("rb") as stream:
        report["sha256"] = digest(stream)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path)
    parser.add_argument("--report", type=Path, help="Write JSON under the build output directory")
    args = parser.parse_args()
    report = audit(args.artifact)
    print(f"Artifact: {report['artifactBytes'] / 2**20:.3f} MiB; SHA-256 {report['sha256']}")
    print(f"Stored payload: {report['storedPayloadBytes'] / 2**20:.3f} MiB; archive overhead: {report['archiveOverheadBytes'] / 2**20:.3f} MiB")
    print("Runtime verified:", json.dumps(report["runtime"], sort_keys=True))
    for name, group in list(report["groups"].items())[:12]:
        print(f"{group['storedBytes'] / 2**20:8.2f} MiB stored; {group['uncompressedBytes'] / 2**20:8.2f} MiB raw  {name}")
    print("Duplicate payload groups >= 100 KB:", len(report["duplicatePayloads"]))
    for group in report["duplicatePayloads"]:
        print("  ", ", ".join(group["paths"]))
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
