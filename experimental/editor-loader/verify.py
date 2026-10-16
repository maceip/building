#!/usr/bin/env python3
"""Build, size-gate and exercise the actual shrunk two-stage release on one emulator.

Uses Google's local-testing transport, NOT the Play backend. Only this prototype's
package is replaced. Network state is restored in finally, even after test failure.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
APP = "ai.building.loader.prototype"
PHASES = ["stageOneOfflineEditingAndSafSave", "stageOneColdRestartOffline",
          "failedAndCanceledDownloadPreservesEditor", "stageTwoDownloadAndRunEditedSource", "stageTwoColdRestartOffline", "newDocumentOffline"]


def run(*args):
    result = subprocess.run([str(a) for a in args], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(result.stdout)
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--adb", default=str(Path.home() / "Library/Android/sdk/platform-tools/adb"))
    parser.add_argument("--bundletool", type=Path, default=REPO / "tools/runtime-pack/build/play-test/bundletool.jar")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--skip-install", action="store_true")
    parser.add_argument("--phase", choices=PHASES, action="append")
    args = parser.parse_args()
    # Explicitly limited to the disposable emulator: leave the user's phone alone.
    if not args.device.startswith("emulator-"):
        raise SystemExit("Use a disposable emulator for this proof workflow")
    output = REPO / "dist/editor-loader-proof"
    output.mkdir(parents=True, exist_ok=True)
    adb = [args.adb, "-s", args.device]
    tool = ["java", "-jar", args.bundletool]
    if not args.skip_build:
        log = run(REPO / "gradlew", "-p", HERE, ":app:bundleRelease", ":app:assembleRelease", ":app:assembleReleaseAndroidTest")
        (output / "build.log").write_text(log)
    bundle = HERE / "app/build/outputs/bundle/release/app-release.aab"
    manifest = ET.fromstring(run(*tool, "dump", "manifest", "--bundle=" + str(bundle), "--module=base"))
    assert manifest.get("package") == APP
    assert manifest.find("application").get("{http://schemas.android.com/apk/res/android}debuggable") != "true"
    (output / "bundle-validation.txt").write_text(run(*tool, "validate", "--bundle=" + str(bundle)))
    run(*tool, "get-device-spec", "--device-id=" + args.device, "--adb=" + args.adb,
        "--output=" + str(output / "device.json"), "--overwrite")
    signing = ["--ks=" + str(Path.home() / ".android/debug.keystore"), "--ks-key-alias=androiddebugkey",
               "--ks-pass=pass:android", "--key-pass=pass:android"]
    if not args.skip_install:
        run(*tool, "build-apks", "--bundle=" + str(bundle), "--output=" + str(output / "local.apks"),
            "--device-spec=" + str(output / "device.json"), "--local-testing", "--overwrite", *signing)
        # Measure a normal (non-local-testing) download too: no test config in the shipping estimate.
        run(*tool, "build-apks", "--bundle=" + str(bundle), "--output=" + str(output / "release.apks"),
            "--device-spec=" + str(output / "device.json"), "--overwrite", *signing)
        run(*tool, "build-apks", "--bundle=" + str(bundle), "--output=" + str(output / "universal.apks"),
            "--mode=universal", "--overwrite", *signing)
        with zipfile.ZipFile(output / "universal.apks") as archive:
            (output / "building-editor-stage-one.apk").write_bytes(archive.read("universal.apk"))
    estimates = {}
    for modules in ("base", "base,node_runtime"):
        csv = run(*tool, "get-size", "total", "--apks=" + str(output / "release.apks"), "--modules=" + modules)
        (output / ("size-" + modules.replace(",", "-") + ".csv")).write_text(csv)
        estimates[modules] = int(csv.strip().splitlines()[-1].split(",")[-1])
    standalone = output / "building-editor-stage-one.apk"
    assert standalone.stat().st_size < 10_000_000, "Standalone editor APK exceeds 10 MB"
    assert estimates["base"] < 10_000_000, "Initial Play download exceeds 10 MB"
    with zipfile.ZipFile(bundle) as archive:
        base_libraries = [n for n in archive.namelist() if n.startswith("base/lib/")]
        assert {Path(n).name for n in base_libraries} <= {
            "libsdk-exec-prefix.so", "libdevruntime-node.so", "libandroidx.graphics.path.so"}
        assert {"libsdk-exec-prefix.so", "libdevruntime-node.so"} <= {Path(n).name for n in base_libraries}
        assert not any(n.startswith("base/assets/node-runtime/") for n in archive.namelist())
    print(f"Standalone APK {standalone.stat().st_size:,} bytes; initial download {estimates['base']:,} bytes", flush=True)
    if not args.skip_install:
        packages = run(*adb, "shell", "pm", "list", "packages", APP).splitlines()
        for package in (APP, APP + ".test"):
            if "package:" + package in packages:
                run(*adb, "uninstall", package)
        run(*tool, "install-apks", "--apks=" + str(output / "local.apks"), "--device-id=" + args.device, "--adb=" + args.adb)
        run(*adb, "install", "-r", "-t", HERE / "app/build/outputs/apk/androidTest/release/app-release-androidTest.apk")
    original = {key: run(*adb, "shell", "settings", "get", "global", key).strip()
                for key in ("airplane_mode_on", "wifi_on", "mobile_data")}
    phases = args.phase or PHASES
    results = {}
    def check_phase(phase, prefix=""):
        offline = phase.startswith("stageOne") or phase in ("stageTwoColdRestartOffline", "newDocumentOffline")
        run(*adb, "shell", "cmd", "connectivity", "airplane-mode", "enable" if offline else "disable")
        run(*adb, "shell", "svc", "wifi", "disable" if offline else "enable")
        run(*adb, "shell", "svc", "data", "disable" if offline else "enable")
        run(*adb, "shell", "am", "force-stop", APP)
        text = run(*adb, "shell", "am", "instrument", "-w", "-e", "phase",
                   phase, APP + ".test/ai.building.loader.ProofRunner")
        (output / (prefix + phase + ".log")).write_text(text)
        results[prefix + phase] = "OK (1 test)" in text
        try:
            run(*adb, "pull", f"/sdcard/Android/data/{APP}/files/{phase}.png", output / (prefix + phase + ".png"))
        except RuntimeError:
            pass  # A crash may happen before the runner can capture the screen.
        print(text, flush=True)
        if not results[prefix + phase]:
            raise RuntimeError("Device check failed: " + phase)
    try:
        for phase in phases:
            check_phase(phase)
        if not args.phase:
            # Independently install the measured standalone APK without any feature splits.
            run(*adb, "uninstall", APP)
            run(*adb, "install", standalone)
            for phase in PHASES[:2]:
                check_phase(phase, "standalone-")
    finally:
        run(*adb, "shell", "cmd", "connectivity", "airplane-mode", "enable" if original["airplane_mode_on"] == "1" else "disable")
        run(*adb, "shell", "svc", "wifi", "enable" if original["wifi_on"] in ("1", "2") else "disable")
        run(*adb, "shell", "svc", "data", "enable" if original["mobile_data"] == "1" else "disable")
        receipt = {
            "applicationId": APP, "releaseShrunk": True, "debuggable": False,
            "transport": "bundletool local testing (simulated Play transport)", "realPlayVerified": False,
            "standaloneApkBytes": standalone.stat().st_size,
            "standaloneApkSha256": hashlib.file_digest(standalone.open("rb"), "sha256").hexdigest(),
            "baseDownloadBytes": estimates["base"],
            "nodeAdditionalDownloadBytes": estimates["base,node_runtime"] - estimates["base"],
            "aabBytes": bundle.stat().st_size,
            "aabSha256": hashlib.file_digest(bundle.open("rb"), "sha256").hexdigest(),
            "baseNativeLibraries": base_libraries, "tests": results,
            "allRequestedChecksPassed": len(results) == len(phases) + (0 if args.phase else 2) and all(results.values()),
            "device": args.device, "sdk": run(*adb, "shell", "getprop", "ro.build.version.sdk").strip(),
            "pageSize": run(*adb, "shell", "getconf", "PAGE_SIZE").strip(),
            "networkRestored": original,
            "networkAfter": {key: run(*adb, "shell", "settings", "get", "global", key).strip() for key in original},
        }
        (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
        shutil.copyfile(bundle, output / "building-editor-two-stage.aab")


if __name__ == "__main__":
    main()
