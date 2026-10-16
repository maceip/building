#!/usr/bin/env python3
"""Partition the verified runtime once; shared libraries stay in the base APK.

No acquisition or guest execution. Inputs are the existing checksum-locked pack.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

NODE_OWNERS = {"nodejs-lts", "npm", "pnpm"}
MODULE = "developer_runtime_node"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def partition(source, output):
    assets = source / "assets/developer-runtime"
    full = json.loads((assets / "manifest.json").read_text())
    parts = {name: {key: [] for key in ("files", "native", "links")} for name in ("base", "node")}
    for key in ("files", "native", "links"):
        for item in full[key]:
            parts["node" if item["owner"] in NODE_OWNERS else "base"][key].append(item)
    # A base link must not silently require the optional feature. Node can use base dependencies.
    base_paths = {item["path"] for key in ("files", "native", "links") for item in parts["base"][key]}
    for item in parts["base"]["links"]:
        if item["target"] not in base_paths:
            raise ValueError(f"Base link depends on Node: {item}")
    base_libraries = {item["library"] for item in parts["base"]["native"]}
    node_libraries = {item["library"] for item in parts["node"]["native"]} - base_libraries
    manifests = {}
    for name, records in parts.items():
        root = output / name
        if root.exists():
            shutil.rmtree(root)  # Only generated partition outputs, never app/user data.
        target = root / ("assets/developer-runtime" if name == "base" else "assets/developer-runtime-node")
        native = root / "jniLibs/arm64-v8a"
        target.mkdir(parents=True)
        native.mkdir(parents=True)
        with zipfile.ZipFile(assets / "prefix.zip") as src, zipfile.ZipFile(target / "prefix.zip", "w", zipfile.ZIP_DEFLATED, compresslevel=6) as dst:
            for item in records["files"]:
                data = src.read(item["path"])
                if digest(data) != item["sha256"]:
                    raise ValueError(f"Source integrity mismatch: {item['path']}")
                info = src.getinfo(item["path"])
                dst.writestr(info, data)
        for library in sorted(base_libraries if name == "base" else node_libraries):
            shutil.copyfile(source / "jniLibs/arm64-v8a" / library, native / library)
        if name == "base":
            shutil.copyfile(source / "jniLibs/arm64-v8a/libdevruntime-launcher.so", native / "libdevruntime-launcher.so")
            shutil.copyfile(source / "jniLibs/arm64-v8a/libdevruntime-node.so", native / "libdevruntime-node.so")
            # Keep the complete source/license inventory once, with the mandatory base.
            shutil.copytree(assets / "notices", target / "notices")
        manifest = dict(full, **records, component=name, sourcePackId=full["id"],
                        payloadSha256=digest((target / "prefix.zip").read_bytes()),
                        payloadBytes=(target / "prefix.zip").stat().st_size)
        manifest.pop("id")
        manifest["id"] = "termux-arm64-" + digest(json.dumps(manifest, sort_keys=True).encode())[:16]
        manifests[name] = manifest
        (target / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    catalog = {"format": 1, "module": MODULE, "assetRoot": "developer-runtime-node",
               "id": manifests["node"]["id"], "sourcePackId": full["id"],
               "payloadBytes": manifests["node"]["payloadBytes"],
               "nativeBytes": sum((output / "node/jniLibs/arm64-v8a" / f).stat().st_size for f in node_libraries)}
    (output / "base/assets/developer-runtime/delivery.json").write_text(json.dumps(catalog, indent=2) + "\n")
    print(f"Node feature: {len(node_libraries)} native payloads, {len(parts['node']['files'])} data files; no duplicate native bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    partition(args.source, args.output)
