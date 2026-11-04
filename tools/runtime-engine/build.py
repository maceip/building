#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build ONE Android embedding payload from pinned source, never an installed CLI prefix.

Outputs are jniLibs/, assets/, and a corresponding-source tarball, consumed by payload
AARs and the native generated-app packager. Build-host network only. NDK is downloaded
into this tool's ignored build directory; no sdkmanager or installed config mutation.
"""
from __future__ import annotations

import argparse
import fcntl
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile

from source_io import HERE, ROOT, acquire, digest, host_environment, lock_data, run, unpack

TARGETS = {"arm64-v8a": ("aarch64-linux-android", "arm64", "android-arm64"),
           "x86_64": ("x86_64-linux-android", "x64", "android-x86_64")}
DISABLED_PYTHON = ("_bz2", "_lzma", "_zstd", "_sqlite3", "_ctypes", "_curses", "_curses_panel",
                   "_dbm", "_gdbm", "readline", "_tkinter", "_uuid", "_multiprocessing", "_posixshmem",
                   "_remote_debugging")
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}


def toolchain(ndk: Path) -> Path:
    host = "darwin-x86_64" if sys.platform == "darwin" else "linux-x86_64"
    result = ndk / "toolchains/llvm/prebuilt" / host
    if not (result / "bin/clang").is_file():
        raise ValueError(f"Unsupported/missing NDK toolchain: {result}")
    return result


def environment(ndk: Path, abi: str, api: int) -> dict:
    triplet = TARGETS[abi][0]
    env = host_environment()
    tools = toolchain(ndk) / "bin"
    env.update({"CC": str(tools / f"{triplet}{api}-clang"),
                "CXX": str(tools / f"{triplet}{api}-clang++"),
                "AR": str(tools / "llvm-ar"), "RANLIB": str(tools / "llvm-ranlib"),
                "LD": str(tools / "ld.lld"), "NM": str(tools / "llvm-nm"),
                "READELF": str(tools / "llvm-readelf"), "STRIP": str(tools / "llvm-strip"),
                "PATH": str(tools) + os.pathsep + env["PATH"],
                "CFLAGS": "-O2 -fPIC -D__BIONIC_NO_PAGE_SIZE_MACRO",
                "CXXFLAGS": "-O2 -fPIC -D__BIONIC_NO_PAGE_SIZE_MACRO",
                "LDFLAGS": "-Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 -Wl,--no-undefined",
                "PKG_CONFIG_LIBDIR": "", "PKG_CONFIG_PATH": ""})
    return env


def prepare_source(name: str, args, lock: dict, archives: dict) -> Path:
    archive = acquire(lock[name], args.work / "downloads", args.offline)
    archives[name] = archive
    return unpack(archive, args.work / "sources" / name)


def working_copy(source: Path, destination: Path) -> Path:
    if not destination.exists():
        shutil.copytree(source, destination, symlinks=True)
    return destination


def write_if_changed(path: Path, content: str) -> None:
    if not path.is_file() or path.read_text() != content:
        path.write_text(content)


def python_build(source: Path, deps: dict, args, ndk: Path, env: dict) -> tuple[Path, list[Path]]:
    work = args.work / "python" / args.abi
    work.mkdir(parents=True, exist_ok=True)
    prefix = work / "prefix"
    prefix.mkdir(exist_ok=True)
    env = dict(env)
    env["PKG_CONFIG_LIBDIR"] = str(prefix / "lib/pkgconfig")
    env["CFLAGS"] += f" -I{prefix}/include"
    env["LDFLAGS"] += f" -L{prefix}/lib -lm"
    zlib = working_copy(deps["zlib"], work / "zlib")
    if not (prefix / "lib/libz.a").exists():
        run(zlib / "configure", "--static", "--uname=Linux", f"--prefix={prefix}", cwd=zlib, env=env)
        (zlib / "libz.a").unlink(missing_ok=True)
        run("make", f"-j{args.jobs}", cwd=zlib, env=env)
        run("make", "install", cwd=zlib, env=env)
    ssl = working_copy(deps["openssl"], work / "openssl")
    if not (prefix / "lib/libssl.a").exists():
        ssl_env = dict(env, ANDROID_NDK_ROOT=str(ndk))
        run("perl", ssl / "Configure", TARGETS[args.abi][2], f"-D__ANDROID_API__={args.api}",
            "no-shared", "no-tests", "no-module", "no-engine", "no-apps", "-fPIC",
            f"--prefix={prefix}", "--libdir=lib", cwd=ssl, env=ssl_env)
        run("make", f"-j{args.jobs}", "build_libs", cwd=ssl, env=ssl_env)
        run("make", "install_dev", cwd=ssl, env=ssl_env)
    # CPython requires a matching build interpreter, not whichever host Python happens to be installed.
    host = args.work / "python/host"
    host.mkdir(parents=True, exist_ok=True)
    (host / "Modules").mkdir(exist_ok=True)
    # Host generators do not need TLS. Do not accidentally link an installed
    # OpenSSL 4 while generating a separately pinned OpenSSL 3.5 Android build.
    write_if_changed(host / "Modules/Setup.local", "*disabled*\n_ssl\n_hashlib\n")
    host_env = host_environment()
    if sys.platform == "darwin":
        host_env["CC"] = subprocess.check_output(["xcrun", "--find", "clang"], text=True).strip()
        sdk = subprocess.check_output(["xcrun", "--show-sdk-path"], text=True).strip()
        host_env.update(CFLAGS=f"-isysroot {sdk}", LDFLAGS=f"-isysroot {sdk}")
    if not (host / "Makefile").exists():
        run(source / "configure", "--without-ensurepip", "--disable-test-modules", cwd=host, env=host_env)
    run("make", f"-j{args.jobs}", cwd=host, env=host_env)
    build_python = host / ("python.exe" if (host / "python.exe").exists() else "python")
    build = work / "cpython"
    build.mkdir(exist_ok=True)
    (build / "Modules").mkdir(exist_ok=True)
    # Official CPython static-module switch: native extensions become part of libpython.
    # No executable extension is extracted from assets into writable storage.
    write_if_changed(build / "Modules/Setup.local", "*disabled*\n" + "\n".join(DISABLED_PYTHON) + "\n")
    env["MODULE_BUILDTYPE"] = "static"
    env["LIBS"] = "-ldl -lm -llog"
    build_triplet = subprocess.check_output([str(source / "config.guess")], text=True).strip()
    if not (build / "Makefile").exists():
        run(source / "configure", f"--host={TARGETS[args.abi][0]}", f"--build={build_triplet}",
            f"--with-build-python={build_python}", f"--prefix={prefix}",
            "--enable-shared", "--without-static-libpython", "--without-ensurepip",
            "--disable-test-modules", "--with-system-expat=no", "--without-readline",
            f"--with-openssl={prefix}", "--with-openssl-rpath=no", cwd=build, env=env)
    run("make", f"-j{args.jobs}", cwd=build, env=env)
    return build, [build / "libpython3.14.so"]


def node_build(source: Path, args, ndk: Path, env: dict) -> tuple[Path, list[Path]]:
    build = working_copy(source, args.work / "node" / args.abi)
    cpu = build / "deps/zlib/android-cpufeatures"
    if not cpu.exists():
        shutil.copytree(ndk / "sources/android/cpufeatures", cpu)
    # Patches are versioned inputs, fail closed when an upstream hunk no longer applies.
    for patch in sorted((HERE / "patches/node").glob("*.patch")):
        stamp = build / (".applied-" + patch.name + "-" + digest(patch))
        if not stamp.exists():
            if list(build.glob(".applied-" + patch.name + "-*")):
                raise ValueError(f"Applied patch changed: {patch}; use a fresh Node build tree")
            run("patch", "--batch", "--forward", "-p1", "-i", patch, cwd=build)
            stamp.write_text(digest(patch) + "\n")
            (build / "out/Makefile").unlink(missing_ok=True)
    env = dict(env)
    host_os = "mac" if sys.platform == "darwin" else "linux"
    if sys.platform == "darwin":
        env["CC_host"] = subprocess.check_output(["xcrun", "--find", "clang"], text=True).strip()
        env["CXX_host"] = subprocess.check_output(["xcrun", "--find", "clang++"], text=True).strip()
        sdk = subprocess.check_output(["xcrun", "--show-sdk-path"], text=True).strip()
        env.update(CFLAGS_host=f"-isysroot {sdk}", CXXFLAGS_host=f"-isysroot {sdk}",
                   LDFLAGS_host=f"-isysroot {sdk} -framework CoreFoundation")
    else:
        env.update(CC_host="cc", CXX_host="c++")
    env["AR_host"] = str(toolchain(ndk) / "bin/llvm-ar")
    env["GYP_DEFINES"] = (f"target_arch={TARGETS[args.abi][1]} v8_target_arch={TARGETS[args.abi][1]} "
                          f"android_target_arch={TARGETS[args.abi][1]} host_os={host_os} OS=android "
                          f"android_ndk_path={ndk} v8_os_page_size=16")  # V8 flag uses KiB, ELF linker flag uses bytes.
    env["LDFLAGS"] += " -static-libstdc++"
    configure_stamp = build / ".engine-configure-inputs"
    configure_identity = env["GYP_DEFINES"] + "\n" + env["LDFLAGS"] + "\n" + env["AR_host"]
    if not (build / "out/Makefile").exists() or not configure_stamp.exists() or configure_stamp.read_text() != configure_identity:
        run(sys.executable, build / "configure", f"--dest-cpu={TARGETS[args.abi][1]}",
            "--dest-os=android", "--cross-compiling", "--shared", "--without-npm",
            "--without-node-snapshot", "--openssl-no-asm", "--with-intl=small-icu",
            cwd=build, env=env)
        configure_stamp.write_text(configure_identity)
    run("make", "-C", build / "out", f"-j{args.jobs}", "BUILDTYPE=Release",
        f"AR.host={env['AR_host']}", "libnode", cwd=build, env=env)
    library = build / "out/Release/libnode.so"
    if not library.is_file():
        raise FileNotFoundError(f"Upstream shared libnode output missing: {library}")
    return build, [library]


def check_elf(path: Path, ndk: Path, abi: str, allowed: set[str]) -> None:
    readelf = toolchain(ndk) / "bin/llvm-readelf"
    header = subprocess.check_output([str(readelf), "-h", str(path)], text=True)
    machine = "AArch64" if abi == "arm64-v8a" else "Advanced Micro Devices X86-64"
    if machine not in header or "DYN" not in header:
        raise ValueError(f"Not a {abi} shared ELF: {path}")
    program = subprocess.check_output([str(readelf), "-lW", str(path)], text=True)
    for line in program.splitlines():
        if line.strip().startswith("LOAD") and int(line.split()[-1], 16) < 16384:
            raise ValueError(f"ELF is not 16-KiB page compatible: {path}")
    dynamic = subprocess.check_output([str(readelf), "-dW", str(path)], text=True)
    needed = set(re.findall(r"\(NEEDED\).*?\[([^]]+)\]", dynamic))
    if needed - allowed - SYSTEM_LIBS:
        raise ValueError(f"Unpackaged native dependencies in {path}: {needed - allowed - SYSTEM_LIBS}")
    if "RPATH" in dynamic or "RUNPATH" in dynamic:
        raise ValueError(f"Runtime search prefix forbidden: {path}")
    blob = path.read_bytes()
    for forbidden in (b"/data/data/com.termux", b"/data/user/0/com.termux"):
        if forbidden in blob:
            raise ValueError(f"Termux prefix in embedding artifact: {path}")
    symbols = subprocess.check_output([str(readelf), "--dyn-syms", "--wide", str(path)], text=True)
    symbol_by_library = {"libnode.so": "_ZN4node5Start", "libpython3.14.so": "Py_InitializeFromConfig",
                         "libengine_node.so": "ca_get_engine_v1", "libengine_python.so": "ca_get_engine_v1",
                         "libengine_host.so": "Java_dev_ide_runtime_engine_NativeBridge_run"}
    expected = symbol_by_library.get(path.name)
    if expected and expected not in symbols:
        raise ValueError(f"Native engine entry point missing from {path}: {expected}")


def compile_bridges(args, ndk: Path, env: dict, source: Path, build: Path, output: Path) -> list[Path]:
    native = ROOT / "android/runtime-engine/native"
    libs = output / "jniLibs" / args.abi
    libs.mkdir(parents=True, exist_ok=True)
    flags = ["-O2", "-fPIC", "-fvisibility=hidden", "-shared", "-Wl,-z,max-page-size=16384",
             "-Wl,--build-id=sha1", "-Wl,--no-undefined"]
    host = libs / "libengine_host.so"
    run(env["CC"], *flags, "-std=c11", native / "engine_host.c", "-ldl", "-llog",
        "-Wl,-soname,libengine_host.so", "-o", host, cwd=HERE)
    adapter = libs / f"libengine_{args.engine}.so"
    if args.engine == "node":
        run(env["CXX"], *flags, "-std=c++20", "-static-libstdc++", "-I" + str(source / "src"),
            "-I" + str(source / "deps/v8/include"), native / "engine_node.cc", "-L" + str(libs), "-lnode",
            "-Wl,-soname,libengine_node.so", "-o", adapter, cwd=HERE)
    else:
        run(env["CC"], *flags, "-std=c11", "-I" + str(source / "Include"), "-I" + str(build),
            native / "engine_python.c", "-L" + str(libs), "-lpython3.14",
            "-Wl,-soname,libengine_python.so", "-o", adapter, cwd=HERE)
    return [host, adapter]


def stdlib_zip(source: Path, destination: Path) -> None:
    excluded = {"test", "tests", "idlelib", "tkinter", "turtledemo", "ensurepip", "__pycache__", "site-packages"}
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted((source / "Lib").rglob("*")):
            relative = path.relative_to(source / "Lib")
            if not path.is_file() or set(relative.parts) & excluded or path.suffix != ".py":
                continue
            info = zipfile.ZipInfo(relative.as_posix(), date_time=(2026, 9, 30, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, path.read_bytes())


def package_sources(args, sources: dict, archives: dict, output: Path, ndk: Path) -> Path:
    licenses = output / "assets/runtime-engine" / args.engine / "licenses"
    licenses.mkdir(parents=True, exist_ok=True)
    # All upstream license files are retained, not just a guessed SPDX string.
    notices = ["CodeAssist runtime engine: GPL-3.0-or-later. No runtime linking exception has been granted.",
               "This bundle is collected source material, not a legal compliance certification.",
               "Generated app code and any third-party packages added by its author require a separate review."]
    for name, source in sources.items():
        found = 0
        for path in sorted(source.rglob("*")):
            if path.is_file() and (path.name.upper().startswith(("LICENSE", "LICENCE", "COPYING", "NOTICE"))
                                   or path.name.lower() in ("copyright", "copyright.txt")):
                relative = Path(name) / path.relative_to(source)
                target = licenses / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)
                found += 1
        if not found:
            raise ValueError(f"No actual license files collected for {name}")
        notices.append(f"{name}: source archive {archives[name].name}; SHA256 {digest(archives[name])}")
    for name in ("LICENSE", "LICENSE-EXCEPTION"):
        shutil.copyfile(ROOT / name, licenses / ("CodeAssist-" + name))
    for name in ("NOTICE", "NOTICE.toolchain", "source.properties"):
        shutil.copyfile(ndk / name, licenses / ("NDK-" + name))
    # Source correspondence includes compiler runtime / CRT code linked from the
    # pinned NDK, not just the engine source and a link to the toolchain website.
    source_pins = lock_data()
    extra_archives = {}
    for name in ("llvm_runtime", "llvm_android", "bionic", "ndk_sources"):
        archive = acquire(source_pins[name], args.work / "downloads", args.offline)
        extra_archives[name] = archive
        notices.append(f"{name}: {archive.name}; SHA256 {digest(archive)}; commit {source_pins[name]['commit']}")
        with tarfile.open(archive) as upstream:
            for entry in upstream:
                # Preserve notices without extracting arbitrary source paths.
                if entry.isfile() and Path(entry.name).name.upper().startswith(("LICENSE", "LICENCE", "COPYING", "NOTICE")):
                    target = licenses / name / entry.name
                    if not target.resolve().is_relative_to(licenses.resolve()):
                        raise ValueError("Upstream license path escapes bundle")
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with upstream.extractfile(entry) as stream:
                        target.write_bytes(stream.read())
    (licenses / "NOTICE.txt").write_text("\n".join(notices) + "\n")
    bundle = output / f"{args.engine}-{source_pins[args.engine]['version']}-corresponding-source.tar.xz"
    with tarfile.open(bundle, "w:xz", preset=1) as tar:
        for name, archive in sorted({**archives, **extra_archives}.items()):
            tar.add(archive, arcname="upstream/" + archive.name, recursive=False)
        tar.add(ndk / "sources/android/cpufeatures", arcname="ndk-linked-sources/cpufeatures")
        tar.add(toolchain(ndk) / "clang_source_info.md", arcname="ndk-linked-sources/clang_source_info.md")
        tar.add(ndk / "source.properties", arcname="ndk-linked-sources/source.properties")
        for directory in (HERE, ROOT / "android/runtime-engine", ROOT / "android/applog-runtime/src/main/java",
                          ROOT / "services/developer-workbench/src/main/kotlin/dev/ide/workbench/appengine"):
            for path in sorted(directory.rglob("*")):
                relative = path.relative_to(ROOT)
                if not path.is_file() or any(part in {"build", ".gradle", "__pycache__"} for part in relative.parts):
                    continue
                tar.add(path, arcname="local/" + relative.as_posix(), recursive=False)
        for name in ("LICENSE", "LICENSE-EXCEPTION", "android/applog-runtime/build.gradle.kts"):
            tar.add(ROOT / name, arcname="local/" + name, recursive=False)
        tar.add(licenses, arcname="notices")
    return bundle


def stage(args, lock: dict, ndk: Path, sources: dict, archives: dict, build: Path, libraries: list[Path], env: dict) -> None:
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=args.output.name + ".staging-", dir=args.output.parent))
    try:
        libs = temporary / "jniLibs" / args.abi
        libs.mkdir(parents=True)
        for library in libraries:
            if not library.is_file():
                raise FileNotFoundError(f"Native engine not built: {library}")
            shutil.copyfile(library, libs / library.name)
        compile_bridges(args, ndk, env, sources[args.engine], build, temporary)
        names = {p.name for p in libs.iterdir()}
        for library in libs.iterdir():
            run(env["STRIP"], "--strip-unneeded", library, cwd=HERE)
            check_elf(library, ndk, args.abi, names)
        assets = temporary / "assets/runtime-engine" / args.engine
        assets.mkdir(parents=True)
        if args.engine == "python":
            stdlib_zip(sources["python"], assets / "python314.zip")
        support = lock[args.engine]["support"]
        capabilities = ("javascript,builtin-node-modules,small-icu,no-npm,no-native-addons" if args.engine == "node"
                        else "python,pure-python-packages,json,asyncio,socket,ssl,zlib,no-pip,no-native-extensions")
        metadata = (f"schema=1\nengine={args.engine}\nversion={lock[args.engine]['version']}\n"
                    f"abi={args.abi}\napi={args.api}\nqualified=false\nsupport={support}\n"
                    f"license=GPL-3.0-or-later\ncapabilities={capabilities}\n"
                    f"disabledModules={','.join(DISABLED_PYTHON) if args.engine == 'python' else 'npm,native-addons'}\n")
        (assets / "engine.properties").write_text(metadata)
        source_bundle = package_sources(args, sources, archives, temporary, ndk)
        # Runtime parser verifies hashes of assets at extraction. This is a shipping index, not a report.
        entries = []
        for path in sorted(assets.rglob("*")):
            if path.is_file():
                entries.append(f"{digest(path)}\t{path.stat().st_size}\t{path.relative_to(assets).as_posix()}")
        (assets / "files.sha256").write_text("\n".join(entries) + "\n")
        (temporary / "SHA256SUMS").write_text("\n".join(
            f"{digest(p)}  {p.relative_to(temporary).as_posix()}" for p in sorted(temporary.rglob("*")) if p.is_file()) + "\n")
        if args.output.exists():
            shutil.rmtree(args.output)
        temporary.replace(args.output)
        print(f"Staged {args.engine} for {args.abi}: {args.output}")
        print(f"Native bytes: {sum(p.stat().st_size for p in (args.output / 'jniLibs').rglob('*.so'))}")
        print(f"Corresponding source: {args.output / source_bundle.name}")
        print("Android device qualification, generated APK recovery, and license release approval remain separate gates.")
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", choices=["node", "python"], required=True)
    parser.add_argument("--abi", choices=list(TARGETS), default="arm64-v8a")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--work", type=Path, default=HERE / "build")
    parser.add_argument("--ndk", type=Path, help="Existing read-only NDK; exact pinned revision required")
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--allow-unqualified-node", action="store_true")
    parser.add_argument("--native-only", action="store_true", help="Build source library without staging adapters")
    args = parser.parse_args()
    if args.engine == "node" and not args.allow_unqualified_node:
        parser.error("Upstream does not support Android. Explicit --allow-unqualified-node required; not production qualification.")
    if args.jobs < 1 or args.jobs > 32:
        parser.error("--jobs must be in 1..32")
    lock = lock_data()
    args.api = lock["api"]
    args.work = args.work.resolve()
    args.output = (args.output or HERE / "build/payloads" / args.engine).resolve()
    if sys.platform not in ("darwin", "linux"):
        parser.error("Native build host must be macOS or Linux")
    args.work.mkdir(parents=True, exist_ok=True)
    # Held for this process lifetime, including staging. Both ABIs share the
    # build interpreter, so lock by engine, not by ABI. Node/Python remain independent.
    build_lock = (args.work / ("." + args.engine + ".build.lock")).open("a+")
    try:
        fcntl.flock(build_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        parser.error(f"Another {args.engine} source build/staging operation is active in {args.work}")
    ndk = args.ndk.resolve() if args.ndk else unpack(
        acquire(lock["ndk"]["darwin" if sys.platform == "darwin" else "linux"], args.work / "downloads", args.offline),
        args.work / "sources/ndk")
    if f"Pkg.Revision = {lock['ndk_revision']}" not in (ndk / "source.properties").read_text():
        parser.error(f"NDK must be exactly {lock['ndk_revision']}; no installed SDK changes are made")
    archives = {}
    names = ["node"] if args.engine == "node" else ["python", "openssl", "zlib"]
    sources = {name: prepare_source(name, args, lock, archives) for name in names}
    env = environment(ndk, args.abi, args.api)
    if args.engine == "node":
        build, libraries = node_build(sources["node"], args, ndk, env)
    else:
        build, libraries = python_build(sources["python"], sources, args, ndk, env)
    if args.native_only:
        print("Built source libraries only:", *libraries)
    else:
        stage(args, lock, ndk, sources, archives, build, libraries, env)


if __name__ == "__main__":
    main()
