#!/usr/bin/env python3
"""Stage a checksum-locked Android runtime into APK assets and installer-owned native libraries.

Network is used ONLY on the build host. Nothing in the Android provisioner fetches a URL.
No archive scripts execute. Only files beneath the exact Termux prefix are admitted.
"""
import argparse
import base64
import concurrent.futures
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import struct
import subprocess
import sys
import tarfile
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parent
PREFIX = "data/data/com.termux/files/usr/"
EXCLUDED = ("libexec/installed-tests/",)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def package_specs():
    lock_bytes = (HERE / "packages.lock.json").read_bytes()
    lock = json.loads(lock_bytes)
    catalog_bytes = (HERE / lock["bootstrapCatalog"]).resolve().read_bytes()
    catalog = json.loads(catalog_bytes)
    by_name = {p["name"]: p for p in catalog["packages"]}
    selected = [by_name[name] for name in lock["bootstrapPackages"]] + lock["packages"]
    if len({p["name"] for p in selected}) != len(selected):
        raise ValueError("Duplicate package in runtime lock")
    for p in selected:
        if len(p["sha256"]) != 64 or not p["filename"].startswith("pool/") or ".." in p["filename"]:
            raise ValueError("Invalid locked package")
    return lock, sorted(selected, key=lambda p: p["name"])


def fetch_package(spec, base, cache, offline):
    cached = cache / (spec["sha256"] + ".deb")
    if cached.exists():
        data = cached.read_bytes()
        if len(data) == spec["size"] and sha256(data) == spec["sha256"]:
            return cached
        raise ValueError(f"Corrupt cached package (remove this file to retry): {cached}")
    if offline:
        raise ValueError(f"Missing offline build input: {spec['name']}")
    request = urllib.request.Request(base + spec["filename"], headers={"User-Agent": "Building-Runtime-Pack/1"})
    with urllib.request.urlopen(request, timeout=120) as response:
        data = response.read(spec["size"] + 1)
    if len(data) != spec["size"] or sha256(data) != spec["sha256"]:
        raise ValueError(f"Package size/SHA256 mismatch: {spec['name']}")
    temporary = cached.with_suffix(".partial")
    temporary.write_bytes(data)
    temporary.replace(cached)
    print(f"Verified {spec['name']} {spec['version']} ({len(data)} bytes)", flush=True)
    return cached


def ar_data(data):
    if not data.startswith(b"!<arch>\n"):
        raise ValueError("Not a Debian ar archive")
    offset = 8
    while offset + 60 <= len(data):
        header = data[offset:offset + 60]
        if header[58:60] != b"`\n":
            raise ValueError("Invalid ar header")
        size = int(header[48:58].strip())
        name = header[:16].decode("ascii").strip().rstrip("/")
        offset += 60
        content = data[offset:offset + size]
        if len(content) != size:
            raise ValueError("Truncated ar entry")
        if name.startswith("data.tar"):
            return content
        offset += size + size % 2
    raise ValueError("Debian archive has no data.tar")


def safe_relative(name):
    name = name.removeprefix("./")
    if not name.startswith(PREFIX):
        return None
    relative = name[len(PREFIX):].rstrip("/")
    if not relative:
        return None
    if "\\" in relative or any(x in ("", ".", "..") for x in relative.split("/")):
        raise ValueError(f"Unsafe archive path: {name}")
    return relative


def verify_elf(data, name):
    if data[:6] != b"\x7fELF\x02\x01" or len(data) < 64 or struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError(f"Expected arm64 ELF: {name}")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    headers = [struct.unpack_from("<IIQQQQQQ", data, offset + i * size) for i in range(count)]
    alignments = [h[-1] for h in headers if h[0] == 1]
    if not alignments or min(alignments) < 16384:
        raise ValueError(f"Native input is not 16 KB aligned: {name}: {alignments}")
    return alignments


