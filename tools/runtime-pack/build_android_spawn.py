#!/usr/bin/env python3
"""Build the explicitly source-locked 16K libandroid-spawn replacement on the host.

Never modifies a package archive, package lock, or original package digest. No
Android/device execution, Gradle, or upstream shell recipe execution is involved.
The main packager must still run its normal verify_elf on the generated member.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import shlex
import struct
import subprocess
import tarfile
import tempfile
import urllib.request

HERE = Path(__file__).resolve().parent
LOCK = HERE / "android-spawn.source.lock.json"
MANIFEST = "generated.json"
NOTICE = "notices/libandroid-spawn"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def verify_bytes(data, spec):
    if len(data) != spec["size"] or sha256(data) != spec["sha256"]:
        raise ValueError(f"Size/SHA256 mismatch: {spec.get('path', spec.get('name', 'input'))}")
    return data


def relative_path(value):
    if not value or value.startswith("/") or "\\" in value or any(p in ("", ".", "..") for p in value.split("/")):
        raise ValueError(f"Unsafe relative path: {value}")
    return value


def read_lock():
    lock = json.loads(LOCK.read_bytes())
    if lock["format"] != 1 or lock["name"] != "libandroid-spawn" or lock["patches"]:
        raise ValueError("Unsupported spawn source lock")
    for spec in [*lock["sources"], *lock["toolchain"]["files"], *lock["toolchain"]["licenses"], lock["artifact"], lock["replaces"]]:
        relative_path(spec["path"])
    if {s["path"] for s in lock["sources"]} != {"build.sh", "LICENSE", "posix_spawn.h", "posix_spawn.cpp"}:
        raise ValueError("Incomplete official source inventory")
    return lock


def fetch_source(spec, cache, offline):
    """Content-addressed source cache; corrupt cache and offline misses fail closed."""
    cache = Path(cache)
    cache.mkdir(parents=True, exist_ok=True)
    suffix = {"recipe": ".recipe", "archive": ".tar.gz"}.get(spec["role"], ".source")
    path = cache / (spec["sha256"] + suffix)
    if path.exists():
        return verify_bytes(path.read_bytes(), spec)
    if offline:
        raise ValueError(f"Missing offline source: {spec['path']}")
    if not spec["url"].startswith("https://"):
        raise ValueError("Source URL must use HTTPS")
    request = urllib.request.Request(spec["url"], headers={"User-Agent": "Building-Runtime-Source/1"})
    with urllib.request.urlopen(request, timeout=90) as response:
        data = verify_bytes(response.read(spec["size"] + 1), spec)
    with tempfile.NamedTemporaryFile(dir=cache, prefix=path.name + ".", delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(data)
    temporary.replace(path)
    return data


def elf_info(data, require_16k=True):
    """Bounded ELF64/AArch64 inspection, including actual DT_NEEDED and symbols."""
    if len(data) < 64 or data[:7] != b"\x7fELF\x02\x01\x01" or struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError("Expected little-endian ELF64/AArch64")
    if struct.unpack_from("<H", data, 16)[0] not in (2, 3):
        raise ValueError("Expected an ELF executable or shared object")

    def unpack(fmt, offset):
        if offset < 0 or offset + struct.calcsize(fmt) > len(data):
            raise ValueError("Truncated ELF structure")
        return struct.unpack_from(fmt, data, offset)

    phoff = unpack("<Q", 32)[0]
    phsize, phcount = unpack("<HH", 54)
    if phsize != 56 or not phcount or phoff + phsize * phcount > len(data):
        raise ValueError("Invalid ELF program table")
    headers = [unpack("<IIQQQQQQ", phoff + i * phsize) for i in range(phcount)]
    loads = [h for h in headers if h[0] == 1]
    if not loads:
        raise ValueError("ELF has no PT_LOAD segments")
    for h in loads:
        if h[2] + h[5] > len(data) or h[5] > h[6] or not h[7] or h[7] & (h[7] - 1):
            raise ValueError("Invalid ELF load segment")
        if (h[3] - h[2]) % h[7] or (require_16k and (h[7] < 16384 or (h[3] - h[2]) % 16384)):
            raise ValueError("ELF load segment is not 16 KB aligned")

    def address(addr, size):
        for h in loads:
            if h[3] <= addr and addr + size <= h[3] + h[5]:
                return h[2] + addr - h[3]
        raise ValueError("ELF virtual address has no file backing")

    def string(table, offset):
        end = table.find(b"\0", offset) if 0 <= offset < len(table) else -1
        if end == -1:
            raise ValueError("Invalid ELF string")
        return table[offset:end].decode("utf-8")

    entries = []
    for h in headers:
        if h[0] != 2:
            continue
        if h[5] % 16 or h[2] + h[5] > len(data):
            raise ValueError("Invalid ELF dynamic table")
        for offset in range(h[2], h[2] + h[5], 16):
            tag, value = unpack("<qQ", offset)
            if tag == 0:
                break
            entries.append((tag, value))
        else:
            raise ValueError("Unterminated ELF dynamic table")
    tags = dict(entries)
    table = b""
    if entries:
        if 5 not in tags or 10 not in tags:
            raise ValueError("Missing ELF dynamic string table")
        start = address(tags[5], tags[10])
        table = data[start:start + tags[10]]
    needed = [string(table, value) for tag, value in entries if tag == 1]
    soname = string(table, tags[14]) if 14 in tags else None
    runpath = [string(table, value) for tag, value in entries if tag in (15, 29)]
    defined, undefined = set(), set()
    shoff = unpack("<Q", 40)[0]
    shsize, shcount = unpack("<HH", 58)
    if shcount:
        if shsize != 64 or shoff + shsize * shcount > len(data):
            raise ValueError("Invalid ELF section table")
        sections = [unpack("<IIQQQQIIQQ", shoff + i * shsize) for i in range(shcount)]
        for section in sections:
            if section[1] != 11:  # SHT_DYNSYM
                continue
            if section[9] != 24 or section[5] % 24 or section[6] >= len(sections):
                raise ValueError("Invalid ELF symbol table")
            strings = sections[section[6]]
            if strings[4] + strings[5] > len(data) or section[4] + section[5] > len(data):
                raise ValueError("Truncated ELF symbol data")
            symbols = data[strings[4]:strings[4] + strings[5]]
            for offset in range(section[4], section[4] + section[5], 24):
                name, info, other, index, _, _ = unpack("<IBBHQQ", offset)
                if name and info >> 4 in (1, 2) and other & 3 not in (1, 2):
                    if index:
                        defined.add(string(symbols, name))
                    elif info >> 4 == 1:  # Weak unresolved symbols are permitted.
                        undefined.add(string(symbols, name))
    return {"loadAlignments": [h[7] for h in loads], "needed": needed, "soname": soname,
            "runpath": runpath, "exports": sorted(defined), "undefined": sorted(undefined)}


def validate_artifact(data, lock):
    verify_bytes(data, lock["artifact"])
    info = elf_info(data)
    expected = lock["artifact"]
    if info["soname"] != expected["soname"] or info["needed"] != expected["needed"] or info["runpath"]:
        raise ValueError("Generated ELF dependency/SONAME mismatch")
    if sorted(s for s in info["exports"] if s.startswith("posix_spawn")) != expected["publicExports"]:
        raise ValueError("Generated ELF public spawn ABI mismatch")
    return info


def verify_toolchain(ndk, lock):
    for spec in [*lock["toolchain"]["files"], *lock["toolchain"]["licenses"]]:
        path = (ndk / spec["path"]).resolve()
        if not path.is_relative_to(ndk):
            raise ValueError("NDK input escapes toolchain root")
        verify_bytes(path.read_bytes(), spec)


def dependency_paths(text):
    # Only the first make rule contains dependencies; lld also emits empty rules.
    rule = text.replace("\\\n", " ").splitlines()[0]
    return shlex.split(rule.split(":", 1)[1])


def consumed_inputs(work, ndk, lock):
    paths = set()
    sources = {(work / "source" / s["path"]).resolve() for s in lock["sources"]}
    for name in ("compile.d", "link.d"):
        for token in dependency_paths((work / name).read_text()):
            path = (work / token).resolve()
            if path.is_relative_to(ndk):
                paths.add(path)
            elif path not in sources and path != (work / "posix_spawn.o"):
                raise ValueError(f"Unpinned build input: {path}")
    inventory = [{"path": p.relative_to(ndk).as_posix(), "size": p.stat().st_size, "sha256": sha256(p.read_bytes())}
                 for p in sorted(paths)]
    expected = lock["toolchain"]["consumedInputs"]
    if len(inventory) != expected["count"] or sha256(canonical(inventory)) != expected["sha256"]:
        raise ValueError("Consumed NDK headers/libraries do not match the pinned toolchain inputs")
    return inventory


def build_library(ndk, cache, output, offline=False):
    """Return provenance and emit output/lib/libandroid-spawn.so plus notices."""
    lock = read_lock()
    ndk, output = Path(ndk).resolve(), Path(output).resolve()
    verify_toolchain(ndk, lock)
    source_data = {s["path"]: fetch_source(s, cache, offline) for s in lock["sources"]}
    output.mkdir(parents=True, exist_ok=True)
    tools = ndk / "toolchains/llvm/prebuilt" / lock["toolchain"]["host"] / "bin"
    compiler = tools / (lock["build"]["target"] + "-clang++")
    with tempfile.TemporaryDirectory(prefix="spawn-", dir=output) as directory:
        work = Path(directory).resolve()
        source = work / "source"
        source.mkdir()
        for name, data in source_data.items():
            (source / name).write_bytes(data)
        environment = {"PATH": "/usr/bin:/bin", "HOME": str(work), "TMPDIR": str(work), "LC_ALL": "C",
                       "LANG": "C", "TZ": "UTC", "SOURCE_DATE_EPOCH": lock["build"]["sourceDateEpoch"], "ZERO_AR_DATE": "1"}
        mappings = [f"-ffile-prefix-map={work}=.", f"-ffile-prefix-map={ndk}=NDK"]
        commands = [
            [str(compiler), *lock["build"]["compileFlags"], *mappings, "-MD", "-MF", "compile.d",
             "-c", "source/posix_spawn.cpp", "-o", "posix_spawn.o"],
            [str(compiler), *lock["build"]["linkFlags"], "posix_spawn.o", "-Wl,--dependency-file=link.d",
             "-o", "libandroid-spawn.so"],
            [str(tools / "llvm-strip"), *lock["build"]["stripFlags"], "libandroid-spawn.so"],
        ]
        for command in commands:
            subprocess.run(command, cwd=work, env=environment, check=True)
        inputs = consumed_inputs(work, ndk, lock)
        data = (work / "libandroid-spawn.so").read_bytes()
        info = validate_artifact(data, lock)
        normalized_commands = [[arg.replace(str(ndk), "$NDK").replace(str(work), "$WORK") for arg in command]
                               for command in commands]
    notices = output / NOTICE
    notices.mkdir(parents=True, exist_ok=True)
    for name, content in source_data.items():
        (notices / name).write_bytes(content)
    (notices / LOCK.name).write_bytes(LOCK.read_bytes())
    (notices / Path(__file__).name).write_bytes(Path(__file__).read_bytes())
    for spec in lock["toolchain"]["licenses"]:
        (notices / ("NDK-" + spec["path"])).write_bytes((ndk / spec["path"]).read_bytes())
    licenses = [{"path": f"{NOTICE}/LICENSE", "license": "BSD-2-Clause", "scope": "Unmodified official libandroid-spawn sources"},
                {"path": f"{NOTICE}/NDK-NOTICE", "license": "NDK component notices", "scope": "Android startup objects and headers"},
                {"path": f"{NOTICE}/NDK-NOTICE.toolchain", "license": "LLVM/NDK toolchain notices", "scope": "compiler-rt/libunwind and toolchain components"}]
    for item in licenses:
        content = (output / item["path"]).read_bytes()
        item.update(size=len(content), sha256=sha256(content))
    manifest = {"format": 1, "name": lock["name"], "version": lock["version"],
                "kind": "generated-from-pinned-source", "sourceLockSha256": sha256(LOCK.read_bytes()),
                "builderSha256": sha256(Path(__file__).read_bytes()), "sourceRevision": lock["sourceRevision"],
                "sources": lock["sources"], "patches": lock["patches"], "originalPackage": lock["originalPackage"],
                "replaces": lock["replaces"], "artifact": dict(lock["artifact"], **info),
                "toolchain": dict(lock["toolchain"], inputs=inputs), "commands": normalized_commands,
                "environment": {k: ("$WORK" if k in ("HOME", "TMPDIR") else v) for k, v in environment.items()},
                "deviations": lock["build"]["deviations"], "licenses": licenses}
    artifact = output / lock["artifact"]["path"]
    artifact.parent.mkdir(parents=True, exist_ok=True)
    artifact.write_bytes(data)
    artifact.chmod(0o755)
    encoded = json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    (notices / MANIFEST).write_text(encoded)
    (output / MANIFEST).write_text(encoded)
    return manifest


def apply_override(files, owners, output):
    """Fail closed unless both the original package member and generated inputs match."""
    lock, output = read_lock(), Path(output)
    original = lock["replaces"]
    name = original["path"]
    if owners.get(name) != lock["name"] or name not in files:
        raise ValueError("Expected locked libandroid-spawn package member before override")
    verify_bytes(files[name], original)
    header = next(s for s in lock["sources"] if s["path"] == "posix_spawn.h")
    license_spec = next(s for s in lock["sources"] if s["path"] == "LICENSE")
    verify_bytes(files.get("include/spawn.h", b""), header)
    verify_bytes(files.get("share/doc/libandroid-spawn/LICENSE", b""), license_spec)
    manifest = json.loads((output / MANIFEST).read_bytes())
    if manifest.get("sourceLockSha256") != sha256(LOCK.read_bytes()) or manifest.get("builderSha256") != sha256(Path(__file__).read_bytes()):
        raise ValueError("Generated provenance does not match current source lock/builder")
    for key in ("originalPackage", "sources", "patches", "replaces", "sourceRevision"):
        if manifest.get(key) != lock[key]:
            raise ValueError(f"Generated provenance mismatch: {key}")
    if {k: manifest["toolchain"].get(k) for k in lock["toolchain"]} != lock["toolchain"]:
        raise ValueError("Generated toolchain provenance mismatch")
    if sha256(canonical(manifest["toolchain"]["inputs"])) != lock["toolchain"]["consumedInputs"]["sha256"]:
        raise ValueError("Generated NDK input inventory mismatch")
    for spec in lock["sources"]:
        verify_bytes((output / NOTICE / spec["path"]).read_bytes(), spec)
    for spec in lock["toolchain"]["licenses"]:
        verify_bytes((output / NOTICE / ("NDK-" + spec["path"])).read_bytes(), spec)
    if (output / NOTICE / LOCK.name).read_bytes() != LOCK.read_bytes() or (output / NOTICE / Path(__file__).name).read_bytes() != Path(__file__).read_bytes():
        raise ValueError("Redistributable source lock/builder mismatch")
    data = (output / lock["artifact"]["path"]).read_bytes()
    expected = dict(lock["artifact"], **validate_artifact(data, lock))
    if manifest.get("artifact") != expected:
        raise ValueError("Generated artifact provenance mismatch")
    files[name] = data
    # Ownership stays attached to the original component; generated provenance
    # explains the only replaced file and its new, distinct source-built digest.
    return manifest


def stage_optional_notices(cache, assets, offline=False):
    """Stage pinned Ninja/libuv missing notices beneath runtime assets/notices."""
    path = HERE / "optional-notices.lock.json"
    lock = json.loads(path.read_bytes())
    if lock["format"] != 1:
        raise ValueError("Unsupported supplemental notice lock")
    destination = Path(assets) / "notices/optional-tools"
    payloads = {}
    for package in lock["packages"]:
        recipe = Path(cache) / (package["recipeSha256"] + ".recipe")
        data = recipe.read_bytes()
        if sha256(data) != package["recipeSha256"] or package["source"]["sha256"].encode() not in data:
            raise ValueError(f"Supplemental source recipe mismatch: {package['name']}")
        archive = fetch_source(package["source"], cache, offline)
        with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as tar:
            members = tar.getmembers()
            for expected in package["members"]:
                name = relative_path(expected["path"])
                matches = [m for m in members if m.name == name]
                if len(matches) != 1 or not matches[0].isfile() or matches[0].size != expected["size"]:
                    raise ValueError(f"Invalid pinned license member: {name}")
                target = relative_path(expected["output"])
                if target in payloads:
                    raise ValueError(f"Duplicate supplemental notice: {target}")
                payloads[target] = verify_bytes(tar.extractfile(matches[0]).read(), expected)
    destination.mkdir(parents=True, exist_ok=True)
    for name, data in payloads.items():
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    (destination / path.name).write_bytes(path.read_bytes())
    return {"sourceLockSha256": sha256(path.read_bytes()), "path": "notices/optional-tools", "packages": lock["packages"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--cache", type=Path, default=HERE / "build/cache")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    result = build_library(args.ndk, args.cache, args.output, args.offline)
    artifact = result["artifact"]
    print(f"Verified source-built {args.output / artifact['path']} ({artifact['size']} bytes, SHA256 {artifact['sha256']})")


if __name__ == "__main__":
    main()
