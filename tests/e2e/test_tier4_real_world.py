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
        """Tier 4: Assert zero PRoot and zero external-Termux-app dependencies in production source.

        Scope: all production source trees (app/, android/, run/, services/, platform/,
        lang/), excluding build outputs and vendored libraries (app/ide-android/vendor/
        contains the termux-terminal emulator library, which is bundled code, not a
        dependency on the external Termux app). Patterns cover the builder, the native
        proot binaries/loaders, and the RUN_COMMAND intent contract.
        """
        patterns = "ProotCommandBuilder|libproot|proot_loader|proot_exec|RUN_COMMAND"
        grep = subprocess.run(
            ["grep", "-rnI", "--exclude-dir=build", "--exclude-dir=.gradle", "--exclude-dir=vendor",
             "-E", patterns, "app/", "android/", "run/", "services/", "platform/", "lang/"],
            cwd=REPO_ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(grep.stdout.strip(), "", f"Found illegal PRoot/Termux-app reference in source code:\n{grep.stdout}")

if __name__ == "__main__":
    unittest.main()
