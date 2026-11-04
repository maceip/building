import hashlib
import importlib.util
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
import warnings
import zipfile

spec = importlib.util.spec_from_file_location("packaging_audit", Path(__file__).with_name("audit.py"))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def elf():
    data = bytearray(120)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", data, 18, 183)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 100, 100, 16384)
    return bytes(data)


class PackagingAuditTest(unittest.TestCase):
    def fixture(self, root, *, bundle=False, change=None):
        native = elf()
        data = b"print('runtime fixture')\n"
        payload = io.BytesIO()
        with zipfile.ZipFile(payload, "w") as z:
            z.writestr("lib/fixture.py", data)
        manifest = {
            "id": "fixture", "packages": [{"name": n} for n in sorted(audit.REQUIRED_PACKAGES)],
            "npmTools": [{"name": "pnpm"}], "payloadSha256": sha(payload.getvalue()),
            "launcherSha256": sha(native),
            "native": [{"path": "bin/node", "library": "libdevruntime_fixture.so", "size": len(native), "sha256": sha(native)}],
            "files": [{"path": "lib/fixture.py", "size": len(data), "sha256": sha(data)}],
            "links": [{"path": "bin/" + n, "target": "bin/node"} for n in ("npm", "pnpm", "python3", "sh", "clang", "clang++", "ld.lld")],
        }
        base = "base/" if bundle else ""
        contents = {
            base + ("manifest/" if bundle else "") + "AndroidManifest.xml": b"manifest fixture",
            base + "assets/developer-runtime/prefix.zip": payload.getvalue(),
            base + "lib/arm64-v8a/libdevruntime_fixture.so": native,
            base + "lib/arm64-v8a/libdevruntime-launcher.so": native,
        }
        if change:
            change(manifest, contents)
        contents[base + "assets/developer-runtime/manifest.json"] = json.dumps(manifest).encode()
        path = root / ("app.aab" if bundle else "app.apk")
        with zipfile.ZipFile(path, "w") as z:
            for name, value in contents.items():
                z.writestr(name, value)
        return path

    def test_verifies_both_apk_and_aab_base_without_installing(self):
        with tempfile.TemporaryDirectory() as temp:
            for bundle in (False, True):
                report = audit.audit(self.fixture(Path(temp), bundle=bundle))
                self.assertTrue(report["runtime"]["verified"])
                self.assertEqual(1, report["runtime"]["uniqueNativePayloads"])

    def test_fails_when_native_bytes_disagree_with_manifest(self):
        def change(manifest, contents):
            name = "lib/arm64-v8a/libdevruntime_fixture.so"
            contents[name] = contents[name][:-1] + b"x"
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                audit.audit(self.fixture(Path(temp), change=change))

    def test_fails_when_a_required_tool_is_missing(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(ValueError, "Mandatory development packages missing"):
                audit.audit(self.fixture(Path(temp), change=lambda m, c: m["packages"].pop()))

    def test_fails_when_asset_record_is_stale_even_with_valid_zip_digest(self):
        with tempfile.TemporaryDirectory() as temp:
            def change(manifest, contents):
                manifest["files"][0]["sha256"] = "0" * 64
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                audit.audit(self.fixture(Path(temp), change=change))

    def test_fails_on_dangling_tool_alias(self):
        with tempfile.TemporaryDirectory() as temp:
            def change(manifest, contents):
                manifest["links"][0]["target"] = "bin/missing"
            with self.assertRaisesRegex(ValueError, "Dangling or cyclic"):
                audit.audit(self.fixture(Path(temp), change=change))

    def test_fails_on_untracked_native_payload(self):
        with tempfile.TemporaryDirectory() as temp:
            def change(manifest, contents):
                contents["lib/arm64-v8a/libdevruntime_stale.so"] = elf()
            with self.assertRaisesRegex(ValueError, "disagree with manifest"):
                audit.audit(self.fixture(Path(temp), change=change))

    def test_rejects_duplicate_zip_paths(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.fixture(Path(temp))
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with zipfile.ZipFile(path, "a") as z:
                    z.writestr("AndroidManifest.xml", b"duplicate")
            with self.assertRaisesRegex(ValueError, "Duplicate archive entry"):
                audit.audit(path)


if __name__ == "__main__":
    unittest.main()
