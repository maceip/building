#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Fail-closed inspection of an ACTUAL AAB, not a scaffold or a Play delivery certification.

Prints normal command output only. Requires native and stdlib assets in the expected
on-demand module and their absence in base; optional bundletool validates compiled manifest.
A Play account/device delivery exercise is still required separately.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import subprocess
import zipfile
import xml.etree.ElementTree as ET

FEATURE = 'runtime_engine_ide_feature'
EXPECTED = {
    'python': {'libengine_host.so', 'libengine_python.so', 'libpython3.14.so'},
    'node': {'libengine_host.so', 'libengine_node.so', 'libnode.so'},
}


def inspect(aab: Path, engine: str, module: str = FEATURE) -> list[str]:
    with zipfile.ZipFile(aab) as bundle:
        names = bundle.namelist()
        if len(names) != len(set(names)): raise ValueError('Duplicate AAB entries')
        libs = {name.rsplit('/', 1)[-1] for name in names if name.startswith(f'{module}/lib/arm64-v8a/')}
        if not EXPECTED[engine] <= libs:
            raise ValueError(f'Actual {module} split lacks native libraries: {EXPECTED[engine] - libs}')
        for name in names:
            if name.startswith('base/') and (name.rsplit('/', 1)[-1] in EXPECTED[engine]
                                            or name.startswith('base/assets/runtime-engine/' + engine + '/')):
                raise ValueError('Optional native engine leaked into base: ' + name)
        forbidden = EXPECTED['node' if engine == 'python' else 'python'] - {'libengine_host.so'}
        if any(name.rsplit('/', 1)[-1] in forbidden for name in names):
            raise ValueError('Unselected native engine packaged')
        assets = f'{module}/assets/runtime-engine/{engine}/'
        for name in ('engine.properties', 'files.sha256', 'licenses/NOTICE.txt'):
            if assets + name not in names: raise ValueError('Actual feature asset missing: ' + assets + name)
        if engine == 'python' and assets + 'python314.zip' not in names:
            raise ValueError('Python feature lacks stdlib asset')
        diagnostic = f'{module}/assets/runtime-engine/app/engine-check.' + ('py' if engine == 'python' else 'js')
        if diagnostic not in names: raise ValueError('Feature lacks real bundled diagnostic entry')
        manifest = f'{module}/manifest/AndroidManifest.xml'
        if manifest not in names: raise ValueError('Feature split manifest absent')
        return sorted(name for name in names if name.startswith(module + '/') and ('/lib/' in name or '/assets/runtime-engine/' in name))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('aab', type=Path)
    parser.add_argument('--engine', choices=EXPECTED, required=True)
    parser.add_argument('--module', default=FEATURE)
    parser.add_argument('--bundletool', type=Path, help='Existing bundletool JAR to validate AAB and on-demand manifest')
    args = parser.parse_args()
    entries = inspect(args.aab, args.engine, args.module)
    print('Native+asset split verified:', args.module)
    print('Actual AAB bytes:', args.aab.stat().st_size)
    print('Native entries:', *[x for x in entries if '/lib/' in x], sep='\n')
    print('Feature runtime assets:', len([x for x in entries if '/assets/' in x]))
    if args.bundletool:
        subprocess.run(['java', '-jar', str(args.bundletool), 'validate', '--bundle=' + str(args.aab)], check=True)
        xml = subprocess.check_output(['java', '-jar', str(args.bundletool), 'dump', 'manifest',
                                       '--bundle=' + str(args.aab), '--module=' + args.module], text=True)
        manifest = ET.fromstring(xml)
        dist = '{http://schemas.android.com/apk/distribution}'
        definition = manifest.find(dist + 'module')
        if definition is None or definition.find(dist + 'delivery/' + dist + 'on-demand') is None:
            raise ValueError('Actual compiled split is NOT on-demand')
        application = manifest.find('application')
        android = '{http://schemas.android.com/apk/res/android}'
        if application is None or application.attrib.get(android + 'extractNativeLibs') != 'true':
            raise ValueError('Engine ABI requires extracted signed feature JNI libraries')
        print('Compiled on-demand manifest and native extraction verified by bundletool.')
    else:
        print('Compiled manifest/on-demand validation not performed: pass an existing --bundletool JAR.')
    print('Play delivery NOT tested. Requires account, signed release/test track and device exercise.')


if __name__ == '__main__': main()