def collect(packages, archives):
    files, links, owners = {}, {}, {}
    for package in packages:
        with tarfile.open(fileobj=io.BytesIO(ar_data(archives[package["name"]].read_bytes())), mode="r:*") as tar:
            for entry in tar:
                name = safe_relative(entry.name)
                if name is None or entry.isdir() or name.startswith(EXCLUDED) or name.endswith(".pyc"):
                    continue
                if entry.issym():
                    target = entry.linkname
                    if target.startswith("/" + PREFIX):
                        target = target[len(PREFIX) + 1:]
                    elif target.startswith("/"):
                        raise ValueError(f"External absolute symlink: {name} -> {target}")
                    else:
                        target = str(PurePosixPath(name).parent / target)
                    parts = []
                    for part in target.split("/"):
                        if part == "..":
                            if not parts:
                                raise ValueError(f"Escaping link: {name}")
                            parts.pop()
                        elif part not in ("", "."):
                            parts.append(part)
                    links[name] = "/".join(parts)
                elif entry.islnk():
                    target = safe_relative(entry.linkname)
                    if not target:
                        raise ValueError(f"Invalid hard link: {name}")
                    links[name] = target
                elif entry.isfile():
                    if entry.size > 128 * 1024 * 1024:
                        raise ValueError(f"Oversized runtime member: {name}")
                    content = tar.extractfile(entry).read()
                    if name in files and files[name] != content:
                        raise ValueError(f"Conflicting package path: {name}")
                    files[name] = content
                else:
                    raise ValueError(f"Special file not allowed: {name}")
                owners[name] = package["name"]
    return files, links, owners


def add_npm_tools(files, links, owners, cache, offline):
    lock = json.loads((HERE / "npm-tools.lock.json").read_bytes())
    if lock["format"] != 1:
        raise ValueError("Unsupported npm tool lock")
    for package in lock["packages"]:
        cached = cache / (package["sha256"] + ".npm.tgz")
        if not cached.exists():
            if offline:
                raise ValueError(f"Missing offline npm tool: {package['name']}")
            if not package["url"].startswith("https://registry.npmjs.org/"):
                raise ValueError("Only exact npm registry tool archives are supported")
            with urllib.request.urlopen(package["url"], timeout=90) as response:
                data = response.read(package["size"] + 1)
        else:
            data = cached.read_bytes()
        integrity = "sha512-" + base64.b64encode(hashlib.sha512(data).digest()).decode()
        if len(data) != package["size"] or sha256(data) != package["sha256"] or integrity != package["integrity"]:
            raise ValueError(f"Npm tool integrity mismatch: {package['name']}")
        if not cached.exists():
            cached.write_bytes(data)
        base = "lib/node_modules/" + package["name"] + "/"
        seen, excluded = set(), set()
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
            for entry in archive:
                name = entry.name.removeprefix("package/")
                if not entry.name.startswith("package/") or not name or any(p in ("", ".", "..") for p in name.split("/")) or "\\" in name:
                    raise ValueError("Invalid npm tool archive path")
                if entry.isdir():
                    continue
                if not entry.isfile() or name in seen or entry.size > 32 * 1024 * 1024:
                    raise ValueError("Unsupported npm tool archive member")
                seen.add(name)
                if name in package.get("excludedPlatformArtifacts", []):
                    excluded.add(name)
                    continue
                target = base + name
                if target in files or target in links:
                    raise ValueError("Npm tool collides with a runtime file")
                files[target] = archive.extractfile(entry).read()
                owners[target] = package["name"]
        if excluded != set(package.get("excludedPlatformArtifacts", [])):
            raise ValueError("Npm tool platform-artifact exclusions changed")
        for command, target in package["bin"].items():
            if base + target not in files or "bin/" + command in files or "bin/" + command in links:
                raise ValueError("Invalid npm tool executable mapping")
            links["bin/" + command] = base + target
            owners["bin/" + command] = package["name"]
        if not any(name.upper().endswith("LICENSE") for name in seen):
            raise ValueError("Npm tool license missing")
    return lock["packages"]


