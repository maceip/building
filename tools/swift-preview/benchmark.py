#!/usr/bin/env python3
"""Build and run the opt-in Android JNI versus JSON benchmark."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent


def run(*args, cwd=None, env=None):
    return subprocess.check_output(list(map(str, args)), cwd=cwd, env=env, text=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true", help="Use the existing --benchmark build")
    args = parser.parse_args()
    if not args.skip_build:
        subprocess.run([sys.executable, str(ROOT / "preview.py"), "--benchmark"], check=True)
    receipt = json.loads((ROOT / ".build/build-receipt.json").read_text())
    if not receipt.get("benchmark_fixtures"):
        parser.error("Build with preview.py --benchmark first")
    android = ROOT / ".build/android"
    env = os.environ.copy()
    env["JAVA_HOME"] = run("/usr/libexec/java_home", "-v", "21").strip()
    env.setdefault("ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools")
    subprocess.run([str(android / "gradlew"), ":preview-app:assembleDebugAndroidTest", "--console=plain"],
                   cwd=android, env=env, check=True)
    app = android / "preview-app/build/outputs/apk/debug/preview-app-debug.apk"
    test = android / "preview-app/build/outputs/apk/androidTest/debug/preview-app-debug-androidTest.apk"
    print(run("adb", "install", "-r", app))
    print(run("adb", "install", "-r", test))
    run("adb", "shell", "am", "force-stop", "ai.building.swiftpreview")
    result = run("adb", "shell", "am", "instrument", "-w", "-e", "class",
                 "dev.ide.swiftpreview.BridgeBenchmark",
                 "ai.building.swiftpreview.test/androidx.test.runner.AndroidJUnitRunner")
    print(result)
    if "OK (2 tests)" not in result:
        raise SystemExit("Android benchmark or disposal assertion failed")
    measurements = json.loads(run("adb", "exec-out", "run-as", "ai.building.swiftpreview",
                                  "cat", "files/jni-benchmark.json"))
    measurements["build"] = receipt
    measurements["measured_at"] = datetime.now(timezone.utc).isoformat()
    measurements["device"] = {
        key: run("adb", "shell", "getprop", prop).strip()
        for key, prop in {"model": "ro.product.model", "abi": "ro.product.cpu.abi",
                          "fingerprint": "ro.build.fingerprint"}.items()
    }
    installed = run("adb", "shell", "pm", "path", "ai.building.swiftpreview").strip().splitlines()[0][8:]
    actual = run("adb", "shell", "sha256sum", installed).split()[0]
    assert actual == receipt["apk_sha256"], "Installed APK differs from the build receipt"
    output = ROOT / ".build/evidence/jni-benchmark.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(measurements, indent=2) + "\n")
    artifacts = ROOT / ".build/artifacts"
    artifacts.mkdir(parents=True, exist_ok=True)
    shutil.copy2(app, artifacts / "jni-benchmark.apk")
    shutil.copy2(test, artifacts / "jni-benchmark-test.apk")
    print(f"Measurements: {output}")


if __name__ == "__main__":
    main()
