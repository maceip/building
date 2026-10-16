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
XTOOL_MODULE = "developer_runtime_xtool"
SWIFT_SUPPORT_MODULE = "developer_runtime_swift_support"
FEATURES = {"node": MODULE, "xtool": XTOOL_MODULE, "swift_support": SWIFT_SUPPORT_MODULE}


def component(item):
    return item["component"] if item.get("component") in {"xtool", "swift_support"} else "node" if item["owner"] in NODE_OWNERS else "base"


def permitted_dependencies(part):
    return {"base", "xtool", "swift_support"} if part in {"xtool", "swift_support"} else {"base", part}


def path_owners(parts):
    paths = {}
    for part, records in parts.items():
        for key in ("files", "native", "links"):
            for item in records[key]:
                if item["path"] in paths:
                    raise ValueError("Duplicate runtime path: " + item["path"])
                paths[item["path"]] = part
    # Directory links are valid (e.g. Swift reuses the base Clang resource directory).
    directories = {}
    for path, part in paths.items():
        for parent in Path(path).parents:
            if str(parent) != ".":
                directories.setdefault(str(parent), set()).add(part)
    return paths, directories


def digest(data):
    return hashlib.sha256(data).hexdigest()


def partition(source, output):
    assets = source / "assets/developer-runtime"
    full = json.loads((assets / "manifest.json").read_text())
    parts = {name: {key: [] for key in ("files", "native", "links")} for name in ("base", *FEATURES)}
    for key in ("files", "native", "links"):
        for item in full[key]:
            parts[component(item)][key].append(item)
    paths, directories = path_owners(parts)
    links = {r["path"]: r["target"] for p in parts.values() for r in p["links"]}
    for name, target in links.items():
        owner, seen = paths[name], {name}
        while target in links and target not in seen:
            if paths[target] not in permitted_dependencies(owner):
                raise ValueError(f"Link depends on another optional pack: {name}")
            seen.add(target)
            target = links[target]
        allowed = permitted_dependencies(owner)
        if target in links or (target not in paths and target not in directories):
            raise ValueError(f"Dangling/cyclic link: {name}")
        if (target in paths and paths[target] not in allowed) or (target in directories and not directories[target] & allowed):
            raise ValueError(f"Link depends on another optional pack: {name}")
    # Deduplicate across all packs; anything shared lives once in the mandatory base.
    users = {}
    for name, records in parts.items():
        for item in records["native"]:
            users.setdefault(item["library"], set()).add(name)
    libraries = {name: set() for name in parts}
    for library, owners in users.items():
        libraries["base" if len(owners) > 1 else next(iter(owners))].add(library)
    manifests = {}
    for name, records in parts.items():
        root = output / name
        if root.exists():
            shutil.rmtree(root)  # Only generated partition outputs, never app/user data.
        target = root / ("assets/developer-runtime" if name == "base" else "assets/developer-runtime-" + name)
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
        for library in sorted(libraries[name]):
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
    catalog = {"format": 2, "modules": [
        {"module": module, "assetRoot": "developer-runtime-" + name,
         "requestModules": [XTOOL_MODULE, SWIFT_SUPPORT_MODULE] if name in {"xtool", "swift_support"} else [module],
         "hidden": name == "swift_support",
         "id": manifests[name]["id"], "sourcePackId": full["id"],
         "payloadBytes": manifests[name]["payloadBytes"],
         "nativeBytes": sum((output / name / "jniLibs/arm64-v8a" / f).stat().st_size for f in libraries[name])}
        for name, module in FEATURES.items()]}
    (output / "base/assets/developer-runtime/delivery.json").write_text(json.dumps(catalog, indent=2) + "\n")
    for name in FEATURES:
        print(f"{name} feature: {len(libraries[name])} native payloads, {len(parts[name]['files'])} data files; no duplicate native bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    partition(args.source, args.output)
