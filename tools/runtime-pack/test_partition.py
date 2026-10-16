"""The optional payload must not duplicate bytes or leave base links requiring Node."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from partition import partition


class PartitionTests(unittest.TestCase):
    def fixture(self, root):
        source = root / "source"
        assets = source / "assets/developer-runtime"
        native = source / "jniLibs/arm64-v8a"
        (assets / "notices").mkdir(parents=True)
        native.mkdir(parents=True)
        (native / "libdevruntime-launcher.so").write_bytes(b"launcher")
        (native / "libdevruntime-node.so").write_bytes(b"node-launcher")
        manifest = {"id": "fixture", "files": [], "native": [], "links": []}
        with zipfile.ZipFile(assets / "prefix.zip", "w") as archive:
            for name, owner in (("lib/node_modules/npm/cli.js", "npm"), ("lib/python.py", "python"), ("lib/swift/test.swiftmodule", "swift")):
                data = name.encode()
                archive.writestr(name, data)
                manifest["files"].append({**({"component": "swift_support"} if owner == "swift" else {}), "path": name, "owner": owner, "size": len(data), "sha256": hashlib.sha256(data).hexdigest()})
        for path, owner, payload in (("bin/node", "nodejs-lts", b"node"), ("lib/shared.so", "openssl", b"shared")):
            name = "libdevruntime_" + hashlib.sha256(payload).hexdigest() + ".so"
            (native / name).write_bytes(payload)
            manifest["native"].append({"path": path, "owner": owner, "library": name})
        (assets / "manifest.json").write_text(json.dumps(manifest))
        return source, manifest

    def test_partition_preserves_data_and_stores_native_once(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, original = self.fixture(root)
            partition(source, root / "out")
            outputs = {}
            all_native = []
            for name, asset in (("base", "developer-runtime"), ("node", "developer-runtime-node"), ("xtool", "developer-runtime-xtool"), ("swift_support", "developer-runtime-swift_support")):
                with zipfile.ZipFile(root / f"out/{name}/assets/{asset}/prefix.zip") as archive:
                    outputs[name] = archive.namelist()
                all_native += [p.name for p in (root / f"out/{name}/jniLibs/arm64-v8a").iterdir()]
            self.assertEqual(["lib/python.py"], outputs["base"])
            self.assertEqual(["lib/node_modules/npm/cli.js"], outputs["node"])
            self.assertEqual(["lib/swift/test.swiftmodule"], outputs["swift_support"])
            catalog = json.loads((root / "out/base/assets/developer-runtime/delivery.json").read_text())
            xtool = next(p for p in catalog["modules"] if p["module"] == "developer_runtime_xtool")
            self.assertEqual(["developer_runtime_xtool", "developer_runtime_swift_support"], xtool["requestModules"])
            self.assertEqual(len(set(all_native)), len(all_native))
            self.assertFalse((root / "out/node/assets/developer-runtime-node/notices").exists())

    def test_base_cannot_depend_on_optional_node(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, manifest = self.fixture(root)
            manifest["links"] = [{"path": "bin/base-tool", "target": "bin/node", "owner": "python"}]
            (source / "assets/developer-runtime/manifest.json").write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, "Link depends on another optional pack"):
                partition(source, root / "out")

    def test_corrupt_input_cannot_be_repackaged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, manifest = self.fixture(root)
            manifest["files"][0]["sha256"] = "0" * 64
            (source / "assets/developer-runtime/manifest.json").write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, "integrity mismatch"):
                partition(source, root / "out")

    def test_optional_packs_cannot_require_one_another(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, manifest = self.fixture(root)
            manifest["links"] = [{"path": "bin/swift", "target": "bin/node", "owner": "swift", "component": "xtool"}]
            (source / "assets/developer-runtime/manifest.json").write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, "another optional pack"):
                partition(source, root / "out")

    def test_shared_bytes_are_stored_once_in_base(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, manifest = self.fixture(root)
            shared = dict(manifest["native"][0], path="bin/swift-shared", component="xtool", owner="swift")
            manifest["native"].append(shared)
            (source / "assets/developer-runtime/manifest.json").write_text(json.dumps(manifest))
            partition(source, root / "out")
            self.assertTrue((root / "out/base/jniLibs/arm64-v8a" / shared["library"]).exists())
            for optional in ("node", "xtool"):
                self.assertFalse((root / f"out/{optional}/jniLibs/arm64-v8a" / shared["library"]).exists())
