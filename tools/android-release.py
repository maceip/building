#!/usr/bin/env python3
"""Validate an explicit production environment, then optionally build signed APK/AAB files."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PLACEMENTS = (
    "STORE", "STORE_ITEM", "PROJECTS", "LEARN", "CHALLENGE", "BUILD_CONSOLE",
    "RUN_RESULT", "SDK_MANAGER", "SETTINGS", "SIDEBAR",
)


def public_defaults():
    """The shared file deliberately uses only literal NAME=value lines (no shell/Java escapes)."""
    values = {}
    for raw in (ROOT / "config/android-release.properties").read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key) or key in values or "\\" in value:
            fail("Invalid public Android configuration; use unique literal NAME=value lines.")
        if key.startswith("RELEASE_"):
            fail("Signing values must not appear in public Android configuration.")
        values[key] = value
    return values


def fail(message):
    raise ValueError(message)


def environment(path):
    values = public_defaults()
    if path.exists():
        if path.stat().st_mode & 0o077:
            fail(f"Protect the environment file first: chmod 600 {path}")
        for number, raw in enumerate(path.read_text().splitlines(), 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            key, separator, value = line.partition("=")
            key = key.strip()
            if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
                fail(f"Invalid environment assignment on line {number}; use NAME=value.")
            try:
                parts = shlex.split(value, comments=True)
            except ValueError:
                fail(f"Invalid quoting on environment line {number}.")
            if len(parts) > 1:
                fail(f"Quote values containing spaces on environment line {number}.")
            values[key] = parts[0] if parts else ""
    values.update(os.environ)
    return values


def inspect(command, env):
    result = subprocess.run(command, cwd=ROOT, env=env, text=True, capture_output=True)
    if result.returncode:
        fail(f"{Path(command[0]).name} failed. Check the configured file, alias, and credentials.")
    return result.stdout + result.stderr


def preflight(env):
    required = (
        "ANDROID_APPLICATION_ID", "ANDROID_VERSION_CODE", "ANDROID_VERSION_NAME",
        "RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD",
        "ANDROID_ADS_ENABLED",
    )
    missing = [key for key in required if not env.get(key)]
    if missing:
        fail("Missing production values: " + ", ".join(missing))
    app_id = env["ANDROID_APPLICATION_ID"]
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", app_id):
        fail("ANDROID_APPLICATION_ID must be a valid dotted Android application ID.")
    if app_id.endswith(".dev"):
        fail("Choose a production application ID rather than the device development ID.")
    if not env["ANDROID_VERSION_CODE"].isdigit() or not 1 <= int(env["ANDROID_VERSION_CODE"]) <= 2100000000:
        fail("ANDROID_VERSION_CODE must be between 1 and 2100000000.")
    env["ANDROID_VERSION_CODE"] = str(int(env["ANDROID_VERSION_CODE"]))
    if "\n" in env["ANDROID_VERSION_NAME"] or not env["ANDROID_VERSION_NAME"].strip():
        fail("ANDROID_VERSION_NAME must be a nonempty single line.")
    for key in ("SUPABASE_URL", "SUPABASE_KEY", "JUDGE_URL"):
        if key not in env:
            fail(f"Set {key} explicitly; an empty value disables this integration.")
    if bool(env["SUPABASE_URL"]) != bool(env["SUPABASE_KEY"]):
        fail("Set both SUPABASE_URL and SUPABASE_KEY, or leave both empty.")
    for key in ("SUPABASE_URL", "JUDGE_URL"):
        if env[key] and not env[key].startswith("https://"):
            fail(f"{key} must use HTTPS.")
    public_key = env["SUPABASE_KEY"]
    if public_key.startswith("sb_secret_"):
        fail("SUPABASE_KEY must be a public publishable/anon key, never a server secret.")
    if public_key.count(".") == 2:
        try:
            payload = public_key.split(".")[1]
            claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
            role = claims.get("role") if isinstance(claims, dict) else None
        except (ValueError, UnicodeError):
            role = None
        if role == "service_role":
            fail("SUPABASE_KEY must not contain a service-role key.")
    if env["ANDROID_ADS_ENABLED"] not in ("true", "false"):
        fail("Set ANDROID_ADS_ENABLED explicitly to true or false.")
    for key in ("ADMOB_APP_ID", "ADMOB_NATIVE_UNIT_ID", "ADMOB_INTERSTITIAL_UNIT_ID"):
        if env["ANDROID_ADS_ENABLED"] == "false":
            env[key] = ""
            continue
        separator = "~" if key == "ADMOB_APP_ID" else "/"
        if not re.fullmatch(r"ca-app-pub-\d{16}" + re.escape(separator) + r"\d{10}", env.get(key, "")):
            fail(f"{key} must be an AdMob ID owned by this project.")
        if "3940256099942544" in env[key]:
            fail(f"{key} is a Google test ID, not a production ad unit.")
    for placement in PLACEMENTS:
        key = "ADMOB_NATIVE_UNIT_" + placement
        if env["ANDROID_ADS_ENABLED"] == "false":
            env[key] = ""
        else:
            env.setdefault(key, "")
    store = Path(env["RELEASE_STORE_FILE"]).expanduser()
    if not store.is_absolute() or not store.is_file():
        fail("RELEASE_STORE_FILE must point to an existing keystore using an absolute path.")
    env["RELEASE_STORE_FILE"] = str(store)
    keytool = shutil.which("keytool", path=env.get("PATH"))
    if not keytool:
        fail("Install a JDK and put keytool on PATH.")
    certificate = inspect([
        keytool, "-J-Duser.language=en", "-J-Duser.country=US", "-list", "-v",
        "-keystore", str(store), "-storepass:env", "RELEASE_STORE_PASSWORD",
        "-alias", env["RELEASE_KEY_ALIAS"],
    ], env)
    if "PrivateKeyEntry" not in certificate or "CN=Android Debug" in certificate:
        fail("The configured alias must contain a production private key, not a debug certificate.")
    match = re.search(r"SHA256:\s*([0-9A-Fa-f:]+)", certificate)
    if not match:
        fail("Could not read the signing certificate's SHA-256 fingerprint.")
    fingerprint = match.group(1).replace(":", "").lower()
    sdk_value = env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")
    if not sdk_value:
        fail("Set ANDROID_HOME to the Android SDK directory.")
    sdk = Path(sdk_value).expanduser().resolve()
    for relative in ("platforms/android-36.1/android.jar", "build-tools/36.0.0/apksigner", "platform-tools/adb"):
        if not (sdk / relative).is_file():
            fail(f"Missing SDK component: {relative}")
    env["ANDROID_HOME"] = env["ANDROID_SDK_ROOT"] = str(sdk)
    print(f"Application: {app_id} {env['ANDROID_VERSION_NAME']} ({env['ANDROID_VERSION_CODE']})")
    print(f"Signing certificate SHA-256: {fingerprint}")
    print("Signing alias and SDK found. Passwords and client configuration values were not printed.")
    return sdk, fingerprint


def verify_configuration(record, env):
    expected = {key: env[key] for key in public_defaults()}
    if not isinstance(record, dict) or record != expected:
        # Values can include a public backend key; report only field names, never values.
        changed = sorted(key for key in set(expected) | set(record if isinstance(record, dict) else {})
                         if not isinstance(record, dict) or record.get(key) != expected.get(key) or key not in expected)
        fail("Release configuration differs from preflight: " + ", ".join(changed or ["record format"]))


def verify_artifact_configuration(artifact, env):
    path = ("base/" if artifact.suffix == ".aab" else "") + "assets/building-release-config.json"
    with zipfile.ZipFile(artifact) as archive:
        if archive.namelist().count(path) != 1:
            fail(f"{artifact.name} must contain exactly one public release configuration record.")
        verify_configuration(json.loads(archive.read(path)), env)


def gradle_command(build):
    tasks = [":ide-android:generateReleaseConfiguration"]
    if build:
        tasks += [":ide-android:assembleRelease", ":ide-android:bundleRelease"]
    # Only this non-secret selector is on the command line. All validated values travel in env.
    return [str(ROOT / "gradlew"), *tasks, "-PANDROID_RELEASE_CONFIG_SOURCE=environment", "--console=plain"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env.production")
    parser.add_argument("--build", action="store_true", help="Build and verify signed release APK and AAB files.")
    args = parser.parse_args()
    env = environment(args.env_file)
    sdk, fingerprint = preflight(env)
    subprocess.run(gradle_command(args.build), cwd=ROOT, env=env, check=True)
    record = ROOT / "app/ide-android/build/generated/release-configuration/building-release-config.json"
    verify_configuration(json.loads(record.read_text()), env)
    if not args.build:
        print("Preflight and resolved Gradle configuration passed. Add --build to verify signed artifacts.")
        return
    apk = ROOT / "app/ide-android/build/outputs/apk/release/ide-android-release.apk"
    aab = ROOT / "app/ide-android/build/outputs/bundle/release/ide-android-release.aab"
    for artifact in (apk, aab):
        verify_artifact_configuration(artifact, env)
    certs = inspect([str(sdk / "build-tools/36.0.0/apksigner"), "verify", "--print-certs", str(apk)], env)
    if fingerprint not in certs.lower():
        fail("The APK signing certificate differs from the configured keystore.")
    metadata = inspect([str(sdk / "build-tools/36.0.0/aapt"), "dump", "badging", str(apk)], env)
    for value in (
        f"name='{env['ANDROID_APPLICATION_ID']}'", f"versionCode='{env['ANDROID_VERSION_CODE']}'",
        f"versionName='{env['ANDROID_VERSION_NAME']}'",
    ):
        if value not in metadata.splitlines()[0]:
            fail("The APK application ID/version does not match the production environment.")
    if "application-debuggable" in metadata:
        fail("The release APK is unexpectedly debuggable.")
    if env["ANDROID_ADS_ENABLED"] == "false":
        manifest = inspect([str(sdk / "build-tools/36.0.0/aapt"), "dump", "xmltree", str(apk), "AndroidManifest.xml"], env)
        for forbidden in (
            "com.google.android.gms.permission.AD_ID", "android.permission.ACCESS_ADSERVICES_",
            "com.google.android.gms.ads.APPLICATION_ID", "com.google.android.gms.ads.MobileAdsInitProvider",
            "com.mbridge.msdk.config.component.status.MBComponentLifecycleProvider",
        ):
            if forbidden in manifest:
                fail(f"Ads-disabled release still contains manifest entry: {forbidden}")
    jarsigner = shutil.which("jarsigner", path=env.get("PATH"))
    if not jarsigner:
        fail("Install a JDK and put jarsigner on PATH.")
    verified = inspect([jarsigner, "-J-Duser.language=en", "-verify", str(aab)], env)
    if "jar verified." not in verified:
        fail("The AAB signature was not verified.")
    bundle_certificate = inspect([
        shutil.which("keytool", path=env.get("PATH")), "-J-Duser.language=en",
        "-printcert", "-jarfile", str(aab),
    ], env)
    if fingerprint not in bundle_certificate.replace(":", "").lower():
        fail("The AAB signing certificate differs from the configured keystore.")
    for artifact in (apk, aab):
        with artifact.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        print(f"Verified {artifact}\nSHA-256: {digest}")
    print("Signed artifacts built and verified locally. Nothing was uploaded or published.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"Release setup: {error}", file=sys.stderr)
        sys.exit(1)
