"""Host-only pack boundary tests. Never downloads packages or runs guest code."""
import importlib.util
import io
from pathlib import Path
import struct
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("runtime_pack_build", Path(__file__).with_name("build.py"))
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class RuntimePackTests(unittest.TestCase):
    def test_accepts_only_the_exact_prefix(self):
        self.assertEqual("bin/node", build.safe_relative("./data/data/com.termux/files/usr/bin/node"))
        self.assertIsNone(build.safe_relative("etc/passwd"))
        with self.assertRaises(ValueError):
            build.safe_relative("data/data/com.termux/files/usr/../../escape")

    def test_requires_arm64_and_16k_load_segments(self):
        data = bytearray(64 + 56)
        data[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<H", data, 18, 183)
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<HH", data, 54, 56, 1)
        struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 100, 100, 16384)
        self.assertEqual([16384], build.verify_elf(data, "test"))
        struct.pack_into("<Q", data, 64 + 48, 4096)
        with self.assertRaises(ValueError):
            build.verify_elf(data, "test")

    def test_rejects_archive_without_data_payload(self):
        with self.assertRaises(ValueError):
            build.ar_data(b"!<arch>\n")
        with self.assertRaises(ValueError):
            build.ar_data(b"not an archive")

    def test_identical_native_files_keep_both_paths_and_share_one_payload(self):
        data = bytearray(120)
        data[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<H", data, 18, 183)
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<HH", data, 54, 56, 1)
        struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 100, 100, 16384)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = build.stage_native(root, "lib/first.so", data, "first-package")
            second = build.stage_native(root, "lib/second.so", data, "second-package")
            self.assertEqual(first["library"], second["library"])
            self.assertEqual(1, len(list(root.iterdir())))
            self.assertEqual("lib/second.so", second["path"])
            self.assertEqual("second-package", second["owner"])
            data[7] = 1
            third = build.stage_native(root, "lib/third.so", data, "third-package")
            self.assertNotEqual(first["library"], third["library"])
            self.assertEqual(2, len(list(root.iterdir())))

    def test_offline_cache_verifies_digest_and_never_downloads(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            package = {"name":"fixture", "sha256":build.sha256(b"payload"), "size":7, "filename":"pool/fixture.deb"}
            with self.assertRaises(ValueError):
                build.fetch_package(package, "https://invalid.example/", cache, True)
            cached = cache / (package["sha256"] + ".deb")
            cached.write_bytes(b"payload")
            self.assertEqual(cached, build.fetch_package(package, "https://invalid.example/", cache, True))
            cached.write_bytes(b"corrupt")
            with self.assertRaises(ValueError):
                build.fetch_package(package, "https://invalid.example/", cache, True)

    def test_lock_is_unique_and_contains_the_development_runtime(self):
        _, packages = build.package_specs()
        names = {p["name"] for p in packages}
        self.assertEqual(len(names), len(packages))
        self.assertTrue({"nodejs-lts", "npm", "python", "dash"} <= names)
        self.assertFalse({"openjdk-17", "openjdk-21"} & names)

    def test_compiler_wrappers_relocate_sysroot_and_keep_cpp_mode(self):
        files = {"bin/clang-21": b"native compiler"}
        links = {"bin/clang": "bin/clang-21", "bin/clang++": "bin/clang-21"}
        owners = {"bin/clang-21": "clang"}
        build.add_compiler_wrappers(files, links, owners)
        self.assertNotIn("bin/clang", links)
        self.assertNotIn("bin/clang++", links)
        for name in ("clang", "clang++", "cc", "c++", "gcc", "g++"):
            self.assertTrue(files["bin/" + name].startswith(b"#!/system/bin/sh\n"))
            self.assertIn(b'--sysroot=$PREFIX', files["bin/" + name])
            self.assertIn(b'-resource-dir=$PREFIX/lib/clang/21', files["bin/" + name])
            self.assertIn(b'"$@"', files["bin/" + name])
        self.assertIn(b'--driver-mode=g++', files["bin/clang++"])
        self.assertLess(files["bin/clang++"].index(b'include/c++/v1'), files["bin/clang++"].index(b'"$PREFIX/include"'))
        self.assertNotIn(b'include/c++/v1', files["bin/clang"])

    def test_development_inputs_are_not_pruned(self):
        for name in ("include/node/node_api.h", "lib/libnode.a", "lib/python3.14/config/python.o", "lib/pkgconfig/libnode.pc"):
            self.assertFalse(name.startswith(build.EXCLUDED), name)


if __name__ == "__main__":
    unittest.main()
