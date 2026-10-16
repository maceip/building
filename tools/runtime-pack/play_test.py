#!/usr/bin/env python3
"""Build/install the dedicated local Play delivery test app; never touch the normal app ID.

Supply an existing official bundletool-all JAR and an explicit arm64 device serial.
No upload to Play. The local transport is simulated; all runtime bytes are real.
"""
import argparse
from pathlib import Path
import shlex
import shutil
import subprocess
import tarfile
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
APP = "ai.building.pack.dev"
TEST = APP + ".test/androidx.test.runner.AndroidJUnitRunner"


def run(*args, capture=False):
    print("+", " ".join(map(str, args)), flush=True)
    return subprocess.run(list(map(str, args)), cwd=ROOT, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--bundletool", type=Path, required=True)
    parser.add_argument("--adb", default=shutil.which("adb") or "/opt/homebrew/share/android-commandlinetools/platform-tools/adb")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--reset-test-app", action="store_true", help="Erase only ai.building.pack.dev to repeat base-only tests")
    parser.add_argument("--test", action="store_true", help="Run ordered base, failed delivery, real execution and cold-start checks")
    parser.add_argument("--apple-sdk", type=Path, help="Optional developer-owned darwin.xtoolsdk for a local unsigned iOS build; never bundled or uploaded")
    args = parser.parse_args()
    output = ROOT / "tools/runtime-pack/build/play-test"
    output.mkdir(parents=True, exist_ok=True)
    bundletool = args.bundletool.resolve()
    if not args.skip_build:
        run(ROOT / "gradlew", ":ide-android:bundleDebug", ":ide-android:assembleDebugAndroidTest",
            "-PIDE_RUNTIME_DELIVERY=play", "-PANDROID_APPLICATION_ID=" + APP, "--console=plain")
    bundle = ROOT / "app/ide-android/build/outputs/bundle/debug/ide-android-debug.aab"
    tool = ["java", "-jar", bundletool]
    manifest = ET.fromstring(run(*tool, "dump", "manifest", "--bundle=" + str(bundle), "--module=base", capture=True))
    if manifest.get("package") != APP:
        raise ValueError("Refusing to install an artifact with a different application ID; rebuild the dedicated test app.")
    run("python3", ROOT / "tools/runtime-pack/verify_delivery.py", bundle, "--bundletool", bundletool)
    shutil.copyfile(bundle, output / "building-node-play-debug.aab")
    adb = [args.adb, "-s", args.device]
    run(*tool, "get-device-spec", "--device-id=" + args.device, "--adb=" + args.adb, "--output=" + str(output / "device.json"), "--overwrite")
    run(*tool, "build-apks", "--bundle=" + str(bundle), "--output=" + str(output / "building-node-local.apks"),
        "--device-spec=" + str(output / "device.json"), "--local-testing", "--overwrite",
        "--ks=" + str(Path.home() / ".android/debug.keystore"), "--ks-key-alias=androiddebugkey", "--ks-pass=pass:android", "--key-pass=pass:android")
    if args.reset_test_app and ("package:" + APP) in run(*adb, "shell", "pm", "list", "packages", APP, capture=True).splitlines():
        run(*adb, "uninstall", APP)
    run(*tool, "install-apks", "--apks=" + str(output / "building-node-local.apks"), "--device-id=" + args.device, "--adb=" + args.adb)
    if args.test:
        run(*adb, "install", "-r", "-t", ROOT / "app/ide-android/build/outputs/apk/androidTest/debug/ide-android-debug-androidTest.apk")
        suite = "dev.ide.android.workbench.runtime.RuntimeDeliveryDeviceTest"
        for method in ("baseWorksWithoutNode", "failedDownloadLeavesBaseUsableAndCanBeRetried", "cancelDownloadKeepsNodeAbsent", "downloadSwiftWithoutNodeThenCompile", "installedSwiftCompilesAfterColdStart", "downloadThenExecuteNodeAndChildren", "installedPackWorksAfterColdStart"):
            run(*adb, "shell", "am", "force-stop", APP)
            log = run(*adb, "shell", "am", "instrument", "-w", "-e", "class", ("dev.ide.android.workbench.runtime.XtoolDeliveryDeviceTest" if "Swift" in method else suite) + "#" + method, TEST, capture=True)
            (output / (method + ".log")).write_text(log)
            print(log)
            if "OK (1 test)" not in log:
                raise RuntimeError("Device check failed: " + method)
        if args.apple_sdk:
            sdk = args.apple_sdk.resolve(strict=True)
            if not (sdk / "swift-sdk.json").is_file():
                raise ValueError("Expected a darwin.xtoolsdk directory containing swift-sdk.json")
            # Stream into only the dedicated debug application's data, not its distributed APK.
            with tempfile.TemporaryFile() as payload:
                with tarfile.open(fileobj=payload, mode="w") as archive:
                    archive.add(sdk, arcname="darwin.xtoolsdk")
                payload.seek(0)
                command = shlex.join(["run-as", APP, "/system/bin/sh", "-c",
                                      "mkdir -p files/xtool-test-sdk && cd files/xtool-test-sdk && tar -xf -"])
                subprocess.run([*adb, "shell", command], stdin=payload, check=True)
            run(*adb, "shell", "am", "force-stop", APP)
            log = run(*adb, "shell", "am", "instrument", "-w", "-e", "class",
                      "dev.ide.android.workbench.runtime.XtoolDeliveryDeviceTest#compileUnsignedIosWithSuppliedSdk", TEST, capture=True)
            (output / "compileUnsignedIosWithSuppliedSdk.log").write_text(log)
            print(log)
            if "OK (1 test)" not in log:
                raise RuntimeError("Unsigned iOS compile/edit/export check failed")
            with (output / "PhoneHello-unsigned.ipa").open("wb") as artifact:
                subprocess.run([*adb, "exec-out", "run-as", APP, "cat", "files/xtool-ios-proof/xtool/PhoneHello.ipa"], stdout=artifact, check=True)
    run(*adb, "shell", "am", "start", "-n", APP + "/dev.ide.android.MainActivity")
    print("Local pack build:", output)


if __name__ == "__main__":
    main()
