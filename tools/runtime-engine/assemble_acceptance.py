#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Assemble the small real engine acceptance app; no Gradle, ADB, IDE dependency or downloads.

This is not a release signer. It uses a dedicated local debug key in the output directory.
The app intentionally contains crash and infinite-loop scripts behind explicit UI buttons.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import shutil
import subprocess
import zipfile
from source_io import HERE, ROOT, digest, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", choices=["node", "python"], required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--payload", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--version-code", type=int, default=1)
    args = parser.parse_args()
    work = (args.output or HERE / "build/acceptance" / args.engine).resolve()
    payload = (args.payload or HERE / "build/payloads" / args.engine).resolve()
    tools = args.sdk.resolve() / "build-tools/36.0.0"
    android = args.sdk.resolve() / "platforms/android-36/android.jar"
    engine_source = ROOT / "android/runtime-engine"
    package = f"dev.ide.runtime.acceptance.{args.engine}"
    if not (payload / "assets/runtime-engine" / args.engine / "engine.properties").is_file():
        parser.error("Build the actual selected engine payload first")
    work.mkdir(parents=True, exist_ok=True)
    for name in ("classes", "dex", "res", "assets"):
        directory = work / name
        if directory.exists(): shutil.rmtree(directory)
        directory.mkdir()
    assets = work / "assets"
    shutil.copytree(payload / "assets", assets, dirs_exist_ok=True)
    app = assets / "runtime-engine/app"
    shutil.copytree(engine_source / "acceptance/scripts" / args.engine, app)
    (app / "files.sha256").write_text("\n".join(
        f"{digest(path)}\t{path.stat().st_size}\t{path.relative_to(app).as_posix()}"
        for path in sorted(app.rglob("*")) if path.is_file()) + "\n")
    values = work / "res/values"; values.mkdir()
    (values / "strings.xml").write_text(f'<resources><string name="app_name">Engine acceptance {args.engine}</string><string name="engine_id">{args.engine}</string></resources>\n')
    manifest = work / "AndroidManifest.xml"
    manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="{args.version_code}" android:versionName="1.0">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
  <application android:label="@string/app_name" android:debuggable="true" android:allowBackup="false" android:extractNativeLibs="true" android:usesCleartextTraffic="false" android:theme="@android:style/Theme.Material.Light.NoActionBar">
    <activity android:name="dev.ide.runtime.acceptance.MainActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>
    <service android:name="dev.ide.runtime.engine.RuntimeService" android:process=":runtime" android:exported="false" android:stopWithTask="true"/>
  </application>
