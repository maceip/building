# SPDX-License-Identifier: GPL-3.0-or-later
import hashlib
import io
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from source_io import HERE, ROOT, acquire, digest, lock_data, unpack
from build import check_elf, stdlib_zip


class SourceBuildTest(unittest.TestCase):
    def test_pins_are_immutable_source_archives_not_termux_packages(self):
        lock = lock_data()
        for name in ('node', 'python', 'openssl', 'zlib', 'llvm_runtime', 'llvm_android', 'bionic', 'ndk_sources'):
            self.assertRegex(lock[name]['sha256'], r'^[0-9a-f]{64}$')
            self.assertTrue(lock[name]['url'].startswith('https://'))
            self.assertNotIn('termux', lock[name]['url'])
        self.assertEqual('upstream-lts-android-unqualified', lock['node']['support'])

    def test_bad_cache_hash_fails_closed_offline(self):
        with tempfile.TemporaryDirectory() as temp:
            cache = Path(temp)
            (cache / 'source.tar.gz').write_bytes(b'changed')
            pin = dict(url='https://example.invalid/source.tar.gz', sha256='0' * 64)
            with self.assertRaises(ValueError): acquire(pin, cache, True)
            (cache / 'source.tar.gz').unlink()
            with self.assertRaises(FileNotFoundError): acquire(pin, cache, True)

    def test_source_archive_traversal_and_external_links_rejected(self):
        for member_name, link in [('../escape', None), ('source/link', '/etc/passwd')]:
            with self.subTest(member_name=member_name), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); archive = root / 'source.tar'
                with tarfile.open(archive, 'w') as tar:
                    item = tarfile.TarInfo(member_name)
                    if link:
                        item.type = tarfile.SYMTYPE; item.linkname = link; tar.addfile(item)
                    else:
                        item.size = 1; tar.addfile(item, io.BytesIO(b'x'))
                with self.assertRaises((ValueError, tarfile.FilterError)):
                    unpack(archive, root / 'result')

    def test_stdlib_assets_contain_only_source_not_native_test_or_installer(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name in ['json/__init__.py', 'encodings/__init__.py', 'site.py', 'test/test_os.py',
                         'ensurepip/__init__.py', 'native.so', 'module.pyc', '__pycache__/x.py']:
                path = root / 'Lib' / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text('# fixture\n')
            output = root / 'stdlib.zip'; stdlib_zip(root, output)
            with zipfile.ZipFile(output) as archive:
                self.assertEqual({'json/__init__.py', 'encodings/__init__.py', 'site.py'}, set(archive.namelist()))

    def test_elf_gate_rejects_unselected_dependencies_and_small_page_alignment(self):
        with tempfile.TemporaryDirectory() as temp, patch('build.toolchain') as tools:
            tools.return_value = Path(temp)
            native = Path(temp) / 'engine.so'; native.write_bytes(b'ELF-placeholder-for-parser-test')
            header = 'Type: DYN\nMachine: AArch64\n'
            page = 'LOAD 0 0 0 0 0 R E 0x4000\n'
            bad_page = page.replace('0x4000', '0x1000')
            with patch('build.subprocess.check_output', side_effect=[header, bad_page]):
                with self.assertRaisesRegex(ValueError, '16-KiB'): check_elf(native, Path(temp), 'arm64-v8a', set())
            with patch('build.subprocess.check_output', side_effect=[header, page, '(NEEDED) Shared library: [libtermux-exec.so]']):
                with self.assertRaisesRegex(ValueError, 'Unpackaged'): check_elf(native, Path(temp), 'arm64-v8a', set())
            with patch('build.subprocess.check_output', side_effect=[header, page, '(RUNPATH) /build-host/prefix']):
                with self.assertRaisesRegex(ValueError, 'prefix'): check_elf(native, Path(temp), 'arm64-v8a', set())

    def test_manifest_crash_containment_and_selected_only_feature(self):
        from xml.etree import ElementTree as ET
        manifest = ET.parse(ROOT / 'android/runtime-engine/core/src/main/AndroidManifest.xml').getroot()
        service = manifest.find('application/service')
        android = '{http://schemas.android.com/apk/res/android}'
        self.assertEqual(':runtime', service.attrib[android + 'process'])
        self.assertEqual('false', service.attrib[android + 'exported'])
        self.assertNotIn(android + 'isolatedProcess', service.attrib)
        self.assertEqual([], manifest.findall('uses-permission'))
        feature = (ROOT / 'android/runtime-engine/ide-feature/build.gradle.kts').read_text()
        self.assertIn('project(":runtime-engine-$engine")', feature)
        self.assertNotIn('developer-runtime-pack', feature)
        self.assertNotIn('runtime-pack/build.py', feature)

    def test_shipping_diagnostics_have_exact_asset_hashes(self):
        for engine in ('node', 'python'):
            root = ROOT / 'android/runtime-engine' / engine / 'src/main/assets/runtime-engine/app'
            for line in (root / 'files.sha256').read_text().splitlines():
                sha, size, path = line.split('\t')
                self.assertEqual(sha, digest(root / path))
                self.assertEqual(int(size), (root / path).stat().st_size)

    def test_node_build_requires_explicit_qualification_consent(self):
        result = subprocess.run(['python3', str(HERE / 'build.py'), '--engine', 'node', '--offline'], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('--allow-unqualified-node', result.stderr)

    def test_config_writes_are_idempotent_for_incremental_native_builds(self):
        from build import write_if_changed
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'Setup.local'
            write_if_changed(path, '*disabled*\\n_ssl\\n')
            timestamp = path.stat().st_mtime_ns
            write_if_changed(path, '*disabled*\\n_ssl\\n')
            self.assertEqual(timestamp, path.stat().st_mtime_ns)
            write_if_changed(path, '*disabled*\\n_ssl\\n_hashlib\\n')
            self.assertIn('_hashlib', path.read_text())

    def test_source_bundle_contains_actual_ndk_runtime_source_archives(self):
        archive = HERE / 'build/payloads/python/python-3.14.8-corresponding-source.tar.xz'
        if not archive.exists(): self.skipTest('Native source bundle requires explicit build')
        with tarfile.open(archive) as tar:
            names = tar.getnames()
        for stem in ('Python-', 'openssl-', 'zlib-', 'llvm-project-', 'llvm_android-', 'bionic-', 'ndk-'):
            self.assertTrue(any(n.startswith('upstream/' + stem) for n in names), stem)
        self.assertIn('local/tools/runtime-engine/build.py', names)
        self.assertIn('local/android/runtime-engine/native/engine_python.c', names)
        self.assertIn('local/LICENSE', names)

    def test_actual_python_payload_when_built(self):
        root = HERE / 'build/payloads/python'
        if not root.exists(): self.skipTest('Native source build must be run explicitly')
        lock = lock_data()
        self.assertTrue((root / f"python-{lock['python']['version']}-corresponding-source.tar.xz").is_file())
        for line in (root / 'SHA256SUMS').read_text().splitlines():
            sha, name = line.split('  ', 1)
            self.assertEqual(sha, digest(root / name), name)
        self.assertEqual({'libengine_host.so', 'libengine_python.so', 'libpython3.14.so'},
                         {p.name for p in (root / 'jniLibs/arm64-v8a').iterdir()})
        self.assertLess(sum(p.stat().st_size for p in (root / 'jniLibs').rglob('*.so')), 20 * 1024 * 1024)


if __name__ == '__main__': unittest.main()
