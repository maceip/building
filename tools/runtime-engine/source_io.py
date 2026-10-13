#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Verified build-host acquisition. Never imported by the Android runtime."""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tomllib
import urllib.parse
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def lock_data() -> dict:
    with (HERE / "sources.lock.toml").open("rb") as stream:
        return tomllib.load(stream)


def digest(path: Path, algorithm: str = "sha256") -> str:
    result = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def acquire(pin: dict, cache: Path, offline: bool = False) -> Path:
    cache.mkdir(parents=True, exist_ok=True)
    name = pin.get("filename") or Path(urllib.parse.urlparse(pin["url"]).path).name
    algorithm = "sha256" if "sha256" in pin else "sha1"
    target = cache / name
    if target.exists():
        if digest(target, algorithm) != pin[algorithm]:
            raise ValueError(f"Pinned {algorithm} mismatch: {target}; remove it explicitly before retrying")
        return target
    if offline:
        raise FileNotFoundError(f"Offline source unavailable: {target}")
    temporary = target.with_name(target.name + ".part")
    try:
        subprocess.run(["curl", "--fail", "--location", "--proto", "=https", "--tlsv1.2",
                        "--retry", "3", "--silent", "--show-error", "--output", str(temporary), pin["url"]], check=True)
        if "size" in pin and temporary.stat().st_size != pin["size"]:
            raise ValueError(f"Pinned size mismatch: {name}")
        if digest(temporary, algorithm) != pin[algorithm]:
            raise ValueError(f"Pinned {algorithm} mismatch: {name}")
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
    return target


def unpack(archive: Path, destination: Path) -> Path:
    """One top-level directory; no traversal/devices. Preserve reviewed source symlinks."""
    marker = destination / ".engine-source-sha256"
    expected = digest(archive)
    if marker.is_file():
        if marker.read_text().strip() != expected:
            raise ValueError(f"Source changed: {destination}; use a fresh build directory")
        return destination
    if destination.exists():
        raise FileExistsError(f"Partial source tree: {destination}; remove explicitly before retrying")
    temporary = destination.with_name(destination.name + ".unpacking")
    if temporary.exists():
        shutil.rmtree(temporary)
    temporary.mkdir(parents=True)
    try:
        if zipfile.is_zipfile(archive):
            with zipfile.ZipFile(archive) as stream:
                for item in stream.infolist():
                    output = (temporary / item.filename).resolve()
                    if not output.is_relative_to(temporary.resolve()):
                        raise ValueError(f"Archive traversal: {item.filename}")
                    mode = item.external_attr >> 16
                    if (mode & 0o170000) == 0o120000:
                        target = stream.read(item).decode("utf-8")
                        if not (output.parent / target).resolve().is_relative_to(temporary.resolve()):
                            raise ValueError("Escaping archive symlink")
                        output.parent.mkdir(parents=True, exist_ok=True)
                        output.symlink_to(target)
                    else:
                        stream.extract(item, temporary)
                        if mode and not item.is_dir():
                            output.chmod(mode & 0o777)
        else:
            with tarfile.open(archive) as stream:
                stream.extractall(temporary, filter="data")
        children = list(temporary.iterdir())
        if len(children) != 1 or not children[0].is_dir():
            raise ValueError(f"Expected one source root in {archive}")
        destination.parent.mkdir(parents=True, exist_ok=True)
        children[0].replace(destination)
        marker.write_text(expected + "\n")
    finally:
        shutil.rmtree(temporary, ignore_errors=True)
    return destination


def run(*command: object, cwd: Path, env: dict | None = None) -> None:
    print("+", " ".join(str(part) for part in command), flush=True)
    subprocess.run([str(part) for part in command], cwd=cwd, env=env, check=True)


def host_environment() -> dict[str, str]:
    # Do not inherit caller toolchain/prefix injection from another project.
    result = dict(os.environ)
    for key in ("CC", "CXX", "CFLAGS", "CPPFLAGS", "CXXFLAGS", "LDFLAGS", "LIBRARY_PATH",
                "CPATH", "PYTHONPATH", "PYTHONHOME", "NODE_OPTIONS", "GYP_DEFINES",
                "PKG_CONFIG_PATH", "PKG_CONFIG_LIBDIR", "SDKROOT", "MACOSX_DEPLOYMENT_TARGET"):
        result.pop(key, None)
    result.update({"LC_ALL": "C", "TZ": "UTC", "SOURCE_DATE_EPOCH": "1790726400"})
    return result
