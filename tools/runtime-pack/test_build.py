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

    def test_lock_is_unique_and_contains_runtime_not_whole_toolchain(self):
        _, packages = build.package_specs()
        names = {p["name"] for p in packages}
        self.assertEqual(27, len(packages))
        self.assertTrue({"nodejs-lts", "npm", "python", "dash"} <= names)
        self.assertFalse({"openjdk-17", "openjdk-21", "clang", "cmake"} & names)


if __name__ == "__main__":
    unittest.main()
