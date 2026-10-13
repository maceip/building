#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Compile an actual AppEnginePackager native-model project using the host Android SDK.

Validation/acceptance helper, not an alternative IDE build system. Does not generate
or replace Java, scripts, manifest or library inputs. No Gradle, ADB, network or release key.
"""
from pathlib import Path
import argparse
import json
import shutil
import xml.etree.ElementTree as ET
import zipfile
from source_io import HERE, ROOT, digest, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('project', type=Path)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--key', type=Path, required=True, help='Local acceptance-debug.p12, not a production key')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = args.project.resolve(); out = args.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    tools = args.sdk.resolve() / 'build-tools/36.0.0'; android = args.sdk.resolve() / 'platforms/android-36/android.jar'
    manifest = root / 'app/src/main/AndroidManifest.xml'
    xml = ET.parse(manifest).getroot(); package = xml.attrib['package']
    workspace = json.loads((root / '.platform/workspace.json').read_text())
    project = workspace['projects'][0]
    if project['buildSystem'] != 'native': raise ValueError('Not an actual native-model generated project')
    jars = [root / name for lib in project['libraries'] for name in lib['classes']]
    if not jars or not all(p.is_file() for p in jars): raise ValueError('Generated runtime library paths do not resolve')
    classes = out / 'classes'; dex = out / 'dex'
    for directory in (classes, dex):
        if directory.exists(): shutil.rmtree(directory)
        directory.mkdir()
    sources = sorted((root / 'app/src/main/java').rglob('*.java'))
    run('javac', '--release', '8', '-Xlint:-options', '-cp', ':'.join(map(str, [android, *jars])),
        '-d', classes, *sources, cwd=HERE)
    app_jar = out / 'app.jar'; run('jar', 'cf', app_jar, '-C', classes, '.', cwd=HERE)
    run('java', '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '26', '--lib', android,
        '--output', dex, app_jar, *jars, cwd=HERE)
    compiled = out / 'res.zip'; unsigned = out / 'unsigned.apk'; aligned = out / 'aligned.apk'
    run(tools / 'aapt2', 'compile', '--dir', root / 'app/src/main/res', '-o', compiled, cwd=HERE)
    run(tools / 'aapt2', 'link', '-o', unsigned, '-I', android, '--manifest', manifest,
        '--version-code', '1', '--version-name', '1.0', '-A', root / 'app/src/main/assets', compiled, cwd=HERE)
    with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as apk:
        for path in dex.glob('*.dex'): apk.write(path, path.name)
        libs = root / 'app/src/main/jniLibs'
        for path in sorted(libs.rglob('*.so')): apk.write(path, 'lib/' + path.relative_to(libs).as_posix())
    run(tools / 'zipalign', '-f', '-p', '4', unsigned, aligned, cwd=HERE)
    signed = out / 'generated-app.apk'
    signer = ['java', '-jar', tools / 'lib/apksigner.jar']
    run(*signer, 'sign', '--ks', args.key, '--ks-key-alias', 'acceptance', '--ks-pass', 'pass:android',
        '--key-pass', 'pass:android', '--out', signed, aligned, cwd=HERE)
    run(*signer, 'verify', '--verbose', '--print-certs', signed, cwd=HERE)
    # Re-use the normal production-API instrumentation source, targeting this generated app.
    test_classes = out / 'test-classes'; test_dex = out / 'test-dex'
    test_classes.mkdir(exist_ok=True); test_dex.mkdir(exist_ok=True)
    source = ROOT / 'android/runtime-engine/acceptance/src/androidTest/java/dev/ide/runtime/acceptance/EngineInstrumentation.java'
    run('javac', '--release', '8', '-Xlint:-options', '-cp', ':'.join(map(str, [android, *jars])),
        '-d', test_classes, source, cwd=HERE)
    test_jar = out / 'test.jar'; run('jar', 'cf', test_jar, '-C', test_classes, '.', cwd=HERE)
    run('java', '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '26', '--lib', android,
        '--classpath', jars[0], '--output', test_dex, test_jar, cwd=HERE)
    test_manifest = out / 'test-AndroidManifest.xml'
    test_manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}.test" android:versionCode="1" android:versionName="1.0">
 <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
 <application android:label="Generated engine app tests" android:debuggable="true"/>
 <instrumentation android:name="dev.ide.runtime.acceptance.EngineInstrumentation" android:targetPackage="{package}" android:functionalTest="true"/>
</manifest>\n''')
    test_unsigned = out / 'test-unsigned.apk'
    run(tools / 'aapt2', 'link', '-o', test_unsigned, '-I', android, '--manifest', test_manifest, cwd=HERE)
    with zipfile.ZipFile(test_unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as apk:
        for path in test_dex.glob('*.dex'): apk.write(path, path.name)
    test_aligned = out / 'test-aligned.apk'; test_signed = out / 'generated-app-test.apk'
    run(tools / 'zipalign', '-f', '4', test_unsigned, test_aligned, cwd=HERE)
    run(*signer, 'sign', '--ks', args.key, '--ks-key-alias', 'acceptance', '--ks-pass', 'pass:android',
        '--key-pass', 'pass:android', '--out', test_signed, test_aligned, cwd=HERE)
    run(*signer, 'verify', '--verbose', test_signed, cwd=HERE)
    print('GENERATED_APK', signed, 'SHA256', digest(signed), 'BYTES', signed.stat().st_size)
    print('PACKAGE', package, 'ACTIVITY', package + '.MainActivity')
    print('TEST_APK', test_signed, 'SHA256', digest(test_signed))
    print('INSTRUMENTATION', package + '.test/dev.ide.runtime.acceptance.EngineInstrumentation')
    print('Actual generated project compiled; device and IDE-native-builder execution remain separate gates.')


if __name__ == '__main__': main()
