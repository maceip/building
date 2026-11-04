#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Run owned engine JVM/host tests without taking the repository Gradle lock.

Requires the repository's already-resolved compiler/JUnit artifacts. It never fetches
packages or edits installed tools. Full Gradle integration remains a separate gate.
"""
from pathlib import Path
import argparse
import subprocess
import sys
from source_io import HERE, ROOT, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android-jar', type=Path, required=True)
    parser.add_argument('--gradle-cache', type=Path, default=Path.home() / '.gradle/caches/modules-2/files-2.1')
    args = parser.parse_args()
    base = args.gradle_cache
    def jar(group, artifact, version=None):
        path = base / group / artifact
        if version: path /= version
        matches = sorted(p for p in path.rglob('*.jar') if not p.name.endswith('-all.jar'))
        if not matches: raise FileNotFoundError(f'Already-resolved dependency required: {path}')
        return matches[0]
    compiler = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.20'),
                jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.20'),
                jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.4.20'),
                jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.11.0'),
                jar('org.jetbrains', 'annotations', '13.0')]
    junit5 = [jar('org.junit.jupiter', 'junit-jupiter-api', '5.11.4'),
              jar('org.junit.jupiter', 'junit-jupiter-engine', '5.11.4'),
              jar('org.junit.platform', 'junit-platform-commons', '1.11.4'),
              jar('org.junit.platform', 'junit-platform-engine', '1.11.4'),
              jar('org.junit.platform', 'junit-platform-launcher', '1.11.4'),
              jar('org.opentest4j', 'opentest4j'), jar('org.apiguardian', 'apiguardian-api'),
              jar('org.jetbrains.kotlin', 'kotlin-test', '2.4.20')]
    junit4 = [jar('junit', 'junit', '4.13.2'), jar('org.hamcrest', 'hamcrest-core')]
    output = HERE / 'build/host-tests'; output.mkdir(parents=True, exist_ok=True)
    classes = output / 'classes'; classes.mkdir(exist_ok=True)
    java = list((ROOT / 'android/runtime-engine/core/src/main/java').rglob('*.java'))
    java += list((ROOT / 'android/runtime-engine/core/src/test/java').rglob('*.java'))
    run('javac', '--release', '8', '-Xlint:-options', '-cp', ':'.join(map(str, [args.android_jar, *junit4])),
        '-d', classes, *java, cwd=ROOT)
    run('java', '-cp', ':'.join(map(str, [classes, args.android_jar, *junit4])), 'org.junit.runner.JUnitCore',
        'dev.ide.runtime.engine.EngineContractTest', 'dev.ide.runtime.engine.TerminalDeliveryTest', cwd=ROOT)
    bridge = list((ROOT / 'android/runtime-engine/reliability/src/main/java').rglob('*.java'))
    bridge += list((ROOT / 'android/runtime-engine/reliability/src/test/java').rglob('*.java'))
    bridge += list((ROOT / 'android/applog-runtime/src/main/java').rglob('*.java'))
    run('javac', '--release', '8', '-Xlint:-options', '-cp', ':'.join(map(str, [classes, args.android_jar, *junit4])),
        '-d', classes, *bridge, cwd=ROOT)
    run('java', '-cp', ':'.join(map(str, [classes, args.android_jar, *junit4])), 'org.junit.runner.JUnitCore',
        'dev.ide.runtime.engine.reliability.ReliabilityEngineListenerTest', cwd=ROOT)
    kotlin = list((ROOT / 'services/developer-workbench/src/main/kotlin/dev/ide/workbench/appengine').glob('*.kt'))
    kotlin += list((ROOT / 'services/developer-workbench/src/test/kotlin/dev/ide/workbench/appengine').glob('*.kt'))
    run('java', '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', ':'.join(map(str, [compiler[1], *junit5])),
        '-jvm-target', '17', '-Xjdk-release=17', '-d', classes, *kotlin, cwd=ROOT)
    # Tiny launcher source is a transient build input, not an alternative test framework.
    launcher = output / 'EngineTestLauncher.java'
    launcher.write_text('''import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
public class EngineTestLauncher {
 public static void main(String[] args) {
  var listener = new SummaryGeneratingListener(); var launcher = LauncherFactory.create();
  launcher.registerTestExecutionListeners(listener);
  launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(selectClass("dev.ide.workbench.appengine.AppEnginePackagerTest")).build());
  listener.getSummary().printTo(new java.io.PrintWriter(System.out));
  listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.err));
  if (listener.getSummary().getTestsFailedCount() != 0) System.exit(1);
 }
}\n''')
    cp = ':'.join(map(str, [classes, compiler[1], *junit5]))
    run('javac', '--release', '17', '-cp', cp, '-d', classes, launcher, cwd=ROOT)
    run('java', '-cp', cp, 'EngineTestLauncher', cwd=ROOT)
    run(sys.executable, '-m', 'unittest', 'discover', '-s', HERE, '-p', 'test_*.py', '-v', cwd=ROOT)


if __name__ == '__main__': main()
