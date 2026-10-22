#!/usr/bin/env python3
"""
Tier 2: Boundary & Corner Cases (Opaque-Box Verification)
Verifies resilience against extreme, malformed, empty, and non-standard inputs:
- Android: Empty projects, missing assets, large file sets (>60 files), non-standard activity names, corrupted DEX.
- iOS: Micro (10x10) and ultra (2048x2048) surface resolutions, out-of-bounds touch coordinates, malformed JSON lines, deeply nested ViewNode trees.
"""

import subprocess
import sys
import unittest
from pathlib import Path

REPO_ROOT = Path("/Users/mac/building")

class Tier2BoundaryCasesTest(unittest.TestCase):

    def test_android_apk_boundary_cases(self):
        """Tier 2: Verifies Android ApkBuilder & DexVm under boundary conditions."""
        cmd = [
            "./gradlew",
            ":android-apk:jvmTest",
            "--tests",
            "dev.ide.android.apk.e2e.AndroidApkE2ETest.tier2*",
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertEqual(res.returncode, 0, f"Tier 2 Android APK boundary tests failed:\n{res.stdout}\n{res.stderr}")

    def test_ios_preview_boundary_resolutions_and_touch(self):
        """Tier 2: Verifies VmPreviewEntry under micro/ultra resolutions and out-of-bounds pointer coords."""
        cmd = [
            "./gradlew",
            ":interp-compose:desktopTest",
            "--tests",
            "dev.ide.interp.compose.VmPreviewE2ETest.tier2*",
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertEqual(res.returncode, 0, f"Tier 2 Compose Preview boundary tests failed:\n{res.stdout}\n{res.stderr}")

    def test_ios_swift_boundary_json_and_depth(self):
        """Tier 2: Verifies ViewHost protocol against corrupted JSON lines, deep nesting (>15 levels), unicode."""
        cmd = [
            "./gradlew",
            ":ide-core:test",
            "--tests",
            "dev.ide.core.runtime.e2e.SwiftCupertinoE2ETest.tier2*",
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertEqual(res.returncode, 0, f"Tier 2 Swift ViewHost boundary tests failed:\n{res.stdout}\n{res.stderr}")

if __name__ == "__main__":
    unittest.main()
