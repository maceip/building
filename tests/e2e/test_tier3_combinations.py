#!/usr/bin/env python3
"""
Tier 3: Cross-Feature Combinations (Pairwise & Matrix Interactions)
Verifies multi-feature interactions across both pipelines:
- Android: Custom Application + Compose Theme + Custom String resources + Gradle namespace overrides.
- iOS: Multi-density (1.0x, 2.0x, 3.0x iPhone XS Max), dark theme + background color, multi-control Forms (Button, Toggle, TextField).
"""

import subprocess
import sys
import unittest
from pathlib import Path

REPO_ROOT = Path("/Users/mac/building")

class Tier3CrossCombinationsTest(unittest.TestCase):

    def test_android_apk_cross_combinations(self):
        """Tier 3: Verifies Custom Application + Launch Activity + Multiple Components + Gradle namespace."""
        cmd = [
            "./gradlew",
            ":android-apk:jvmTest",
            "--tests",
            "dev.ide.android.apk.e2e.AndroidApkE2ETest.tier3*",
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertEqual(res.returncode, 0, f"Tier 3 Android APK cross combination tests failed:\n{res.stdout}\n{res.stderr}")

    def test_ios_preview_density_and_theming_combinations(self):
        """Tier 3: Verifies multi-density (1.0x-3.0x) and dark theme/background combinations in Compose Preview."""
        cmd = [
            "./gradlew",
            ":interp-compose:desktopTest",
            "--tests",
            "dev.ide.interp.compose.VmPreviewE2ETest.tier3*",
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertEqual(res.returncode, 0, f"Tier 3 Compose Preview cross combination tests failed:\n{res.stdout}\n{res.stderr}")

    def test_ios_swift_mixed_control_forms(self):
        """Tier 3: Verifies ViewHost protocol handling complex Forms with Buttons, Toggles, and TextFields."""
        cmd = [
            "./gradlew",
            ":ide-core:test",
            "--tests",
            "dev.ide.core.runtime.e2e.SwiftCupertinoE2ETest.tier3*",
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertEqual(res.returncode, 0, f"Tier 3 Swift ViewHost mixed control tests failed:\n{res.stdout}\n{res.stderr}")

if __name__ == "__main__":
    unittest.main()
