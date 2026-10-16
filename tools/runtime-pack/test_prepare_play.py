import io
import struct
import unittest
import zipfile

from prepare_play import native_alignment, verify_manifests


BASE = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="ai.building" android:versionCode="110" android:versionName="1.0.1">
    <uses-sdk android:targetSdkVersion="36" />
    <application android:debuggable="false" />
</manifest>'''
FEATURE = '''<manifest xmlns:dist="http://schemas.android.com/apk/distribution"
    package="ai.building" split="developer_runtime_node">
    <dist:module><dist:delivery><dist:on-demand /></dist:delivery>
    <dist:fusing dist:include="false" /></dist:module>
</manifest>'''
ENV = {"ANDROID_APPLICATION_ID": "ai.building", "ANDROID_VERSION_CODE": "110", "ANDROID_VERSION_NAME": "1.0.1"}


class PreparePlayTest(unittest.TestCase):
    def test_accepts_release_with_on_demand_node(self):
        verify_manifests(BASE, FEATURE, ENV)

    def test_rejects_wrong_identity_stale_version_and_debug_build(self):
        for old, new in (("ai.building", "ai.building.pack.dev"), ('="110"', '="109"'),
                         ('="1.0.1"', '="1.0.0"'), ('debuggable="false"', 'debuggable="true"'),
                         ('targetSdkVersion="36"', 'targetSdkVersion="34"')):
            with self.subTest(new=new), self.assertRaises(ValueError):
                verify_manifests(BASE.replace(old, new), FEATURE, ENV)

    def test_rejects_local_testing_metadata(self):
        base = BASE.replace('<application android:debuggable="false" />', '''<application>
            <meta-data android:name="local_testing_dir" android:value="local_testing" />
            </application>''')
        with self.assertRaisesRegex(ValueError, "simulation"):
            verify_manifests(base, FEATURE, ENV)

    def test_rejects_install_time_fused_or_wrong_feature(self):
        for old, new in (("on-demand", "install-time"), ('include="false"', 'include="true"'),
                         ('split="developer_runtime_node"', 'split="different"')):
            with self.subTest(new=new), self.assertRaises(ValueError):
                verify_manifests(BASE, FEATURE.replace(old, new), ENV)

    def elf(self, alignment=16384, offset=0, relro_end=16384):
        data = bytearray(176)
        data[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<H", data, 18, 183)
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<HH", data, 54, 56, 2)
        struct.pack_into("<IIQQQQQQ", data, 64, 1, 6, offset, 0, 0, 0, 0, alignment)
        struct.pack_into("<IIQQQQQQ", data, 120, 0x6474E552, 4, 0, 0, 0, 0, relro_end, 1)
        return data

    def inspect(self, data):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            # Deliberately outside the developer-runtime naming convention.
            archive.writestr("base/lib/arm64-v8a/libthirdparty.so", data)
        with zipfile.ZipFile(buffer) as archive:
            return native_alignment(archive)

    def test_all_native_libraries_are_checked(self):
        self.assertEqual(1, self.inspect(self.elf())["arm64LibrariesChecked"])
        for data in (self.elf(alignment=4096), self.elf(offset=4096), b"not an ELF"):
            with self.assertRaises(ValueError):
                self.inspect(data)

    def test_reports_relro_boundary_without_claiming_device_qualification(self):
        report = self.inspect(self.elf(relro_end=4096))
        self.assertEqual(["base/lib/arm64-v8a/libthirdparty.so"], report["relroEndsNot16KAligned"])
        self.assertFalse(report["runtimeOn16KDeviceVerified"])


if __name__ == "__main__":
    unittest.main()