</manifest>\n''')
    sources = sorted((engine_source / "core/src/main/java").rglob("*.java")) + sorted((engine_source / "acceptance/src/main/java").rglob("*.java"))
    run("javac", "--release", "8", "-Xlint:-options", "-cp", android, "-d", work / "classes", *sources, cwd=HERE)
    classes_jar = work / "classes.jar"
    run("jar", "cf", classes_jar, "-C", work / "classes", ".", cwd=HERE)
    # Also produce the production runtime-only classes JAR for generated-app integration.
    runtime_jar = HERE / "build/runtime-engine.jar"
    run("jar", "cf", runtime_jar, "-C", work / "classes", "dev/ide/runtime/engine", cwd=HERE)
    run("java", "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8", "--min-api", "26", "--lib", android,
        "--output", work / "dex", classes_jar, cwd=HERE)
    compiled = work / "compiled-res.zip"
    run(tools / "aapt2", "compile", "--dir", work / "res", "-o", compiled, cwd=HERE)
    unsigned = work / "unsigned.apk"
    run(tools / "aapt2", "link", "-o", unsigned, "-I", android, "--manifest", manifest,
        "--min-sdk-version", "26", "--target-sdk-version", "36", "-A", assets, compiled, cwd=HERE)
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as apk:
        for dex in sorted((work / "dex").glob("*.dex")): apk.write(dex, dex.name)
        for library in sorted((payload / "jniLibs").rglob("*.so")):
            apk.write(library, "lib/" + library.relative_to(payload / "jniLibs").as_posix())
    aligned = work / "aligned.apk"
    run(tools / "zipalign", "-f", "-p", "4", unsigned, aligned, cwd=HERE)
    key = work / "acceptance-debug.p12"
    if not key.exists():
        run("keytool", "-genkeypair", "-keystore", key, "-storepass", "android", "-keypass", "android",
            "-alias", "acceptance", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
            "-dname", "CN=Local engine acceptance,OU=Internal test,O=CodeAssist,C=US", cwd=HERE)
    signed = work / f"engine-{args.engine}-acceptance.apk"
    run("java", "-jar", tools / "lib/apksigner.jar", "sign", "--ks", key, "--ks-key-alias", "acceptance",
        "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", signed, aligned, cwd=HERE)
    run("java", "-jar", tools / "lib/apksigner.jar", "verify", "--verbose", "--print-certs", signed, cwd=HERE)
    print("APK", signed)
    print("SHA256", digest(signed))
    print("BYTES", signed.stat().st_size)
    print("PACKAGE", package)
    print("VERSION_CODE", args.version_code)
    print("ACTIVITY", "dev.ide.runtime.acceptance.MainActivity")
    print("Runtime library", runtime_jar, "SHA256", digest(runtime_jar))
    # Standard instrumentation targets the acceptance app UID; no exported production test API.
    tests = work / "test-classes"; tests.mkdir(exist_ok=True)
    test_dex = work / "test-dex"; test_dex.mkdir(exist_ok=True)
    test_source = engine_source / "acceptance/src/androidTest/java/dev/ide/runtime/acceptance/EngineInstrumentation.java"
    run("javac", "--release", "8", "-Xlint:-options", "-cp", str(android) + ":" + str(runtime_jar),
        "-d", tests, test_source, cwd=HERE)
    test_jar = work / "test-classes.jar"
    run("jar", "cf", test_jar, "-C", tests, ".", cwd=HERE)
    run("java", "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8", "--min-api", "26", "--lib", android,
        "--classpath", runtime_jar, "--output", test_dex, test_jar, cwd=HERE)
    test_manifest = work / "test-AndroidManifest.xml"
    test_manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}.test" android:versionCode="{args.version_code}" android:versionName="1.0">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
  <application android:label="Engine instrumentation" android:debuggable="true"/>
  <instrumentation android:name="dev.ide.runtime.acceptance.EngineInstrumentation" android:targetPackage="{package}" android:functionalTest="true"/>
</manifest>\n''')
    test_unsigned = work / "test-unsigned.apk"
    run(tools / "aapt2", "link", "-o", test_unsigned, "-I", android, "--manifest", test_manifest, cwd=HERE)
    with zipfile.ZipFile(test_unsigned, "a", compression=zipfile.ZIP_DEFLATED) as apk:
        for dex in sorted(test_dex.glob("*.dex")): apk.write(dex, dex.name)
    test_aligned = work / "test-aligned.apk"
    run(tools / "zipalign", "-f", "4", test_unsigned, test_aligned, cwd=HERE)
    test_apk = work / f"engine-{args.engine}-acceptance-test.apk"
    run("java", "-jar", tools / "lib/apksigner.jar", "sign", "--ks", key, "--ks-key-alias", "acceptance",
        "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", test_apk, test_aligned, cwd=HERE)
    run("java", "-jar", tools / "lib/apksigner.jar", "verify", "--verbose", test_apk, cwd=HERE)
    print("TEST_APK", test_apk, "SHA256", digest(test_apk))
    print("INSTRUMENTATION", f"{package}.test/dev.ide.runtime.acceptance.EngineInstrumentation")
    print("Device execution NOT performed by this script.")


if __name__ == "__main__": main()