def add_compiler_wrappers(files, links, owners):
    compilers = [name for name in files if name.startswith("bin/clang-") and name.removeprefix("bin/clang-").isdigit()]
    if not compilers:
        return
    if len(compilers) != 1:
        raise ValueError("Expected one pinned Clang development compiler")
    compiler = compilers[0]
    major = compiler.removeprefix("bin/clang-")
    # Installer-owned ELF filenames cannot supply Clang's usual relative sysroot/resource discovery.
    for name, cxx in (("clang", False), ("clang++", True), ("cc", False), ("c++", True), ("gcc", False), ("g++", True)):
        arguments = [f'"$PREFIX/{compiler}"', "--driver-mode=g++" if cxx else "--driver-mode=gcc",
                     "--target=aarch64-linux-android24", '"--sysroot=$PREFIX"',
                     f'"-resource-dir=$PREFIX/lib/clang/{major}"']
        if cxx:
            arguments += ["-isystem", '"$PREFIX/include/c++/v1"']
        arguments += ["-isystem", '"$PREFIX/include"', "-isystem", '"$PREFIX/include/aarch64-linux-android"',
                      '"-B$PREFIX/bin"', '"-B$PREFIX/lib"', '"-L$PREFIX/lib"',
                      "-L/system/lib64", "-L/apex/com.android.runtime/lib64/bionic",
                      '"--ld-path=$PREFIX/bin/ld.lld"', '"$@"']
        path = "bin/" + name
        links.pop(path, None)
        files[path] = ('#!/system/bin/sh\n: "${PREFIX:?Bundled runtime prefix is required}"\nexec ' + " ".join(arguments) + "\n").encode()
        owners[path] = "building-runtime (GPL-3.0-or-later)"


