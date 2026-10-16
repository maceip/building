#!/usr/bin/env python3
"""Verify actual Play AAB partition bytes, manifests, ownership and link closure."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from partition import MODULE, XTOOL_MODULE, FEATURES, component, path_owners, SWIFT_SUPPORT_MODULE
from build import verify_elf


def inspect(path):
    with zipfile.ZipFile(path) as bundle:
        names = bundle.namelist()
        assert len(names) == len(set(names)), "Duplicate bundle entries"
        base = json.loads(bundle.read("base/assets/developer-runtime/manifest.json"))
        catalog = json.loads(bundle.read("base/assets/developer-runtime/delivery.json"))
        assert catalog["format"] == 2
        manifests = {"base": ("developer-runtime", base)}
        assert {entry["module"] for entry in catalog["modules"]} == set(FEATURES.values())
        for entry in catalog["modules"]:
            asset = entry["assetRoot"]
            manifest = json.loads(bundle.read(f"{entry['module']}/assets/{asset}/manifest.json"))
            assert entry["id"] == manifest["id"]
            assert entry["sourcePackId"] == manifest["sourcePackId"] == base["sourcePackId"]
            manifests[entry["module"]] = (asset, manifest)
        for filename, key in (("libdevruntime-launcher.so", "launcherSha256"), ("libdevruntime-node.so", "nodeLauncherSha256")):
            content = bundle.read("base/lib/arm64-v8a/" + filename)
            assert hashlib.sha256(content).hexdigest() == base[key]
            verify_elf(content, filename)
        native_bytes, paths, links = {}, {}, {}
        report = {}
        for module, (asset, manifest) in manifests.items():
            prefix = bundle.read(f"{module}/assets/{asset}/prefix.zip")
            assert hashlib.sha256(prefix).hexdigest() == manifest["payloadSha256"]
            with zipfile.ZipFile(io.BytesIO(prefix)) as data:
                assert set(data.namelist()) == {r["path"] for r in manifest["files"]}
                for record in manifest["files"]:
                    content = data.read(record["path"])
                    assert len(content) == record["size"] and hashlib.sha256(content).hexdigest() == record["sha256"]
            for key in ("files", "native", "links"):
                for record in manifest[key]:
                    assert FEATURES.get(component(record), "base") == module, record
                    assert record["path"] not in paths, record
                    paths[record["path"]] = module
                    if key == "links": links[record["path"]] = record["target"]
            for record in manifest["native"]:
                name = f'{module}/lib/arm64-v8a/{record["library"]}'
                # Shared native payloads may be referenced by the optional pack, never copied.
                if name not in names: name = f'base/lib/arm64-v8a/{record["library"]}'
                content = bundle.read(name)
                assert len(content) == record["size"] and hashlib.sha256(content).hexdigest() == record["sha256"]
                verify_elf(content, name)
                native_bytes[name] = record["sha256"]
            report[module] = {"storedBytes": sum(i.compress_size for i in bundle.infolist() if i.filename.startswith(module + "/")),
                              "files": len(manifest["files"]), "native": len(manifest["native"])}
        assert len(set(native_bytes.values())) == len(native_bytes), "Duplicated native bytes across modules"
        packaged = {n for n in names if "/lib/arm64-v8a/libdevruntime_" in n}
        assert packaged == set(native_bytes), "Unaccounted native runtime payload"
        _, directories = path_owners({module: manifest for module, (_, manifest) in manifests.items()})
        for path, target in links.items():
            allowed = {"base", XTOOL_MODULE, SWIFT_SUPPORT_MODULE} if paths[path] in {XTOOL_MODULE, SWIFT_SUPPORT_MODULE} else {"base", paths[path]}
            seen = {path}
            while target in links and target not in seen:
                assert paths[target] in allowed, f"Cross-feature link: {path}"
                seen.add(target); target = links[target]
            assert (target in paths or target in directories) and target not in links, f"Dangling/cyclic link: {path}"
            assert (paths.get(target) in allowed or bool(directories.get(target, set()) & allowed)), f"Cross-feature dependency: {path}"
        for name in ("bin/node", "bin/npm", "bin/pnpm", "include/node/node.h"):
            assert paths[name] == MODULE, name
        for name in ("bin/python3", "bin/sh", "bin/clang", "bin/ld.lld"):
            assert paths[name] == "base", name
        for name in ("bin/xtool", "bin/swift", "bin/swiftc", "bin/swift-frontend"):
            assert paths[name] == XTOOL_MODULE, name
        assert paths["lib/swift/android/Swift.swiftmodule/aarch64-unknown-linux-android.swiftmodule"] == SWIFT_SUPPORT_MODULE
        assert not any("darwin.xtoolsdk" in p or ".sdk/usr/lib/swift/iphoneos" in p for p in paths), "Apple SDK must be user supplied"
        return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aab", type=Path)
    parser.add_argument("--bundletool", type=Path, required=True)
    args = parser.parse_args()
    report = inspect(args.aab)
    subprocess.run(["java", "-jar", str(args.bundletool), "validate", "--bundle=" + str(args.aab)], check=True, stdout=subprocess.DEVNULL)
    for module in FEATURES.values():
        xml = subprocess.check_output(["java", "-jar", str(args.bundletool), "dump", "manifest", "--bundle=" + str(args.aab), "--module=" + module], text=True)
        manifest = ET.fromstring(xml)
        ns = "{http://schemas.android.com/apk/distribution}"
        assert manifest.find(f"{ns}module/{ns}delivery/{ns}on-demand") is not None
        assert manifest.find("application").get("{http://schemas.android.com/apk/res/android}extractNativeLibs") == "true"
    print(json.dumps({"verified": True, "modules": report}, indent=2))
