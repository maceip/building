#!/usr/bin/env python3
"""
Tier 4: Real-World Application Scenarios (Full Realistic App Execution)
Verifies full realistic application scenarios across both pipelines:
- Android: BitChord (music app), vivi-music (Material3 music player) packaging and launch.
- iOS: SwiftUI Calculator app with multi-file subdirectories (Models/, Views/), host swiftc compilation,
  interactive button taps driving math state transitions (7 + 5 = 12), and physical device harness validation.
"""

import subprocess
import sys
import unittest
from pathlib import Path

REPO_ROOT = Path("/Users/mac/building")

class Tier4RealWorldScenarioTest(unittest.TestCase):

    def test_android_real_world_apps(self):
        """Tier 4: Full packaging and execution of BitChord and vivi-music real-world Android apps."""
        cmd = [
            "./gradlew",
            ":android-apk:jvmTest",
            "--tests",
            "dev.ide.android.apk.e2e.AndroidApkE2ETest.tier4*",
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertEqual(res.returncode, 0, f"Tier 4 Android Real-World App tests failed:\n{res.stdout}\n{res.stderr}")

    def test_ios_preview_real_world_button_scene(self):
        """Tier 4: Compose interactive button scene with pointer event dispatch and state invalidation."""
        cmd = [
            "./gradlew",
            ":interp-compose:desktopTest",
            "--tests",
            "dev.ide.interp.compose.VmPreviewE2ETest.tier4*",
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertEqual(res.returncode, 0, f"Tier 4 Compose Preview real-world tests failed:\n{res.stdout}\n{res.stderr}")

    def test_ios_swiftui_calculator_real_app_compilation_and_execution(self):
        """Tier 4: Multi-file SwiftUI Calculator app compilation, execution, and state verification."""
        cmd = [
            "./gradlew",
            ":ide-core:test",
            "--tests",
            "dev.ide.core.runtime.e2e.SwiftCupertinoE2ETest.tier4*",
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertEqual(res.returncode, 0, f"Tier 4 Swift Cupertino real-world tests failed:\n{res.stdout}\n{res.stderr}")

    def test_zero_fabrication_audit(self):
        """Tier 4: Assert zero PRoot and zero Termux RUN_COMMAND dependencies in production source."""
        proot_grep = subprocess.run(
            ["grep", "-rnI", "--exclude-dir=build", "--exclude-dir=.gradle", "ProotCommandBuilder", "app/", "android/", "run/"],
            cwd=REPO_ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proot_grep.stdout.strip(), "", "Found illegal ProotCommandBuilder reference in source code")

        termux_grep = subprocess.run(
            ["grep", "-rnI", "--exclude-dir=build", "--exclude-dir=.gradle", "com.termux.RUN_COMMAND", "app/", "android/", "run/"],
            cwd=REPO_ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(termux_grep.stdout.strip(), "", "Found illegal Termux RUN_COMMAND reference in source code")

if __name__ == "__main__":
    unittest.main()
