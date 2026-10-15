"""Offline source-rebuild boundary tests; opt-in NDK test also compiles twice."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("android_spawn_builder", HERE / "build_android_spawn.py")
spawn = importlib.util.module_from_spec(spec)
spec.loader.exec_module(spawn)


def elf_fixture(alignment=16384):
    data = bytearray(120)
    data[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<HH", data, 16, 3, 183)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, len(data), len(data), alignment)
    return bytes(data)


def pin(path, data, **extra):
    return dict(path=path, size=len(data), sha256=spawn.sha256(data), **extra)


class SpawnInputTests(unittest.TestCase):
    def test_source_lock_contains_exact_recipe_source_and_license_inventory(self):
        lock = spawn.read_lock()
        self.assertEqual([], lock["patches"])
        self.assertEqual({"build.sh", "posix_spawn.h", "posix_spawn.cpp", "LICENSE"}, {s["path"] for s in lock["sources"]})
        self.assertEqual("0.3", lock["version"])
        self.assertNotEqual(lock["replaces"]["sha256"], lock["artifact"]["sha256"])
        self.assertEqual(25, len(lock["artifact"]["publicExports"]))
        self.assertIn("-Wl,-z,max-page-size=16384,-z,common-page-size=16384", lock["build"]["linkFlags"])
        self.assertIn("libc++_shared.so", lock["artifact"]["needed"])
        self.assertEqual(134, lock["toolchain"]["consumedInputs"]["count"])
        for source in lock["sources"]:
            self.assertIn("/" + lock["sourceRevision"] + "/packages/libandroid-spawn/", source["url"])

    def test_offline_source_cache_requires_size_and_hash(self):
        data = b"locked source"
        source = pin("source.cpp", data, role="source", url="https://invalid.example/source.cpp")
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(spawn.urllib.request, "urlopen") as network:
            cache = Path(directory)
            with self.assertRaisesRegex(ValueError, "Missing offline source"):
                spawn.fetch_source(source, cache, True)
            path = cache / (source["sha256"] + ".source")
            path.write_bytes(data)
            self.assertEqual(data, spawn.fetch_source(source, cache, True))
            path.write_bytes(b"x" * len(data))
            with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
                spawn.fetch_source(source, cache, True)
            network.assert_not_called()

    def test_cached_recipe_reuses_existing_recipe_suffix(self):
        data = b"official recipe"
        source = pin("build.sh", data, role="recipe", url="https://invalid.example/build.sh")
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            (cache / (source["sha256"] + ".recipe")).write_bytes(data)
            self.assertEqual(data, spawn.fetch_source(source, cache, True))

    def test_mismatched_download_is_never_cached(self):
        source = pin("source.cpp", b"expected", role="source", url="https://invalid.example/source.cpp")
        response = mock.MagicMock()
        response.__enter__.return_value.read.return_value = b"tampered"
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(spawn.urllib.request, "urlopen", return_value=response):
            with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
                spawn.fetch_source(source, directory, False)
            self.assertEqual([], list(Path(directory).iterdir()))

    def test_toolchain_mismatch_fails_before_compiling(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(spawn.subprocess, "run") as run:
            ndk = Path(directory).resolve()
            (ndk / "compiler").write_bytes(b"other compiler")
            lock = {"toolchain": {"files": [pin("compiler", b"pinned compiler")], "licenses": []}}
            with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
                spawn.verify_toolchain(ndk, lock)
            run.assert_not_called()

    def test_dependency_parser_handles_spaces_and_lld_phony_rules(self):
        rule = "out: \\\n /ndk/with\\ space/header.h \\\n source/posix_spawn.cpp\n\n/ndk/with\\ space/header.h:\n"
        self.assertEqual(["/ndk/with space/header.h", "source/posix_spawn.cpp"], spawn.dependency_paths(rule))

    def test_paths_must_be_contained(self):
        for value in ("/absolute", "../escape", "a/../b", "a//b", "a\\b", ""):
            with self.subTest(value=value), self.assertRaises(ValueError):
                spawn.relative_path(value)
        self.assertEqual("lib/libandroid-spawn.so", spawn.relative_path("lib/libandroid-spawn.so"))

    def test_supplemental_notice_lock_pins_archives_and_complete_members(self):
        lock = json.loads((HERE / "optional-notices.lock.json").read_bytes())
        packages = {p["name"]: p for p in lock["packages"]}
        self.assertEqual({"ninja", "libuv"}, set(packages))
        self.assertEqual(["ninja/COPYING"], [p["output"] for p in packages["ninja"]["members"]])
        self.assertTrue({"libuv/LICENSE", "libuv/LICENSE-extra", "libuv/src/inet.c", "libuv/src/queue.h", "libuv/include/uv/tree.h"}
                        <= {p["output"] for p in packages["libuv"]["members"]})
        for package in packages.values():
            for member in [package["source"], *package["members"]]:
                self.assertEqual(64, len(member["sha256"]))
                self.assertGreater(member["size"], 0)
                spawn.relative_path(member["path"])


class SpawnElfTests(unittest.TestCase):
    def test_16k_gate_rejects_original_4k_layout(self):
        self.assertEqual([16384], spawn.elf_info(elf_fixture())["loadAlignments"])
        with self.assertRaisesRegex(ValueError, "16 KB"):
            spawn.elf_info(elf_fixture(4096))

    def test_16k_gate_checks_load_offset_congruence(self):
        data = bytearray(elf_fixture())
        struct.pack_into("<Q", data, 64 + 16, 1)
        with self.assertRaisesRegex(ValueError, "16 KB"):
            spawn.elf_info(data)

    def test_gate_rejects_wrong_machine_and_truncated_tables(self):
        wrong = bytearray(elf_fixture())
        struct.pack_into("<H", wrong, 18, 62)
        for data in (b"not ELF", elf_fixture()[:100], wrong):
            with self.subTest(data=bytes(data[:20])), self.assertRaises(ValueError):
                spawn.elf_info(data)

    def test_gate_requires_at_least_one_load(self):
        data = bytearray(elf_fixture())
        struct.pack_into("<I", data, 64, 4)
        with self.assertRaisesRegex(ValueError, "no PT_LOAD"):
            spawn.elf_info(data)


class SpawnOverrideTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name)
        self.original = b"original locked package member"
        self.binary = elf_fixture()
        self.source_data = {"LICENSE": b"license", "build.sh": b"recipe", "posix_spawn.cpp": b"source", "posix_spawn.h": b"header"}
        sources = [pin(name, data) for name, data in self.source_data.items()]
        self.lock = {"name": "libandroid-spawn", "originalPackage": {"sha256": "original .deb digest"},
                     "sources": sources, "patches": [], "sourceRevision": "immutable",
                     "replaces": pin("lib/libandroid-spawn.so", self.original),
                     "artifact": pin("lib/libandroid-spawn.so", self.binary, soname=None, needed=[], publicExports=[]),
                     "toolchain": {"files": [], "licenses": [], "consumedInputs": {"count": 0, "sha256": spawn.sha256(spawn.canonical([]))}}}
        self.patch = mock.patch.object(spawn, "read_lock", return_value=self.lock)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        self.files = {"lib/libandroid-spawn.so": self.original, "include/spawn.h": b"header",
                      "share/doc/libandroid-spawn/LICENSE": b"license", "bin/npm": b"untouched npm"}
        self.owners = {"lib/libandroid-spawn.so": "libandroid-spawn", "bin/npm": "npm"}
        notices = self.output / spawn.NOTICE
        notices.mkdir(parents=True)
        for name, content in self.source_data.items():
            (notices / name).write_bytes(content)
        (notices / spawn.LOCK.name).write_bytes(spawn.LOCK.read_bytes())
        (notices / Path(spawn.__file__).name).write_bytes(Path(spawn.__file__).read_bytes())
        (self.output / "lib").mkdir()
        (self.output / "lib/libandroid-spawn.so").write_bytes(self.binary)
        self.manifest = {key: copy.deepcopy(self.lock[key]) for key in ("originalPackage", "sources", "patches", "replaces", "sourceRevision")}
        self.manifest.update(sourceLockSha256=spawn.sha256(spawn.LOCK.read_bytes()), builderSha256=spawn.sha256(Path(spawn.__file__).read_bytes()),
                             toolchain=dict(self.lock["toolchain"], inputs=[]), artifact=dict(self.lock["artifact"], **spawn.elf_info(self.binary)))
        self.save_manifest()

    def save_manifest(self):
        (self.output / spawn.MANIFEST).write_text(json.dumps(self.manifest))

    def test_explicit_override_keeps_original_owner_and_other_files(self):
        before = dict(self.files)
        provenance = spawn.apply_override(self.files, self.owners, self.output)
        self.assertEqual(self.binary, self.files.pop("lib/libandroid-spawn.so"))
        before.pop("lib/libandroid-spawn.so")
        self.assertEqual(before, self.files)
        self.assertEqual("libandroid-spawn", self.owners["lib/libandroid-spawn.so"])
        self.assertEqual(self.lock["originalPackage"], provenance["originalPackage"])
        self.assertNotEqual(provenance["replaces"]["sha256"], provenance["artifact"]["sha256"])

    def test_override_refuses_different_original_member(self):
        self.files["lib/libandroid-spawn.so"] = b"nonmatching package"
        with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
            spawn.apply_override(self.files, self.owners, self.output)

    def test_override_refuses_wrong_owner(self):
        self.owners["lib/libandroid-spawn.so"] = "other"
        with self.assertRaisesRegex(ValueError, "locked libandroid-spawn"):
            spawn.apply_override(self.files, self.owners, self.output)

    def test_override_refuses_nonmatching_installed_header(self):
        self.files["include/spawn.h"] = b"wrong ABI"
        with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
            spawn.apply_override(self.files, self.owners, self.output)

    def test_override_refuses_stale_builder_provenance(self):
        self.manifest["builderSha256"] = "0" * 64
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "source lock/builder"):
            spawn.apply_override(self.files, self.owners, self.output)

    def test_override_refuses_forged_generated_hash(self):
        changed = elf_fixture(4096)
        (self.output / "lib/libandroid-spawn.so").write_bytes(changed)
        self.manifest["artifact"]["sha256"] = spawn.sha256(changed)
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
            spawn.apply_override(self.files, self.owners, self.output)

    def test_override_requires_complete_license_notice(self):
        (self.output / spawn.NOTICE / "LICENSE").write_bytes(b"missing disclaimer")
        with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
            spawn.apply_override(self.files, self.owners, self.output)

    def test_override_requires_locked_toolchain_input_inventory(self):
        self.manifest["toolchain"]["inputs"].append({"path": "unknown", "sha256": "0" * 64, "size": 1})
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "NDK input inventory"):
            spawn.apply_override(self.files, self.owners, self.output)


@unittest.skipUnless(os.environ.get("ANDROID_SPAWN_TEST_NDK"), "Set ANDROID_SPAWN_TEST_NDK to run real offline host compiles")
class SpawnReproducibilityTests(unittest.TestCase):
    def test_real_build_is_reproducible_and_passes_unchanged_pack_gate(self):
        cache = Path(os.environ.get("ANDROID_SPAWN_TEST_CACHE", str(HERE / "build/cache")))
        ndk = Path(os.environ["ANDROID_SPAWN_TEST_NDK"])
        work = HERE / "build/android-spawn"
        work.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="test-", dir=work) as directory:
            first, second = Path(directory) / "one", Path(directory) / "another path"
            manifest = spawn.build_library(ndk, cache, first, offline=True)
            with mock.patch.dict(os.environ, {"CXXFLAGS": "invalid", "CPATH": "/nonexistent", "LIBRARY_PATH": "/nonexistent", "SOURCE_DATE_EPOCH": "1"}):
                other = spawn.build_library(ndk, cache, second, offline=True)
            self.assertEqual(manifest, other)
            self.assertEqual((first / spawn.MANIFEST).read_bytes(), (second / spawn.MANIFEST).read_bytes())
            binary = (first / manifest["artifact"]["path"]).read_bytes()
            self.assertEqual(binary, (second / other["artifact"]["path"]).read_bytes())
            spec = importlib.util.spec_from_file_location("runtime_pack_gate", HERE / "build.py")
            pack = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(pack)
            package = spawn.read_lock()["originalPackage"]
            archive = pack.fetch_package(package, "https://invalid.example/", cache, True)
            files, _, owners = pack.collect([package], {package["name"]: archive})
            with self.assertRaises(ValueError):
                pack.verify_elf(files["lib/libandroid-spawn.so"], "original")
            spawn.apply_override(files, owners, first)
            self.assertEqual([16384] * 4, pack.verify_elf(files["lib/libandroid-spawn.so"], "generated"))
            self.assertEqual(package["sha256"], spawn.sha256(archive.read_bytes()))

    def test_real_supplemental_notices_stage_offline(self):
        cache = Path(os.environ.get("ANDROID_SPAWN_TEST_CACHE", str(HERE / "build/cache")))
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(spawn.urllib.request, "urlopen") as network:
            assets = Path(directory)
            result = spawn.stage_optional_notices(cache, assets, offline=True)
            for package in result["packages"]:
                for member in package["members"]:
                    spawn.verify_bytes((assets / result["path"] / member["output"]).read_bytes(), member)
            network.assert_not_called()


if __name__ == "__main__":
    unittest.main()
