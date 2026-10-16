#!/usr/bin/env python3
"""Build and stage a signed internal-track candidate. Never upload or publish it."""
import argparse
import csv
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

from verify_delivery import inspect as inspect_delivery
from partition import FEATURES

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("android_release", ROOT / "tools/android-release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
ANDROID = "{http://schemas.android.com/apk/res/android}"
DIST = "{http://schemas.android.com/apk/distribution}"
MODULE = "developer_runtime_node"


def verify_manifests(base_xml, feature_xml, env, module=MODULE):
    base, feature = ET.fromstring(base_xml), ET.fromstring(feature_xml)
    for key, expected in (("package", env["ANDROID_APPLICATION_ID"]),
                          (ANDROID + "versionCode", env["ANDROID_VERSION_CODE"]),
                          (ANDROID + "versionName", env["ANDROID_VERSION_NAME"])):
        if base.get(key) != expected:
            raise ValueError("Bundle identity/version does not match the requested release: " + key)
    if base.find("application").get(ANDROID + "debuggable", "false") != "false":
        raise ValueError("Internal-track bundle must not be debuggable.")
    if int(base.find("uses-sdk").get(ANDROID + "targetSdkVersion", "0")) < 36:
        raise ValueError("This release requires target SDK 36 or higher.")
    for manifest in (base, feature):
        for metadata in manifest.iter("meta-data"):
            if "local_testing" in metadata.get(ANDROID + "name", "").lower():
                raise ValueError("Local Play simulation metadata must not reach an internal-track release.")
    if feature.get("split") != module or feature.get("package") != base.get("package"):
        raise ValueError("Wrong feature identity.")
    if feature.find(f"{DIST}module/{DIST}delivery/{DIST}on-demand") is None:
        raise ValueError("Runtime must be delivered on demand.")
    fusing = feature.find(f"{DIST}module/{DIST}fusing")
    if fusing is None or fusing.get(DIST + "include") != "false":
        raise ValueError("Runtime must not be fused into the base install.")


def native_alignment(archive):
    """Check every packaged ELF, not just the developer-runtime subset.

    Report RELRO end boundaries separately: LOAD alignment alone is not a claim
    that the native dependencies have been qualified on a 16 KB device.
    """
    checked, relro = [], []
    for name in archive.namelist():
        if "/lib/" not in name or not name.endswith(".so"):
            continue
        data = archive.read(name)
        if data[:6] != b"\x7fELF\x02\x01" or len(data) < 64 or struct.unpack_from("<H", data, 18)[0] != 183:
            raise ValueError("Expected ARM64 ELF: " + name)
        offset = struct.unpack_from("<Q", data, 32)[0]
        size, count = struct.unpack_from("<HH", data, 54)
        if size < 56 or offset + size * count > len(data):
            raise ValueError("Invalid ELF program headers: " + name)
        headers = [struct.unpack_from("<IIQQQQQQ", data, offset + i * size) for i in range(count)]
        loads = [h for h in headers if h[0] == 1]
        if not loads or any(h[7] < 16384 or (h[2] - h[3]) % 16384 for h in loads):
            raise ValueError("ELF LOAD segments are not 16 KB aligned: " + name)
        if any((h[3] + h[6]) % 16384 for h in headers if h[0] == 0x6474E552):
            relro.append(name)
        checked.append(name)
    if not checked:
        raise ValueError("Bundle has no native runtime libraries.")
    return {"arm64LibrariesChecked": len(checked), "loadSegments16KAligned": True,
            "relroEndsNot16KAligned": relro, "runtimeOn16KDeviceVerified": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version-code", required=True, type=int, help="Choose an unused code above the Play Console maximum.")
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env.production")
    parser.add_argument("--bundletool", type=Path, required=True, help="Official bundletool-all JAR.")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--skip-build", action="store_true", help="Verify/stage the existing release AAB against all requested settings.")
    args = parser.parse_args()
    bundletool = args.bundletool.resolve(strict=True)
    env = release.environment(args.env_file.resolve())
    env.update(ANDROID_VERSION_CODE=str(args.version_code), ANDROID_VERSION_NAME=args.version_name)
    _, fingerprint = release.preflight(env)
    output = (args.output or ROOT / "dist/play-internal" / str(args.version_code)).resolve()
    output.mkdir(parents=True, exist_ok=True)
    (output / "receipt.json").unlink(missing_ok=True)
    if not args.skip_build:
        print("Building signed Play release; log:", output / "build.log", flush=True)
        with (output / "build.log").open("w") as log:
            subprocess.run([sys.executable, str(ROOT / "tools/android-release.py"), "--build",
                            "--runtime-delivery", "play", "--env-file", str(args.env_file.resolve())],
                           cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    source = ROOT / "app/ide-android/build/outputs/bundle/release/ide-android-release.aab"
    # Preserve the exact verified candidate independently of later Gradle builds.
    aab = output / f"building-{args.version_code}-play-release.aab"
    shutil.copyfile(source, aab)
    release.verify_artifact_configuration(aab, env)
    verified = release.inspect(["jarsigner", "-J-Duser.language=en", "-verify", str(aab)], env)
    if "jar verified." not in verified:
        raise ValueError("AAB signature was not verified.")
    certificate = release.inspect(["keytool", "-J-Duser.language=en", "-printcert", "-jarfile", str(aab)], env)
    if fingerprint not in certificate.replace(":", "").lower():
        raise ValueError("AAB does not match the configured upload key.")
    release.inspect(["keytool", "-exportcert", "-rfc", "-keystore", env["RELEASE_STORE_FILE"],
                     "-storepass:env", "RELEASE_STORE_PASSWORD", "-alias", env["RELEASE_KEY_ALIAS"],
                     "-file", str(output / "upload-certificate.pem")], env)

    tool = ["java", "-Xmx4g", "-jar", str(bundletool)]

    def run(*arguments):
        return subprocess.check_output([*tool, *map(str, arguments)], cwd=ROOT, env=env, text=True)

    (output / "bundle-validation.txt").write_text(run("validate", "--bundle=" + str(aab)))
    manifests = {module: run("dump", "manifest", "--bundle=" + str(aab), "--module=" + module)
                 for module in ("base", *FEATURES.values())}
    for module in FEATURES.values():
        verify_manifests(manifests["base"], manifests[module], env, module)
    for module, xml in manifests.items():
        (output / (module + "-manifest.xml")).write_text(xml)
    with zipfile.ZipFile(aab) as archive:
        modules = {n.split("/")[0] for n in archive.namelist() if n.endswith("/manifest/AndroidManifest.xml")}
        if modules != {"base", *FEATURES.values()}:
            raise ValueError("Unexpected bundle modules: " + str(sorted(modules)))
        alignment = native_alignment(archive)
    delivery = inspect_delivery(aab)
    (output / "native-alignment.json").write_text(json.dumps(alignment, indent=2) + "\n")
    (output / "delivery-audit.json").write_text(json.dumps(delivery, indent=2) + "\n")

    # Reference-device size estimation only; no ADB install or local-testing flag.
    device = output / "reference-device.json"
    device.write_text(json.dumps({"supportedAbis": ["arm64-v8a"], "supportedLocales": ["en"],
                                 "screenDensity": 420, "sdkVersion": 36}, indent=2) + "\n")
    apks = output / "reference-device.apks"
    with tempfile.TemporaryDirectory(prefix="building-bundle-signing-") as directory:
        store_pass, key_pass = Path(directory) / "store", Path(directory) / "key"
        for path, key in ((store_pass, "RELEASE_STORE_PASSWORD"), (key_pass, "RELEASE_KEY_PASSWORD")):
            path.touch(mode=0o600)
            path.write_text(env[key])
        run("build-apks", "--bundle=" + str(aab), "--output=" + str(apks), "--overwrite",
            "--device-spec=" + str(device), "--ks=" + env["RELEASE_STORE_FILE"],
            "--ks-key-alias=" + env["RELEASE_KEY_ALIAS"], "--ks-pass=file:" + str(store_pass),
            "--key-pass=file:" + str(key_pass))
    sizes = {}
    for name, modules in [("base", "base")] + [(name, "base," + module) for name, module in FEATURES.items()]:
        result = run("get-size", "total", "--apks=" + str(apks), "--device-spec=" + str(device), "--modules=" + modules)
        (output / (name + "-download-size.csv")).write_text(result)
        sizes[name] = {k: int(v) for k, v in next(csv.DictReader(io.StringIO(result))).items()}
    # Conservative decimal interpretation of the documented 500 MB per-module limit.
    if sizes["base"]["MAX"] > 500_000_000 or any(sizes[name]["MAX"] - sizes["base"]["MIN"] > 500_000_000 for name in FEATURES):
        raise ValueError("Estimated module download exceeds 500 MB; inspect the size reports.")
    with aab.open("rb") as stream:
        sha = hashlib.file_digest(stream, "sha256").hexdigest()
    (output / "SHA256SUMS").write_text(sha + "  " + aab.name + "\n")
    (output / "release-notes.txt").write_text(
        "<en-US>\nDownload Swift and xtool or Node.js separately from Settings > SDK Manager. "
        "Test installation, compilation, restart and offline reuse. iPhone builds require your own Apple SDK. "
        "Signing and iOS simulation are not included.\n</en-US>\n")
    receipt = {"applicationId": env["ANDROID_APPLICATION_ID"], "versionCode": args.version_code,
               "versionName": args.version_name, "track": "internal", "aab": aab.name, "sha256": sha,
               "uploadCertificateSha256": fingerprint, "runtimeDelivery": "play", "localTesting": False,
               "modules": delivery, "referenceDownloadBytes": sizes,
               "preparationRevision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
               "preparationWorkingTreeDirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()),
               "buildSkipped": args.skip_build, "bundletoolVersion": run("version").strip(),
               "uploaded": False, "playDeliveryVerified": False,
               "pending": ["Confirm package, unused version code and upload certificate in Play Console",
                           "Qualify native dependencies on a 16 KB device; see native-alignment.json",
                           "Upload to internal track, enroll testers and install through Google Play",
                           "Download Swift/xtool and Node through Settings, compile a project, restart and repeat offline"]}
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print("Signed internal-track candidate:", aab)
    print("SHA-256:", sha)
    print("Reference download bytes:", sizes)
    print("Console identity/version/key checks and real Play delivery remain pending. Nothing uploaded.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile, AssertionError) as error:
        print("Play preparation:", error, file=sys.stderr)
        sys.exit(1)
