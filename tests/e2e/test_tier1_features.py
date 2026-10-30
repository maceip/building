#!/usr/bin/env python3
"""
Tier 1: Feature Coverage (Opaque-Box Verification)
Verifies individual features in isolation across both pipelines:
- Android: ManifestParser, ApkBuilder project spec, APK ZIP structure, Dalvik DEX classes.
- iOS: Swiftc toolchain availability, SwiftUI ViewNode JSON decoding, ViewHost event callback encoding.
"""

import json
import os
import subprocess
import sys
import unittest
from pathlib import Path

REPO_ROOT = Path("/Users/mac/building")

class Tier1FeatureCoverageTest(unittest.TestCase):

    def test_android_apk_pipeline_jvm_tests(self):
        """Tier 1: Verifies Android ApkBuilder, ManifestParser, and DexVm in isolation."""
        cmd = [
            "./gradlew",
            ":android-apk:jvmTest",
            "--tests",
            "dev.ide.android.apk.e2e.AndroidApkE2ETest.tier1*",
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertEqual(res.returncode, 0, f"Tier 1 Android APK tests failed:\n{res.stdout}\n{res.stderr}")

    def test_ios_compose_preview_rendering(self):
        """Tier 1: Verifies VmPreviewEntry Compose Skia PNG frame generation and pointer input."""
        cmd = [
            "./gradlew",
            ":interp-compose:desktopTest",
            "--tests",
            "dev.ide.interp.compose.VmPreviewE2ETest.tier1*",
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertEqual(res.returncode, 0, f"Tier 1 Compose Preview tests failed:\n{res.stdout}\n{res.stderr}")

    def test_ios_swift_viewhost_protocol(self):
        """Tier 1: Verifies ViewNode JSON decoding and ViewHost event callback sink formatting."""
        cmd = [
            "./gradlew",
            ":ide-core:test",
            "--tests",
            "dev.ide.core.runtime.e2e.SwiftCupertinoE2ETest.tier1*",
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertEqual(res.returncode, 0, f"Tier 1 Swift ViewHost tests failed:\n{res.stdout}\n{res.stderr}")

    # Removed: test_opaque_box_viewnode_json_schema. The previous version round-tripped
    # a hand-written dict through Python's json module, which exercises no production
    # code. ViewNode JSON decoding is covered by the Kotlin tests invoked above
    # (SwiftCupertinoE2ETest.tier1*), which parse ViewNode lines with the real decoder.

if __name__ == "__main__":
    unittest.main()