def find_ndk(explicit):
    if explicit:
        candidates = [Path(explicit)]
    else:
        candidates = [HERE.parent / "android-sdk/build/android-ndk-r29-beta1"]
        for variable in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
            if os.environ.get(variable):
                candidates.insert(0, Path(os.environ[variable]))
        for sdk in (os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME"), str(Path.home() / "Library/Android/sdk"), "/opt/homebrew/share/android-commandlinetools"):
            if sdk:
                candidates += sorted((Path(sdk) / "ndk").glob("*"), reverse=True)
    host = "darwin-x86_64" if sys.platform == "darwin" else "linux-x86_64"
    for ndk in candidates:
        cc = ndk / f"toolchains/llvm/prebuilt/{host}/bin/aarch64-linux-android26-clang"
        if cc.is_file():
            return cc
    raise ValueError("Install an Android NDK before staging; this task never downloads an NDK. Set ANDROID_NDK_HOME.")


def stage_provenance(packages, assets, cache, offline):
    recipes = json.loads((HERE / "recipes.lock.json").read_text())
    notices = assets / "notices"
    recipes_out = notices / "recipes"
    recipes_out.mkdir(parents=True, exist_ok=True)
    inventory = []
    for package in packages:
        name = package["name"]
        parent = recipes["aliases"].get(name, name)
        recipe = recipes["recipes"][parent]
        url = recipes["baseUrl"] + parent + "/build.sh"
        cached = cache / (recipe["sha256"] + ".recipe")
        if not cached.exists():
            if offline:
                raise ValueError(f"Missing offline source-provenance input: {name}")
            with urllib.request.urlopen(url, timeout=45) as source:
                data = source.read(1024 * 1024)
            if sha256(data) != recipe["sha256"]:
                raise ValueError(f"Source recipe hash mismatch: {name}")
            cached.write_bytes(data)
        data = cached.read_bytes()
        if sha256(data) != recipe["sha256"]:
            raise ValueError(f"Corrupt source recipe cache: {name}")
        (recipes_out / (parent + ".sh")).write_bytes(data)
        inventory.append(dict(package, license=recipe["license"], recipeUrl=url,
                              recipeSha256=recipe["sha256"],
                              sourceTree=f"https://github.com/termux/termux-packages/tree/{recipes['commit']}/packages/{parent}"))
    (notices / "packages.json").write_text(json.dumps({"sourceRevision": recipes["commit"], "packages": inventory}, indent=2) + "\n")
    # The host app is GPL-3.0-or-later; retain the full license used by gdbm/readline too.
    shutil.copyfile(HERE.parent.parent / "LICENSE", notices / "GPL-3.0.txt")
    (notices / "distribution.txt").write_text(
        "Building developer runtime: native executables and libraries are delivered in the APK.\n"
        "Package archives are pinned by size/SHA256; recipes are pinned by source revision/SHA256.\n"
        "Upstream copyright/license files inside package data are preserved in prefix.zip, including\n"
        "Node/V8 notices, CPython LICENSE, npm LICENSE and npm/pip vendored dependency licenses.\n"
        "Termux recipes are not the full corresponding source. Before redistribution, provide the exact\n"
        "corresponding source (including patches/build configuration) for copyleft binaries and complete\n"
        "upstream notices as required. A URL to a mutable package repository is not a source offer.\n"
        "No assertion of Google Play approval is made. See the app's runtime-platform documentation.\n"
    )
    return inventory


def zip_entry(archive, name, data):
    info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100600 << 16
    archive.writestr(info, data)


def stage_native(directory, name, data, owner):
    """Keep every logical tool path while storing byte-identical ELF payloads once."""
    alignment = verify_elf(data, name)
    digest = sha256(data)
    packaged = "libdevruntime_" + digest + ".so"
    destination = directory / packaged
    if not destination.is_file() or destination.read_bytes() != data:
        destination.write_bytes(data)
        destination.chmod(0o755)
    return {"path": name, "library": packaged, "sha256": digest, "size": len(data),
            "owner": owner, "loadAlignments": alignment}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cache", type=Path, default=HERE / "build/cache")
    parser.add_argument("--ndk")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    lock, packages = package_specs()
    output = args.output.resolve()
    args.cache.mkdir(parents=True, exist_ok=True)
    output.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        paths = list(pool.map(lambda p: fetch_package(p, lock["baseUrl"], args.cache, args.offline), packages))
    archives = dict(zip((p["name"] for p in packages), paths))
    files, links, owners = collect(packages, archives)
    npm_tools = add_npm_tools(files, links, owners, args.cache, args.offline)
    add_compiler_wrappers(files, links, owners)
    native_dir = output / "jniLibs/arm64-v8a"
    assets = output / "assets/developer-runtime"
    native_dir.mkdir(parents=True, exist_ok=True)
    assets.mkdir(parents=True, exist_ok=True)
    provenance = stage_provenance(packages, assets, args.cache, args.offline)
    shutil.copyfile(HERE / "npm-tools.lock.json", assets / "notices/npm-tools.lock.json")
    compiler = find_ndk(args.ndk)
    generated_libraries = []
    supplemental_notices = None
    if any(package["name"] == "libandroid-spawn" for package in packages):
        import build_android_spawn
        generated = output / "source-built/android-spawn"
        build_android_spawn.build_library(compiler.parents[5], args.cache, generated, args.offline)
        generated_libraries.append(build_android_spawn.apply_override(files, owners, generated))
        shutil.copytree(generated / "notices/libandroid-spawn", assets / "notices/libandroid-spawn", dirs_exist_ok=True)
        supplemental_notices = build_android_spawn.stage_optional_notices(args.cache, assets, args.offline)
    launcher = native_dir / "libdevruntime-launcher.so"
    subprocess.run([str(compiler), str(HERE / "runtime-launcher.c"), "-Os", "-fPIE", "-pie", "-Wl,-z,max-page-size=16384,-z,common-page-size=16384", "-o", str(launcher)], check=True)
    verify_elf(launcher.read_bytes(), launcher.name)
    node_launcher = native_dir / "libdevruntime-node.so"
    subprocess.run([str(compiler), str(HERE / "node-launcher.c"), "-Os", "-fPIE", "-pie", "-Wl,-z,max-page-size=16384,-z,common-page-size=16384", "-o", str(node_launcher)], check=True)
    verify_elf(node_launcher.read_bytes(), node_launcher.name)
    env_helper = output / "runtime-env"
    subprocess.run([str(compiler), str(HERE / "runtime-env.c"), "-Os", "-fPIE", "-pie", "-Wl,-z,max-page-size=16384,-z,common-page-size=16384", "-o", str(env_helper)], check=True)
    files["bin/env"] = env_helper.read_bytes()
    owners["bin/env"] = "building-runtime (GPL-3.0-or-later)"
    payload = assets / "prefix.zip"
    native, data_manifest, kept_links = [], [], []
    live_native = {launcher.name, node_launcher.name}
    with zipfile.ZipFile(payload, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name, data in sorted(files.items()):
            if data.startswith(b"\x7fELF") and len(data) >= 18 and struct.unpack_from("<H", data, 16)[0] in (2, 3):
                entry = stage_native(native_dir, name, data, owners[name])
                native.append(entry)
                live_native.add(entry["library"])
            else:
                original_hash = sha256(data)
                transform = None
                if data.startswith(b"#!/data/data/com.termux/files/usr/"):
                    # termux-exec parses shebangs internally, bypassing our execve-entry relocator.
                    # Its standard /usr prefix mapping honors TERMUX__PREFIX. Only interpreted
                    # script headers change; upstream body and all native bytes remain intact.
                    data = b"#!/usr/" + data[len(b"#!/data/data/com.termux/files/usr/"):]
                    transform = "legacy-Termux-shebang-to-/usr (termux-exec relocates to active prefix)"
                zip_entry(archive, name, data)
                data_manifest.append({"path": name, "owner": owners[name], "sha256": sha256(data), "size": len(data), "executableScript": data.startswith(b"#!"), "originalSha256": original_hash, "transform": transform})
        for name, target in sorted(links.items()):
            # Omit dangling links while retaining development headers and libraries.
            seen = {name}
            resolved = target
            while resolved in links and resolved not in seen:
                seen.add(resolved)
                resolved = links[resolved]
            if resolved not in files:
                continue
            kept_links.append({"path": name, "target": target, "owner": owners[name]})
    # Delete only this generator's obsolete output files, never user/workspace directories.
    for old in native_dir.glob("libdevruntime_*.so"):
        if old.name not in live_native:
            old.unlink()
    identity = {"format": 1, "abi": lock["abi"], "packages": provenance, "npmTools": npm_tools, "generatedLibraries": generated_libraries, "supplementalNotices": supplemental_notices, "launcherSha256": sha256(launcher.read_bytes()), "nodeLauncherSha256": sha256(node_launcher.read_bytes()), "payloadSha256": sha256(payload.read_bytes()), "stagingVersion": sha256((HERE / "build.py").read_bytes() + (HERE / "runtime-env.c").read_bytes())}
    manifest = dict(identity, id="termux-arm64-" + sha256(json.dumps(identity, sort_keys=True).encode())[:16],
                    payloadBytes=payload.stat().st_size, native=native, files=data_manifest, links=kept_links,
                    approvedNpmPackages=lock["approvedNpmPackages"], policy=lock["policy"], metadata=lock["metadata"],
                    sourceRecipes=lock["sourceRecipes"],
                    sourceNotice="Exact matching package sources/patches and required license notices must accompany release distribution. GPL components include gdbm/readline; build-time fetch does not waive source obligations.")
    (assets / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Staged {len(packages)} pinned packages: {len(native)} native files, {payload.stat().st_size} asset bytes; pack {manifest['id']}")


if __name__ == "__main__":
    main()
